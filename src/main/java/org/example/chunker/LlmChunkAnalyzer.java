package org.example.chunker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppConfig;
import org.example.model.FileChunk;
import org.example.model.FileType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Analyzes a whole source file (COBOL program, "Smart COBOL" variant, copybook,
 * COBOL-syntax batch job, or JCL job stream) with a single LLM call and lets the
 * model decide chunk boundaries and every semantic field for each chunk.
 *
 * <p>No hardcoded division/section/step pattern matching — the model reads the
 * line-numbered source and returns structured JSON; this class only slices the
 * ORIGINAL source lines at the line ranges the model returns (so chunk content
 * is always byte-exact, never LLM-reproduced code).
 */
public class LlmChunkAnalyzer {

    private final String apiKey;
    private final String model;
    private final String url;
    private final int maxContentChars;
    private final int maxTokens;
    private final HttpClient http;
    private final ObjectMapper mapper;

    private LlmChunkAnalyzer(String apiKey, String model, String url, int maxContentChars, int maxTokens) {
        this.apiKey          = apiKey;
        this.model            = model;
        this.url              = url;
        this.maxContentChars  = maxContentChars;
        this.maxTokens        = maxTokens;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.mapper = new ObjectMapper();
    }

    /** Returns null when OPENAI_API_KEY is absent — callers must treat chunking as unavailable. */
    public static LlmChunkAnalyzer create() {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) return null;

        String envModel = System.getenv("OPENAI_CHUNK_MODEL");
        String chatDefault = AppConfig.get("openai.chat.model", "gpt-4o-mini");
        String model = (envModel != null && !envModel.isBlank())
            ? envModel
            : AppConfig.get("OPENAI_MODEL", "openai.chunk.model", chatDefault);
        String url        = AppConfig.get("openai.chat.url", "https://api.openai.com/v1/chat/completions");
        int maxChars      = AppConfig.getInt("openai.chunk.max-content-chars", 100_000);
        int maxTokens     = AppConfig.getInt("openai.chunk.max-tokens", 8000);
        return new LlmChunkAnalyzer(key, model, url, maxChars, maxTokens);
    }

    public String getModel() { return model; }

    public ChunkAnalysis analyze(String fileName, FileType fileType, List<String> lines) throws Exception {
        String numbered = numberLines(lines);
        boolean truncated = false;
        if (numbered.length() > maxContentChars) {
            numbered = numbered.substring(0, maxContentChars) + "\n... [truncated]";
            truncated = true;
        }

        String prompt = buildPrompt(fileName, fileType, numbered, truncated);
        String json = callOpenAi(prompt);
        return parse(json);
    }

    // -------------------------------------------------------
    // Turns FileChunk-ready specs into actual FileChunk objects, slicing
    // content from the ORIGINAL file lines (never from LLM-echoed text).
    // -------------------------------------------------------

    public static List<FileChunk> toFileChunks(ChunkAnalysis analysis, List<String> lines,
                                                 String fileName, FileType fileType, String programId) {
        List<FileChunk> chunks = new ArrayList<>();
        int totalLines = lines.size();

        List<ChunkSpec> specs = analysis.chunks.stream()
            .filter(s -> s.lineStart >= 1 && s.lineEnd >= s.lineStart)
            .sorted(Comparator.comparingInt(s -> s.lineStart))
            .toList();

        int idx = 0;
        for (ChunkSpec spec : specs) {
            int start = Math.max(1, spec.lineStart);
            int end   = Math.min(totalLines, spec.lineEnd);
            if (end < start) continue;

            String content = String.join("\n", lines.subList(start - 1, end));
            if (content.isBlank()) continue;

            FileChunk chunk = new FileChunk();
            chunk.setChunkId(buildChunkId(fileName, spec.sectionName, idx));
            chunk.setSourceFile(fileName);
            chunk.setFileType(fileType.label);
            chunk.setProgramId(programId);
            chunk.setAuthor(blankToNull(analysis.author));
            chunk.setDateWritten(blankToNull(analysis.dateWritten));
            chunk.setProgramDescription(blankToNull(analysis.programDescription));
            chunk.setDomain(spec.domain);
            chunk.setSubDomain(spec.subDomain);
            chunk.setProcessingType(spec.processingType);
            chunk.setDivision(blankToNull(spec.division));
            chunk.setSectionName(spec.sectionName);
            chunk.setLineStart(start);
            chunk.setLineEnd(end);
            chunk.setSectionPurpose(spec.sectionPurpose);
            chunk.setFilesRead(emptyToNull(spec.filesRead));
            chunk.setFilesWritten(emptyToNull(spec.filesWritten));
            chunk.setFilesUpdated(emptyToNull(spec.filesUpdated));
            chunk.setFilesDeleted(emptyToNull(spec.filesDeleted));
            chunk.setCopybooksReferenced(emptyToNull(spec.copybooksReferenced));
            chunk.setEntryPoints(emptyToNull(spec.entryPoints));
            chunk.setExternalProgramsCalled(emptyToNull(spec.externalProgramsCalled));
            chunk.setParagraphsCalled(emptyToNull(spec.paragraphsCalled));
            chunk.setKeyDataFields(emptyToNull(spec.keyDataFields));
            chunk.setBusinessConditions(emptyToNull(spec.businessConditions));
            chunk.setHasFileIO(spec.hasFileIO);
            chunk.setHasErrorHandling(spec.hasErrorHandling);
            chunk.setTags((spec.tags == null || spec.tags.isEmpty()) ? null : spec.tags);
            chunk.setContent(content);
            chunks.add(chunk);
            idx++;
        }

        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setChunkIndex(i + 1);
            chunks.get(i).setTotalChunks(chunks.size());
        }
        return chunks;
    }

    private static String buildChunkId(String fileName, String section, int index) {
        String base = fileName.replaceAll("\\.[^.]+$", "").toUpperCase();
        String sec = (section == null || section.isBlank() ? "CHUNK" : section)
            .replaceAll("[^A-Za-z0-9-]", "_").toUpperCase();
        return base + "_" + sec + "_" + String.format("%03d", index + 1);
    }

    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }
    private static List<String> emptyToNull(List<String> l) { return (l == null || l.isEmpty()) ? null : l; }

    // -------------------------------------------------------
    // Prompt construction
    // -------------------------------------------------------

    private String numberLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(i + 1).append(": ").append(lines.get(i)).append("\n");
        }
        return sb.toString();
    }

    private String buildPrompt(String fileName, FileType fileType, String numberedSource, boolean truncated) {
        String kindGuidance = switch (fileType) {
            case COPYBOOK -> """
                This file is a COBOL COPYBOOK: a pure data-layout definition with no PROCEDURE DIVISION.
                Segment it into one chunk per top-level record/group definition (typically each 01-level,
                but use your judgment for the file's actual structure). division should be null for every
                chunk. processingType should be "COPYBOOK_RECORD_LAYOUT". filesRead/filesWritten/filesUpdated/
                filesDeleted/externalProgramsCalled/paragraphsCalled should be empty arrays. keyDataFields
                should list the field names defined in that record.
                """;
            case JCL -> """
                This file is a JCL (Job Control Language) batch job stream. Segment it into a JOB header
                chunk followed by one chunk per EXEC step (or equivalent logical unit). For each step chunk:
                externalProgramsCalled is the program named on EXEC PGM=; filesRead are DD-statement datasets
                that are inputs (e.g. DISP=SHR/OLD); filesWritten are DD-statement datasets that are outputs
                (e.g. DISP=NEW/MOD); businessConditions should capture PARM= values and COND=/IF-THEN
                conditional-execution logic as short phrases; keyDataFields may list SYSIN parameter values.
                division can be e.g. "JOB_CARD" for the header and "JCL_STEP" for steps. processingType should
                be "BATCH_JCL" for the header and "BATCH_JCL_STEP" for steps.
                """;
            default -> """
                This file is expected to be a COBOL program — classic ILE COBOL, a modernized "Smart COBOL"
                variant, or a batch job expressed in COBOL (.cbl) syntax. A single file may freely MIX
                classic COBOL and Smart COBOL constructs across different sections/paragraphs — do not
                assume the whole file is one dialect. Judge each chunk purely on the code it actually
                contains, section by section, even if the dialect changes partway through the file. Do not
                assume the file follows the classic four-division layout if the code itself doesn't — infer
                the real structure. Typically: one chunk per header division (IDENTIFICATION/ENVIRONMENT),
                one chunk per DATA DIVISION section, and one chunk per PROCEDURE DIVISION section/paragraph/
                batch step. filesRead/filesWritten/filesUpdated/filesDeleted are logical file names from
                READ/WRITE/REWRITE/DELETE statements or EXEC CICS file verbs. externalProgramsCalled are
                CALL/EXEC CICS LINK/XCTL targets. paragraphsCalled are PERFORM targets.
                """;
        };

        String truncNote = truncated
            ? "\nNOTE: the source below was truncated to fit the model context window — only chunk the lines actually shown.\n"
            : "";

        return """
            You are a COBOL/AS400 legacy-modernization expert building a RAG knowledge base and a
            dependency graph directly from source code. You will be given the COMPLETE, line-numbered
            source of one file. Decide, entirely from the code itself, how to split it into coherent
            semantic chunks and what metadata each chunk carries. Do not rely on any fixed rule set —
            read the actual code and use your own judgment.

            FILE: %s
            DECLARED TYPE HINT: %s (a hint only — override it if the code itself indicates otherwise)

            %s
            %s
            SOURCE (line-numbered, 1-based; the lineStart/lineEnd you return must match these numbers exactly):
            ```
            %s
            ```

            TASK
            1. Identify file-level metadata: the program/job id, author, date written, a short business
               description, every COPY target referenced anywhere in the file, and every ENTRY point
               declared anywhere in the file (empty arrays/null if not applicable).
            2. Split the file into an ORDERED list of non-overlapping chunks that each represent one
               coherent unit. Merge trivial fragments; split any unit larger than ~200 lines at a natural
               sub-boundary. Every line of the file should belong to some chunk.
            3. For EACH chunk, decide every one of these values yourself, based only on that chunk's code
               and the file-level context above:
               - division: enclosing structural unit if applicable, else null.
               - sectionName: a short identifying name for the chunk, upper-cased, as it appears in the source.
               - lineStart / lineEnd: 1-based inclusive line numbers matching the numbering shown above.
               - sectionPurpose: 2-3 sentence BUSINESS-level description of what this chunk does — not
                 syntax narration.
               - domain: best-fit high-level business domain (e.g. INSURANCE, BANKING, GENERAL).
               - subDomain: a specific UPPER_SNAKE_CASE sub-domain label (e.g. POLICY_MANAGEMENT,
                 CLAIMS_PROCESSING, ACCOUNT_MANAGEMENT, CARD_MANAGEMENT, BATCH_OPERATIONS) — invent a
                 fitting label if nothing standard applies.
               - processingType: how this chunk executes (e.g. BATCH_PROCESSING, ONLINE_INQUIRY,
                 ONLINE_UPDATE, CICS_ONLINE, SERVICE_PROGRAM_BO, REPORT_GENERATION,
                 COPYBOOK_RECORD_LAYOUT, BATCH_JCL, BATCH_JCL_STEP).
               - filesRead / filesWritten / filesUpdated / filesDeleted: logical file or dataset names
                 this chunk actually touches. Empty arrays if none.
               - copybooksReferenced: COPY targets referenced within this specific chunk.
               - entryPoints: ENTRY point names declared within this specific chunk.
               - externalProgramsCalled: programs invoked from this chunk.
               - paragraphsCalled: paragraph/section names this chunk invokes via PERFORM.
               - keyDataFields: the most important business data field names this chunk manipulates
                 (max 12, most-referenced first).
               - businessConditions: notable business rules/conditions evaluated in this chunk (max 8),
                 each as a short phrase.
               - hasFileIO: true if this chunk performs any file/database I/O.
               - hasErrorHandling: true if this chunk contains error/exception handling.
               - tags: 4-10 lowercase-kebab search tags summarizing this chunk (domain, sub-domain,
                 capability words, "file-io"/"error-handling"/"batch"/"cics" as applicable).

            OUTPUT — respond with ONLY a single JSON object, no markdown fences, no commentary, matching
            exactly this shape:
            {
              "programId": "string",
              "author": "string or null",
              "dateWritten": "string or null",
              "programDescription": "string or null",
              "copybooksUsed": ["string", ...],
              "entryPoints": ["string", ...],
              "chunks": [
                {
                  "division": "string or null",
                  "sectionName": "string",
                  "lineStart": 1,
                  "lineEnd": 10,
                  "sectionPurpose": "string",
                  "domain": "string",
                  "subDomain": "string",
                  "processingType": "string",
                  "filesRead": ["string", ...],
                  "filesWritten": ["string", ...],
                  "filesUpdated": ["string", ...],
                  "filesDeleted": ["string", ...],
                  "copybooksReferenced": ["string", ...],
                  "entryPoints": ["string", ...],
                  "externalProgramsCalled": ["string", ...],
                  "paragraphsCalled": ["string", ...],
                  "keyDataFields": ["string", ...],
                  "businessConditions": ["string", ...],
                  "hasFileIO": true,
                  "hasErrorHandling": false,
                  "tags": ["string", ...]
                }
              ]
            }
            """.formatted(fileName, fileType.label, kindGuidance, truncNote, numberedSource);
    }

    // -------------------------------------------------------
    // OpenAI call + JSON parsing
    // -------------------------------------------------------

    private String callOpenAi(String prompt) throws Exception {
        Map<String, Object> body = Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "max_tokens", maxTokens,
            "temperature", 0.1,
            "response_format", Map.of("type", "json_object")
        );

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI API HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = parseJson(response.body());
        String content = root.path("choices").get(0).path("message").path("content").asText();
        if (content == null || content.isBlank()) {
            throw new RuntimeException("OpenAI returned an empty chunk analysis");
        }
        return content;
    }

    private JsonNode parseJson(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON: " + e.getMessage(), e);
        }
    }

    private ChunkAnalysis parse(String json) {
        JsonNode root = parseJson(json);

        ChunkAnalysis analysis = new ChunkAnalysis();
        analysis.programId = textOrNull(root, "programId");
        analysis.author = textOrNull(root, "author");
        analysis.dateWritten = textOrNull(root, "dateWritten");
        analysis.programDescription = textOrNull(root, "programDescription");
        analysis.copybooksUsed = textArray(root.path("copybooksUsed"));
        analysis.entryPoints = textArray(root.path("entryPoints"));

        for (JsonNode c : root.path("chunks")) {
            ChunkSpec spec = new ChunkSpec();
            spec.division = textOrNull(c, "division");
            spec.sectionName = c.path("sectionName").asText("CHUNK");
            spec.lineStart = c.path("lineStart").asInt(0);
            spec.lineEnd = c.path("lineEnd").asInt(0);
            spec.sectionPurpose = c.path("sectionPurpose").asText("");
            spec.domain = c.path("domain").asText("GENERAL");
            spec.subDomain = textOrNull(c, "subDomain");
            spec.processingType = textOrNull(c, "processingType");
            spec.filesRead = textArray(c.path("filesRead"));
            spec.filesWritten = textArray(c.path("filesWritten"));
            spec.filesUpdated = textArray(c.path("filesUpdated"));
            spec.filesDeleted = textArray(c.path("filesDeleted"));
            spec.copybooksReferenced = textArray(c.path("copybooksReferenced"));
            spec.entryPoints = textArray(c.path("entryPoints"));
            spec.externalProgramsCalled = textArray(c.path("externalProgramsCalled"));
            spec.paragraphsCalled = textArray(c.path("paragraphsCalled"));
            spec.keyDataFields = textArray(c.path("keyDataFields"));
            spec.businessConditions = textArray(c.path("businessConditions"));
            spec.hasFileIO = c.path("hasFileIO").asBoolean(false);
            spec.hasErrorHandling = c.path("hasErrorHandling").asBoolean(false);
            spec.tags = textArray(c.path("tags"));
            analysis.chunks.add(spec);
        }

        if (analysis.chunks.isEmpty()) {
            throw new RuntimeException("LLM returned no chunks");
        }
        return analysis;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static List<String> textArray(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                String s = n.asText();
                if (s != null && !s.isBlank()) result.add(s.trim());
            }
        }
        return result;
    }

    // -------------------------------------------------------
    // Result model
    // -------------------------------------------------------

    public static class ChunkAnalysis {
        public String programId;
        public String author;
        public String dateWritten;
        public String programDescription;
        public List<String> copybooksUsed = new ArrayList<>();
        public List<String> entryPoints = new ArrayList<>();
        public List<ChunkSpec> chunks = new ArrayList<>();
    }

    public static class ChunkSpec {
        public String division;
        public String sectionName;
        public int lineStart;
        public int lineEnd;
        public String sectionPurpose;
        public String domain;
        public String subDomain;
        public String processingType;
        public List<String> filesRead;
        public List<String> filesWritten;
        public List<String> filesUpdated;
        public List<String> filesDeleted;
        public List<String> copybooksReferenced;
        public List<String> entryPoints;
        public List<String> externalProgramsCalled;
        public List<String> paragraphsCalled;
        public List<String> keyDataFields;
        public List<String> businessConditions;
        public boolean hasFileIO;
        public boolean hasErrorHandling;
        public List<String> tags;
    }
}
