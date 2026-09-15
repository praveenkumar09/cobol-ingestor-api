package org.example.fetcher;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Set;
import java.util.zip.*;

public class GitHubFetcher {

    private static final String DEFAULT_OWNER  = "aws-samples";
    private static final String DEFAULT_REPO   = "aws-mainframe-modernization-carddemo";
    private static final String DEFAULT_BRANCH = "main";

    // Resolve ZIP URL:
    //   1. GITHUB_REPO_URL env var (full URL)
    //   2. GITHUB_OWNER + GITHUB_REPO + GITHUB_BRANCH env vars
    //   3. Java system properties: github.owner, github.repo, github.branch
    //   4. Built-in carddemo defaults
    private static final String ZIP_URL = resolveZipUrl();

    private static String resolveZipUrl() {
        String full = System.getenv("GITHUB_REPO_URL");
        if (full != null && !full.isBlank()) return full;

        String owner  = coalesce(System.getenv("GITHUB_OWNER"),
                                  System.getProperty("github.owner"), DEFAULT_OWNER);
        String repo   = coalesce(System.getenv("GITHUB_REPO"),
                                  System.getProperty("github.repo"),  DEFAULT_REPO);
        String branch = coalesce(System.getenv("GITHUB_BRANCH"),
                                  System.getProperty("github.branch"), DEFAULT_BRANCH);
        return String.format("https://github.com/%s/%s/archive/refs/heads/%s.zip",
            owner, repo, branch);
    }

    private static String coalesce(String... values) {
        for (String v : values) { if (v != null && !v.isBlank()) return v; }
        return "";
    }

    // Derived repo name (last path segment before /archive) — used to strip ZIP root prefix
    private static final String REPO_AND_BRANCH = deriveRepoBranch();

    private static String deriveRepoBranch() {
        // Extract from URL: https://github.com/owner/REPO/archive/refs/heads/BRANCH.zip
        // The ZIP root inside the archive is "REPO-BRANCH/"
        try {
            String[] parts = ZIP_URL.split("/");
            // parts: ["https:", "", "github.com", owner, repo, "archive", "refs", "heads", "BRANCH.zip"]
            String repo   = parts[4];
            String branch = parts[parts.length - 1].replace(".zip", "");
            return repo + "-" + branch;
        } catch (Exception e) {
            return DEFAULT_REPO + "-" + DEFAULT_BRANCH;
        }
    }

    private static final Set<String> SUPPORTED_EXTS = Set.of("cbl", "cpy", "jcl");
    private static final Set<String> SKIP_DIRS      = Set.of("bms", "ctl", "sql", "yaml",
        "json", "properties", "txt", "sh");

    /**
     * Downloads and extracts carddemo app/ files into targetDir.
     * Skips download if .CBL files already exist (cached).
     *
     * @return number of files extracted (0 if already cached)
     */
    public int fetchIfAbsent(Path targetDir) throws IOException, InterruptedException {
        Files.createDirectories(targetDir);

        // Cache check: if any .CBL or .cbl file already present, skip
        try (var stream = Files.walk(targetDir)) {
            boolean hasCbl = stream.anyMatch(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".cbl");
            });
            if (hasCbl) {
                System.out.println("  CardDemo already cached at: " + targetDir);
                return 0;
            }
        }

        System.out.println("  Downloading CardDemo from GitHub...");
        System.out.println("  URL: " + ZIP_URL);

        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(ZIP_URL))
            .header("User-Agent", "CobolIngestor/1.0")
            .GET()
            .build();

        HttpResponse<InputStream> response = client.send(request,
            HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("GitHub download failed: HTTP " + response.statusCode()
                + " for " + ZIP_URL);
        }

        int count = extractFiles(response.body(), targetDir);
        System.out.println("  Downloaded " + count + " source files to: " + targetDir);
        return count;
    }

    private int extractFiles(InputStream zipStream, Path targetDir) throws IOException {
        int count = 0;
        // GitHub ZIP root: "<repo>-<branch>/app/"
        String appPrefix = REPO_AND_BRANCH + "/app/";

        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(zipStream))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();

                if (entry.isDirectory() || !name.startsWith(appPrefix)) {
                    zip.closeEntry();
                    continue;
                }

                // Relative path under app/, e.g. "cbl/COADM01C.CBL"
                String relative = name.substring(appPrefix.length());
                if (relative.isEmpty() || relative.startsWith("/")) {
                    zip.closeEntry();
                    continue;
                }

                // First path segment = subdirectory (cbl, cpy, jcl, bms, ...)
                int slashIdx = relative.indexOf('/');
                String subDir = slashIdx > 0
                    ? relative.substring(0, slashIdx).toLowerCase()
                    : "";
                String fileName = relative.substring(relative.lastIndexOf('/') + 1);
                String ext = extension(fileName).toLowerCase();

                if (SKIP_DIRS.contains(subDir) || !SUPPORTED_EXTS.contains(ext)) {
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

    private String extension(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i + 1) : "";
    }
}