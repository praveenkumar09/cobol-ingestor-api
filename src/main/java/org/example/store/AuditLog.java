package org.example.store;

import org.example.config.AppConfig;

import java.sql.*;

/**
 * Reads and writes ingestion run records to the ingestion_runs audit table.
 *
 * Connection env vars (same as VectorStore):
 *   PG_HOST, PG_PORT, PG_DB, PG_USER, PG_PASS
 */
public class AuditLog implements AutoCloseable {

    private static final String SELECT_SHA = """
        SELECT commit_sha FROM ingestion_runs
        WHERE repo_name = ? AND status = 'SUCCESS'
        ORDER BY completed_at DESC
        LIMIT 1
        """;

    private static final String INSERT_RUN = """
        INSERT INTO ingestion_runs
            (repo_name, commit_sha, previous_sha,
             files_processed, chunks_upserted, embeddings_generated,
             status, started_at, completed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
        """;

    private final Connection conn;
    private final Timestamp startedAt;

    private AuditLog(Connection conn) {
        this.conn      = conn;
        this.startedAt = new Timestamp(System.currentTimeMillis());
    }

    /** Returns null when PostgreSQL is unreachable — callers skip audit logging gracefully. */
    public static AuditLog create() {
        try {
            String host = AppConfig.get("PG_HOST", "db.pg.host", "localhost");
            String port = AppConfig.get("PG_PORT", "db.pg.port", "5432");
            String db   = AppConfig.get("PG_DB",   "db.pg.name", "ingestor");
            String user = AppConfig.get("PG_USER", "db.pg.user", "admin");
            String pass = AppConfig.get("PG_PASS", "db.pg.pass", "admin");
            Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://" + host + ":" + port + "/" + db, user, pass);
            conn.setAutoCommit(true);
            return new AuditLog(conn);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the commit SHA from the most recent successful run for this repo,
     * or null if no successful run exists yet.
     */
    public String getLastSuccessfulSha(String repoName) {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_SHA)) {
            ps.setString(1, repoName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("commit_sha") : null;
            }
        } catch (Exception e) {
            System.out.println("  WARNING: Could not read last SHA from audit log: " + e.getMessage());
            return null;
        }
    }

    /**
     * Records the outcome of an ingestion run.
     * status should be one of: SUCCESS, NO_CHANGE, FAILED
     */
    public void recordRun(String repoName, String commitSha, String prevSha,
                          int filesProcessed, int chunksUpserted, int embeddingsGenerated,
                          String status) {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_RUN)) {
            ps.setString(1, repoName);
            ps.setString(2, commitSha);
            ps.setString(3, prevSha);
            ps.setInt   (4, filesProcessed);
            ps.setInt   (5, chunksUpserted);
            ps.setInt   (6, embeddingsGenerated);
            ps.setString(7, status);
            ps.setTimestamp(8, startedAt);
            ps.executeUpdate();
        } catch (Exception e) {
            System.out.println("  WARNING: Could not write audit log: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (Exception ignored) {}
    }

}