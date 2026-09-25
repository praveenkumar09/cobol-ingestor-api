package org.example.chunker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppConfig;
import org.example.model.FileChunk;
import org.example.model.FileType;
import org.example.util.RetryUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Analyzes a whole source file (COBOL program, "Smart COBOL" variant, copybook,
 * COBOL-syntax batch job, or JCL job stream) with the LLM and lets the model
 * decide chunk boundaries and every semantic field for each chunk.
 *
 * <p>No hardcoded division/section/step pattern matching — the model reads
 * line-numbered source and returns structured JSON; this class only slices the
 * ORIGINAL source lines at the line ranges the model returns (so chunk content
 * is always byte-exact, never LLM-reproduced code).
 *
 * <p><b>Large files.</b> A file bigger than {@code openai.chunk.window-lines} is
 * split up front into overlapping windows, each analyzed independently and then
 * merged (overlap zones de-duplicated). A window that fails is retried, and if
 * still failing is recursively split in half and retried — down to
 * {@code openai.chunk.window-min-lines} — so a single dense/failing region
 * shrinks itself rather than taking the whole file down. If a window at the
 * floor size still can't be analyzed, its raw source is preserved as a single
 * "unanalyzed" placeholder chunk rather than being dropped. A final coverage
 * pass guarantees every line of the file ends up in some chunk — analyzed or
 * placeholder — so no content is ever silently lost.
 */
public class LlmChunkAnalyzer {

    private final String apiKey;
    private final String model;
    private final String url;
    private final int maxTokens;
    private final int windowLines;
    private final int windowMinLines;
    private final int windowOverlapLines;
    private final HttpClient http;
    private final ObjectMapper mapper;

    private LlmChunkAnalyzer(String apiKey, String model, String url, int maxTokens,
                              int windowLines, int windowMinLines, int windowOverlapLines) {
        this.apiKey             = apiKey;
        this.model              = model;
        this.url                = url;
        this.maxTokens          = maxTokens;
        this.windowLines        = windowLines;
        this.windowMinLines     = windowMinLines;
        this.windowOverlapLines = windowOverlapLines;
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
        String url = AppConfig.get("openai.chat.url", "https://api.openai.com/v1/chat/completions");
        int maxTokens          = AppConfig.getInt("openai.chunk.max-tokens", 16000);
        int windowLines        = AppConfig.getInt("openai.chunk.window-lines", 900);
        int windowMinLines     = AppConfig.getInt("openai.chunk.window-min-lines", 150);
        int windowOverlapLines = AppConfig.getInt("openai.chunk.window-overlap-lines", 150);
        return new LlmChunkAnalyzer(key, model, url, maxTokens, windowLines, windowMinLines, windowOverlapLines);
    }

    public String getModel() { return model; }

    // -------------------------------------------------------
    // Entry point: proactively window large files, resiliently analyze
    // each window, merge, then guarantee full line coverage.
    // -------------------------------------------------------

    public ChunkAnalysis analyze(String fileName, FileType fileType, List<String> lines) {
        int totalLines = lines.size();
        if (totalLines == 0) {
            return new ChunkAnalysis();
        }

        List<int[]> windows = splitIntoWindows(totalLines);
        if (windows.size() > 1) {
            System.out.println("  " + fileName + " ... " + totalLines + " lines, "
                + windows.size() + " windows to analyze");
        }

        // Windows are analyzed strictly in file order (this loop, not
        // parallel) — see CarriedContext for why that matters: it lets each
        // window's prompt know what earlier windows of the SAME file already
        // established, instead of judging domain/subDomain in total
        // isolation from the rest of the file.
        CarriedContext context = new CarriedContext();
        List<ChunkAnalysis> results = new ArrayList<>(windows.size());
        for (int i = 0; i < windows.size(); i++) {
            int[] w = windows.get(i);
            ChunkAnalysis result = analyzeWindowResilient(
                fileName, fileType, lines, w[0], w[1], context, i + 1, windows.size());
            results.add(result);
            context.absorb(result);
        }

        ChunkAnalysis merged = mergeWindowResults(results);
        fillCoverageGaps(merged, totalLines, fileName);
        return merged;
    }

    /**
     * Minimal file-level context threaded sequentially from earlier windows
     * into later windows' prompts. Without this, each window judges
     * domain/subDomain in total isolation: a generic routing/dispatch
     * paragraph analyzed on its own can reasonably look domain-agnostic even
     * when it's part of the SAME insurance program as an earlier, obviously
     * insurance-specific window — producing wildly inconsistent
     * classifications for one file that then confuse an LLM asked to answer
     * a question about "the program" using chunks from both.
     *
     * <p>Anchors to the first window that reports a non-GENERAL domain
     * (rather than continuously re-voting as more windows complete) and
     * never changes after that — simple and deterministic, and avoids
     * locking in an early window's merely-uninformed "GENERAL" guess before
     * a later window reveals the file's real domain.
     */
    private static final class CarriedContext {
        String programId;
        String author;
        String dateWritten;
        String programDescription;
        String dominantDomain;
        String dominantSubDomain;

        void absorb(ChunkAnalysis a) {
            programId = firstNonBlank(programId, a.programId);
            author = firstNonBlank(author, a.author);
            dateWritten = firstNonBlank(dateWritten, a.dateWritten);
            programDescription = firstNonBlank(programDescription, a.programDescription);
            if (dominantDomain == null) {
                a.chunks.stream()
                    .filter(c -> c.domain != null && !"GENERAL".equalsIgnoreCase(c.domain))
                    .findFirst()
                    .ifPresent(c -> {
                        dominantDomain = c.domain;
                        dominantSubDomain = c.subDomain;
                    });
            }
        }

        /** Null once nothing has been established yet (e.g. this is the file's first window). */
        String describeForPrompt() {
            if (programId == null && programDescription == null && dominantDomain == null) return null;
            StringBuilder sb = new StringBuilder(
                "CONTEXT ESTABLISHED FROM EARLIER WINDOWS OF THIS SAME FILE (for consistency — keep "
                + "this chunk's domain/subDomain aligned with this UNLESS the code shown here clearly "
                + "indicates a distinct, self-contained utility that genuinely doesn't fit):\n");
            if (programId != null) sb.append("- Program/job id: ").append(programId).append("\n");
            if (programDescription != null) sb.append("- Description: ").append(programDescription).append("\n");
            if (dominantDomain != null) {
                sb.append("- Established domain/subDomain: ").append(dominantDomain);
                if (dominantSubDomain != null) sb.append(" / ").append(dominantSubDomain);
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    private List<int[]> splitIntoWindows(int totalLines) {
        List<int[]> windows = new ArrayList<>();
        if (totalLines <= windowLines) {
            windows.add(new int[]{1, totalLines});
            return windows;
        }
        int start = 1;
        while (start <= totalLines) {
            int end = Math.min(start + windowLines - 1, totalLines);
            windows.add(new int[]{start, end});
            if (end >= totalLines) break;
            int next = end - windowOverlapLines + 1;
            start = Math.max(next, start + 1);
        }
        return windows;
    }

    // -------------------------------------------------------
    // Resilient per-window analysis: retry, then adaptively shrink on
    // failure, down to a floor — below which raw content is preserved
    // as a placeholder chunk rather than lost.
    // -------------------------------------------------------

    private ChunkAnalysis analyzeWindowResilient(String fileName, FileType fileType, List<String> lines,
                                                  int start, int end, CarriedContext context,
                                                  int windowNumber, int totalWindows) {
        int size = end - start + 1;
        String progress = totalWindows > 1 ? " [window " + windowNumber + "/" + totalWindows + "]" : "";
        String label = fileName + progress + " [lines " + start + "-" + end + "]";
        System.out.println("  " + label + " ... analyzing...");
        try {
            ChunkAnalysis result = RetryUtil.withRetry(label, attempt -> {
                String numbered = numberLinesRange(lines, start, end);
                String prompt = buildPrompt(fileName, fileType, numbered, context.describeForPrompt());
                String json = callOpenAiClassified(prompt);
                return parseAndValidate(json, start, end);
            });
            System.out.println("  " + label + " ... " + result.chunks.size() + " chunks");
            return result;
        } catch (Exception e) {
            if (size <= windowMinLines) {
                System.out.println("  " + label + " ... FAILED after retries — preserving raw content "
                    + "without analysis: " + e.getMessage());
                recordPermanentFailure(fileName, e.getMessage());
                return placeholderAnalysis(start, end, e.getMessage());
            }
            System.out.println("  " + label + " ... failed (" + e.getMessage() + "), splitting and retrying");
            int mid = start + size / 2;
            ChunkAnalysis left  = analyzeWindowResilient(
                fileName, fileType, lines, start, mid - 1, context, windowNumber, totalWindows);
            ChunkAnalysis right = analyzeWindowResilient(
                fileName, fileType, lines, mid, end, context, windowNumber, totalWindows);
            return mergeTwo(left, right);
        }
    }

    private ChunkAnalysis placeholderAnalysis(int start, int end, String reason) {
        ChunkAnalysis analysis = new ChunkAnalysis();
        analysis.chunks.add(placeholderSpec(start, end, reason));
        return analysis;
    }

    // -------------------------------------------------------
    // Failure diagnostics: why windows permanently fall back to a raw,
    // unanalyzed placeholder. Static (not instance-scoped) so counts
    // aggregate correctly across every file/thread in a run regardless of
    // how many LlmChunkAnalyzer instances get created. See Main's end-of-run
    // summary — this exists because "43% of chunks are unanalyzed" was
    // previously just a raw count with no visibility into WHY, which led to
    // guessing at fixes instead of measuring the actual cause.
    // -------------------------------------------------------

    private static final Map<String, AtomicInteger> FAILURE_REASON_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> FAILURE_FILE_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> GAP_FILE_COUNTS = new ConcurrentHashMap<>();
    private static final AtomicInteger GAP_TOTAL_LINES = new AtomicInteger();
    private static final AtomicInteger GAP_COUNT = new AtomicInteger();

    private static void recordPermanentFailure(String fileName, String message) {
        FAILURE_REASON_COUNTS.computeIfAbsent(categorizeFailure(message), k -> new AtomicInteger()).incrementAndGet();
        FAILURE_FILE_COUNTS.computeIfAbsent(fileName, k -> new AtomicInteger()).incrementAndGet();
    }

    /** A coverage gap — distinct from an outright analysis failure (see
     * recordPermanentFailure): the window(s) covering this file succeeded,
     * but the chunks the model returned still don't cover every line.
     * Measured directly: for a single-window file (no inter-window overlap
     * or dedup involved at all) this can ONLY come from the model's own
     * per-window chunk list having a hole in it — confirmed as the dominant
     * cause here (many files saw more gaps than they could possibly have
     * window-overlap boundaries). fillCoverageGaps absorbs these into the
     * preceding chunk rather than leaving them as contentless placeholders;
     * tracked separately so this is visible without being conflated with
     * real LLM-call failures. */
    private static void recordCoverageGap(String fileName, int gapLines) {
        GAP_FILE_COUNTS.computeIfAbsent(fileName, k -> new AtomicInteger()).incrementAndGet();
        GAP_TOTAL_LINES.addAndGet(gapLines);
        GAP_COUNT.incrementAndGet();
    }

    private static String categorizeFailure(String message) {
        if (message == null) return "unknown";
        String m = message.toLowerCase();
        if (m.contains("truncated")) return "truncated output (hit max_tokens)";
        if (m.contains("malformed json")) return "malformed JSON from LLM";
        if (m.contains("malformed http envelope")) return "malformed HTTP envelope";
        if (m.contains("no chunks")) return "LLM returned no chunks";
        if (m.contains("empty response")) return "LLM returned empty response";
        if (m.contains("http 429")) return "rate limited (429), retries exhausted";
        if (m.matches(".*http [45]\\d\\d.*")) return "OpenAI HTTP error, retries exhausted";
        if (m.contains("http call failed")) return "network/transport failure, retries exhausted";
        return "other: " + truncate(message, 80);
    }

    /** Printed once at the end of a run (see Main) — a reason/file breakdown of
     * every window that permanently fell back to an unanalyzed placeholder,
     * plus separately, any merge-time coverage gaps (see recordCoverageGap). */
    public static String failureSummary() {
        StringBuilder sb = new StringBuilder();

        if (FAILURE_REASON_COUNTS.isEmpty()) {
            sb.append("No permanent chunk-analysis failures this run.\n");
        } else {
            int total = FAILURE_REASON_COUNTS.values().stream().mapToInt(AtomicInteger::get).sum();
            sb.append("Permanent chunk-analysis failures: ").append(total).append("\n");
            sb.append("By reason:\n");
            FAILURE_REASON_COUNTS.entrySet().stream()
                .sorted((a, b) -> b.getValue().get() - a.getValue().get())
                .forEach(e -> sb.append("  ").append(e.getValue().get()).append("  ").append(e.getKey()).append("\n"));
            sb.append("By file (top 15):\n");
            FAILURE_FILE_COUNTS.entrySet().stream()
                .sorted((a, b) -> b.getValue().get() - a.getValue().get())
                .limit(15)
                .forEach(e -> sb.append("  ").append(e.getValue().get()).append("  ").append(e.getKey()).append("\n"));
        }

        if (GAP_COUNT.get() == 0) {
            sb.append("No merge-time coverage gaps this run.\n");
        } else {
            sb.append("Merge-time coverage gaps: ").append(GAP_COUNT.get())
                .append(" (").append(GAP_TOTAL_LINES.get()).append(" total lines)\n");
            sb.append("By file (top 15):\n");
            GAP_FILE_COUNTS.entrySet().stream()
                .sorted((a, b) -> b.getValue().get() - a.getValue().get())
                .limit(15)
                .forEach(e -> sb.append("  ").append(e.getValue().get()).append("  ").append(e.getKey()).append("\n"));
        }

        return sb.toString();
    }

    private ChunkSpec placeholderSpec(int start, int end, String reason) {
        ChunkSpec spec = new ChunkSpec();
        spec.division = null;
        spec.sectionName = "UNANALYZED_" + start + "_" + end;
        spec.lineStart = start;
        spec.lineEnd = end;
        spec.sectionPurpose = "Automated analysis unavailable for lines " + start + "-" + end
            + " after repeated failures (" + safeMessage(reason) + "). Raw source content is preserved below "
            + "and remains searchable, but domain/purpose/relationship metadata could not be derived.";
        spec.domain = "GENERAL";
        spec.subDomain = "UNANALYZED";
        spec.processingType = "UNANALYZED";
        spec.filesRead = List.of();
        spec.filesWritten = List.of();
        spec.filesUpdated = List.of();
        spec.filesDeleted = List.of();
        spec.copybooksReferenced = List.of();
        spec.entryPoints = List.of();
        spec.externalProgramsCalled = List.of();
        spec.paragraphsCalled = List.of();
        spec.keyDataFields = List.of();
        spec.fieldsDefined = List.of();
        spec.fieldsReferenced = List.of();
        spec.businessConditions = List.of();
        spec.hasFileIO = false;
        spec.hasErrorHandling = false;
        spec.tags = List.of("unanalyzed", "needs-review");
        return spec;
    }

    private static String safeMessage(String s) {
        return (s == null || s.isBlank()) ? "unknown error" : s;
    }

    // -------------------------------------------------------
    // Merging window results
    // -------------------------------------------------------

    /** Merges two halves produced by recursively splitting one failing window. No overlap between these. */
    private ChunkAnalysis mergeTwo(ChunkAnalysis a, ChunkAnalysis b) {
        ChunkAnalysis merged = new ChunkAnalysis();
        merged.programId           = firstNonBlank(a.programId, b.programId);
        merged.author              = firstNonBlank(a.author, b.author);
        merged.dateWritten         = firstNonBlank(a.dateWritten, b.dateWritten);
        merged.programDescription  = firstNonBlank(a.programDescription, b.programDescription);
        merged.copybooksUsed       = unionDistinct(a.copybooksUsed, b.copybooksUsed);
        merged.entryPoints         = unionDistinct(a.entryPoints, b.entryPoints);
        merged.chunks.addAll(a.chunks);
        merged.chunks.addAll(b.chunks);
        return merged;
    }

    /** Merges the top-level proactive windows, which DO overlap — de-duplicates the overlap zones. */
    private ChunkAnalysis mergeWindowResults(List<ChunkAnalysis> results) {
        ChunkAnalysis merged = new ChunkAnalysis();
        for (ChunkAnalysis r : results) {
            merged.programId          = firstNonBlank(merged.programId, r.programId);
            merged.author             = firstNonBlank(merged.author, r.author);
            merged.dateWritten        = firstNonBlank(merged.dateWritten, r.dateWritten);
            merged.programDescription = firstNonBlank(merged.programDescription, r.programDescription);
            merged.copybooksUsed      = unionDistinct(merged.copybooksUsed, r.copybooksUsed);
            merged.entryPoints        = unionDistinct(merged.entryPoints, r.entryPoints);
            merged.chunks.addAll(r.chunks);
        }
        merged.chunks.sort(Comparator.comparingInt(c -> c.lineStart));
        merged.chunks = dedupOverlaps(merged.chunks);
        return merged;
    }

    // Overlap beyond this many lines can no longer be explained by ordinary
    // boundary fuzz (a chunk starting/ending a line or two differently than
    // an adjacent one) — it means the same region of the file was genuinely
    // analyzed twice, once per window.
    private static final int OVERLAP_FUZZ_TOLERANCE_LINES = 10;

    /**
     * Drops (or trims — see below) a chunk whose line range substantially
     * overlaps an already-kept one — the signature of the same region being
     * analyzed independently by two different (adjacent, overlapping)
     * windows. Real, distinct semantic units from a single coherent analysis
     * don't overlap each other at all; any overlap beyond a small
     * boundary-fuzz tolerance means duplication, not two legitimately
     * different chunks that happen to share a few lines.
     *
     * <p>Compares against the SMALLER of the two chunks' own lengths (not just
     * the candidate's) — using only the candidate's length here misses a real
     * duplicate whenever the two overlapping chunks are very different sizes
     * (e.g. a small single-paragraph chunk from one window entirely contained
     * within a large multi-paragraph chunk from an adjacent window would be
     * "small overlap" relative to the large chunk's length, but is 100% of
     * the small chunk — a duplicate either way you look at it). This
     * asymmetry is what let duplicate/overlapping chunks for the same file
     * region survive into storage undetected.
     *
     * <p><b>Trim, don't just drop.</b> A candidate can legitimately extend
     * PAST the kept chunk it overlaps with — e.g. window overlap zones are
     * {@code windowOverlapLines} wide, but nothing stops the two windows'
     * own chunk boundaries inside that zone from landing differently, so a
     * candidate can straddle the kept chunk's end and reach into territory
     * the kept chunk never covered at all. Dropping such a candidate
     * wholesale used to throw that unique tail away too, and it would then
     * surface as an entirely un-analyzed "coverage gap" placeholder
     * downstream (see fillCoverageGaps) — accounting for a large share of
     * this ingestor's unanalyzed-chunk rate, confirmed by seeing that rate
     * stay high even on runs with zero actual analysis failures. Instead,
     * when the candidate's unique tail beyond the kept chunk is itself
     * bigger than the fuzz tolerance, keep just that tail (trimming its
     * lineStart forward) rather than discarding the whole chunk. Its
     * LLM-written metadata (sectionPurpose, keyDataFields, etc.) still
     * describes the chunk's original, untrimmed range, which may not
     * perfectly fit the trimmed remainder alone — an acceptable approximation
     * given the alternative is that content having no metadata at all.
     */
    private List<ChunkSpec> dedupOverlaps(List<ChunkSpec> sorted) {
        List<ChunkSpec> kept = new ArrayList<>();
        for (ChunkSpec original : sorted) {
            ChunkSpec c = original;
            boolean isDuplicate = false;
            for (ChunkSpec k : kept) {
                int overlapStart = Math.max(c.lineStart, k.lineStart);
                int overlapEnd   = Math.min(c.lineEnd, k.lineEnd);
                int overlapLen   = overlapEnd - overlapStart + 1;
                if (overlapLen <= OVERLAP_FUZZ_TOLERANCE_LINES) continue;

                int cLen = c.lineEnd - c.lineStart + 1;
                int kLen = k.lineEnd - k.lineStart + 1;
                int smallerLen = Math.min(cLen, kLen);
                if (overlapLen < smallerLen * 0.5) continue;

                int uniqueTail = c.lineEnd - k.lineEnd;
                if (uniqueTail > OVERLAP_FUZZ_TOLERANCE_LINES) {
                    c.lineStart = k.lineEnd + 1;
                    continue; // re-check the trimmed remainder against the other kept chunks
                }
                isDuplicate = true;
                break;
            }
            if (!isDuplicate) kept.add(c);
        }
        return kept;
    }

    /**
     * Final safety net: after merging, fill any remaining gap in 1..totalLines
     * with a placeholder chunk. Guarantees every line belongs to some chunk
     * even if a bug elsewhere in merge/dedup dropped a slice.
     */
    /**
     * Final safety net: after merging, every line in 1..totalLines must
     * belong to some chunk. Most such gaps turn out to be small holes the
     * model left BETWEEN two chunks it otherwise analyzed just fine (a
     * skipped blank/comment line, a paragraph boundary it didn't assign to
     * either neighbor) — not a real analysis failure — so a gap with a real
     * chunk immediately before it gets absorbed into that chunk (extending
     * its lineEnd) rather than spawning a separate, contentless "unanalyzed"
     * placeholder. Its LLM-written metadata (sectionPurpose, keyDataFields,
     * etc.) was written for its original, smaller range and may not
     * perfectly describe the few absorbed lines too — an acceptable
     * approximation given the alternative is that content having no
     * metadata at all. A gap with no preceding chunk (i.e. right at the very
     * start of the file — in practice, usually the model skipping past
     * IDENTIFICATION-DIVISION-style header boilerplate before its first real
     * chunk) is handled symmetrically: absorbed forward into the FIRST
     * chunk by pulling its lineStart back to 1, rather than spawning a
     * placeholder for just those first few lines. The only case that can
     * still fall through to an actual placeholder is a file with zero
     * chunks at all, which existing checks elsewhere already treat as a
     * hard failure before this method is ever reached.
     */
    private void fillCoverageGaps(ChunkAnalysis merged, int totalLines, String fileName) {
        merged.chunks.sort(Comparator.comparingInt(c -> c.lineStart));
        List<ChunkSpec> leadingGaps = new ArrayList<>();
        int cursor = 1;
        ChunkSpec previous = null;
        for (ChunkSpec c : merged.chunks) {
            if (c.lineStart > cursor) {
                int gapEnd = c.lineStart - 1;
                recordCoverageGap(fileName, gapEnd - cursor + 1);
                if (previous != null) {
                    previous.lineEnd = gapEnd;
                } else {
                    c.lineStart = cursor;
                }
            }
            cursor = Math.max(cursor, c.lineEnd + 1);
            previous = c;
        }
        if (cursor <= totalLines) {
            recordCoverageGap(fileName, totalLines - cursor + 1);
            if (previous != null) {
                previous.lineEnd = totalLines;
            } else {
                leadingGaps.add(placeholderSpec(cursor, totalLines, "merge produced a trailing coverage gap"));
            }
        }
        if (!leadingGaps.isEmpty()) {
            merged.chunks.addAll(leadingGaps);
            merged.chunks.sort(Comparator.comparingInt(c -> c.lineStart));
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }

    private static List<String> unionDistinct(List<String> a, List<String> b) {
        List<String> result = new ArrayList<>(a == null ? List.of() : a);
        if (b != null) {
            for (String s : b) {
                if (!result.contains(s)) result.add(s);
            }
        }
        return result;
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
            chunk.setFieldsDefined(emptyToNull(spec.fieldsDefined));
            chunk.setFieldsReferenced(emptyToNull(spec.fieldsReferenced));
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

    private String numberLinesRange(List<String> lines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            sb.append(i).append(": ").append(lines.get(i - 1)).append("\n");
        }
        return sb.toString();
    }

    private String buildPrompt(String fileName, FileType fileType, String numberedSource, String carriedContext) {
        String kindGuidance = switch (fileType) {
            case COPYBOOK -> """
                This file is a COBOL COPYBOOK: a pure data-layout definition with no PROCEDURE DIVISION.
                Segment it into one chunk per top-level record/group definition (typically each 01-level,
                but use your judgment for the file's actual structure). division should be null for every
                chunk. processingType should be "COPYBOOK_RECORD_LAYOUT". filesRead/filesWritten/filesUpdated/
                filesDeleted/externalProgramsCalled/paragraphsCalled should be empty arrays. keyDataFields
                should list the field names defined in that record. fieldsDefined should list every field
                name defined in that record, not just the "key" ones — this feeds a dependency graph, so
                completeness matters more than brevity — but cap it at the 40 most important if the record
                genuinely has more than that. fieldsReferenced should be an empty array (copybooks have no
                procedure logic to reference fields from).
                """;
            case JCL -> """
                This file is a JCL (Job Control Language) batch job stream. Segment it into a JOB header
                chunk followed by one chunk per EXEC step (or equivalent logical unit). For each step chunk:
                externalProgramsCalled is the program named on EXEC PGM=; filesRead are DD-statement datasets
                that are inputs (e.g. DISP=SHR/OLD); filesWritten are DD-statement datasets that are outputs
                (e.g. DISP=NEW/MOD); businessConditions should capture PARM= values and COND=/IF-THEN
                conditional-execution logic as short phrases; keyDataFields may list SYSIN parameter values.
                division can be e.g. "JOB_CARD" for the header and "JCL_STEP" for steps. processingType should
                be "BATCH_JCL" for the header and "BATCH_JCL_STEP" for steps. fieldsDefined and
                fieldsReferenced should be empty arrays (JCL has no COBOL data items).
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
                CALL/EXEC CICS LINK/XCTL targets. paragraphsCalled are PERFORM targets. For a DATA DIVISION
                chunk, fieldsDefined should list every field name this chunk defines (every level number +
                PIC clause / group item, not just the "key" business ones — completeness matters here since
                this feeds a dependency graph — but cap it at the 40 most important if there are genuinely
                more than that in one chunk); fieldsReferenced should be empty for such a chunk. For a
                PROCEDURE DIVISION chunk, fieldsReferenced should list every field name this chunk's logic
                actually reads, writes, or tests (MOVE/IF/COMPUTE/EVALUATE/etc. — including fields defined in
                a copied copybook, whatever dialect the syntax is written in), same 40-field cap;
                fieldsDefined should be empty for such a chunk unless it also declares working-storage inline.
                """;
        };

        return """
            You are a COBOL/AS400 legacy-modernization expert building a RAG knowledge base and a
            dependency graph directly from source code. You will be given a line-numbered EXCERPT of one
            file — it may be the whole file, or one window of a larger file, so it may start or end
            mid-structure. Decide, entirely from the code shown, how to split it into coherent semantic
            chunks and what metadata each chunk carries. Do not rely on any fixed rule set — read the
            actual code and use your own judgment.

            FILE: %s
            DECLARED TYPE HINT: %s (a hint only — override it if the code itself indicates otherwise)

            %s
            %s
            SOURCE (line-numbered; the lineStart/lineEnd you return must match these numbers exactly —
            they are ABSOLUTE line numbers in the original file, not relative to this excerpt):
            ```
            %s
            ```

            TASK
            1. Identify whatever file-level metadata is evident FROM THIS EXCERPT: the program/job id,
               author, date written, a short business description, every COPY target referenced in this
               excerpt, and every ENTRY point declared in this excerpt (empty arrays/null if not present
               in what you were shown — do not guess at metadata that would only appear elsewhere in the
               file).
            2. Split the shown lines into an ORDERED list of non-overlapping chunks that each represent one
               coherent unit. Merge trivial fragments; split any unit larger than ~200 lines at a natural
               sub-boundary. Every line shown must belong to some chunk — including a partial unit at the
               very start or end of this excerpt if the excerpt begins or ends mid-structure, and including
               blank lines, comment-only lines, and lines between paragraphs that don't feel like they
               belong to either neighbor — attribute those to whichever adjacent chunk they're physically
               closer to rather than omitting them. The chunks must be perfectly CONTIGUOUS as well as
               non-overlapping: chunk N's lineEnd + 1 must exactly equal chunk N+1's lineStart, with no
               unaccounted-for lines anywhere between the first and last chunk, not just at the ends.
            3. For EACH chunk, decide every one of these values yourself, based only on that chunk's code
               and the file-level context above:
               - division: enclosing structural unit if applicable, else null.
               - sectionName: a short identifying name for the chunk, upper-cased, as it appears in the source.
               - lineStart / lineEnd: ABSOLUTE 1-based inclusive line numbers matching the numbering shown above.
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
               - fieldsDefined: every field name this chunk's DATA DIVISION defines — used to build a
                 dependency graph, so list all of them, not just the important ones (max 40; if there are
                 genuinely more, keep the 40 most important). Empty array if this chunk defines no data items.
               - fieldsReferenced: every field name this chunk's PROCEDURE DIVISION logic actually reads,
                 writes, or tests — including fields defined in a copied copybook (max 40, same rule). Empty
                 array if this chunk has no procedure logic.
               - businessConditions: notable business rules/conditions evaluated in this chunk (max 8),
                 each as a short phrase.
               - hasFileIO: true if this chunk performs any file/database I/O.
               - hasErrorHandling: true if this chunk contains error/exception handling.
               - tags: 4-10 lowercase-kebab search tags summarizing this chunk (domain, sub-domain,
                 capability words, "file-io"/"error-handling"/"batch"/"cics" as applicable).

            IMPORTANT: cover every line shown, from the first line number to the last line number in the
            source above, with chunks — do not stop early, and do not leave any gap BETWEEN chunks either;
            re-check that each chunk's lineEnd + 1 equals the next chunk's lineStart before responding.

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
                  "fieldsDefined": ["string", ...],
                  "fieldsReferenced": ["string", ...],
                  "businessConditions": ["string", ...],
                  "hasFileIO": true,
                  "hasErrorHandling": false,
                  "tags": ["string", ...]
                }
              ]
            }
            """.formatted(fileName, fileType.label, kindGuidance,
                carriedContext != null ? carriedContext : "", numberedSource);
    }

    // -------------------------------------------------------
    // OpenAI call + JSON parsing
    // -------------------------------------------------------

    /**
     * Calls OpenAI and classifies failures for the retry loop:
     *   - network/5xx/429           -> RetryableApiException (retry same size, honoring Retry-After)
     *   - other 4xx (e.g. context-length-exceeded), empty content -> NoRetryException (escalate: shrink window)
     */
    private String callOpenAiClassified(String prompt) throws Exception {
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
            .timeout(Duration.ofSeconds(60000))
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
            throw new RetryUtil.NoRetryException(
                "OpenAI HTTP " + status + ": " + truncate(response.body(), 300));
        }

        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (Exception e) {
            throw new RetryUtil.RetryableApiException("Malformed HTTP envelope: " + e.getMessage(), null);
        }
        String content = root.path("choices").get(0).path("message").path("content").asText();
        if (content == null || content.isBlank()) {
            throw new RetryUtil.NoRetryException("OpenAI returned an empty response");
        }
        return content;
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

    /**
     * Parses the LLM's JSON and validates it's usable: non-empty, and its
     * coverage roughly reaches the window's end (a large unexplained shortfall
     * is the signature of the model hitting its own output-token ceiling mid
     * window — treated as "needs a smaller window", not "just retry the same
     * call again").
     *
     * <p>Before concluding a shortfall means truncation, checks for a simpler
     * and surprisingly common failure: the model reporting line numbers below
     * this window's own start — which is impossible for correctly-numbered
     * absolute line numbers (a window can't cover lines before it begins) and
     * is the signature of the model numbering its response some other way
     * (e.g. relative to the excerpt) despite being shown absolute numbers. In
     * that case the whole response is usually still complete and correct —
     * just mislabeled — so it's shifted back into alignment and reused rather
     * than discarded and retried/split for no real reason.
     *
     * <p>That repair is only trusted when a MAJORITY of the window's chunks
     * show the same below-start pattern — i.e. the whole response is
     * uniformly mis-numbered. If only one or two outlier chunks report a low
     * lineStart while the rest are already correctly positioned within this
     * window, the outliers are the problem, not the window's numbering as a
     * whole: shifting every chunk by an offset computed from an outlier would
     * drag the ALREADY-CORRECT majority away from their true position and
     * into overlap with whatever an adjacent window legitimately covers
     * there — turning one bad chunk into a duplicated/misplaced region. In
     * that case the repair is skipped and this falls through to the normal
     * truncation check on the unmodified analysis.
     */
    private ChunkAnalysis parseAndValidate(String json, int start, int end) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new RetryUtil.NoRetryException("Malformed JSON from LLM: " + e.getMessage(), e);
        }

        ChunkAnalysis analysis = toChunkAnalysis(root);
        if (analysis.chunks.isEmpty()) {
            throw new RetryUtil.NoRetryException("LLM returned no chunks for this window");
        }

        int windowSize = end - start + 1;
        // A shortfall within the configured overlap is not a real problem: the
        // NEXT window starts inside that same overlap zone and will re-scan
        // whatever this one left off (the model often reasonably declines to
        // guess about a paragraph this window's own arbitrary line-count cutoff
        // sliced in half). Only a shortfall bigger than the overlap can leave an
        // actual gap, so that's the threshold that should trigger a retry/split.
        int tolerance = Math.max(windowOverlapLines, windowSize / 10);

        int minLine = analysis.chunks.stream().mapToInt(c -> c.lineStart).min().orElse(start);
        int maxLine = analysis.chunks.stream().mapToInt(c -> c.lineEnd).max().orElse(0);

        if (minLine < start) {
            int reportedSpan = maxLine - minLine + 1;
            // Only repair if the SHAPE of what was reported plausibly matches this
            // window (not wildly larger/smaller) — otherwise this isn't a simple
            // offset and forcing a shift would just paper over real garbage.
            boolean spanPlausible = reportedSpan > 0 && reportedSpan <= windowSize * 2;
            long belowStartCount = analysis.chunks.stream().filter(c -> c.lineStart < start).count();
            boolean systematic = belowStartCount * 2 >= analysis.chunks.size();
            if (spanPlausible && systematic) {
                int offset = start - minLine;
                for (ChunkSpec c : analysis.chunks) {
                    c.lineStart += offset;
                    c.lineEnd += offset;
                }
                System.out.println("  [lines " + start + "-" + end + "] ... auto-corrected a "
                    + offset + "-line numbering offset in the LLM's response ("
                    + analysis.chunks.size() + " chunks kept)");
                minLine += offset;
                maxLine += offset;
            } else if (spanPlausible) {
                System.out.println("  [lines " + start + "-" + end + "] ... " + belowStartCount + "/"
                    + analysis.chunks.size() + " chunks reported lines before this window's start — "
                    + "not a majority, skipping auto-repair to avoid displacing the correctly-numbered chunks");
            }
        }

        if (maxLine < end - tolerance) {
            throw new RetryUtil.NoRetryException("LLM output looks truncated — " + analysis.chunks.size()
                + " chunks covering lines " + minLine + "-" + maxLine + " of a window " + start + "-" + end);
        }
        return analysis;
    }

    private ChunkAnalysis toChunkAnalysis(JsonNode root) {
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
            spec.fieldsDefined = textArray(c.path("fieldsDefined"));
            spec.fieldsReferenced = textArray(c.path("fieldsReferenced"));
            spec.businessConditions = textArray(c.path("businessConditions"));
            spec.hasFileIO = c.path("hasFileIO").asBoolean(false);
            spec.hasErrorHandling = c.path("hasErrorHandling").asBoolean(false);
            spec.tags = textArray(c.path("tags"));
            analysis.chunks.add(spec);
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
    // Program/job-level classification rollup
    // -------------------------------------------------------

    /**
     * Majority-vote domain/subDomain/processingType across a file's chunks,
     * used as the single program- or job-level classification for the
     * knowledge graph. Each window is analyzed independently with no
     * visibility into how OTHER windows of the same file were classified, so
     * different sections of one large file can end up with different
     * domain/subDomain/processingType labels — legitimately (a shared
     * utility paragraph really can look domain-agnostic in isolation) or not
     * (see the dedup/repair fixes above). Either way, the file as a whole
     * needs ONE consistent classification for the graph, so this picks
     * whichever combination the chunks agree on most, weighted by how many
     * lines each chunk covers — a single large, clearly-classified section
     * should outweigh a couple of short, ambiguous ones — rather than
     * arbitrarily trusting whichever chunk happened to be first.
     */
    public static ProgramClassification majorityClassification(List<FileChunk> chunks) {
        Map<String, Integer> weightByKey = new LinkedHashMap<>();
        Map<String, ProgramClassification> valueByKey = new HashMap<>();
        for (FileChunk c : chunks) {
            if (c.getDomain() == null) continue;
            String key = c.getDomain() + "|" + c.getSubDomain() + "|" + c.getProcessingType();
            int lines = Math.max(1, c.getLineEnd() - c.getLineStart() + 1);
            weightByKey.merge(key, lines, Integer::sum);
            valueByKey.putIfAbsent(key,
                new ProgramClassification(c.getDomain(), c.getSubDomain(), c.getProcessingType()));
        }
        return weightByKey.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(e -> valueByKey.get(e.getKey()))
            .orElseGet(() -> chunks.isEmpty()
                ? new ProgramClassification("GENERAL", null, null)
                : new ProgramClassification(chunks.get(0).getDomain(), chunks.get(0).getSubDomain(),
                    chunks.get(0).getProcessingType()));
    }

    public record ProgramClassification(String domain, String subDomain, String processingType) {}

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
        public List<String> fieldsDefined;
        public List<String> fieldsReferenced;
        public List<String> businessConditions;
        public boolean hasFileIO;
        public boolean hasErrorHandling;
        public List<String> tags;
    }
}
