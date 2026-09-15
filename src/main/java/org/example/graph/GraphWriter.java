package org.example.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;

public class GraphWriter {

    private final ObjectMapper mapper;

    public GraphWriter() {
        mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void write(KnowledgeGraph graph, Path outputDir) throws IOException {
        Path out = outputDir.resolve("knowledge_graph.json");
        mapper.writeValue(out.toFile(), graph);
        System.out.printf("  Written: knowledge_graph.json (%d nodes, %d edges)%n",
            graph.getNodes().size(), graph.getEdges().size());
    }
}