package org.example.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Low-level client for OpenAI's Batch API — upload a JSONL file, create a
 * batch job against it, poll for completion, download the result file.
 *
 * <p>Batch requests go into a queue with a completely SEPARATE token/request
 * budget from the synchronous per-model rate limits that {@link
 * org.example.chunker.LlmChunkAnalyzer} and {@link LlmEnricher} otherwise
 * compete for — this is the structural fix for the HTTP 429 contention seen
 * repeatedly in this codebase's synchronous path, not another round of
 * client-side concurrency/retry tuning. Trade-off, stated plainly: {@code
 * completion_window} only accepts {@code "24h"} today — OpenAI says batches
 * "often" finish faster, but there's no guaranteed fast tier, so callers
 * should expect a real wait, not an immediate result.
 *
 * <p>Raw {@link HttpClient}, no SDK — matches every other OpenAI-calling
 * class in this codebase (EmbeddingClient, LlmEnricher, LlmChunkAnalyzer).
 * {@code multipart/form-data} for the file upload is hand-built since {@code
 * java.net.http} has no built-in multipart support — see {@link
 * #uploadJsonlFile}.
 */
public class OpenAiBatchClient {

    private final String apiKey;
    private final String baseUrl;
    private final String completionWindow;
    private final HttpClient http;
    private final ObjectMapper mapper;

    private OpenAiBatchClient(String apiKey, String baseUrl, String completionWindow) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.completionWindow = completionWindow;
        // Longer connect timeout than the other clients' 30s — not for any one
        // call (uploads/creates/polls are all quick), just consistent headroom
        // given this client is used for long-running, unattended background work.
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.mapper = new ObjectMapper();
    }

    /** Returns null when OPENAI_API_KEY is absent — callers skip batch mode. */
    public static OpenAiBatchClient create() {
        String key = AppConfig.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) return null;
        String baseUrl = AppConfig.get("openai.batch.base-url", "https://api.openai.com");
        String completionWindow = AppConfig.get(
            "OPENAI_BATCH_COMPLETION_WINDOW", "openai.batch.completion-window", "24h");
        return new OpenAiBatchClient(key, baseUrl, completionWindow);
    }

    public record BatchStatus(String id, String status, String outputFileId, String errorFileId,
                               int total, int completed, int failed) {
        /** Terminal states per OpenAI's documented status table — pollUntilTerminal stops here. */
        public boolean isTerminal() {
            return switch (status) {
                case "completed", "failed", "expired", "cancelled" -> true;
                default -> false; // validating, in_progress, finalizing, cancelling
            };
        }
        public boolean succeeded() { return "completed".equals(status); }
    }

    // -------------------------------------------------------------------
    // 1. Upload the JSONL input file — POST /v1/files, multipart/form-data
    // -------------------------------------------------------------------

    /** Uploads a JSONL file with purpose=batch, returns the resulting file id (e.g. "file-abc123"). */
    public String uploadJsonlFile(Path jsonlFile) throws IOException, InterruptedException {
        String boundary = "----CobolIngestorBatch" + UUID.randomUUID();
        byte[] body = buildMultipartBody(boundary, jsonlFile);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/files"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .timeout(Duration.ofMinutes(5)) // file can be up to ~150MB (see config) — plain POST/upload, generous timeout
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        requireOk(response, "file upload");
        JsonNode root = mapper.readTree(response.body());
        String fileId = textOrNull(root, "id");
        if (fileId == null) {
            throw new IOException("File upload response had no 'id': " + truncate(response.body(), 500));
        }
        return fileId;
    }

    /**
     * Hand-builds a multipart/form-data body with two parts: purpose=batch
     * (plain field) and file=<jsonlFile> (file field, application/jsonl).
     * Mirrors exactly what {@code curl -F purpose="batch" -F file=@x.jsonl}
     * sends on the wire — java.net.http has no multipart helper, so this is
     * built directly against the multipart/form-data spec (RFC 7578): each
     * part starts with "--boundary\r\n", a Content-Disposition header (and
     * Content-Type for the file part), a blank line, the part's content, and
     * a trailing "\r\n"; the whole body ends with "--boundary--\r\n".
     */
    private byte[] buildMultipartBody(String boundary, Path jsonlFile) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String crlf = "\r\n";

        // Part 1: purpose=batch
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"purpose\"" + crlf + crlf)
            .getBytes(StandardCharsets.UTF_8));
        out.write(("batch" + crlf).getBytes(StandardCharsets.UTF_8));

        // Part 2: file=<jsonl content>
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
            + jsonlFile.getFileName() + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: application/jsonl" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(Files.readAllBytes(jsonlFile));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));

        out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    // -------------------------------------------------------------------
    // 2. Create the batch job — POST /v1/batches
    // -------------------------------------------------------------------

    /** @param endpoint e.g. "/v1/chat/completions" */
    public String createBatch(String inputFileId, String endpoint) throws IOException, InterruptedException {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("input_file_id", inputFileId);
        body.put("endpoint", endpoint);
        body.put("completion_window", completionWindow);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/batches"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        requireOk(response, "batch creation");
        JsonNode root = mapper.readTree(response.body());
        String batchId = textOrNull(root, "id");
        if (batchId == null) {
            throw new IOException("Batch creation response had no 'id': " + truncate(response.body(), 500));
        }
        return batchId;
    }

    // -------------------------------------------------------------------
    // 3. Poll status — GET /v1/batches/{id}
    // -------------------------------------------------------------------

    public BatchStatus getStatus(String batchId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/batches/" + batchId))
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        requireOk(response, "batch status check");
        JsonNode root = mapper.readTree(response.body());
        JsonNode counts = root.path("request_counts");
        return new BatchStatus(
            textOrNull(root, "id"),
            textOrNull(root, "status"),
            textOrNull(root, "output_file_id"),
            textOrNull(root, "error_file_id"),
            counts.path("total").asInt(0),
            counts.path("completed").asInt(0),
            counts.path("failed").asInt(0)
        );
    }

    /**
     * Polls until the batch reaches a terminal status, logging progress every
     * poll. Throws if maxWaitMs elapses first (a safety net for a true stuck/
     * hung batch — the caller should treat that as "fall this group back to
     * synchronous processing," not retry the poll forever).
     */
    public BatchStatus pollUntilTerminal(String batchId, long pollIntervalMs, long maxWaitMs)
            throws IOException, InterruptedException {
        long start = System.currentTimeMillis();
        BatchStatus status = getStatus(batchId);
        while (!status.isTerminal()) {
            if (System.currentTimeMillis() - start > maxWaitMs) {
                throw new IOException("Batch " + batchId + " did not reach a terminal status within "
                    + String.format("%.1fh", maxWaitMs / 3_600_000.0) + " (last status: " + status.status() + ")");
            }
            System.out.println("  batch " + batchId + " ... " + status.status()
                + " (" + status.completed() + "+" + status.failed() + "/" + status.total() + ")");
            Thread.sleep(pollIntervalMs);
            status = getStatus(batchId);
        }
        System.out.println("  batch " + batchId + " ... " + status.status()
            + " (" + status.completed() + "+" + status.failed() + "/" + status.total() + ")");
        return status;
    }

    // -------------------------------------------------------------------
    // 4. Download a result/error file — GET /v1/files/{id}/content
    // -------------------------------------------------------------------

    public String downloadFileContent(String fileId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/files/" + fileId + "/content"))
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofMinutes(5))
            .GET()
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        requireOk(response, "file content download");
        return response.body();
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

    private void requireOk(HttpResponse<String> response, String action) throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new IOException("OpenAI " + action + " failed: HTTP " + response.statusCode()
                + ": " + truncate(response.body(), 500));
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
