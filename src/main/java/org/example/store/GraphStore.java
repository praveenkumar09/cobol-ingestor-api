package org.example.store;

import org.example.graph.GraphEdge;
import org.example.graph.GraphNode;
import org.example.graph.KnowledgeGraph;
import org.example.config.AppConfig;
import org.neo4j.driver.*;
import org.neo4j.driver.exceptions.Neo4jException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** Clears the graph and reloads all nodes and edges. */
    public void ingestGraph(KnowledgeGraph graph) {
        try (Session session = driver.session()) {
            // Clear for idempotent re-runs
            session.run("MATCH (n) DETACH DELETE n");

            ingestNodes(session, graph.getNodes());
            ingestEdges(session, graph.getEdges());
        }
    }

    private void ingestNodes(Session session, List<GraphNode> nodes) {
        for (GraphNode node : nodes) {
            String label = safeLabel(node.getType());

            // Build properties map: merge node.properties + id + label
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

            session.run(
                "MERGE (n:" + label + " {id: $id}) SET n += $props",
                Map.of("id", node.getId(), "props", props)
            );
        }
    }

    private void ingestEdges(Session session, List<GraphEdge> edges) {
        for (GraphEdge edge : edges) {
            String relType = safeLabel(edge.getType());
            try {
                session.run(
                    "MATCH (a {id: $from}), (b {id: $to}) " +
                    "MERGE (a)-[r:" + relType + "]->(b)",
                    Map.of("from", edge.getFrom(), "to", edge.getTo())
                );
            } catch (Neo4jException e) {
                // Log and continue — one bad edge should not abort the whole graph
                System.err.println("  WARN: edge " + edge.getFrom()
                    + " -[" + relType + "]-> " + edge.getTo() + " skipped: " + e.getMessage());
            }
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