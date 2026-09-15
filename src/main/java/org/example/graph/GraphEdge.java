package org.example.graph;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"id", "from", "to", "type", "label"})
public class GraphEdge {
    private String id;
    private String from;
    private String to;
    private String type;
    private String label;

    public GraphEdge() {}

    public GraphEdge(String id, String from, String to, String type, String label) {
        this.id = id;
        this.from = from;
        this.to = to;
        this.type = type;
        this.label = label;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}