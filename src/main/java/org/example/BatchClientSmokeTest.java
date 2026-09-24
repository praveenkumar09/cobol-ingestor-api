package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.llm.BatchJsonl;
import org.example.llm.OpenAiBatchClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One-off manual verification of OpenAiBatchClient's plumbing (upload,
 * create, poll, download) against the real Batch API, with trivial prompts —
 * plan §1 "plumbing-only smoke test", run BEFORE trusting this client with
 * real COBOL chunk-analysis/enrichment prompts. Not part of the normal build
 * or ingestion run.
 *
 * Usage: java -cp target/classes:... org.example.BatchClientSmokeTest
 */
public class BatchClientSmokeTest {

    public static void main(String[] args) throws Exception {
        OpenAiBatchClient client = OpenAiBatchClient.create();
        if (client == null) {
            System.err.println("OPENAI_API_KEY not set");
            System.exit(1);
        }
        ObjectMapper mapper = new ObjectMapper();

        List<BatchJsonl.RequestLine> requests = List.of(
            new BatchJsonl.RequestLine("smoke-1", "POST", "/v1/chat/completions",
                Map.of("model", "gpt-4o-mini", "messages",
                    List.of(Map.of("role", "user", "content", "Reply with exactly the word PONG and nothing else.")),
                    "max_tokens", 10)),
            new BatchJsonl.RequestLine("smoke-2", "POST", "/v1/chat/completions",
                Map.of("model", "gpt-4o-mini", "messages",
                    List.of(Map.of("role", "user", "content", "Reply with exactly the word PONG and nothing else.")),
                    "max_tokens", 10)),
            new BatchJsonl.RequestLine("smoke-3", "POST", "/v1/chat/completions",
                Map.of("model", "gpt-4o-mini", "messages",
                    List.of(Map.of("role", "user", "content", "Reply with exactly the word PONG and nothing else.")),
                    "max_tokens", 10))
        );

        Path jsonlFile = Files.createTempFile("batch-smoke-", ".jsonl");
        System.out.println("Writing " + requests.size() + " requests to " + jsonlFile);
        BatchJsonl.writeJsonl(jsonlFile, requests, mapper);
        System.out.println("--- input JSONL ---");
        System.out.println(Files.readString(jsonlFile));

        System.out.println("Uploading...");
        String fileId = client.uploadJsonlFile(jsonlFile);
        System.out.println("Uploaded: " + fileId);

        System.out.println("Creating batch...");
        String batchId = client.createBatch(fileId, "/v1/chat/completions");
        System.out.println("Batch created: " + batchId);

        System.out.println("Polling (every 10s, max 10 min for this smoke test)...");
        OpenAiBatchClient.BatchStatus status = client.pollUntilTerminal(batchId, 10_000, 10 * 60_000L);
        System.out.println("Final status: " + status);

        if (!status.succeeded()) {
            System.err.println("Batch did not complete successfully — status: " + status.status());
            System.exit(1);
        }

        System.out.println("Downloading output file " + status.outputFileId() + "...");
        String outputRaw = client.downloadFileContent(status.outputFileId());
        System.out.println("--- raw output JSONL ---");
        System.out.println(outputRaw);

        Map<String, BatchJsonl.ResultLine> results = BatchJsonl.readResultsByCustomId(outputRaw, mapper);
        System.out.println("--- parsed results ---");
        boolean allOk = true;
        for (String customId : List.of("smoke-1", "smoke-2", "smoke-3")) {
            BatchJsonl.ResultLine line = results.get(customId);
            if (line == null) {
                System.out.println(customId + " -> MISSING FROM RESULTS");
                allOk = false;
                continue;
            }
            String content = BatchJsonl.extractMessageContent(line);
            System.out.println(customId + " -> succeeded=" + line.succeeded() + " content=" + content);
            if (content == null || !content.toUpperCase().contains("PONG")) allOk = false;
        }

        System.out.println(allOk ? "\nSMOKE TEST PASSED" : "\nSMOKE TEST FAILED");
        System.exit(allOk ? 0 : 1);
    }
}
