package org.example;

import org.example.chunker.BatchChunkAnalysisService;
import org.example.chunker.CobolChunker;
import org.example.chunker.JclChunker;
import org.example.chunker.LlmChunkAnalyzer;
import org.example.config.AppConfig;
import org.example.fetcher.SourceFetcher;
import org.example.graph.GraphHtmlExporter;
import org.example.graph.GraphWriter;
import org.example.graph.KnowledgeGraph;
import org.example.graph.KnowledgeGraphBuilder;
import org.example.llm.BatchEnrichmentService;
import org.example.llm.EmbeddingClient;
import org.example.llm.EmbeddingDocumentBuilder;
import org.example.llm.LlmEnricher;
import org.example.llm.OpenAiBatchClient;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class Main {

    // How many small files' LLM chunk-analysis calls are allowed IN FLIGHT at
    // once — the real ceiling here is OpenAI's rate limit for your account/tier
    // (RPM/TPM), not JVM thread cost, since these are cheap virtual threads
    // (see runConcurrently). OpenAI no longer publishes exact per-tier RPM/TPM
    // numbers for a given model in its public docs (checked directly) — verify
    // your account's real numbers at platform.openai.com/settings/organization/
    // limits, or read the x-ratelimit-limit-requests/x-ratelimit-limit-tokens
    // response headers from one live call.
    //
    // 5 is deliberately conservative, NOT a raised default — a live test at 10
    // concurrent (this constant's old default) produced sustained "OpenAI HTTP
    // 429" errors across MULTIPLE simultaneously-started files, several of
    // which exhausted all retry attempts and fell back to unanalyzed
    // placeholders (real data loss, not just slowness). That result overrides
    // the earlier guess that 10-15 would be safely under a typical paid tier's
    // headroom — this account's real limit is evidently lower. Raise this only
    // after confirming real headroom via the dashboard/response-headers above.
    private static final int PARALLELISM =
        AppConfig.getInt("INGEST_PARALLELISM", "ingest.parallelism", 5);

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

    /** One discovered, not-yet-processed file — used only by the batch-mode
     * orchestration (see runBatchOrchestration), which needs every source's
     * files gathered up front (Phase A0) before tiering/processing any of
     * them, unlike the legacy per-source processFlat/processFilesTiered flow. */
    private record DiscoveredFile(Path file, String displayName, FileType cpyTypeHint) {}

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

        // ── Chunk-analysis: batch (structural rate-limit fix) or legacy synchronous ──
        boolean useBatchApi = AppConfig.getBoolean("INGEST_USE_BATCH_API", "ingest.use-batch-api", true);
        OpenAiBatchClient batchClient = useBatchApi ? OpenAiBatchClient.create() : null;
        System.out.println("  Batch API      : " + (batchClient != null
            ? "ENABLED (structural rate-limit fix — separate queue, ~24h completion window)"
            : "DISABLED" + (useBatchApi ? " (set OPENAI_API_KEY to enable)" : " (ingest.use-batch-api=false)")));

        if (batchClient != null && chunkAnalyzer != null) {
            runBatchOrchestration(inputRoot, cardDemoRoot, cacheDirName, chunkAnalyzer,
                cobolChunker, jclChunker, batchClient, graphBuilder, writer, outputDir,
                counters, enricher, allChunks);
        } else {
            runLegacyOrchestration(inputRoot, cardDemoRoot, cacheDirName,
                cobolChunker, jclChunker, graphBuilder, writer, outputDir,
                counters, enricher, allChunks);
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

    // ─── Legacy orchestration: today's proven, fully-synchronous flow, ────────
    // ─── unchanged — selected when ingest.use-batch-api=false or no API key ──

    private static void runLegacyOrchestration(Path inputRoot, Path cardDemoRoot, String cacheDirName,
                                                CobolChunker cobolChunker, JclChunker jclChunker,
                                                KnowledgeGraphBuilder graphBuilder, ChunkWriter writer,
                                                Path outputDir, Counters counters, LlmEnricher enricher,
                                                List<FileChunk> allChunks) {
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
    }

    // ─── Batch-mode orchestration ──────────────────────────────────────────────
    // Phase A0: discover every source's files up front, tier globally.
    // Phase A1: background batch chunk-analysis for small (single-window) files,
    //           concurrently with synchronous large-file chunking on the main
    //           thread; join, falling back to synchronous chunk() per small
    //           file that the batch pass didn't succeed on.
    // Phase B:  one enrichment batch pass over every file's chunks together.
    // Phase C:  unchanged tail — embedding-doc build, write, aggregate.

    private static void runBatchOrchestration(Path inputRoot, Path cardDemoRoot, String cacheDirName,
                                               LlmChunkAnalyzer chunkAnalyzer, CobolChunker cobolChunker,
                                               JclChunker jclChunker, OpenAiBatchClient batchClient,
                                               KnowledgeGraphBuilder graphBuilder, ChunkWriter writer,
                                               Path outputDir, Counters counters, LlmEnricher enricher,
                                               List<FileChunk> allChunks) {
        // Phase A0: discovery only — no chunking yet.
        List<DiscoveredFile> discovered = new ArrayList<>();
        discovered.addAll(listFlatDiscovered(inputRoot.resolve("copybooks"), FileType.COPYBOOK, "INSURANCE COPYBOOKS"));
        discovered.addAll(listFlatDiscovered(inputRoot.resolve("cobol"), FileType.COBOL_PROGRAM, "INSURANCE COBOL"));
        discovered.addAll(listFlatDiscovered(inputRoot.resolve("jcl"), null, "INSURANCE JCL"));

        if (Files.exists(cardDemoRoot)) {
            System.out.println("\n--- Discovering: REMOTE SOURCE — " + cacheDirName + " (recursive) ---");
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
                for (Path f : cardFiles) {
                    discovered.add(new DiscoveredFile(f, cardDemoRoot.relativize(f).toString(), FileType.COPYBOOK));
                }
            } catch (IOException e) {
                System.out.println("ERROR walking source: " + e.getMessage());
            }
        }

        if (discovered.isEmpty()) {
            System.out.println("  No files discovered — nothing to do.");
            return;
        }

        List<DiscoveredFile> small = new ArrayList<>();
        List<DiscoveredFile> large = new ArrayList<>();
        for (DiscoveredFile d : discovered) {
            if (countLines(d.file()) > LARGE_FILE_THRESHOLD_LINES) large.add(d); else small.add(d);
        }
        System.out.println("\n--- Batch-mode ingestion: " + discovered.size() + " file(s) total ("
            + small.size() + " small / " + large.size() + " large) ---");

        // Phase A1a (background): submit + poll the small-file batch chunk-analysis pass.
        ExecutorService batchExecutor = Executors.newSingleThreadExecutor();
        Future<Map<Path, LlmChunkAnalyzer.ChunkAnalysis>> batchFuture = batchExecutor.submit(() -> {
            List<BatchChunkAnalysisService.SmallFileTask> tasks = new ArrayList<>();
            for (DiscoveredFile d : small) {
                String ext = getExtension(d.file().getFileName().toString()).toLowerCase();
                FileType ft = resolvedFileType(ext, d.cpyTypeHint());
                if (ft == null) continue; // unsupported extension — chunkOnly's own switch will no-op it too
                List<String> lines;
                try { lines = Files.readAllLines(d.file()); }
                catch (IOException e) { continue; } // unreadable — falls back to chunkOnly below, which will hit + report the same error
                tasks.add(new BatchChunkAnalysisService.SmallFileTask(d.file(), d.displayName(), ft, lines));
            }
            BatchChunkAnalysisService svc = new BatchChunkAnalysisService(chunkAnalyzer, batchClient, outputDir);
            return svc.analyzeAll(tasks);
        });

        // Phase A1b (main thread, concurrent with the above): large files,
        // synchronously and one at a time — unchanged rationale from
        // processFilesTiered. Chunking only; enrichment happens together with
        // every other file's chunks in Phase B below.
        Map<Path, List<FileChunk>> resultsByFile = Collections.synchronizedMap(new LinkedHashMap<>());
        List<Path> largePaths = new ArrayList<>(large.size());
        Map<Path, DiscoveredFile> largeByPath = new HashMap<>();
        for (DiscoveredFile d : large) { largePaths.add(d.file()); largeByPath.put(d.file(), d); }
        runInBatches(largePaths, 1, file -> {
            DiscoveredFile d = largeByPath.get(file);
            resultsByFile.put(file, chunkOnly(file, d.displayName(), d.cpyTypeHint(), cobolChunker, jclChunker, graphBuilder, counters));
        });

        // Join.
        Map<Path, LlmChunkAnalyzer.ChunkAnalysis> batchSuccess;
        try {
            batchSuccess = batchFuture.get();
        } catch (Exception e) {
            System.out.println("  WARNING: batch chunk-analysis phase failed entirely (" + e.getMessage()
                + ") — all " + small.size() + " small file(s) fall back to synchronous analysis");
            batchSuccess = Map.of();
        } finally {
            batchExecutor.shutdown();
        }

        for (DiscoveredFile d : small) {
            Path file = d.file();
            LlmChunkAnalyzer.ChunkAnalysis analysis = batchSuccess.get(file);
            List<FileChunk> chunks = null;
            if (analysis != null) {
                String ext = getExtension(file.getFileName().toString()).toLowerCase();
                try {
                    List<String> lines = Files.readAllLines(file);
                    chunks = switch (ext) {
                        case "cbl" -> cobolChunker.fromAnalysis(d.displayName(), FileType.COBOL_PROGRAM, lines, analysis, graphBuilder);
                        case "cpy" -> cobolChunker.fromAnalysis(d.displayName(), d.cpyTypeHint(), lines, analysis, graphBuilder);
                        case "jcl" -> jclChunker.fromAnalysis(d.displayName(), lines, analysis, graphBuilder);
                        default -> List.of();
                    };
                    if (chunks.isEmpty()) {
                        System.out.println("  " + d.displayName() + " ... 0 chunks");
                    } else {
                        counters.files.incrementAndGet();
                        counters.chunks.addAndGet(chunks.size());
                        System.out.println("  " + d.displayName() + " ... " + chunks.size() + " chunks (batch)");
                    }
                } catch (Exception e) {
                    System.out.println("  " + d.displayName()
                        + " ... post-analysis ERROR, falling back to synchronous: " + e.getMessage());
                    chunks = null;
                }
            }
            if (chunks == null) {
                chunks = chunkOnly(file, d.displayName(), d.cpyTypeHint(), cobolChunker, jclChunker, graphBuilder, counters);
            }
            resultsByFile.put(file, chunks);
        }

        // Phase B: one enrichment batch pass over every file's chunks together.
        List<FileChunk> everything = new ArrayList<>();
        for (List<FileChunk> chunks : resultsByFile.values()) everything.addAll(chunks);

        if (enricher != null && !everything.isEmpty()) {
            new BatchEnrichmentService(enricher, batchClient, outputDir).enrichAll(everything);
        }

        // Phase C: unchanged tail — embedding-doc build, write, aggregate.
        for (Map.Entry<Path, List<FileChunk>> entry : resultsByFile.entrySet()) {
            List<FileChunk> chunks = entry.getValue();
            if (chunks.isEmpty()) continue;
            EmbeddingDocumentBuilder.process(chunks);
            try {
                writer.writeChunks(chunks, outputDir, entry.getKey().getFileName().toString());
            } catch (IOException e) {
                System.out.println("  " + entry.getKey().getFileName() + " ... WRITE ERROR: " + e.getMessage());
                continue;
            }
            allChunks.addAll(chunks);
        }
    }

    private static List<DiscoveredFile> listFlatDiscovered(Path dir, FileType cpyTypeHint, String label) {
        List<DiscoveredFile> result = new ArrayList<>();
        if (!Files.exists(dir)) return result;
        System.out.println("\n--- Discovering: " + label + " ---");

        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        } catch (IOException e) {
            System.out.println("ERROR listing " + dir + ": " + e.getMessage());
            return result;
        }

        System.out.println("  Found " + files.size() + " file(s)");
        for (Path f : files) result.add(new DiscoveredFile(f, f.getFileName().toString(), cpyTypeHint));
        return result;
    }

    /** Mirrors processOneFile/chunkOnly's extension switch — the only place
     * that decides which FileType a file's whole-file batch prompt is built
     * with, kept as its own method so it can never silently drift from that
     * switch. Null means "not a chunkable extension," same as chunkOnly's
     * switch default. */
    private static FileType resolvedFileType(String ext, FileType cpyTypeHint) {
        return switch (ext) {
            case "cbl" -> FileType.COBOL_PROGRAM;
            case "cpy" -> cpyTypeHint;
            case "jcl" -> FileType.JCL;
            default -> null;
        };
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
        List<FileChunk> chunks = chunkOnly(file, displayName, cpyTypeHint, cobolChunker, jclChunker, graphBuilder, counters);
        if (chunks.isEmpty()) return;

        if (enricher != null) enricher.enrichChunks(chunks);
        EmbeddingDocumentBuilder.process(chunks);

        try {
            writer.writeChunks(chunks, outputDir, file.getFileName().toString());
        } catch (IOException e) {
            System.out.println("  " + displayName + " ... WRITE ERROR: " + e.getMessage());
            return;
        }

        allChunks.addAll(chunks);
    }

    /** The chunking-only half of the old processOneFile — shared by the
     * legacy synchronous path above, the batch-mode large-file loop, and the
     * batch-mode small-file fallback (see runBatchOrchestration), so all
     * three ways a file can end up chunked go through byte-for-byte the same
     * dispatch/logging/counting logic. Enrichment, embedding-doc building,
     * and writing are each caller's own concern — batch mode defers
     * enrichment to one combined pass over every file's chunks (Phase B). */
    private static List<FileChunk> chunkOnly(Path file, String displayName, FileType cpyTypeHint,
                                              CobolChunker cobolChunker, JclChunker jclChunker,
                                              KnowledgeGraphBuilder graphBuilder, Counters counters) {
        String ext = getExtension(file.getFileName().toString()).toLowerCase();

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
            return List.of();
        }

        if (chunks.isEmpty()) {
            System.out.println("  " + displayName + " ... 0 chunks");
            return chunks;
        }

        counters.files.incrementAndGet();
        counters.chunks.addAndGet(chunks.size());
        System.out.println("  " + displayName + " ... " + chunks.size() + " chunks");
        return chunks;
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

        runConcurrently(small, PARALLELISM, task);
        runInBatches(large, 1, task);
    }

    private static long countLines(Path file) {
        try (Stream<String> lines = Files.lines(file)) {
            return lines.count();
        } catch (IOException e) {
            return 0; // Unreadable — treat as small; the real processing step reports the actual error.
        }
    }

    // ─── Concurrent execution for the SMALL-file tier: virtual threads, ───────
    // ─── continuously fed, bounded by a semaphore ──────────────────────────────
    //
    // Replaces the previous "batch of PARALLELISM, invokeAll (wait for the
    // WHOLE batch), next batch of PARALLELISM" pattern, which wasted real
    // throughput: if 9 of 10 files in a batch finished in seconds but the 10th
    // hit an OpenAI timeout/retry cascade (routine under load — see
    // LlmChunkAnalyzer's retry handling), the other 9 worker threads sat
    // completely idle until that one straggler finished, before the NEXT batch
    // of 10 could even start. Measured directly on a real run: large stretches
    // of near-zero CPU usage over many real wall-clock minutes, consistent with
    // exactly this "whole batch blocked on one straggler" pattern.
    //
    // This version submits every file's task immediately (no batch boundaries
    // to stall on) to Executors.newVirtualThreadPerTaskExecutor() — virtual
    // threads are cheap enough that "submit all of them at once" is fine even
    // for thousands of files; each one mostly just blocks on HTTP I/O, which is
    // exactly what virtual threads are for. A Semaphore caps how many are
    // actually mid-flight (acquired before the OpenAI call, released after),
    // so a file that starts while 14 others are still running just waits its
    // turn WITHOUT blocking any other already-in-flight file's progress —
    // unlike the old batch-and-wait pattern, one slow file never holds up
    // files that would otherwise already be moving on to the next one.
    // Small, fixed stagger between SUBMITTING each of the FIRST maxConcurrent
    // tasks only — not a rate limiter by itself (the semaphore above is what
    // actually bounds in-flight requests), but avoids that initial burst all
    // hitting OpenAI in the exact same instant, which measured directly as a
    // "thundering herd": several simultaneously-started files all getting HTTP
    // 429 on their very first attempt together, correlated rather than
    // independent failures. Only the initial burst needs this — every
    // submission after that already gets naturally paced by permits.acquire()
    // blocking until an earlier file finishes and releases its permit, so
    // staggering every submission (not just the first maxConcurrent) would add
    // real, unbounded wall-clock cost on a large run (e.g. 250ms x 20,000
    // files = ~83 minutes of pure submission delay) for no further benefit.
    private static final long SUBMIT_STAGGER_MS = 250;

    private static void runConcurrently(List<Path> files, int maxConcurrent, Consumer<Path> task) {
        if (files.isEmpty()) return;
        Semaphore permits = new Semaphore(Math.max(1, maxConcurrent));
        CountDownLatch done = new CountDownLatch(files.size());

        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            int index = 0;
            for (Path file : files) {
                virtualThreads.submit(() -> {
                    try {
                        permits.acquire();
                        try {
                            task.accept(file);
                        } finally {
                            permits.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        System.out.println("  " + file + " ... UNEXPECTED ERROR: " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });
                index++;
                if (index < maxConcurrent) {
                    try {
                        Thread.sleep(SUBMIT_STAGGER_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── Batched sequential execution: used ONLY for the large-file tier ──────
    // (batchSize=1 from processFilesTiered above) — kept deliberately simple
    // and unchanged: one large, multi-window file at a time, its own undivided
    // attention, no competing with other concurrent calls for rate-limit
    // headroom while it works through many windows. See processFilesTiered's
    // comment for why large files stay out of the concurrent tier entirely.

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