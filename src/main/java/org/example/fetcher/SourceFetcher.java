package org.example.fetcher;

import org.example.config.AppConfig;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Set;
import java.util.zip.*;

/**
 * Fetches COBOL source files from any Git hosting service.
 *
 * Environment variables:
 *   SOURCE_URL     — full URL to GitHub or Bitbucket repo page, OR a direct .zip URL
 *                    e.g.  https://github.com/owner/repo
 *                          https://github.com/owner/repo/tree/mybranch
 *                          https://bitbucket.org/workspace/repo
 *                          https://bitbucket.org/workspace/repo/src/mybranch
 *   SOURCE_BRANCH  — branch name (default: main)
 *   SOURCE_SUBDIR  — subdirectory inside the repo to process (default: empty = entire repo).
 *                    e.g. SOURCE_SUBDIR=app limits to the carddemo app/ folder.
 *
 * Backward-compat env vars still supported:
 *   GITHUB_REPO_URL, GITHUB_OWNER, GITHUB_REPO, GITHUB_BRANCH
 */
public class SourceFetcher {

    // ── Defaults for the built-in carddemo sample (overridable in application.properties) ──
    private static final String DEFAULT_OWNER  = AppConfig.get("source.default.owner",  "aws-samples");
    private static final String DEFAULT_REPO   = AppConfig.get("source.default.repo",   "aws-mainframe-modernization-carddemo");
    private static final String DEFAULT_BRANCH = AppConfig.get("source.default.branch", "main");
    private static final String DEFAULT_SUBDIR = AppConfig.get("source.default.subdir", "");

    private static final Set<String> SUPPORTED_EXTS = Set.of("cbl", "cpy", "jcl");

    public enum SourceType { GITHUB, BITBUCKET, DIRECT_ZIP }

    private final String  zipUrl;
    private final String  subDir;
    private final String  repoName;
    private final SourceType sourceType;
    private String owner;   // GitHub owner/org — null for non-GitHub sources
    private String branch;  // resolved branch name

    public SourceFetcher() {
        String sourceUrl = env("SOURCE_URL", env("GITHUB_REPO_URL", null));
        String branch    = env("SOURCE_BRANCH", env("GITHUB_BRANCH",
                            sys("github.branch", DEFAULT_BRANCH)));

        // SOURCE_SUBDIR: null env var → use default; empty string env var → process root
        String subDirRaw = System.getenv("SOURCE_SUBDIR");
        this.subDir = subDirRaw != null ? subDirRaw.trim() : DEFAULT_SUBDIR;

        if (sourceUrl == null || sourceUrl.isBlank()) {
            // Fallback: individual GITHUB_* env vars
            String ghOwner = env("GITHUB_OWNER", sys("github.owner", DEFAULT_OWNER));
            String repo    = env("GITHUB_REPO",  sys("github.repo",  DEFAULT_REPO));
            this.sourceType = SourceType.GITHUB;
            this.repoName   = repo;
            this.owner      = ghOwner;
            this.branch     = branch;
            this.zipUrl     = githubZipUrl(ghOwner, repo, branch);
        } else if (isDirectZip(sourceUrl)) {
            this.sourceType = SourceType.DIRECT_ZIP;
            this.repoName   = repoNameFromZipUrl(sourceUrl);
            this.zipUrl     = sourceUrl;
            this.owner      = null;
            this.branch     = null;
        } else if (sourceUrl.contains("github.com")) {
            this.sourceType = SourceType.GITHUB;
            String[] parsed = parseGitHub(sourceUrl, branch);   // [owner, repo, branch]
            this.repoName   = parsed[1];
            this.owner      = parsed[0];
            this.branch     = parsed[2];
            this.zipUrl     = githubZipUrl(parsed[0], parsed[1], parsed[2]);
            branch = parsed[2];
        } else if (sourceUrl.contains("bitbucket.org")) {
            this.sourceType = SourceType.BITBUCKET;
            String[] parsed = parseBitbucket(sourceUrl, branch); // [workspace, repo, branch]
            this.repoName   = parsed[1];
            this.owner      = null;  // Bitbucket SHA check not supported
            this.branch     = parsed[2];
            this.zipUrl     = bitbucketZipUrl(parsed[0], parsed[1], parsed[2]);
            branch = parsed[2];
        } else {
            // Unknown host — treat as direct ZIP
            this.sourceType = SourceType.DIRECT_ZIP;
            this.repoName   = repoNameFromZipUrl(sourceUrl);
            this.zipUrl     = sourceUrl;
            this.owner      = null;
            this.branch     = null;
        }

        System.out.println("  Source type  : " + this.sourceType);
        System.out.println("  Download URL : " + this.zipUrl);
        System.out.println("  Sub-directory: " + (this.subDir.isBlank() ? "(entire repo)" : this.subDir));
    }

    // ── Public API ──────────────────────────────────────────────────

    public String getRepoName() { return repoName; }

    /**
     * Calls the GitHub Commits API to get the current HEAD SHA for the tracked branch.
     * Returns null for non-GitHub sources or when the API call fails.
     * Set GITHUB_TOKEN env var to avoid rate limiting (5 000 req/hr vs 60/hr anonymous).
     */
    public String fetchCurrentCommitSha() {
        if (sourceType != SourceType.GITHUB || owner == null || branch == null) return null;
        String apiUrl = "https://api.github.com/repos/" + owner + "/" + repoName + "/commits/" + branch;
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                // This Accept header tells GitHub to return just the 40-char SHA as plain text
                .header("Accept", "application/vnd.github.sha")
                .header("User-Agent", "CobolIngestor/1.0")
                .GET();
            String token = System.getenv("GITHUB_TOKEN");
            if (token != null && !token.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = client.send(reqBuilder.build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body().trim().replace("\"", "");
            }
            System.out.println("  WARNING: GitHub API returned HTTP " + response.statusCode()
                + " — SHA check skipped");
            return null;
        } catch (Exception e) {
            System.out.println("  WARNING: Could not fetch commit SHA: " + e.getMessage());
            return null;
        }
    }

    /**
     * Downloads and extracts source files into targetDir.
     * Skips download if targetDir already contains .cbl files (cache hit).
     */
    public int fetchIfAbsent(Path targetDir) throws IOException, InterruptedException {
        Files.createDirectories(targetDir);

        try (var stream = Files.walk(targetDir)) {
            boolean hasCbl = stream.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".cbl"));
            if (hasCbl) {
                System.out.println("  Source already cached at: " + targetDir);
                return 0;
            }
        }

        System.out.println("  Downloading source archive...");

        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(zipUrl))
            .header("User-Agent", "CobolIngestor/1.0")
            .GET()
            .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("Download failed: HTTP " + response.statusCode() + " for " + zipUrl);
        }

        int count = extractFiles(response.body(), targetDir);
        System.out.println("  Extracted " + count + " source files to: " + targetDir);
        return count;
    }

    // ── Extraction ─────────────────────────────────────────────────

    private int extractFiles(InputStream zipStream, Path targetDir) throws IOException {
        int count = 0;
        String zipRoot = null;  // detected from first entry (e.g. "repo-main/" or "repo-abc123/")

        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(zipStream))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');

                // Detect root prefix from the first entry that has a slash
                if (zipRoot == null && name.contains("/")) {
                    zipRoot = name.substring(0, name.indexOf('/') + 1);
                }

                if (entry.isDirectory() || zipRoot == null || !name.startsWith(zipRoot)) {
                    zip.closeEntry();
                    continue;
                }

                // Strip ZIP root prefix → path relative to repo root
                String relative = name.substring(zipRoot.length());

                // Apply SOURCE_SUBDIR filter
                if (!subDir.isBlank()) {
                    String prefix = subDir + "/";
                    if (!relative.startsWith(prefix)) { zip.closeEntry(); continue; }
                    relative = relative.substring(prefix.length());
                }

                if (relative.isEmpty() || relative.endsWith("/")) { zip.closeEntry(); continue; }

                String fileName = relative.substring(relative.lastIndexOf('/') + 1);
                if (!SUPPORTED_EXTS.contains(extension(fileName).toLowerCase())) {
                    zip.closeEntry();
                    continue;
                }

                Path outFile = targetDir.resolve(relative);
                Files.createDirectories(outFile.getParent());
                Files.copy(zip, outFile, StandardCopyOption.REPLACE_EXISTING);
                count++;
                System.out.println("    + " + relative);
                zip.closeEntry();
            }
        }
        return count;
    }

    // ── URL builders ────────────────────────────────────────────────

    private static String githubZipUrl(String owner, String repo, String branch) {
        return String.format("https://github.com/%s/%s/archive/refs/heads/%s.zip",
            owner, repo, branch);
    }

    private static String bitbucketZipUrl(String workspace, String repo, String branch) {
        return String.format("https://bitbucket.org/%s/%s/get/%s.zip",
            workspace, repo, branch);
    }

    // ── URL parsers ──────────────────────────────────────────────────

    /** Returns [owner, repo, branch] */
    private static String[] parseGitHub(String url, String fallbackBranch) {
        // Strip protocol and host
        String path = url.replaceFirst("https?://github\\.com/", "").replaceAll("/$", "");
        String[] parts = path.split("/");
        String owner  = parts.length > 0 ? parts[0] : "unknown";
        String repo   = parts.length > 1 ? parts[1] : "unknown";
        // https://github.com/owner/repo/tree/BRANCH/...
        String branch = (parts.length > 3 && "tree".equals(parts[2])) ? parts[3] : fallbackBranch;
        return new String[]{owner, repo, branch};
    }

    /** Returns [workspace, repo, branch] */
    private static String[] parseBitbucket(String url, String fallbackBranch) {
        String path = url.replaceFirst("https?://bitbucket\\.org/", "").replaceAll("/$", "");
        String[] parts = path.split("/");
        String workspace = parts.length > 0 ? parts[0] : "unknown";
        String repo      = parts.length > 1 ? parts[1] : "unknown";
        // https://bitbucket.org/workspace/repo/src/BRANCH/...
        String branch = (parts.length > 3 && "src".equals(parts[2])) ? parts[3] : fallbackBranch;
        return new String[]{workspace, repo, branch};
    }

    private static boolean isDirectZip(String url) {
        return url.endsWith(".zip");
    }

    private static String repoNameFromZipUrl(String url) {
        String[] parts = url.split("/");
        String last = parts[parts.length - 1];
        return last.replace(".zip", "").replaceAll("-[a-f0-9]{7,}$", ""); // strip commit hash suffix
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static String extension(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i + 1) : "";
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    private static String sys(String key, String fallback) {
        String v = System.getProperty(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}