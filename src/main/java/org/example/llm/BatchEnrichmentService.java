package org.example.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppConfig;
import org.example.model.FileChunk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates Phase B: one enrichment batch pass over every non-trivial
 * chunk from EVERY file in the run (small and large tier alike — enrichment
 * has zero cross-chunk dependency regardless of file size, unlike chunk
 * analysis's CarriedContext), instead of the per-file synchronous {@link
 * LlmEnricher#enrichChunks} calls this same traffic used to make. This is
 * the single most rate-limit-contested path measured in this codebase (see
 * LlmEnricher's own semaphore/circuit-breaker, which this class doesn't need
 * — the Batch API queue is a separate budget entirely).
 *
 * <p>A chunk whose batch entry fails, errors, or comes back empty is simply
 * left with whatever sectionPurpose chunk-analysis already gave it — exactly
 * today's existing fallback semantics, no change needed there at all.
 */
public class BatchEnrichmentService {

    private final LlmEnricher enricher;
    private final OpenAiBatchClient client;
    private final Path outputDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public BatchEnrichmentService(LlmEnricher enricher, OpenAiBatchClient client, Path outputDir) {
        this.enricher = enricher;
        this.client = client;
        this.outputDir = outputDir;
    }

    /** Enriches sectionPurpose in-place on every eligible chunk across the
     * whole run; chunks left unenriched simply keep their existing purpose. */
    public void enrichAll(List<FileChunk> allChunks) {
        List<FileChunk> eligible = new ArrayList<>();
        for (FileChunk c : allChunks) {
            if (!enricher.isTrivialChunk(c)) eligible.add(c);
        }
        if (eligible.isEmpty()) return;

        // custom_id = this list's own index — own global index namespace,
        // independent of BatchChunkAnalysisService's (see plan), and immune
        // to any chunkId collision risk across files.
        List<BatchJsonl.RequestLine> requestLines = new ArrayList<>(eligible.size());
        List<Long> tokenEstimates = new ArrayList<>(eligible.size());
        for (int i = 0; i < eligible.size(); i++) {
            String prompt = enricher.buildEnrichPrompt(eligible.get(i));
            Map<String, Object> body = enricher.buildRequestBody(prompt);
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
            System.out.println("  WARNING: batch enrichment partitioning failed (" + e.getMessage()
                + ") — " + eligible.size() + " chunk(s) keep their chunk-analysis purpose, unenriched");
            return;
        }

        System.out.println("  Batch enrichment: " + eligible.size() + " chunk(s) -> "
            + groups.size() + " batch file(s) (sequential — see openai.batch.max-enqueued-tokens)");

        // Sequential, same reasoning as BatchChunkAnalysisService: the
        // enqueued-token cap applies across every in-progress batch for a
        // model at once, not per batch.
        Map<String, BatchJsonl.ResultLine> allResults = new ConcurrentHashMap<>();
        for (int g = 0; g < groups.size(); g++) {
            List<BatchJsonl.RequestLine> group = groups.get(g);
            String batchId;
            try {
                Path jsonlFile = Files.createTempFile("enrichment-batch-", ".jsonl");
                BatchJsonl.writeJsonl(jsonlFile, group, mapper);
                String fileId = client.uploadJsonlFile(jsonlFile);
                batchId = client.createBatch(fileId, "/v1/chat/completions");
                BatchManifest.append(outputDir, batchId, "enrichment", group.size());
                Files.deleteIfExists(jsonlFile);
                System.out.println("  Batch enrichment: submitted " + batchId + " (group " + (g + 1)
                    + "/" + groups.size() + ", " + group.size() + " chunks)");
            } catch (Exception e) {
                System.out.println("  WARNING: failed to submit enrichment batch group " + (g + 1)
                    + " (" + group.size() + " chunks) — they keep their chunk-analysis purpose, unenriched: " + e.getMessage());
                continue;
            }
            BatchPolling.pollAndCollect(client, List.of(batchId), pollIntervalMs, maxWaitMs, "enrichment", mapper, allResults);
        }

        int applied = 0;
        for (int i = 0; i < eligible.size(); i++) {
            BatchJsonl.ResultLine result = allResults.get(String.valueOf(i));
            if (result == null || !result.succeeded()) continue;
            String content = BatchJsonl.extractMessageContent(result);
            if (content == null || content.isBlank()) continue;
            eligible.get(i).setSectionPurpose(content.trim());
            applied++;
        }
        System.out.println("  Batch enrichment: " + applied + "/" + eligible.size()
            + " chunk(s) enriched via batch (remainder keep their chunk-analysis purpose)");
    }
}
