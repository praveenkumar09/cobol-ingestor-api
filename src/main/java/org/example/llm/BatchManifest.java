package org.example.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal crash-survivability record for submitted batch jobs — the locked-in
 * decision for this build-out was "just log the IDs" (no automatic resume
 * tool), so a batch submitted right before the process dies mid-24h-wait is
 * at least identifiable and its cost accounted for, rather than silently lost
 * from view. One JSON object appended per line (JSONL) to
 * outputDir/batch/manifest.json immediately after each createBatch() call,
 * before polling begins — a plain append, never a read-modify-write, so it
 * can't itself be corrupted by a crash mid-write the way rewriting a JSON
 * array each time could be.
 */
public class BatchManifest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void append(Path outputDir, String batchId, String purpose, int fileCount) {
        try {
            Path manifestDir = outputDir.resolve("batch");
            Files.createDirectories(manifestDir);
            Path manifestFile = manifestDir.resolve("manifest.json");

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("batchId", batchId);
            entry.put("purpose", purpose);
            entry.put("fileCount", fileCount);
            entry.put("createdAt", Instant.now().toString());

            String line = MAPPER.writeValueAsString(entry) + System.lineSeparator();
            Files.writeString(manifestFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Best-effort logging only — never fail a real batch submission over
            // a manifest write hiccup.
            System.out.println("  WARNING: could not append batch manifest entry for " + batchId
                + ": " + e.getMessage());
        }
    }
}
