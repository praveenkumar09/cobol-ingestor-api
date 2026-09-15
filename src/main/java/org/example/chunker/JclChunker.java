package org.example.chunker;

import org.example.graph.KnowledgeGraphBuilder;
import org.example.model.FileChunk;
import org.example.model.FileType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Chunks JCL (Job Control Language) job streams.
 *
 * Chunk boundaries and every semantic field are decided by
 * {@link LlmChunkAnalyzer} — one LLM call analyzes the whole job stream and
 * returns structured chunk metadata (job header, one chunk per step, with
 * programs executed, datasets read/written, PARM/COND conditions, etc.).
 * This class only slices the original source lines at the returned line
 * ranges and wires the result into the knowledge graph.
 */
public class JclChunker {

    private final LlmChunkAnalyzer analyzer;

    public JclChunker() {
        this(LlmChunkAnalyzer.create());
    }

    public JclChunker(LlmChunkAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    public boolean isAvailable() {
        return analyzer != null;
    }

    public List<FileChunk> chunk(Path filePath) throws IOException {
        return chunk(filePath, null);
    }

    public List<FileChunk> chunk(Path filePath, KnowledgeGraphBuilder graphBuilder) throws IOException {
        if (analyzer == null) {
            throw new IllegalStateException(
                "LLM chunk analyzer unavailable — set OPENAI_API_KEY to enable JCL chunking");
        }

        List<String> lines = Files.readAllLines(filePath);
        String fileName = filePath.getFileName().toString();

        LlmChunkAnalyzer.ChunkAnalysis analysis;
        try {
            analysis = analyzer.analyze(fileName, FileType.JCL, lines);
        } catch (Exception e) {
            throw new IOException("LLM chunk analysis failed for " + fileName + ": " + e.getMessage(), e);
        }

        String jobName = (analysis.programId == null || analysis.programId.isBlank())
            ? fileName.replaceAll("\\.[^.]+$", "").toUpperCase()
            : analysis.programId.toUpperCase();

        List<FileChunk> chunks = LlmChunkAnalyzer.toFileChunks(analysis, lines, fileName, FileType.JCL, jobName);

        if (graphBuilder != null) {
            registerInGraph(graphBuilder, jobName, chunks);
        }
        return chunks;
    }

    private void registerInGraph(KnowledgeGraphBuilder graphBuilder, String jobName, List<FileChunk> chunks) {
        if (chunks.isEmpty()) return;

        Set<String> programs = new LinkedHashSet<>();
        Set<String> datasets = new LinkedHashSet<>();

        for (FileChunk c : chunks) {
            if (c.getExternalProgramsCalled() != null) programs.addAll(c.getExternalProgramsCalled());
            if (c.getFilesRead()    != null) datasets.addAll(c.getFilesRead());
            if (c.getFilesWritten() != null) datasets.addAll(c.getFilesWritten());
        }

        String domain    = chunks.get(0).getDomain();
        String subDomain = chunks.get(0).getSubDomain();

        graphBuilder.registerJclJob(jobName, domain, subDomain,
            new ArrayList<>(programs), new ArrayList<>(datasets));
    }
}
