package org.example;

import org.example.chunker.CobolChunker;
import org.example.chunker.JclChunker;
import org.example.chunker.LlmChunkAnalyzer;
import org.example.config.AppConfig;
import org.example.fetcher.SourceFetcher;
import org.example.graph.GraphHtmlExporter;
import org.example.graph.GraphWriter;
import org.example.graph.KnowledgeGraph;
import org.example.graph.KnowledgeGraphBuilder;
import org.example.llm.EmbeddingClient;
import org.example.llm.EmbeddingDocumentBuilder;
import org.example.llm.LlmEnricher;
import org.example.model.FileChunk;
import org.example.model.FileType;
import org.example.store.AuditLog;
import org.example.store.GraphStore;
import org.example.store.VectorStore;
import org.example.writer.ChunkWriter;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class Main {

    // Files are processed in fixed-size parallel batches: PARALLELISM files run
    // concurrently, the batch is fully awaited, then the next batch starts.
    private static final int PARALLELISM =
        AppConfig.getInt("INGEST_PARALLELISM", "ingest.parallelism", 10);

    // A file over this many lines needs multiple LLM chunking windows — see
    // processFilesTiered below for why these are pulled out of the fast
    // parallel batch entirely instead of being mixed in with small files.
    private static final int LARGE_FILE_THRESHOLD_LINES =
        AppConfig.getInt("INGEST_LARGE_FILE_THRESHOLD_LINES", "ingest.large-file-threshold-lines", 900);

    /** Thread-safe totalFiles/totalChunks counters shared across parallel workers. */
    private static final class Counters {
        final AtomicInteger files  = new AtomicInteger();
        final AtomicInteger chunks = new AtomicInteger();
    }

    public static void main(String[] args) {
        System.out.println("============================================");
        System.out.println("  COBOL Source Ingestion Tool");
        System.out.println("  Multi-Domain | AS400 RAG + Graph");
        System.out.println("============================================");

        Path resourcesRoot = findResourcesDir();
        Path inputRoot     = resourcesRoot.resolve("input");
        Path outputDir     = resourcesRoot.resolve("output");

        try { Files.createDirectories(outputDir); }
        catch (IOException e) { System.err.println("ERROR creating output dir: " + e.getMessage()); return; }

        KnowledgeGraphBuilder graphBuilder = new KnowledgeGraphBuilder();

        LlmChunkAnalyzer chunkAnalyzer = LlmChunkAnalyzer.create();
        System.out.println("  LLM Chunking   : "
            + (chunkAnalyzer != null
                ? "ENABLED (model: " + chunkAnalyzer.getModel() + ")"
                : "DISABLED (set OPENAI_API_KEY) — COBOL/copybook/JCL files will be skipped"));

        CobolChunker cobolChunker = chunkAnalyzer != null ? new CobolChunker(chunkAnalyzer) : null;
        JclChunker   jclChunker   = chunkAnalyzer != null ? new JclChunker(chunkAnalyzer)   : null;
        ChunkWriter  writer       = new ChunkWriter();
        Counters counters = new Counters();
        System.out.println("  Parallelism    : " + PARALLELISM + " files at a time (override with INGEST_PARALLELISM)");

        LlmEnricher enricher = LlmEnricher.create();
        System.out.println("  LLM Enrichment : "
            + (enricher != null ? "ENABLED (model: " + enricher.getModel() + ")" : "DISABLED (set OPENAI_API_KEY)"));

        EmbeddingClient embedder = EmbeddingClient.create();
        System.out.println("  Embeddings     : "
            + (embedder != null ? "ENABLED (model: " + embedder.getModel() + ")" : "DISABLED (set OPENAI_API_KEY)"));

        // All chunks accumulated here for bulk embed + DB store at the end.
        // synchronizedList because up to PARALLELISM worker threads call addAll()
        // concurrently while processing files; safe to read plainly afterwards
        // since all parallel batches have joined by the time it's read below.
        List<FileChunk> allChunks = Collections.synchronizedList(new ArrayList<>());

        // ── Audit log + SHA-based change detection ────────────────────
        AuditLog auditLog = AuditLog.create();
        if (auditLog == null) {
            System.out.println("  Audit log    : UNAVAILABLE (PostgreSQL unreachable — SHA check skipped)");
        }

        // ── Source fetch ──────────────────────────────────────────────
        System.out.println("\n--- Remote Source ---");
        SourceFetcher fetcher = new SourceFetcher();

        // Check current commit SHA against the last successful run
        String currentSha  = fetcher.fetchCurrentCommitSha();
        String previousSha = null;
        if (currentSha != null) {
            System.out.println("  Current SHA  : " + currentSha.substring(0, 8) + "...");
            if (auditLog != null) {
                previousSha = auditLog.getLastSuccessfulSha(fetcher.getRepoName());
                if (previousSha != null && previousSha.equals(currentSha)) {
                    boolean forceRerun = "true".equalsIgnoreCase(System.getenv("FORCE_RERUN"));
                    if (!forceRerun) {
                        System.out.println("  No source changes since last run (SHA: "
                            + currentSha.substring(0, 8) + "...)");
                        System.out.println("  Set FORCE_RERUN=true to re-process anyway.");
                        auditLog.recordRun(fetcher.getRepoName(), currentSha, previousSha,
                            0, 0, 0, "NO_CHANGE");
                        auditLog.close();
                        return;
                    }
                    System.out.println("  FORCE_RERUN=true — re-processing despite unchanged SHA.");
                } else if (previousSha != null) {
                    System.out.println("  Source changed: " + previousSha.substring(0, 8)
                        + "... → " + currentSha.substring(0, 8) + "...");
                } else {
                    System.out.println("  First run — no previous SHA on record.");
                }
            }
        } else {
            System.out.println("  SHA check    : SKIPPED (non-GitHub source or API unavailable)");
        }

        String cacheDirName = System.getenv("SOURCE_CACHE_DIR");
        if (cacheDirName == null || cacheDirName.isBlank()) cacheDirName = fetcher.getRepoName();
        Path cardDemoRoot = inputRoot.resolve("github").resolve(cacheDirName);
        try {
            fetcher.fetchIfAbsent(cardDemoRoot);
        } catch (Exception e) {
            System.out.println("  WARNING: Fetch failed: " + e.getMessage());
            System.out.println("  Continuing with locally cached files (if any)...");
        }

        // ── Local insurance files (flat directory scan) ───────────────
        processFlat(inputRoot.resolve("copybooks"), FileType.COPYBOOK,
            cobolChunker, null, "INSURANCE COPYBOOKS",
            graphBuilder, writer, outputDir, counters, enricher, allChunks);
        processFlat(inputRoot.resolve("cobol"), FileType.COBOL_PROGRAM,
            cobolChunker, null, "INSURANCE COBOL",
            graphBuilder, writer, outputDir, counters, enricher, allChunks);
        processFlat(inputRoot.resolve("jcl"), null,
            null, jclChunker, "INSURANCE JCL",
            graphBuilder, writer, outputDir, counters, enricher, allChunks);

        // ── Remote source: recursive traversal ───────────────────────
        if (Files.exists(cardDemoRoot)) {
            System.out.println("\n--- Processing: REMOTE SOURCE — " + cacheDirName + " (recursive) ---");
            try {
                List<Path> cardFiles;
                try (Stream<Path> walk = Files.walk(cardDemoRoot)) {
                    cardFiles = walk
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String ext = getExtension(p.getFileName().toString()).toLowerCase();
                            return ext.equals("cbl") || ext.equals("cpy") || ext.equals("jcl");
                        })
                        .sorted(Comparator.comparing((Path p) -> {
                            String ext = getExtension(p.getFileName().toString()).toLowerCase();
                            return switch (ext) { case "cpy" -> "1"; case "cbl" -> "2"; default -> "3"; };
                        }).thenComparing(p -> p.getFileName().toString()))
                        .toList();
                }
                System.out.println("  Found " + cardFiles.size() + " source files");

                processFilesTiered(cardFiles, file -> processOneFile(
                    file, cardDemoRoot.relativize(file).toString(), FileType.COPYBOOK,
                    cobolChunker, jclChunker, graphBuilder, writer, outputDir,
                    counters, enricher, allChunks));
            } catch (IOException e) {
                System.out.println("ERROR walking source: " + e.getMessage());
            }
        }

        // ── Knowledge Graph — JSON + HTML ─────────────────────────────
        System.out.println("\n--- Knowledge Graph ---");
        KnowledgeGraph graph = null;
        try {
            graph = graphBuilder.build();
            new GraphWriter().write(graph, outputDir);
            new GraphHtmlExporter().export(graph, outputDir);
        } catch (Exception e) {
            System.out.println("ERROR writing graph: " + e.getMessage());
        }

        // ── Embed all chunks (batched) ────────────────────────────────
        Map<String, float[]> embeddingMap = embedAll(allChunks, embedder);

        // ── Store chunks → PostgreSQL / pgvector ──────────────────────
        storeToVectorDB(allChunks, embeddingMap);

        // ── Store knowledge graph → Neo4j ─────────────────────────────
        if (graph != null) storeToNeo4j(graph);

        // ── Record audit log entry ────────────────────────────────────
        if (auditLog != null) {
            auditLog.recordRun(fetcher.getRepoName(), currentSha, previousSha,
                counters.files.get(), counters.chunks.get(), embeddingMap.size(), "SUCCESS");
            auditLog.close();
        }

        // ── Summary ───────────────────────────────────────────────────
        System.out.println("\n============================================");
        System.out.println("  INGESTION COMPLETE");
        System.out.printf("  Files processed   : %d%n", counters.files.get());
        System.out.printf("  Total chunks      : %d%n", counters.chunks.get());
        System.out.printf("  Embeddings stored : %d%n", embeddingMap.size());
        System.out.println("  Output directory  : " + outputDir.toAbsolutePath());
        if (currentSha != null) {
            System.out.println("  Commit SHA        : " + currentSha);
        }
        System.out.println("============================================");
        System.out.println(LlmChunkAnalyzer.failureSummary());
        System.out.println("============================================");
    }

    // ─── Embed all embeddable chunks in BATCH_SIZE batches ─────────────────────

    private static Map<String, float[]> embedAll(List<FileChunk> allChunks,
                                                   EmbeddingClient embedder) {
        System.out.println("\n--- Generating Embeddings ---");
        Map<String, float[]> result = new HashMap<>();

        if (embedder == null) {
            System.out.println("  Skipped (OPENAI_API_KEY not set)");
            return result;
        }

        List<FileChunk> embeddable = allChunks.stream()
            .filter(c -> c.isShouldEmbed() && c.getEmbeddingText() != null)
            .toList();

        System.out.printf("  Embedding %d chunks (batch size %d)...%n",
            embeddable.size(), EmbeddingClient.BATCH_SIZE);

        for (int i = 0; i < embeddable.size(); i += EmbeddingClient.BATCH_SIZE) {
            List<FileChunk> batch = embeddable.subList(
                i, Math.min(i + EmbeddingClient.BATCH_SIZE, embeddable.size()));
            List<String> texts = batch.stream().map(FileChunk::getEmbeddingText).toList();

            try {
                List<float[]> vecs = embedder.embedBatch(texts);
                for (int j = 0; j < batch.size(); j++) {
                    result.put(batch.get(j).getChunkId(), vecs.get(j));
                }
                System.out.printf("  ... %d/%d embedded%n",
                    Math.min(i + EmbeddingClient.BATCH_SIZE, embeddable.size()), embeddable.size());

                if (i + EmbeddingClient.BATCH_SIZE < embeddable.size()) {
                    Thread.sleep(150); // stay within rate limits
                }
            } catch (Exception e) {
                System.out.println("  WARNING: batch " + (i / EmbeddingClient.BATCH_SIZE + 1)
                    + " failed: " + e.getMessage());
            }
        }

        System.out.println("  ✓ " + result.size() + " embeddings generated");
        return result;
    }

    // ─── Store to PostgreSQL / pgvector ────────────────────────────────────────

    private static void storeToVectorDB(List<FileChunk> allChunks,
                                         Map<String, float[]> embeddingMap) {
        System.out.println("\n--- Storing to PostgreSQL (pgvector) ---");
        try (VectorStore store = VectorStore.create()) {
            store.upsertChunks(allChunks, embeddingMap);
            System.out.println("  ✓ " + allChunks.size() + " chunks stored");
            System.out.println("    pgAdmin → http://localhost:5050");
        } catch (Exception e) {
            System.out.println("  ERROR: " + e.getMessage());
            System.out.println("  Is PostgreSQL running?  →  docker compose up -d postgres");
        }
    }

    // ─── Store knowledge graph to Neo4j ────────────────────────────────────────

    private static void storeToNeo4j(KnowledgeGraph graph) {
        System.out.println("\n--- Storing Knowledge Graph to Neo4j ---");
        try (GraphStore store = GraphStore.create()) {
            store.ingestGraph(graph);
            int nodes = graph.getNodes() != null ? graph.getNodes().size() : 0;
            int edges = graph.getEdges() != null ? graph.getEdges().size() : 0;
            System.out.println("  ✓ " + nodes + " nodes, " + edges + " edges stored");
            System.out.println("    Neo4j Browser → http://localhost:7474  (neo4j / admin)");
        } catch (Exception e) {
            System.out.println("  ERROR: " + e.getMessage());
            System.out.println("  Is Neo4j running?  →  docker compose up -d neo4j");
        }
    }

    // ─── processFlat ───────────────────────────────────────────────────────────

    private static void processFlat(Path dir, FileType cobolType,
                                     CobolChunker cobolChunker, JclChunker jclChunker,
                                     String label, KnowledgeGraphBuilder graphBuilder,
                                     ChunkWriter writer, Path outputDir, Counters counters,
                                     LlmEnricher enricher, List<FileChunk> allChunks) {
        if (!Files.exists(dir)) return;
        System.out.println("\n--- Processing: " + label + " ---");

        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        } catch (IOException e) {
            System.out.println("ERROR listing " + dir + ": " + e.getMessage());
            return;
        }

        processFilesTiered(files, file -> processOneFile(
            file, file.getFileName().toString(), cobolType,
            cobolChunker, jclChunker, graphBuilder, writer, outputDir,
            counters, enricher, allChunks));
    }

    // ─── Shared per-file chunk/enrich/write/aggregate pipeline ─────────────────
    // Called concurrently by up to PARALLELISM worker threads (see runInBatches).
    // Every side effect here targets state that is either thread-local (the
    // file's own `chunks` list) or already made safe for concurrent access:
    // graphBuilder (synchronized methods), allChunks (synchronizedList),
    // counters (AtomicInteger), writer (writes a distinct file per call).

    private static void processOneFile(Path file, String displayName, FileType cpyTypeHint,
                                        CobolChunker cobolChunker, JclChunker jclChunker,
                                        KnowledgeGraphBuilder graphBuilder, ChunkWriter writer,
                                        Path outputDir, Counters counters, LlmEnricher enricher,
                                        List<FileChunk> allChunks) {
        String fileName = file.getFileName().toString();
        String ext = getExtension(fileName).toLowerCase();

        List<FileChunk> chunks;
        try {
            chunks = switch (ext) {
                case "cbl" -> cobolChunker != null
                    ? cobolChunker.chunk(file, FileType.COBOL_PROGRAM, graphBuilder) : List.of();
                case "cpy" -> cobolChunker != null
                    ? cobolChunker.chunk(file, cpyTypeHint, graphBuilder) : List.of();
                case "jcl" -> jclChunker != null
                    ? jclChunker.chunk(file, graphBuilder) : List.of();
                default -> List.of();
            };
        } catch (Exception e) {
            System.out.println("  " + displayName + " ... ERROR: " + e.getMessage());
            return;
        }

        if (chunks.isEmpty()) {
            System.out.println("  " + displayName + " ... 0 chunks");
            return;
        }

        if (enricher != null) enricher.enrichChunks(chunks);
        EmbeddingDocumentBuilder.process(chunks);

        try {
            writer.writeChunks(chunks, outputDir, fileName);
        } catch (IOException e) {
            System.out.println("  " + displayName + " ... WRITE ERROR: " + e.getMessage());
            return;
        }

        allChunks.addAll(chunks);
        counters.files.incrementAndGet();
        counters.chunks.addAndGet(chunks.size());
        System.out.println("  " + displayName + " ... " + chunks.size() + " chunks");
    }

    // ─── Tiered scheduling: small files fast/parallel, large files careful/solo ─
    // A large file can take many sequential LLM chunking-window calls — one
    // per ~900 lines (see LlmChunkAnalyzer). Mixing it into a fixed-size
    // parallel batch means every OTHER file in that batch finishes and its
    // worker thread sits idle for however long the large file takes, since
    // the whole batch is awaited together before the next one starts (see
    // runInBatches). Splitting large files into their own one-at-a-time
    // phase means small files' throughput is never held hostage by a large
    // one, and each large file gets the run's full, undivided attention —
    // no competing with PARALLELISM other concurrent calls for OpenAI rate-
    // limit headroom while it works through its many windows.

    private static void processFilesTiered(List<Path> files, Consumer<Path> task) {
        if (files.isEmpty()) return;

        List<Path> small = new ArrayList<>();
        List<Path> large = new ArrayList<>();
        for (Path file : files) {
            if (countLines(file) > LARGE_FILE_THRESHOLD_LINES) large.add(file);
            else small.add(file);
        }

        if (!large.isEmpty()) {
            System.out.println("  " + large.size() + " file(s) over " + LARGE_FILE_THRESHOLD_LINES
                + " lines set aside for careful, one-at-a-time processing after the fast batch below");
        }

        runInBatches(small, PARALLELISM, task);
        runInBatches(large, 1, task);
    }

    private static long countLines(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.count();
        } catch (IOException e) {
            return 0; // Unreadable — treat as small; the real processing step reports the actual error.
        }
    }

    // ─── Batched parallel execution: N files at a time ─────────────────────────
    // Runs each batch fully in parallel on a fixed-size pool, then blocks
    // (invokeAll) until the whole batch finishes before starting the next
    // batch — matching "N in parallel, then the next N" exactly. Called with
    // batchSize=1 for the large-file tier above, which naturally processes
    // that list strictly one at a time.

    private static void runInBatches(List<Path> files, int batchSize, Consumer<Path> task) {
        if (files.isEmpty()) return;
        int poolSize = Math.max(1, Math.min(batchSize, files.size()));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            for (int i = 0; i < files.size(); i += batchSize) {
                List<Path> batch = files.subList(i, Math.min(i + batchSize, files.size()));
                List<Callable<Void>> jobs = new ArrayList<>(batch.size());
                for (Path file : batch) {
                    jobs.add(() -> {
                        try {
                            task.accept(file);
                        } catch (Exception e) {
                            System.out.println("  " + file + " ... UNEXPECTED ERROR: " + e.getMessage());
                        }
                        return null;
                    });
                }
                pool.invokeAll(jobs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdown();
        }
    }

    // ─── Utilities ─────────────────────────────────────────────────────────────

    private static Path findResourcesDir() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path candidate = cwd.resolve("src/main/resources");
        if (Files.isDirectory(candidate)) return candidate;
        candidate = cwd.resolve("../src/main/resources").normalize();
        if (Files.isDirectory(candidate)) return candidate;
        return cwd;
    }

    private static String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }
}