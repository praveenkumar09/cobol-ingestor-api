package org.example.graph;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Accumulates graph nodes/edges while files are chunked.
 *
 * <p>Chunking now runs multiple files concurrently (see Main's batched parallel
 * processing), so every public mutating method here is synchronized — each
 * registration does several dependent map/list/counter updates that must land
 * as one atomic unit, not interleave with another thread's registration.
 * Registrations themselves are cheap in-memory operations; the actual
 * bottleneck (the LLM call) happens before this class is ever touched, so
 * serializing access here costs negligible throughput.
 */
public class KnowledgeGraphBuilder {

    private final Map<String, GraphNode> nodes = new LinkedHashMap<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final Set<String> edgeDedup = new HashSet<>();
    private int edgeSeq = 0;

    public synchronized void registerCobolProgram(
            String programId, String domain, String subDomain,
            String processingType, String author, String dateWritten,
            List<String> copybooksUsed, List<String> entryPoints,
            List<String> filesRead, List<String> filesWritten,
            List<String> filesUpdated, List<String> filesDeleted,
            List<String> externalCalls) {

        GraphNode node = getOrCreateNode(programId, "COBOL_PROGRAM",
            humanize(programId) + " program");
        Map<String, Object> p = node.getProperties();
        p.put("domain", domain);
        p.put("subDomain", subDomain);
        p.put("processingType", processingType);
        if (author != null && !author.isBlank()) p.put("author", author);
        if (dateWritten != null && !dateWritten.isBlank()) p.put("dateWritten", dateWritten);

        for (String cpyb : copybooksUsed) {
            getOrCreateNode(cpyb, "COPYBOOK", humanize(cpyb) + " copybook");
            addEdge(programId, cpyb, "COPIES", programId + " copies " + cpyb);
        }
        for (String f : filesRead)    { getOrCreateNode(f, "DATABASE_FILE", f); addEdge(programId, f, "READS",        ""); }
        for (String f : filesWritten) { getOrCreateNode(f, "DATABASE_FILE", f); addEdge(programId, f, "WRITES",       ""); }
        for (String f : filesUpdated) { getOrCreateNode(f, "DATABASE_FILE", f); addEdge(programId, f, "UPDATES",      ""); }
        for (String f : filesDeleted) { getOrCreateNode(f, "DATABASE_FILE", f); addEdge(programId, f, "DELETES_FROM", ""); }
        for (String c : externalCalls) {
            getOrCreateNode(c, "COBOL_PROGRAM", humanize(c) + " program");
            addEdge(programId, c, "CALLS", "");
        }
        for (String ep : entryPoints) {
            GraphNode epNode = getOrCreateNode(ep, "ENTRY_POINT", ep + " entry point");
            epNode.getProperties().put("hostProgram", programId);
            addEdge(programId, ep, "EXPOSES", "");
        }
    }

    public synchronized void registerCopybook(String copybookId, String sourceFile, List<String> recordLayouts) {
        GraphNode node = getOrCreateNode(copybookId, "COPYBOOK",
            humanize(copybookId) + " copybook");
        node.getProperties().put("sourceFile", sourceFile);
        if (!recordLayouts.isEmpty()) node.getProperties().put("recordLayouts", recordLayouts);
    }

    /** Fields a copybook's record layout defines — used to build the FIELD-level
     * dependency graph so impact analysis can tell exactly which programs/copybooks
     * touch a specific field, not just which ones share the enclosing copybook. */
    public synchronized void registerCopybookFields(String copybookId, List<String> fieldsDefined) {
        for (String field : fieldsDefined) {
            String fieldId = normalizeFieldId(field);
            if (fieldId == null) continue;
            getOrCreateNode(fieldId, "FIELD", field.trim());
            addEdge(copybookId, fieldId, "DEFINES", "");
        }
    }

    /** Fields a program defines itself (own WORKING-STORAGE) and/or actually
     * references in its PROCEDURE DIVISION logic (its own fields or ones from a
     * copied copybook) — same purpose as {@link #registerCopybookFields}. */
    public synchronized void registerProgramFields(String programId, List<String> fieldsDefined,
                                                     List<String> fieldsReferenced) {
        for (String field : fieldsDefined) {
            String fieldId = normalizeFieldId(field);
            if (fieldId == null) continue;
            getOrCreateNode(fieldId, "FIELD", field.trim());
            addEdge(programId, fieldId, "DEFINES", "");
        }
        for (String field : fieldsReferenced) {
            String fieldId = normalizeFieldId(field);
            if (fieldId == null) continue;
            getOrCreateNode(fieldId, "FIELD", field.trim());
            addEdge(programId, fieldId, "REFERENCES", "");
        }
    }

    /** Normalizes a field name to its graph node id — uppercased and trimmed, so
     * the same field name used across different programs/copybooks resolves to
     * one shared FIELD node, which is what makes cross-program impact filtering
     * by field name meaningful. Returns null for blank input. */
    private String normalizeFieldId(String field) {
        if (field == null) return null;
        String trimmed = field.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase();
    }

    public synchronized void registerJclJob(String jobName, String domain, String subDomain,
                                List<String> programsExecuted, List<String> datasetsUsed) {
        GraphNode node = getOrCreateNode(jobName, "JCL_JOB",
            humanize(jobName) + " batch job");
        node.getProperties().put("domain", domain);
        node.getProperties().put("subDomain", subDomain);

        for (String prog : programsExecuted) {
            getOrCreateNode(prog, "COBOL_PROGRAM", humanize(prog) + " program");
            addEdge(jobName, prog, "EXECUTES", "");
        }
        for (String ds : datasetsUsed) {
            getOrCreateNode(ds, "DATABASE_FILE", ds);
            addEdge(jobName, ds, "USES_DATASET", "");
        }
    }

    public synchronized KnowledgeGraph build() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("generatedAt", Instant.now().toString());
        meta.put("totalNodes", nodes.size());
        meta.put("totalEdges", edges.size());
        meta.put("nodeTypeCounts", nodes.values().stream()
            .collect(Collectors.groupingBy(GraphNode::getType,
                LinkedHashMap::new, Collectors.counting())));
        meta.put("edgeTypeCounts", edges.stream()
            .collect(Collectors.groupingBy(GraphEdge::getType,
                LinkedHashMap::new, Collectors.counting())));

        KnowledgeGraph graph = new KnowledgeGraph();
        graph.setMetadata(meta);
        graph.setNodes(new ArrayList<>(nodes.values()));
        graph.setEdges(edges);
        return graph;
    }

    private GraphNode getOrCreateNode(String id, String type, String label) {
        return nodes.computeIfAbsent(id, k -> new GraphNode(k, type, label));
    }

    private void addEdge(String from, String to, String type, String label) {
        String key = from + "|" + to + "|" + type;
        if (edgeDedup.add(key)) {
            String edgeLabel = label.isBlank()
                ? from + " " + type.toLowerCase().replace("_", " ") + " " + to
                : label;
            edges.add(new GraphEdge(String.format("e%04d", ++edgeSeq),
                from, to, type, edgeLabel));
        }
    }

    private String humanize(String id) {
        return id.replace("-", " ").replace("_", " ").toLowerCase();
    }
}