package org.example.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared JSONL request/result read-write for OpenAI Batch API jobs — one line
 * per request/result, used by both {@link org.example.chunker.BatchChunkAnalysisService}
 * and {@link BatchEnrichmentService}. Plain Jackson, matching this codebase's
 * existing convention of not layering a dedicated JSON library abstraction
 * on top of {@link ObjectMapper}.
 */
public class BatchJsonl {

    /** One line of a batch INPUT file. */
    public record RequestLine(String customId, String method, String url, Map<String, Object> body) {
        Map<String, Object> toJsonMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("custom_id", customId);
            m.put("method", method);
            m.put("url", url);
            m.put("body", body);
            return m;
        }
    }

    /** One line of a batch OUTPUT (or error) file. */
    public record ResultLine(String id, String customId, ResponseEnvelope response, ErrorInfo error) {
        public boolean succeeded() { return response != null && response.statusCode() == 200 && error == null; }
    }

    public record ResponseEnvelope(int statusCode, String requestId, JsonNode body) {}
    public record ErrorInfo(String code, String message) {}

    /**
     * Crude token estimate for one request — chars/4 (the common heuristic
     * used when a real tokenizer isn't linked in) for the prompt text, plus
     * the request's own declared max_tokens (OpenAI reserves a request's
     * FULL max_tokens against the org's per-model "enqueued tokens" batch
     * quota up front, before generation runs and the real output size is
     * known — confirmed directly: a 164-request gpt-4o chunk-analysis batch
     * at max_tokens=16000/request failed immediately with "token_limit_
     * exceeded ... Limit: 90,000 enqueued tokens", a per-ORG cap entirely
     * separate from and far stricter than the synchronous RPM/TPM limits,
     * which neither the request-count nor file-byte-size cap protects
     * against). Deliberately conservative (over- rather than under-estimate)
     * since the consequence of guessing too low is a whole batch group
     * failing outright, not just a slightly smaller group.
     */
    public static long estimateTokens(String promptText, Map<String, Object> body) {
        long inputEstimate = (promptText == null ? 0 : promptText.length()) / 4;
        Object maxTokens = body.get("max_tokens");
        long outputReserve = (maxTokens instanceof Number n) ? n.longValue() : 0;
        return inputEstimate + outputReserve;
    }

    /**
     * Splits a flat list of RequestLines into groups that each respect
     * OpenAI's per-batch-file caps (request count, file size, AND estimated
     * enqueued tokens — see estimateTokens above) — shared by
     * BatchChunkAnalysisService and BatchEnrichmentService so a corpus large
     * enough to need multiple JSONL files (a real ~20k-file run trivially
     * will) is handled identically in both, rather than each reimplementing
     * its own bin-packing. Greedy single-pass: a group closes and a new one
     * starts as soon as adding the next line would exceed any of the three
     * caps. tokenEstimates must be the same size as lines, index-aligned.
     */
    public static List<List<RequestLine>> partition(List<RequestLine> lines, List<Long> tokenEstimates,
                                                      int maxCount, long maxBytes, long maxTokensPerGroup,
                                                      ObjectMapper mapper) throws IOException {
        List<List<RequestLine>> groups = new ArrayList<>();
        List<RequestLine> current = new ArrayList<>();
        long currentBytes = 0;
        long currentTokens = 0;
        for (int i = 0; i < lines.size(); i++) {
            RequestLine line = lines.get(i);
            long lineBytes = mapper.writeValueAsBytes(line.toJsonMap()).length + 1; // +1 for the newline
            long lineTokens = tokenEstimates.get(i);
            boolean wouldOverflow = !current.isEmpty()
                && (current.size() >= maxCount
                    || currentBytes + lineBytes > maxBytes
                    || currentTokens + lineTokens > maxTokensPerGroup);
            if (wouldOverflow) {
                groups.add(current);
                current = new ArrayList<>();
                currentBytes = 0;
                currentTokens = 0;
            }
            current.add(line);
            currentBytes += lineBytes;
            currentTokens += lineTokens;
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }

    /** Writes one JSONL line per RequestLine — the file OpenAiBatchClient.uploadJsonlFile uploads. */
    public static void writeJsonl(Path file, Iterable<RequestLine> lines, ObjectMapper mapper) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (RequestLine line : lines) {
            sb.append(mapper.writeValueAsString(line.toJsonMap())).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Parses a downloaded output (or error) file's raw JSONL text into a
     * Map keyed by custom_id — output line order is NOT guaranteed to match
     * input order (OpenAI's own documented warning), so every caller must
     * look results up by custom_id, never by position.
     */
    public static Map<String, ResultLine> readResultsByCustomId(String rawJsonlText, ObjectMapper mapper) throws IOException {
        Map<String, ResultLine> results = new LinkedHashMap<>();
        for (String rawLine : rawJsonlText.split("\n")) {
            if (rawLine.isBlank()) continue;
            JsonNode root = mapper.readTree(rawLine);
            String customId = root.path("custom_id").asText(null);
            if (customId == null) continue; // malformed line — skip, caller treats missing custom_id as a fallback case

            ResponseEnvelope response = null;
            JsonNode respNode = root.path("response");
            if (!respNode.isMissingNode() && !respNode.isNull()) {
                response = new ResponseEnvelope(
                    respNode.path("status_code").asInt(0),
                    respNode.path("request_id").asText(null),
                    respNode.path("body"));
            }
            ErrorInfo error = null;
            JsonNode errNode = root.path("error");
            if (!errNode.isMissingNode() && !errNode.isNull()) {
                error = new ErrorInfo(errNode.path("code").asText(null), errNode.path("message").asText(null));
            }
            results.put(customId, new ResultLine(root.path("id").asText(null), customId, response, error));
        }
        return results;
    }

    /** Navigates response.body.choices[0].message.content — mirrors the same
     * envelope-parsing every synchronous *Classified() method in this codebase
     * already does, just against the batch result shape instead of a direct
     * chat-completions response. Returns null if the shape doesn't match
     * (caller treats that as a failure needing fallback, same as an empty/
     * malformed synchronous response is already handled). */
    public static String extractMessageContent(ResultLine line) {
        if (!line.succeeded()) return null;
        JsonNode content = line.response().body().path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) return null;
        String text = content.asText();
        return (text == null || text.isBlank()) ? null : text;
    }
}
