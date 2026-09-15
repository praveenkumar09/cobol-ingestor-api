package org.example.graph;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Generates a fully self-contained graph_visualization.html.
 * vis.js is downloaded once and cached to disk; subsequent runs embed from cache.
 * No internet connection required in the browser.
 */
public class GraphHtmlExporter {

    private static final String VIS_CDN  = "https://unpkg.com/vis-network/standalone/umd/vis-network.min.js";
    private static final String VIS_FILE = "vis-network.min.js";

    private static final Map<String, String> NODE_COLORS = Map.of(
        "COBOL_PROGRAM", "#4A90D9",
        "COPYBOOK",      "#7ED321",
        "DATABASE_FILE", "#F5A623",
        "ENTRY_POINT",   "#9B59B6",
        "JCL_JOB",       "#E74C3C"
    );

    private static final Map<String, String> NODE_SHAPES = Map.of(
        "COBOL_PROGRAM", "box",
        "COPYBOOK",      "ellipse",
        "DATABASE_FILE", "database",
        "ENTRY_POINT",   "star",
        "JCL_JOB",       "hexagon"
    );

    public void export(KnowledgeGraph graph, Path outputDir) throws IOException {
        String visJs = fetchVisJs(outputDir);
        String html  = buildHtml(graph, visJs);
        Path outFile = outputDir.resolve("graph_visualization.html");
        Files.writeString(outFile, html);
        System.out.println("  Graph visualization: " + outFile.toAbsolutePath());
    }

    // ── vis.js acquisition ────────────────────────────────────────────

    private String fetchVisJs(Path outputDir) {
        // 1. Use cached file if it exists
        Path cached = outputDir.resolve(VIS_FILE);
        if (Files.exists(cached)) {
            try {
                System.out.println("  Using cached vis.js");
                return Files.readString(cached);
            } catch (IOException e) {
                System.out.println("  WARNING: Could not read cached vis.js: " + e.getMessage());
            }
        }

        // 2. Download from CDN and cache
        System.out.println("  Downloading vis.js from CDN (one-time)...");
        try {
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
            HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(VIS_CDN))
                    .header("User-Agent", "CobolIngestor/1.0")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            if (resp.statusCode() == 200) {
                String content = resp.body();
                Files.writeString(cached, content);
                System.out.println("  vis.js downloaded and cached to: " + cached);
                return content;
            } else {
                System.out.println("  WARNING: vis.js CDN returned HTTP " + resp.statusCode());
            }
        } catch (Exception e) {
            System.out.println("  WARNING: Could not download vis.js: " + e.getMessage());
        }

        // 3. No vis.js available — return null (HTML will show instructions)
        System.out.println("  vis.js unavailable. HTML will show fallback instructions.");
        return null;
    }

    // ── HTML generation ───────────────────────────────────────────────

    private String buildHtml(KnowledgeGraph graph, String visJs) {
        StringBuilder nodes = new StringBuilder();
        StringBuilder edges = new StringBuilder();

        List<GraphNode> nodeList = graph.getNodes();
        List<GraphEdge> edgeList = graph.getEdges();

        for (int i = 0; i < nodeList.size(); i++) {
            GraphNode n = nodeList.get(i);
            String color = NODE_COLORS.getOrDefault(n.getType(), "#95A5A6");
            String shape = NODE_SHAPES.getOrDefault(n.getType(), "box");
            if (i > 0) nodes.append(",\n");
            nodes.append(String.format(
                "  {id: %d, label: %s, title: %s, color: '%s', shape: '%s', group: '%s'}",
                i, jsStr(n.getId()), jsStr(buildNodeTooltip(n)), color, shape, n.getType()
            ));
        }

        Map<String, Integer> nodeIndex = new java.util.HashMap<>();
        for (int i = 0; i < nodeList.size(); i++) nodeIndex.put(nodeList.get(i).getId(), i);

        int edgeCount = 0;
        for (GraphEdge e : edgeList) {
            Integer fromIdx = nodeIndex.get(e.getFrom());
            Integer toIdx   = nodeIndex.get(e.getTo());
            if (fromIdx == null || toIdx == null) continue;
            if (edgeCount > 0) edges.append(",\n");
            edges.append(String.format(
                "  {from: %d, to: %d, label: %s, arrows: 'to', title: %s}",
                fromIdx, toIdx,
                jsStr(e.getType()),
                jsStr(e.getFrom() + " → " + e.getType() + " → " + e.getTo())
            ));
            edgeCount++;
        }

        long programCount  = nodeList.stream().filter(n -> "COBOL_PROGRAM".equals(n.getType())).count();
        long copybookCount = nodeList.stream().filter(n -> "COPYBOOK".equals(n.getType())).count();
        long dbFileCount   = nodeList.stream().filter(n -> "DATABASE_FILE".equals(n.getType())).count();
        long jclCount      = nodeList.stream().filter(n -> "JCL_JOB".equals(n.getType())).count();

        // Build the vis.js script tag: inline if available, CDN fallback otherwise
        String visScript;
        if (visJs != null) {
            visScript = "<script>\n" + visJs + "\n</script>";
        } else {
            visScript = """
<script>
window.addEventListener('load', function() {
  document.getElementById('network').innerHTML =
    '<div style="color:#F5A623;font-size:15px;padding:40px;text-align:center">' +
    '<b>vis.js could not be loaded.</b><br><br>' +
    'To fix: copy <code>vis-network.min.js</code> from ' +
    '<a href="https://unpkg.com/vis-network/standalone/umd/vis-network.min.js" style="color:#4A90D9">unpkg.com</a> ' +
    'into the same folder as this HTML file, then refresh.<br><br>' +
    'Or open this file on a machine with internet access (vis.js will be cached automatically next run).' +
    '</div>';
});
</script>""";
        }

        String html = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>COBOL Knowledge Graph</title>
%%VIS_SCRIPT%%
<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: 'Segoe UI', Arial, sans-serif; background: #1a1a2e; color: #eee; height: 100vh; display: flex; flex-direction: column; }
#header { background: #16213e; padding: 12px 20px; display: flex; align-items: center; gap: 16px; border-bottom: 1px solid #0f3460; flex-shrink: 0; }
#header h1 { font-size: 18px; color: #4A90D9; }
#stats { display: flex; gap: 12px; flex-wrap: wrap; margin-left: auto; }
.stat { background: #0f3460; border-radius: 6px; padding: 4px 10px; font-size: 12px; }
.stat span { font-weight: bold; color: #4A90D9; }
#controls { background: #16213e; padding: 8px 20px; display: flex; gap: 12px; align-items: center; border-bottom: 1px solid #0f3460; flex-shrink: 0; flex-wrap: wrap; }
#controls label { font-size: 12px; color: #aaa; }
#controls input[type=text] { background: #0f3460; border: 1px solid #4A90D9; color: #fff; padding: 4px 8px; border-radius: 4px; font-size: 12px; width: 180px; }
#controls select { background: #0f3460; border: 1px solid #4A90D9; color: #fff; padding: 4px 8px; border-radius: 4px; font-size: 12px; }
#controls button { background: #4A90D9; border: none; color: #fff; padding: 5px 12px; border-radius: 4px; cursor: pointer; font-size: 12px; }
#controls button:hover { background: #357ABD; }
#legend { display: flex; gap: 14px; flex-wrap: wrap; }
.legend-item { display: flex; align-items: center; gap: 5px; font-size: 11px; }
.legend-dot { width: 12px; height: 12px; border-radius: 50%; }
#network { flex: 1; }
#info { position: fixed; bottom: 20px; right: 20px; background: #16213e; border: 1px solid #0f3460; border-radius: 8px; padding: 14px 18px; max-width: 320px; font-size: 12px; display: none; }
#info h3 { color: #4A90D9; margin-bottom: 8px; font-size: 14px; }
#info .row { display: flex; gap: 8px; margin: 3px 0; }
#info .key { color: #aaa; min-width: 80px; }
#info .val { color: #fff; word-break: break-all; }
#info button { margin-top: 10px; font-size: 11px; background: #0f3460; border: none; color: #aaa; padding: 3px 8px; border-radius: 4px; cursor: pointer; }
</style>
</head>
<body>
<div id="header">
  <h1>COBOL Knowledge Graph</h1>
  <div id="stats">
    <div class="stat">Nodes: <span>%%NODE_COUNT%%</span></div>
    <div class="stat">Edges: <span>%%EDGE_COUNT%%</span></div>
    <div class="stat">Programs: <span>%%PROG_COUNT%%</span></div>
    <div class="stat">Copybooks: <span>%%CPY_COUNT%%</span></div>
    <div class="stat">DB Files: <span>%%DB_COUNT%%</span></div>
    <div class="stat">JCL Jobs: <span>%%JCL_COUNT%%</span></div>
  </div>
</div>
<div id="controls">
  <label>Search: <input type="text" id="searchBox" placeholder="program or file name..."></label>
  <label>Filter type:
    <select id="typeFilter">
      <option value="">All types</option>
      <option value="COBOL_PROGRAM">COBOL Programs</option>
      <option value="COPYBOOK">Copybooks</option>
      <option value="DATABASE_FILE">Database Files</option>
      <option value="JCL_JOB">JCL Jobs</option>
      <option value="ENTRY_POINT">Entry Points</option>
    </select>
  </label>
  <button onclick="resetView()">Reset View</button>
  <button onclick="fitAll()">Fit All</button>
  <div id="legend">
    <div class="legend-item"><div class="legend-dot" style="background:#4A90D9"></div>COBOL Program</div>
    <div class="legend-item"><div class="legend-dot" style="background:#7ED321"></div>Copybook</div>
    <div class="legend-item"><div class="legend-dot" style="background:#F5A623"></div>DB File</div>
    <div class="legend-item"><div class="legend-dot" style="background:#9B59B6"></div>Entry Point</div>
    <div class="legend-item"><div class="legend-dot" style="background:#E74C3C"></div>JCL Job</div>
  </div>
</div>
<div id="network"></div>
<div id="info">
  <h3 id="infoTitle"></h3>
  <div id="infoBody"></div>
  <button onclick="document.getElementById('info').style.display='none'">Close</button>
</div>
<script>
var allNodes = [
%%NODE_DATA%%
];
var allEdges = [
%%EDGE_DATA%%
];

var nodesDS = new vis.DataSet(allNodes);
var edgesDS = new vis.DataSet(allEdges);

var options = {
  nodes: { font: { color: '#fff', size: 11 }, borderWidth: 1, borderWidthSelected: 3 },
  edges: { font: { color: '#aaa', size: 9, align: 'middle' }, color: { color: '#334', highlight: '#4A90D9' }, smooth: { type: 'dynamic' } },
  physics: { stabilization: { iterations: 200 }, barnesHut: { gravitationalConstant: -8000, springLength: 120 } },
  interaction: { hover: true, tooltipDelay: 100 }
};

var network = new vis.Network(document.getElementById('network'), { nodes: nodesDS, edges: edgesDS }, options);

network.on('click', function(params) {
  if (params.nodes.length > 0) {
    var node = allNodes[params.nodes[0]];
    document.getElementById('infoTitle').textContent = node.label;
    document.getElementById('infoBody').innerHTML =
      '<div class="row"><span class="key">Type</span><span class="val">' + node.group + '</span></div>' +
      '<div class="row"><span class="key">Details</span><span class="val">' + (node.title || '') + '</span></div>';
    document.getElementById('info').style.display = 'block';
  }
});

document.getElementById('searchBox').addEventListener('input', function() {
  filterNodes(this.value.toLowerCase(), document.getElementById('typeFilter').value);
});
document.getElementById('typeFilter').addEventListener('change', function() {
  filterNodes(document.getElementById('searchBox').value.toLowerCase(), this.value);
});

function filterNodes(q, typeFilter) {
  nodesDS.update(allNodes.map(function(n) {
    var matchQ = !q || n.label.toLowerCase().includes(q);
    var matchT = !typeFilter || n.group === typeFilter;
    return { id: n.id, hidden: !(matchQ && matchT) };
  }));
}

function resetView() {
  document.getElementById('searchBox').value = '';
  document.getElementById('typeFilter').value = '';
  nodesDS.update(allNodes.map(function(n) { return { id: n.id, hidden: false }; }));
  network.fit();
}

function fitAll() { network.fit({ animation: { duration: 500 } }); }
</script>
</body>
</html>
""";

        return html
            .replace("%%VIS_SCRIPT%%",   visScript)
            .replace("%%NODE_COUNT%%",   String.valueOf(nodeList.size()))
            .replace("%%EDGE_COUNT%%",   String.valueOf(edgeCount))
            .replace("%%PROG_COUNT%%",   String.valueOf(programCount))
            .replace("%%CPY_COUNT%%",    String.valueOf(copybookCount))
            .replace("%%DB_COUNT%%",     String.valueOf(dbFileCount))
            .replace("%%JCL_COUNT%%",    String.valueOf(jclCount))
            .replace("%%NODE_DATA%%",    nodes.toString())
            .replace("%%EDGE_DATA%%",    edges.toString());
    }

    private String buildNodeTooltip(GraphNode n) {
        StringBuilder sb = new StringBuilder();
        sb.append(n.getType()).append(": ").append(n.getId());
        Map<String, Object> props = n.getProperties();
        Object domain = props.get("domain");
        Object sub    = props.get("subDomain");
        Object proc   = props.get("processingType");
        if (domain != null) sb.append(" | Domain: ").append(domain);
        if (sub    != null) sb.append(" | Sub: ").append(sub);
        if (proc   != null) sb.append(" | Proc: ").append(proc);
        return sb.toString();
    }

    private String jsStr(String s) {
        if (s == null) return "''";
        return "'" + s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", " ")
            .replace("\r", "")
            + "'";
    }
}