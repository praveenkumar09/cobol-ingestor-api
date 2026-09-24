package org.example.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared "poll every submitted batch to completion, download whatever
 * finished, merge results by custom_id" logic — used identically by
 * BatchChunkAnalysisService and BatchEnrichmentService so a corpus needing
 * multiple JSONL groups (see BatchJsonl.partition) has every group's wait
 * overlapped on its own virtual thread rather than waited on one at a time,
 * which would otherwise multiply a real multi-hour completion_window by the
 * number of groups.
 *
 * <p>A group that times out or never produces an output file is logged and
 * simply contributes nothing to {@code allResults} — every caller already
 * treats a missing custom_id as "fall back to the synchronous path for this
 * one," so no separate failure-tracking is needed here.
 */
public class BatchPolling {

    public static void pollAndCollect(OpenAiBatchClient client, List<String> batchIds,
                                       long pollIntervalMs, long maxWaitMs, String purpose,
                                       ObjectMapper mapper, Map<String, BatchJsonl.ResultLine> allResults) {
        CountDownLatch latch = new CountDownLatch(batchIds.size());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String batchId : batchIds) {
                pool.submit(() -> {
                    try {
                        OpenAiBatchClient.BatchStatus status = client.pollUntilTerminal(batchId, pollIntervalMs, maxWaitMs);
                        if (status.outputFileId() != null) {
                            String raw = client.downloadFileContent(status.outputFileId());
                            allResults.putAll(BatchJsonl.readResultsByCustomId(raw, mapper));
                        }
                        if (status.errorFileId() != null) {
                            String raw = client.downloadFileContent(status.errorFileId());
                            allResults.putAll(BatchJsonl.readResultsByCustomId(raw, mapper));
                        }
                        if (!status.succeeded() && status.outputFileId() == null) {
                            System.out.println("  WARNING: " + purpose + " batch " + batchId + " ended '"
                                + status.status() + "' with no output file — its entries fall back to the synchronous path");
                        }
                    } catch (Exception e) {
                        System.out.println("  WARNING: " + purpose + " batch " + batchId
                            + " failed/timed out — its entries fall back to the synchronous path: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
