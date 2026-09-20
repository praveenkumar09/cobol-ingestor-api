package org.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.graph.KnowledgeGraph;
import org.example.llm.EmbeddingClient;
import org.example.model.FileChunk;
import org.example.store.GraphStore;
import org.example.store.VectorStore;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Recovery entry point for interrupted ingestion runs.
 *
 * Main.java stops before the embed/store step, so the chunk JSON files it already
 * wrote under {@code src/main/resources/output} (one {@code *_chunks.json} per
 * source file, plus {@code knowledge_graph.json}) are re-read from disk here and
 * pushed into pgvector + Neo4j, without re-running the fetch/chunk/LLM-enrich phase.
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass=org.example.ResumeIngestionFromChunks
 *   (optionally pass the output dir as the first CLI arg to override the default)
 */
public class ResumeIngestionFromChunks {

    public static void main(String[] args) {
        System.out.println("============================================");
        System.out.println("  Resume Ingestion — Chunks → Vector + Graph DB");
        System.out.println("============================================");

        Path outputDir = (args.length > 0) ? Path.of(args[0]) : findOutputDir();
        if (!Files.isDirectory(outputDir)) {
            System.err.println("ERROR: output directory not found: " + outputDir.toAbsolutePath());
            return;
        }
        System.out.println("  Output directory : " + outputDir.toAbsolutePath());

        ObjectMapper mapper = new ObjectMapper();

        // ── Load all previously written chunks ─────────────────────────
        List<FileChunk> allChunks = loadChunks(outputDir, mapper);
        System.out.println("  Chunks loaded    : " + allChunks.size());
        if (allChunks.isEmpty()) {
            System.out.println("  Nothing to embed — exiting.");
            return;
        }

        // ── Embeddings ───────────────────────────────────────────────────
        EmbeddingClient embedder = EmbeddingClient.create();
        System.out.println("  Embeddings       : "
            + (embedder != null ? "ENABLED (model: " + embedder.getModel() + ")" : "DISABLED (set OPENAI_API_KEY)"));
        Map<String, float[]> embeddingMap = embedAll(allChunks, embedder);

        // ── Vector DB ────────────────────────────────────────────────────
        storeToVectorDB(allChunks, embeddingMap);

        // ── Knowledge graph (from knowledge_graph.json, if present) ─────
        KnowledgeGraph graph = loadGraph(outputDir, mapper);
        if (graph != null) {
            storeToNeo4j(graph);
        } else {
            System.out.println("\n--- Knowledge Graph ---");
            System.out.println("  knowledge_graph.json not found — skipping Neo4j load");
        }

        System.out.println("\n============================================");
        System.out.println("  RESUME COMPLETE");
        System.out.printf("  Chunks loaded      : %d%n", allChunks.size());
        System.out.printf("  Embeddings stored  : %d%n", embeddingMap.size());
        System.out.println("============================================");
    }

    // ─── Load chunk JSON files ──────────────────────────────────────────────

    private static List<FileChunk> loadChunks(Path outputDir, ObjectMapper mapper) {
        List<FileChunk> allChunks = new ArrayList<>();
        List<Path> chunkFiles;
        try (Stream<Path> stream = Files.list(outputDir)) {
            chunkFiles = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith("_chunks.json"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        } catch (IOException e) {
            System.err.println("ERROR listing " + outputDir + ": " + e.getMessage());
            return allChunks;
        }

        for (Path file : chunkFiles) {
            try {
                List<FileChunk> chunks = mapper.readValue(file.toFile(), new TypeReference<List<FileChunk>>() {});
                allChunks.addAll(chunks);
                System.out.println("  Read: " + file.getFileName() + " (" + chunks.size() + " chunks)");
            } catch (IOException e) {
                System.err.println("  WARNING: failed to parse " + file.getFileName() + ": " + e.getMessage());
            }
        }
        return allChunks;
    }

    private static KnowledgeGraph loadGraph(Path outputDir, ObjectMapper mapper) {
        Path graphFile = outputDir.resolve("knowledge_graph.json");
        if (!Files.exists(graphFile)) return null;
        try {
            return mapper.readValue(graphFile.toFile(), KnowledgeGraph.class);
        } catch (IOException e) {
            System.err.println("  WARNING: failed to parse knowledge_graph.json: " + e.getMessage());
            return null;
        }
    }

    // ─── Embed all embeddable chunks in BATCH_SIZE batches ─────────────────

    private static Map<String, float[]> embedAll(List<FileChunk> allChunks, EmbeddingClient embedder) {
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

    // ─── Store to PostgreSQL / pgvector ─────────────────────────────────────

    private static void storeToVectorDB(List<FileChunk> allChunks, Map<String, float[]> embeddingMap) {
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

    // ─── Store knowledge graph to Neo4j ─────────────────────────────────────

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

    // ─── Utilities ───────────────────────────────────────────────────────────

    private static Path findOutputDir() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path candidate = cwd.resolve("src/main/resources/output");
        if (Files.isDirectory(candidate)) return candidate;
        candidate = cwd.resolve("../src/main/resources/output").normalize();
        if (Files.isDirectory(candidate)) return candidate;
        return cwd.resolve("src/main/resources/output");
    }
}
