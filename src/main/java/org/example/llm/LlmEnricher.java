package org.example.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.model.FileChunk;
import org.example.util.RetryUtil;

import org.example.config.AppConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enriches chunk sectionPurpose using OpenAI chat completions.
 * Set OPENAI_API_KEY env var to enable; OPENAI_MODEL to override model (default: gpt-4o-mini).
 *
 * <p>One shared instance is used across the whole run (including all parallel
 * file-processing workers — see Main), so failures are never silent and never
 * lose a chunk's purpose: a failure always falls back to the chunk's existing
 * (already-good, chunk-analysis-derived) purpose, transient failures are
 * retried, and a sustained outage trips a circuit breaker so the rest of the
 * run stops burning time retrying-and-failing chunk by chunk.
 *
 * <p><b>Concurrency is deliberately capped independently of Main's file-level
 * concurrency</b> (via {@code enrichPermits} below). {@link #enrichChunks} runs
 * sequentially within one file, but since this one instance is shared across
 * every concurrently-processing file (see Main.runConcurrently), without its
 * own cap, up to `ingest.parallelism` files' enrichment loops would all fire
 * concurrently — a second wave of load stacked directly on top of chunk-
 * analysis's own concurrent OpenAI traffic. Measured directly on a real run:
 * this produced sustained HTTP 429s across multiple interleaved files' chunks
 * closely enough together to trip the circuit breaker (5 consecutive failures,
 * counted globally across all of them) well before any single file's own
 * enrichment was actually exhausted — a self-inflicted burst, not a true
 * sustained outage. Bounding enrichment's own concurrency separately (smaller
 * than file concurrency, since it's optional polish stacked on essential
 * chunk-analysis traffic) fixes the root cause instead of just reshuffling it.
 */
public class LlmEnricher {

    private final String apiKey;
    private final String model;
    private final String openaiUrl;
    private final int maxContentChars;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Semaphore enrichPermits;

    private final int circuitBreakerThreshold;
    private final long circuitCooldownMs;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);
    private final AtomicLong circuitOpenedAtMs = new AtomicLong(0);

    private LlmEnricher(String apiKey, String model, String openaiUrl, int maxContentChars,
                         int circuitBreakerThreshold, int maxConcurrent, long circuitCooldownMs) {
        this.apiKey          = apiKey;
        this.model           = model;
        this.openaiUrl       = openaiUrl;
        this.maxContentChars = maxContentChars;
        this.circuitBreakerThreshold = circuitBreakerThreshold;
        this.circuitCooldownMs = circuitCooldownMs;
        this.enrichPermits = new Semaphore(Math.max(1, maxConcurrent));
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.mapper = new ObjectMapper();
    }

    /** Returns null when OPENAI_API_KEY is absent — callers skip enrichment. */
    public static LlmEnricher create() {
        String key = AppConfig.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) return null;
        // Dedicated OPENAI_ENRICH_MODEL override — deliberately NOT falling back
        // to the generic OPENAI_MODEL env var (unlike LlmChunkAnalyzer's own
        // OPENAI_CHUNK_MODEL, which does fall back to it). Confirmed directly:
        // a stray OPENAI_MODEL=gpt-4o in the environment (set for some other
        // purpose entirely) silently redirected enrichment onto the SAME model
        // — and therefore the SAME contested rate-limit budget — as chunk
        // analysis, defeating the whole point of enrichment running on a
        // separate, cheaper model with its own headroom. This property/env var
        // is the only way to change enrichment's model now, so it can never be
        // cross-contaminated by an env var meant for something else.
        String model = AppConfig.get("OPENAI_ENRICH_MODEL", "openai.enrich.model", "gpt-4o-mini");
        String url          = AppConfig.get("openai.chat.url", "https://api.openai.com/v1/chat/completions");
        int    maxChars     = AppConfig.getInt("openai.chat.max-content-chars", 2500);
        int    breakerN     = AppConfig.getInt("openai.enrich.circuit-breaker-failures", 5);
        // Deliberately smaller than ingest.parallelism (file-level concurrency) —
        // see class Javadoc: this is a second wave of load on top of chunk
        // analysis's own concurrent traffic, for an optional polish step.
        int    maxConcurrent = AppConfig.getInt("OPENAI_ENRICH_MAX_CONCURRENT", "openai.enrich.max-concurrent", 2);
        long   cooldownMs    = AppConfig.getInt("openai.enrich.circuit-cooldown-seconds", 60) * 1000L;
        return new LlmEnricher(key, model, url, maxChars, breakerN, maxConcurrent, cooldownMs);
    }

    public String getModel() { return model; }

    /**
     * Enriches the sectionPurpose of each chunk in-place.
     * Skips trivial chunks (preamble, identification blocks, near-empty sections)
     * to avoid wasting API budget. On any error the existing (chunk-analysis)
     * purpose is preserved — enrichment is best-effort polish, never a
     * requirement for a chunk to have a usable purpose.
     */
    public void enrichChunks(List<FileChunk> chunks) {
        for (FileChunk chunk : chunks) {
            if (circuitOpen.get() && !cooldownElapsed()) {
                // Open, and still within its cooldown window — don't spend time
                // retrying every remaining chunk one by one; each chunk already
                // has a decent purpose from chunk analysis. See tryHalfOpen()
                // for how the circuit gets another chance once cooldown passes.
                continue;
            }
            if (isTrivial(chunk)) continue;
            try {
                enrichPermits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                String enriched = RetryUtil.withRetry("enrich " + chunk.getChunkId(),
                    attempt -> callOpenAiClassified(buildPrompt(chunk)));
                if (enriched != null && !enriched.isBlank()) {
                    chunk.setSectionPurpose(enriched);
                }
                consecutiveFailures.set(0);
                if (circuitOpen.compareAndSet(true, false)) {
                    System.out.println("  Enrichment circuit closed — " + chunk.getChunkId()
                        + " succeeded after cooldown, resuming normal enrichment");
                }
            } catch (Exception e) {
                System.out.println("  WARNING: enrichment failed for " + chunk.getChunkId()
                    + " — keeping existing purpose: " + e.getMessage());
                int failures = consecutiveFailures.incrementAndGet();
                if (failures >= circuitBreakerThreshold) {
                    boolean justOpened = circuitOpen.compareAndSet(false, true);
                    circuitOpenedAtMs.set(System.currentTimeMillis());
                    if (justOpened) {
                        System.out.println("  WARNING: " + failures + " consecutive enrichment failures — "
                            + "pausing enrichment for " + (circuitCooldownMs / 1000)
                            + "s (chunk-analysis purposes are kept in the meantime)");
                    }
                }
            } finally {
                enrichPermits.release();
            }
        }
    }

    /** True once circuitCooldownMs has passed since the circuit opened — lets a
     * single trial chunk through to check whether the outage has cleared,
     * rather than disabling enrichment for the rest of a long run over one
     * rough patch. A failed trial just resets the cooldown clock (see the
     * catch block above, which always re-stamps circuitOpenedAtMs on failure
     * while the breaker is at/above threshold). */
    private boolean cooldownElapsed() {
        return System.currentTimeMillis() - circuitOpenedAtMs.get() >= circuitCooldownMs;
    }

    private boolean isTrivial(FileChunk chunk) {
        String division = chunk.getDivision();
        String section  = chunk.getSectionName();
        if (division == null && section == null) return true; // PREAMBLE / copyright block
        if ("IDENTIFICATION_DIVISION".equals(division)) return true;
        if ("ENVIRONMENT_DIVISION".equals(division) && section == null) return true;
        if ("DATA_DIVISION".equals(division) && section == null) return true;
        String content = chunk.getContent();
        return content == null || content.strip().length() < 80;
    }

    private String buildPrompt(FileChunk chunk) {
        String content = chunk.getContent();
        if (content != null && content.length() > maxContentChars) {
            content = content.substring(0, maxContentChars) + "\n... [truncated]";
        }

        return String.format(
            """
            You are a COBOL/AS400 expert building a RAG knowledge base for legacy system documentation.

            Analyze this COBOL code chunk and write a concise 2-3 sentence business-level description.

            Context:
            - Program: %s
            - Section: %s
            - Division: %s
            - Domain: %s | Sub-domain: %s
            - Processing type: %s
            - File type: %s

            COBOL code:
            ```cobol
            %s
            ```

            Rules:
            - Describe business purpose, not COBOL syntax
            - Mention what data is processed, what files/tables are accessed, and the business outcome
            - Be specific and factual based only on the code above
            - Output ONLY the description — no labels, prefixes, or formatting
            """,
            nvl(chunk.getProgramId(), "UNKNOWN"),
            nvl(chunk.getSectionName(), "N/A"),
            nvl(chunk.getDivision(), "N/A"),
            nvl(chunk.getDomain(), "N/A"),
            nvl(chunk.getSubDomain(), "N/A"),
            nvl(chunk.getProcessingType(), "N/A"),
            nvl(chunk.getFileType(), "N/A"),
            content != null ? content : ""
        );
    }

    /** Exposed for batch mode (see BatchEnrichmentService) — same skip rule
     * the synchronous enrichChunks() loop already applies, just callable
     * directly so batch mode can filter its candidate chunk list up front. */
    public boolean isTrivialChunk(FileChunk chunk) { return isTrivial(chunk); }

    /** Exposed for batch mode — the exact prompt text for one chunk, same
     * buildPrompt() the synchronous path uses. */
    public String buildEnrichPrompt(FileChunk chunk) { return buildPrompt(chunk); }

    /** The request body shape used both by the synchronous call below AND by
     * batch-mode's JSONL request lines (see BatchEnrichmentService) —
     * extracted so both paths send identical model/temperature settings. */
    Map<String, Object> buildRequestBody(String prompt) {
        return Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "max_tokens", 250,
            "temperature", 0.2
        );
    }

    /**
     * Classifies failures the same way chunk analysis does: transient (network,
     * 429/5xx) is worth retrying; other 4xx / empty content is not (retrying the
     * exact same request won't change the outcome) — so RetryUtil escalates
     * immediately in that case instead of wasting attempts.
     */
    private String callOpenAiClassified(String prompt) throws Exception {
        Map<String, Object> body = buildRequestBody(prompt);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(openaiUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RetryUtil.RetryableApiException("HTTP call failed: " + e.getMessage(), null);
        }

        int status = response.statusCode();
        if (status == 429 || status >= 500) {
            throw new RetryUtil.RetryableApiException("OpenAI HTTP " + status, parseRetryAfterMs(response));
        }
        if (status != 200) {
            throw new RetryUtil.NoRetryException("OpenAI HTTP " + status + ": " + truncate(response.body(), 300));
        }

        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (Exception e) {
            throw new RetryUtil.RetryableApiException("Malformed HTTP envelope: " + e.getMessage(), null);
        }
        String content = root.path("choices").get(0).path("message").path("content").asText();
        if (content == null || content.isBlank()) {
            throw new RetryUtil.NoRetryException("OpenAI returned an empty enrichment response");
        }
        return content.trim();
    }

    private static Long parseRetryAfterMs(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After")
            .map(v -> {
                try { return Long.parseLong(v.trim()) * 1000; }
                catch (NumberFormatException e) { return null; }
            })
            .orElse(null);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String nvl(String s, String fallback) {
        return (s != null && !s.isBlank()) ? s : fallback;
    }
}
