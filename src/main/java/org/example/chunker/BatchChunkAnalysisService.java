package org.example.chunker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppConfig;
import org.example.llm.BatchJsonl;
import org.example.llm.BatchManifest;
import org.example.llm.BatchPolling;
import org.example.llm.OpenAiBatchClient;
import org.example.model.FileType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates Phase A1's chunk-analysis batch(es): every SMALL file (single
 * chunking window — see LlmChunkAnalyzer.getWindowLines) has its whole-file
 * prompt submitted through OpenAI's Batch API in one pass instead of
 * competing, one synchronous HTTP call per file, for the same rate-limit
 * budget every large file and every enrichment call also wants. Large,
 * multi-window files are NOT eligible here — see Main's tiering and the
 * plan's "stays synchronous" rationale — and stay on the existing
 * CarriedContext-dependent synchronous path unchanged.
 *
 * <p>A batch entry that fails, errors, or comes back unparseable is simply
 * left out of the returned map — the caller (Main) falls back to the exact
 * same synchronous {@code chunk()} call used before this class existed for
 * any file not present in the result, rather than this class inventing a
 * second retry system of its own.
 */
public class BatchChunkAnalysisService {

    public record SmallFileTask(Path file, String displayName, FileType fileType, List<String> lines) {}

    private final LlmChunkAnalyzer analyzer;
    private final OpenAiBatchClient client;
    private final Path outputDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public BatchChunkAnalysisService(LlmChunkAnalyzer analyzer, OpenAiBatchClient client, Path outputDir) {
        this.analyzer = analyzer;
        this.client = client;
        this.outputDir = outputDir;
    }

    /** Returns successful analyses keyed by file path — any task not present
     * in the returned map should be re-processed synchronously by the caller. */
    public Map<Path, LlmChunkAnalyzer.ChunkAnalysis> analyzeAll(List<SmallFileTask> tasks) {
        if (tasks.isEmpty()) return Map.of();

        // custom_id = the task's index in this exact list — a synthetic global
        // index assigned up front (see plan), so it stays unique even once
        // tasks are partitioned across multiple JSONL groups below, without
        // depending on chunkId/fileName uniqueness across the whole corpus.
        List<BatchJsonl.RequestLine> requestLines = new ArrayList<>(tasks.size());
        List<Long> tokenEstimates = new ArrayList<>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            SmallFileTask t = tasks.get(i);
            String prompt = analyzer.buildWholeFilePrompt(t.displayName(), t.fileType(), t.lines());
            Map<String, Object> body = analyzer.buildBatchRequestBody(prompt);
            requestLines.add(new BatchJsonl.RequestLine(String.valueOf(i), "POST", "/v1/chat/completions", body));
            tokenEstimates.add(BatchJsonl.estimateTokens(prompt, body));
        }

        int maxRequestsPerFile = AppConfig.getInt(
            "OPENAI_BATCH_MAX_REQUESTS_PER_FILE", "openai.batch.max-requests-per-file", 50000);
        long maxFileBytes = AppConfig.getInt(
            "OPENAI_BATCH_MAX_FILE_BYTES", "openai.batch.max-file-bytes", 150_000_000);
        long maxEnqueuedTokens = AppConfig.getInt(
            "OPENAI_BATCH_MAX_ENQUEUED_TOKENS", "openai.batch.max-enqueued-tokens", 65000);
        long pollIntervalMs = AppConfig.getInt(
            "OPENAI_BATCH_POLL_INTERVAL_SECONDS", "openai.batch.poll-interval-seconds", 30) * 1000L;
        long maxWaitMs = AppConfig.getInt(
            "OPENAI_BATCH_MAX_WAIT_HOURS", "openai.batch.max-wait-hours", 26) * 3_600_000L;

        List<List<BatchJsonl.RequestLine>> groups;
        try {
            groups = BatchJsonl.partition(requestLines, tokenEstimates, maxRequestsPerFile, maxFileBytes, maxEnqueuedTokens, mapper);
        } catch (Exception e) {
            System.out.println("  WARNING: batch chunk-analysis partitioning failed (" + e.getMessage()
                + ") — all " + tasks.size() + " small file(s) fall back to synchronous analysis");
            return Map.of();
        }

        System.out.println("  Batch chunk-analysis: " + tasks.size() + " file(s) -> "
            + groups.size() + " batch file(s) (sequential — see openai.batch.max-enqueued-tokens)");

        // Groups are submitted and polled to completion ONE AT A TIME, not all
        // up front: OpenAI's per-org enqueued-token cap applies across EVERY
        // batch currently in progress for a model, not per-batch — submitting
        // several groups back-to-back would just re-trigger the same
        // token_limit_exceeded failure across groups instead of within one.
        Map<String, BatchJsonl.ResultLine> allResults = new ConcurrentHashMap<>();
        for (int g = 0; g < groups.size(); g++) {
            List<BatchJsonl.RequestLine> group = groups.get(g);
            String batchId;
            try {
                Path jsonlFile = Files.createTempFile("chunk-analysis-batch-", ".jsonl");
                BatchJsonl.writeJsonl(jsonlFile, group, mapper);
                String fileId = client.uploadJsonlFile(jsonlFile);
                batchId = client.createBatch(fileId, "/v1/chat/completions");
                BatchManifest.append(outputDir, batchId, "chunk-analysis", group.size());
                Files.deleteIfExists(jsonlFile);
                System.out.println("  Batch chunk-analysis: submitted " + batchId + " (group " + (g + 1)
                    + "/" + groups.size() + ", " + group.size() + " files)");
            } catch (Exception e) {
                System.out.println("  WARNING: failed to submit chunk-analysis batch group " + (g + 1)
                    + " (" + group.size() + " files) — they fall back to synchronous analysis: " + e.getMessage());
                continue;
            }
            BatchPolling.pollAndCollect(client, List.of(batchId), pollIntervalMs, maxWaitMs, "chunk-analysis", mapper, allResults);
        }

        Map<Path, LlmChunkAnalyzer.ChunkAnalysis> succeeded = new LinkedHashMap<>();
        int failCount = 0;
        for (int i = 0; i < tasks.size(); i++) {
            SmallFileTask t = tasks.get(i);
            BatchJsonl.ResultLine result = allResults.get(String.valueOf(i));
            if (result == null || !result.succeeded()) { failCount++; continue; }
            String content = BatchJsonl.extractMessageContent(result);
            if (content == null) { failCount++; continue; }
            try {
                LlmChunkAnalyzer.ChunkAnalysis parsed = analyzer.parseWholeFileResponse(content, t.lines().size());
                LlmChunkAnalyzer.ChunkAnalysis finished =
                    analyzer.finishWholeFileAnalysis(parsed, t.lines().size(), t.displayName());
                succeeded.put(t.file(), finished);
            } catch (Exception e) {
                failCount++;
            }
        }
        System.out.println("  Batch chunk-analysis: " + succeeded.size() + "/" + tasks.size()
            + " succeeded via batch" + (failCount > 0 ? " (" + failCount + " falling back to synchronous)" : ""));
        return succeeded;
    }
}
