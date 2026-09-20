package org.example.store;

import org.example.graph.GraphEdge;
import org.example.graph.GraphNode;
import org.example.graph.KnowledgeGraph;
import org.example.config.AppConfig;
import org.neo4j.driver.*;
import org.neo4j.driver.exceptions.Neo4jException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stores the knowledge graph (nodes + relationships) into Neo4j.
 *
 * Connection is read from env vars:
 *   NEO4J_URI  (default: bolt://localhost:7687)
 *   NEO4J_USER (default: neo4j)
 *   NEO4J_PASS (default: admin)
 */
public class GraphStore implements AutoCloseable {

    private final Driver driver;

    private GraphStore(Driver driver) {
        this.driver = driver;
    }

    public static GraphStore create() {
        String uri  = AppConfig.get("NEO4J_URI",  "db.neo4j.uri",  "bolt://localhost:7687");
        String user = AppConfig.get("NEO4J_USER", "db.neo4j.user", "neo4j");
        String pass = AppConfig.get("NEO4J_PASS", "db.neo4j.pass", "admin");
        Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, pass),
            org.neo4j.driver.Config.builder()
                .withConnectionTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build());
        driver.verifyConnectivity();
        return new GraphStore(driver);
    }

    // Rows per UNWIND batch. Keeps each query's parameter payload small while
    // cutting network round trips from one-per-node/edge down to a handful.
    private static final int BATCH_SIZE = 500;

    /** Clears the graph and reloads all nodes and edges. */
    public void ingestGraph(KnowledgeGraph graph) {
        List<GraphNode> nodes = graph.getNodes() != null ? graph.getNodes() : List.of();
        List<GraphEdge> edges = graph.getEdges() != null ? graph.getEdges() : List.of();

        try (Session session = driver.session()) {
            System.out.println("    Clearing existing graph...");
            session.run("MATCH (n) DETACH DELETE n");

            ensureIndexes(session, nodes);

            // id -> label, so edge MATCHes below can be label-scoped (index-backed)
            // instead of an unlabeled full node-store scan per edge.
            Map<String, String> labelById = ingestNodes(session, nodes);
            ingestEdges(session, edges, labelById);
        }
    }

    /**
     * One index per distinct node label on `id`, created up front. Without this,
     * the edge MATCH below (and any MERGE-by-id) falls back to a full node-store
     * scan per query — fine for a handful of nodes, but on a few thousand nodes
     * and edges this is what makes ingestion appear to hang for a very long time.
     */
    private void ensureIndexes(Session session, List<GraphNode> nodes) {
        Set<String> labels = new HashSet<>();
        for (GraphNode node : nodes) labels.add(safeLabel(node.getType()));
        for (String label : labels) {
            session.run("CREATE INDEX IF NOT EXISTS FOR (n:" + label + ") ON (n.id)");
        }
        System.out.println("    Indexes ready for " + labels.size() + " label(s)");
    }

    /** Returns id -> label for every node ingested, used by ingestEdges. */
    private Map<String, String> ingestNodes(Session session, List<GraphNode> nodes) {
        Map<String, String> labelById = new HashMap<>();
        int total = nodes.size();
        int done = 0;
        System.out.println("    Storing nodes: 0/" + total);

        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<GraphNode> batch = nodes.subList(i, Math.min(i + BATCH_SIZE, total));

            // Group the batch by label — MERGE needs a fixed label per query,
            // so each label gets its own UNWIND statement.
            Map<String, List<Map<String, Object>>> rowsByLabel = new LinkedHashMap<>();
            for (GraphNode node : batch) {
                String label = safeLabel(node.getType());
                labelById.put(node.getId(), label);

                Map<String, Object> props = new HashMap<>();
                if (node.getProperties() != null) {
                    node.getProperties().forEach((k, v) -> {
                        // Neo4j supports String, Number, Boolean, and arrays of those
                        if (v instanceof List<?> list) {
                            props.put(k, list.stream().map(Object::toString).toList());
                        } else if (v != null) {
                            props.put(k, v.toString());
                        }
                    });
                }
                props.put("id",    node.getId());
                props.put("label", node.getLabel() != null ? node.getLabel() : "");

                Map<String, Object> row = new HashMap<>();
                row.put("id", node.getId());
                row.put("props", props);
                rowsByLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(row);
            }

            for (Map.Entry<String, List<Map<String, Object>>> e : rowsByLabel.entrySet()) {
                session.run(
                    "UNWIND $rows AS row " +
                    "MERGE (n:" + e.getKey() + " {id: row.id}) SET n += row.props",
                    Map.of("rows", e.getValue())
                );
            }

            done += batch.size();
            System.out.println("    Storing nodes: " + done + "/" + total);
        }
        return labelById;
    }

    private void ingestEdges(Session session, List<GraphEdge> edges, Map<String, String> labelById) {
        int total = edges.size();
        int done = 0;
        System.out.println("    Storing edges: 0/" + total);

        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<GraphEdge> batch = edges.subList(i, Math.min(i + BATCH_SIZE, total));

            // Group by (fromLabel, toLabel, relType) so each UNWIND's MATCH can
            // name both endpoint labels and use the indexes created above.
            Map<String, List<Map<String, Object>>> rowsByKey = new LinkedHashMap<>();
            Map<String, String[]> keyParts = new HashMap<>();
            for (GraphEdge edge : batch) {
                String relType   = safeLabel(edge.getType());
                String fromLabel = labelById.getOrDefault(edge.getFrom(), null);
                String toLabel   = labelById.getOrDefault(edge.getTo(), null);
                if (fromLabel == null || toLabel == null) {
                    System.err.println("    WARN: edge " + edge.getFrom() + " -[" + relType
                        + "]-> " + edge.getTo() + " skipped: unknown endpoint label");
                    continue;
                }
                String key = fromLabel + "|" + toLabel + "|" + relType;
                keyParts.put(key, new String[]{fromLabel, toLabel, relType});
                Map<String, Object> row = new HashMap<>();
                row.put("from", edge.getFrom());
                row.put("to",   edge.getTo());
                rowsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }

            for (Map.Entry<String, List<Map<String, Object>>> e : rowsByKey.entrySet()) {
                String[] parts = keyParts.get(e.getKey());
                try {
                    session.run(
                        "UNWIND $rows AS row " +
                        "MATCH (a:" + parts[0] + " {id: row.from}), (b:" + parts[1] + " {id: row.to}) " +
                        "MERGE (a)-[r:" + parts[2] + "]->(b)",
                        Map.of("rows", e.getValue())
                    );
                } catch (Neo4jException ex) {
                    // Log and continue — one bad batch should not abort the whole graph
                    System.err.println("    WARN: edge batch " + e.getKey() + " skipped: " + ex.getMessage());
                }
            }

            done += batch.size();
            System.out.println("    Storing edges: " + done + "/" + total);
        }
    }

    /** Makes a node type or relationship type safe as a Cypher identifier. */
    private static String safeLabel(String raw) {
        if (raw == null || raw.isBlank()) return "UNKNOWN";
        return raw.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase();
    }

    @Override
    public void close() {
        try { driver.close(); } catch (Exception ignored) {}
    }

}