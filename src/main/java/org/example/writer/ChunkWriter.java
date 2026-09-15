package org.example.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.model.FileChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes processed chunks to the output directory as JSON.
 * One JSON file per source file, containing an array of chunk objects.
 */
public class ChunkWriter {

    private final ObjectMapper mapper;

    public ChunkWriter() {
        mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void writeChunks(List<FileChunk> chunks, Path outputDir, String sourceFileName) throws IOException {
        if (chunks.isEmpty()) return;

        String outputFileName = sourceFileName.replaceAll("\\.[^.]+$", "") + "_chunks.json";
        Path outputFile = outputDir.resolve(outputFileName);

        mapper.writeValue(outputFile.toFile(), chunks);
        System.out.println("  Written: " + outputFile.getFileName()
            + " (" + chunks.size() + " chunks)");
    }
}