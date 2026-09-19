package org.example.chunker;

import org.example.graph.KnowledgeGraphBuilder;
import org.example.model.FileChunk;
import org.example.model.FileType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Chunks COBOL programs (.cbl) and copybooks (.cpy) into semantic units.
 *
 * Chunk boundaries and every semantic field (purpose, domain, file I/O,
 * business conditions, tags, etc.) are decided by {@link LlmChunkAnalyzer} —
 * one LLM call analyzes the whole file and returns structured chunk
 * metadata. This class only slices the original source lines at the
 * returned line ranges and wires the result into the knowledge graph.
 */
public class CobolChunker {

    private final LlmChunkAnalyzer analyzer;

    public CobolChunker() {
        this(LlmChunkAnalyzer.create());
    }

    public CobolChunker(LlmChunkAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    public boolean isAvailable() {
        return analyzer != null;
    }

    public List<FileChunk> chunk(Path filePath, FileType fileType) throws IOException {
        return chunk(filePath, fileType, null);
    }

    public List<FileChunk> chunk(Path filePath, FileType fileType,
                                  KnowledgeGraphBuilder graphBuilder) throws IOException {
        if (analyzer == null) {
            throw new IllegalStateException(
                "LLM chunk analyzer unavailable — set OPENAI_API_KEY to enable COBOL/copybook chunking");
        }

        List<String> lines = Files.readAllLines(filePath);
        String fileName = filePath.getFileName().toString();

        LlmChunkAnalyzer.ChunkAnalysis analysis;
        try {
            analysis = analyzer.analyze(fileName, fileType, lines);
        } catch (Exception e) {
            throw new IOException("LLM chunk analysis failed for " + fileName + ": " + e.getMessage(), e);
        }

        String programId = (analysis.programId == null || analysis.programId.isBlank())
            ? fileName.replaceAll("\\.[^.]+$", "").toUpperCase()
            : analysis.programId.toUpperCase();

        List<FileChunk> chunks = LlmChunkAnalyzer.toFileChunks(analysis, lines, fileName, fileType, programId);

        if (graphBuilder != null) {
            registerInGraph(graphBuilder, analysis, programId, fileName, chunks, fileType);
        }
        return chunks;
    }

    private void registerInGraph(KnowledgeGraphBuilder graphBuilder,
                                  LlmChunkAnalyzer.ChunkAnalysis analysis, String programId,
                                  String fileName, List<FileChunk> chunks, FileType fileType) {
        if (chunks.isEmpty()) return;

        if (fileType == FileType.COPYBOOK) {
            List<String> records = chunks.stream()
                .map(FileChunk::getSectionName)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            graphBuilder.registerCopybook(programId, fileName, records);

            Set<String> allFieldsDefined = new LinkedHashSet<>();
            for (FileChunk c : chunks) {
                if (c.getFieldsDefined() != null) allFieldsDefined.addAll(c.getFieldsDefined());
            }
            graphBuilder.registerCopybookFields(programId, new ArrayList<>(allFieldsDefined));
            return;
        }

        Set<String> allRead    = new LinkedHashSet<>();
        Set<String> allWritten = new LinkedHashSet<>();
        Set<String> allUpdated = new LinkedHashSet<>();
        Set<String> allDeleted = new LinkedHashSet<>();
        Set<String> allCalls   = new LinkedHashSet<>();
        Set<String> allFieldsDefined    = new LinkedHashSet<>();
        Set<String> allFieldsReferenced = new LinkedHashSet<>();

        for (FileChunk c : chunks) {
            if (c.getFilesRead()             != null) allRead.addAll(c.getFilesRead());
            if (c.getFilesWritten()          != null) allWritten.addAll(c.getFilesWritten());
            if (c.getFilesUpdated()          != null) allUpdated.addAll(c.getFilesUpdated());
            if (c.getFilesDeleted()          != null) allDeleted.addAll(c.getFilesDeleted());
            if (c.getExternalProgramsCalled() != null) allCalls.addAll(c.getExternalProgramsCalled());
            if (c.getFieldsDefined()          != null) allFieldsDefined.addAll(c.getFieldsDefined());
            if (c.getFieldsReferenced()       != null) allFieldsReferenced.addAll(c.getFieldsReferenced());
        }

        LlmChunkAnalyzer.ProgramClassification classification = LlmChunkAnalyzer.majorityClassification(chunks);

        graphBuilder.registerCobolProgram(
            programId, classification.domain(), classification.subDomain(), classification.processingType(),
            analysis.author, analysis.dateWritten,
            new ArrayList<>(analysis.copybooksUsed),
            new ArrayList<>(analysis.entryPoints),
            new ArrayList<>(allRead),
            new ArrayList<>(allWritten),
            new ArrayList<>(allUpdated),
            new ArrayList<>(allDeleted),
            new ArrayList<>(allCalls));
        graphBuilder.registerProgramFields(programId, new ArrayList<>(allFieldsDefined), new ArrayList<>(allFieldsReferenced));
    }
}
