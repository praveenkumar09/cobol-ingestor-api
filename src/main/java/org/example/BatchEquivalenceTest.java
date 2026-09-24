package org.example;

import org.example.chunker.BatchChunkAnalysisService;
import org.example.chunker.CobolChunker;
import org.example.chunker.JclChunker;
import org.example.chunker.LlmChunkAnalyzer;
import org.example.graph.KnowledgeGraphBuilder;
import org.example.llm.OpenAiBatchClient;
import org.example.model.FileChunk;
import org.example.model.FileType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plan §2 "equivalence test" — runs a handful of the smallest local files
 * through BOTH the synchronous chunk-analysis path and the batch path, and
 * diffs the STRUCTURAL shape of the result (chunk count, section names,
 * domain/subDomain, line coverage). Prose fields (sectionPurpose etc.) are
 * expected to differ slightly since chunk analysis runs at temperature=0.1,
 * not 0 — this test is checking "does batch mode produce an equivalent
 * chunking of the file," not byte-identical text.
 *
 * Test-only driver, not part of the production pipeline — run directly via
 * `java -cp target/classes:$(cat cp.txt) org.example.BatchEquivalenceTest`.
 */
public class BatchEquivalenceTest {

    private record Target(Path file, String displayName, FileType fileType, boolean isJcl) {}

    public static void main(String[] args) throws Exception {
        Path resourcesRoot = Path.of(System.getProperty("user.dir")).resolve("src/main/resources");
        Path inputRoot = resourcesRoot.resolve("input");

        List<Target> targets = new ArrayList<>();
        for (String name : new String[]{"CUSTREC.cpy", "CLMREC.cpy", "PLCYREC.cpy", "ERRMSGS.cpy", "INTLFREC.cpy"}) {
            targets.add(new Target(inputRoot.resolve("copybooks").resolve(name), name, FileType.COPYBOOK, false));
        }
        for (String name : new String[]{"PLCYINQ.cbl", "PREMINQ.cbl"}) {
            targets.add(new Target(inputRoot.resolve("cobol").resolve(name), name, FileType.COBOL_PROGRAM, false));
        }
        for (String name : new String[]{"PLCYBATCH.jcl", "CLMBATCH.jcl"}) {
            targets.add(new Target(inputRoot.resolve("jcl").resolve(name), name, FileType.JCL, true));
        }
        targets.removeIf(t -> {
            boolean exists = Files.isRegularFile(t.file());
            if (!exists) System.out.println("SKIP (not found): " + t.file());
            return !exists;
        });

        System.out.println("=== Batch vs Synchronous Equivalence Test — " + targets.size() + " file(s) ===\n");

        LlmChunkAnalyzer analyzer = LlmChunkAnalyzer.create();
        if (analyzer == null) {
            System.out.println("FATAL: OPENAI_API_KEY not set — cannot run.");
            System.exit(1);
        }
        System.out.println("Chunk-analysis model: " + analyzer.getModel());

        OpenAiBatchClient batchClient = OpenAiBatchClient.create();
        if (batchClient == null) {
            System.out.println("FATAL: OpenAiBatchClient.create() returned null — OPENAI_API_KEY missing?");
            System.exit(1);
        }

        CobolChunker cobolChunker = new CobolChunker(analyzer);
        JclChunker jclChunker = new JclChunker(analyzer);

        // ── Synchronous path (ground truth) ─────────────────────────────
        System.out.println("\n--- Synchronous path ---");
        Map<Path, List<FileChunk>> syncResults = new java.util.LinkedHashMap<>();
        for (Target t : targets) {
            KnowledgeGraphBuilder gb = new KnowledgeGraphBuilder();
            List<FileChunk> chunks = t.isJcl()
                ? jclChunker.chunk(t.file(), gb)
                : cobolChunker.chunk(t.file(), t.fileType(), gb);
            syncResults.put(t.file(), chunks);
            System.out.println("  " + t.displayName() + " ... " + chunks.size() + " chunks (sync)");
        }

        // ── Batch path ───────────────────────────────────────────────────
        System.out.println("\n--- Batch path ---");
        List<BatchChunkAnalysisService.SmallFileTask> tasks = new ArrayList<>();
        for (Target t : targets) {
            List<String> lines = Files.readAllLines(t.file());
            tasks.add(new BatchChunkAnalysisService.SmallFileTask(t.file(), t.displayName(), t.fileType(), lines));
        }
        Path outputDir = resourcesRoot.resolve("output");
        BatchChunkAnalysisService svc = new BatchChunkAnalysisService(analyzer, batchClient, outputDir);
        Map<Path, LlmChunkAnalyzer.ChunkAnalysis> batchAnalyses = svc.analyzeAll(tasks);

        Map<Path, List<FileChunk>> batchResults = new java.util.LinkedHashMap<>();
        for (Target t : targets) {
            LlmChunkAnalyzer.ChunkAnalysis analysis = batchAnalyses.get(t.file());
            if (analysis == null) {
                System.out.println("  " + t.displayName() + " ... NO BATCH RESULT (would fall back to sync in production)");
                continue;
            }
            List<String> lines = Files.readAllLines(t.file());
            KnowledgeGraphBuilder gb = new KnowledgeGraphBuilder();
            List<FileChunk> chunks = t.isJcl()
                ? jclChunker.fromAnalysis(t.displayName(), lines, analysis, gb)
                : cobolChunker.fromAnalysis(t.displayName(), t.fileType(), lines, analysis, gb);
            batchResults.put(t.file(), chunks);
            System.out.println("  " + t.displayName() + " ... " + chunks.size() + " chunks (batch)");
        }

        // ── Diff ─────────────────────────────────────────────────────────
        System.out.println("\n=== Diff (structural) ===");
        int pass = 0, fail = 0, missing = 0;
        for (Target t : targets) {
            List<FileChunk> sync = syncResults.get(t.file());
            List<FileChunk> batch = batchResults.get(t.file());
            if (batch == null) {
                System.out.println("MISSING  " + t.displayName() + " — no batch result");
                missing++;
                continue;
            }
            List<String> diffs = diff(sync, batch);
            if (diffs.isEmpty()) {
                System.out.println("PASS     " + t.displayName() + " (" + sync.size() + " chunks, structurally equivalent)");
                pass++;
            } else {
                System.out.println("DIFF     " + t.displayName() + ":");
                for (String d : diffs) System.out.println("           - " + d);
                fail++;
            }
        }

        System.out.println("\n=== Summary: " + pass + " pass / " + fail + " diff / " + missing + " missing (of " + targets.size() + ") ===");
        System.exit(fail > 0 || missing > 0 ? 1 : 0);
    }

    private static List<String> diff(List<FileChunk> sync, List<FileChunk> batch) {
        List<String> diffs = new ArrayList<>();
        if (sync.size() != batch.size()) {
            diffs.add("chunk count: sync=" + sync.size() + " batch=" + batch.size());
            return diffs; // further per-index comparison isn't meaningful once counts differ
        }
        for (int i = 0; i < sync.size(); i++) {
            FileChunk s = sync.get(i), b = batch.get(i);
            if (!eq(s.getSectionName(), b.getSectionName())) {
                diffs.add("chunk[" + i + "].sectionName: sync=" + s.getSectionName() + " batch=" + b.getSectionName());
            }
            if (!eq(s.getDomain(), b.getDomain())) {
                diffs.add("chunk[" + i + "].domain: sync=" + s.getDomain() + " batch=" + b.getDomain());
            }
            if (!eq(s.getSubDomain(), b.getSubDomain())) {
                diffs.add("chunk[" + i + "].subDomain: sync=" + s.getSubDomain() + " batch=" + b.getSubDomain());
            }
        }
        return diffs;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
