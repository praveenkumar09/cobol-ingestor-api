package org.example;

import org.example.chunker.CobolChunker;
import org.example.model.FileChunk;
import org.example.model.FileType;
import org.example.writer.ChunkWriter;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * One-off validation driver for the #1/#2/#4 chunk-analysis prompt changes
 * (domain backfill, subDomain convergence, procedure-division granularity) —
 * re-chunks a small representative subset of already-fetched local source
 * files directly via {@link CobolChunker}, instead of running the full
 * ~250-file {@code Main} pipeline (fetch + chunk + embed + graph, ~50 minutes
 * and real API spend for the whole corpus). Overwrites just those files'
 * {@code *_chunks.json} under src/main/resources/output — everything else in
 * that directory is untouched.
 *
 * Usage (direct java invocation — NOT mvn exec:java, whose exec-maven-plugin
 * config hardcodes mainClass=org.example.Main and silently ignores
 * -Dexec.mainClass on the command line):
 *   java -cp target/classes:$(cat /tmp/cp.txt) org.example.TestSubsetChunking [file.cbl ...]
 * With no args, processes all 4 files. With args, processes only the named
 * ones (matched by filename) — used to test each domain-hint value against
 * only the files that actually belong to that domain (see OPENAI_CHUNK_DOMAIN_HINT).
 */
public class TestSubsetChunking {

    private static final Path INPUT_COBOL = Path.of("src/main/resources/input/cobol");
    private static final Path INPUT_CARDDEMO_CBL = Path.of(
        "src/main/resources/input/github/aws-mainframe-modernization-carddemo/app/cbl");
    private static final Path INPUT_CARDDEMO_TXN_DB2_CBL = Path.of(
        "src/main/resources/input/github/aws-mainframe-modernization-carddemo/app/app-transaction-type-db2/cbl");
    private static final Path OUTPUT_DIR = Path.of("src/main/resources/output");

    public static void main(String[] args) throws Exception {
        List<Path> allFiles = List.of(
            INPUT_COBOL.resolve("BNFUPD.cbl"),           // insurance, small
            INPUT_COBOL.resolve("CLMPRC.cbl"),            // insurance — the coarse-chunk example (#4)
            INPUT_CARDDEMO_CBL.resolve("COCRDUPC.cbl"),   // banking, larger
            INPUT_CARDDEMO_TXN_DB2_CBL.resolve("COTRTUPC.cbl") // banking — the all-GENERAL example (#1)
        );
        Set<String> selected = Set.of(args);
        List<Path> files = selected.isEmpty() ? allFiles
            : allFiles.stream().filter(p -> selected.contains(p.getFileName().toString())).toList();

        CobolChunker chunker = new CobolChunker();
        if (!chunker.isAvailable()) {
            System.err.println("OPENAI_API_KEY not set — cannot run chunk analysis");
            System.exit(1);
        }

        ChunkWriter writer = new ChunkWriter();

        for (Path file : files) {
            if (!file.toFile().exists()) {
                System.out.println("SKIP (not found): " + file);
                continue;
            }
            System.out.println("\n=== " + file.getFileName() + " ===");
            List<FileChunk> chunks = chunker.chunk(file, FileType.COBOL_PROGRAM);
            System.out.println(file.getFileName() + " -> " + chunks.size() + " chunks");
            for (FileChunk c : chunks) {
                System.out.printf("  %-30s lines %4d-%4d  domain=%s/%s%n",
                    c.getSectionName(), c.getLineStart(), c.getLineEnd(),
                    c.getDomain(), c.getSubDomain());
            }
            writer.writeChunks(chunks, OUTPUT_DIR, file.getFileName().toString());
        }

        System.out.println("\nDone.");
    }
}
