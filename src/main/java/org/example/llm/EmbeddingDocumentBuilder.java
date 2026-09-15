package org.example.llm;

import org.example.model.FileChunk;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds a synthetic plain-English embedding document from chunk metadata.
 * This document — not the raw COBOL — is what gets embedded into the vector DB.
 *
 * Also marks each chunk with shouldEmbed=false when it has no retrieval value
 * (preamble headers, trivial identification blocks, near-empty sections).
 */
public class EmbeddingDocumentBuilder {

    public static void process(List<FileChunk> chunks) {
        for (FileChunk chunk : chunks) {
            boolean embeddable = isEmbeddable(chunk);
            chunk.setShouldEmbed(embeddable);
            if (embeddable) {
                chunk.setEmbeddingText(buildEmbeddingText(chunk));
            }
        }
    }

    // -------------------------------------------------------
    // Embeddability rules
    // -------------------------------------------------------

    private static boolean isEmbeddable(FileChunk chunk) {
        String division = chunk.getDivision();
        String section  = chunk.getSectionName();
        String content  = chunk.getContent();

        // PREAMBLE — copyright block, no code
        if (division == null && section == null) return false;

        // Identification block — just PROGRAM-ID / AUTHOR (3 lines)
        if ("IDENTIFICATION_DIVISION".equals(division)) return false;

        // Bare ENVIRONMENT DIVISION header line with no substance
        if ("ENVIRONMENT_DIVISION".equals(division) && section == null) return false;

        // Bare DATA DIVISION header line with no substance
        if ("DATA_DIVISION".equals(division) && section == null) return false;

        // Too little content to be useful
        if (content == null || content.strip().length() < 80) return false;

        return true;
    }

    // -------------------------------------------------------
    // Synthetic embedding document construction
    // -------------------------------------------------------

    private static String buildEmbeddingText(FileChunk chunk) {
        StringBuilder sb = new StringBuilder();

        // ── Header ──────────────────────────────────────────
        sb.append("Program: ").append(nvl(chunk.getProgramId(), "UNKNOWN"));
        sb.append(" | Type: ").append(nvl(chunk.getFileType(), "COBOL"));
        sb.append(" | Domain: ").append(nvl(chunk.getDomain(), "GENERAL"));
        if (chunk.getSubDomain() != null) {
            sb.append(" > ").append(chunk.getSubDomain().replace("_", " "));
        }
        if (chunk.getProcessingType() != null) {
            sb.append(" | Processing: ").append(chunk.getProcessingType().replace("_", " "));
        }
        sb.append("\n");

        // ── Structural location ──────────────────────────────
        if (chunk.getDivision() != null) {
            sb.append("Division: ").append(chunk.getDivision().replace("_", " ")).append("\n");
        }
        if (chunk.getSectionName() != null
                && !chunk.getSectionName().equals(chunk.getDivision())) {
            sb.append("Section: ").append(chunk.getSectionName()).append("\n");
        }

        // ── Purpose (most important field for retrieval) ─────
        if (chunk.getSectionPurpose() != null && !chunk.getSectionPurpose().isBlank()) {
            sb.append("Purpose: ").append(chunk.getSectionPurpose()).append("\n");
        }

        // ── File I/O ─────────────────────────────────────────
        appendList(sb, "Reads", chunk.getFilesRead());
        appendList(sb, "Writes", chunk.getFilesWritten());
        appendList(sb, "Updates", chunk.getFilesUpdated());
        appendList(sb, "Deletes from", chunk.getFilesDeleted());

        // ── Dependencies ─────────────────────────────────────
        appendList(sb, "External calls", chunk.getExternalProgramsCalled());
        appendList(sb, "Copybooks", chunk.getCopybooksReferenced());

        // ── Internal structure (trimmed) ─────────────────────
        if (chunk.getParagraphsCalled() != null && !chunk.getParagraphsCalled().isEmpty()) {
            List<String> paras = chunk.getParagraphsCalled().stream()
                .limit(6)
                .map(p -> p.replaceAll("^\\d{4}-", "").replaceAll("-PARA$", ""))
                .collect(Collectors.toList());
            sb.append("Key paragraphs: ").append(String.join(", ", paras)).append("\n");
        }

        // ── Data fields (top 8 by frequency) ─────────────────
        if (chunk.getKeyDataFields() != null && !chunk.getKeyDataFields().isEmpty()) {
            sb.append("Key fields: ")
              .append(String.join(", ", limit(chunk.getKeyDataFields(), 8)))
              .append("\n");
        }

        // ── Business conditions (top 4) ───────────────────────
        if (chunk.getBusinessConditions() != null && !chunk.getBusinessConditions().isEmpty()) {
            sb.append("Business conditions: ")
              .append(String.join("; ", limit(chunk.getBusinessConditions(), 4)))
              .append("\n");
        }

        // ── Tags ─────────────────────────────────────────────
        if (chunk.getTags() != null && !chunk.getTags().isEmpty()) {
            sb.append("Tags: ").append(String.join(", ", chunk.getTags())).append("\n");
        }

        return sb.toString().strip();
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private static void appendList(StringBuilder sb, String label, List<String> items) {
        if (items != null && !items.isEmpty()) {
            sb.append(label).append(": ").append(String.join(", ", items)).append("\n");
        }
    }

    private static List<String> limit(List<String> list, int max) {
        return list.size() <= max ? list : list.subList(0, max);
    }

    private static String nvl(String s, String fallback) {
        return (s != null && !s.isBlank()) ? s : fallback;
    }
}