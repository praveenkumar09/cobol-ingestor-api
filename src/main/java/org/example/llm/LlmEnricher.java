package org.example.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.model.FileChunk;

import org.example.config.AppConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Enriches chunk sectionPurpose using OpenAI chat completions.
 * Set OPENAI_API_KEY env var to enable; OPENAI_MODEL to override model (default: gpt-4o-mini).
 */
public class LlmEnricher {

    private final String apiKey;
    private final String model;
    private final String openaiUrl;
    private final int maxContentChars;
    private final HttpClient http;
    private final ObjectMapper mapper;

    private LlmEnricher(String apiKey, String model, String openaiUrl, int maxContentChars) {
        this.apiKey          = apiKey;
        this.model           = model;
        this.openaiUrl       = openaiUrl;
        this.maxContentChars = maxContentChars;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.mapper = new ObjectMapper();
    }

    /** Returns null when OPENAI_API_KEY is absent — callers skip enrichment. */
    public static LlmEnricher create() {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) return null;
        String m            = System.getenv("OPENAI_MODEL");
        String defaultModel = AppConfig.get("openai.chat.model", "gpt-4o-mini");
        String url          = AppConfig.get("openai.chat.url", "https://api.openai.com/v1/chat/completions");
        int    maxChars     = AppConfig.getInt("openai.chat.max-content-chars", 2500);
        return new LlmEnricher(key, (m != null && !m.isBlank()) ? m : defaultModel, url, maxChars);
    }

    public String getModel() { return model; }

    /**
     * Enriches the sectionPurpose of each chunk in-place.
     * Skips trivial chunks (preamble, identification blocks, near-empty sections)
     * to avoid wasting API budget. On any error the rule-based purpose is preserved.
     */
    public void enrichChunks(List<FileChunk> chunks) {
        for (FileChunk chunk : chunks) {
            if (isTrivial(chunk)) continue;
            try {
                String enriched = callOpenAi(buildPrompt(chunk));
                if (enriched != null && !enriched.isBlank()) {
                    chunk.setSectionPurpose(enriched);
                }
            } catch (Exception e) {
                // Preserve existing rule-based purpose on failure
            }
        }
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

    private String callOpenAi(String prompt) throws Exception {
        Map<String, Object> body = Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "max_tokens", 250,
            "temperature", 0.2
        );

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(openaiUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI API HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        return root.path("choices").get(0).path("message").path("content").asText().trim();
    }

    private static String nvl(String s, String fallback) {
        return (s != null && !s.isBlank()) ? s : fallback;
    }
}