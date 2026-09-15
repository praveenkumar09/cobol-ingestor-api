package org.example.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppConfig;
import org.example.llm.EmbeddingClient;
import org.example.model.FileChunk;

import java.sql.*;
import java.util.List;

/**
 * Stores chunks and their vector embeddings into PostgreSQL / pgvector.
 *
 * Connection is read from env vars:
 *   PG_HOST (default: localhost)
 *   PG_PORT (default: 5432)
 *   PG_DB   (default: ingestor)
 *   PG_USER (default: admin)
 *   PG_PASS (default: admin)
 */
public class VectorStore implements AutoCloseable {

    private static final String INSERT_SQL = """
        INSERT INTO chunks (
            chunk_id, chunk_index, total_chunks,
            source_file, file_type,
            program_id, author, date_written,
            domain, sub_domain, processing_type,
            division, section_name, line_start, line_end,
            section_purpose, has_file_io, has_error_handling,
            should_embed, embedding_text,
            tags, files_read, files_written,
            external_programs, copybooks_referenced, key_data_fields,
            content, payload, embedding
        ) VALUES (
            ?, ?, ?,
            ?, ?,
            ?, ?, ?,
            ?, ?, ?,
            ?, ?, ?, ?,
            ?, ?, ?,
            ?, ?,
            ?, ?, ?,
            ?, ?, ?,
            ?, ?::jsonb, ?::vector
        )
        ON CONFLICT (chunk_id) DO UPDATE SET
            section_purpose = EXCLUDED.section_purpose,
            embedding_text  = EXCLUDED.embedding_text,
            should_embed    = EXCLUDED.should_embed,
            payload         = EXCLUDED.payload,
            embedding       = EXCLUDED.embedding
        """;

    private final Connection conn;
    private final ObjectMapper mapper;

    private VectorStore(Connection conn) {
        this.conn   = conn;
        this.mapper = new ObjectMapper();
    }

    public static VectorStore create() throws SQLException {
        String host = AppConfig.get("PG_HOST", "db.pg.host", "localhost");
        String port = AppConfig.get("PG_PORT", "db.pg.port", "5432");
        String db   = AppConfig.get("PG_DB",   "db.pg.name", "ingestor");
        String user = AppConfig.get("PG_USER", "db.pg.user", "admin");
        String pass = AppConfig.get("PG_PASS", "db.pg.pass", "admin");
        String url  = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        Connection conn = DriverManager.getConnection(url, user, pass);
        conn.setAutoCommit(false);
        return new VectorStore(conn);
    }

    /**
     * Inserts all chunks in a single transaction.
     * embeddingMap: chunk_id → float[] vector (null if not embedded).
     */
    public void upsertChunks(List<FileChunk> chunks,
                              java.util.Map<String, float[]> embeddingMap) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            for (FileChunk c : chunks) {
                float[] vec = embeddingMap.get(c.getChunkId());

                ps.setString(1,  c.getChunkId());
                ps.setInt   (2,  c.getChunkIndex());
                ps.setInt   (3,  c.getTotalChunks());
                ps.setString(4,  c.getSourceFile());
                ps.setString(5,  c.getFileType());
                ps.setString(6,  c.getProgramId());
                ps.setString(7,  c.getAuthor());
                ps.setString(8,  c.getDateWritten());
                ps.setString(9,  c.getDomain());
                ps.setString(10, c.getSubDomain());
                ps.setString(11, c.getProcessingType());
                ps.setString(12, c.getDivision());
                ps.setString(13, c.getSectionName());
                ps.setInt   (14, c.getLineStart());
                ps.setInt   (15, c.getLineEnd());
                ps.setString(16, c.getSectionPurpose());
                ps.setBoolean(17, c.isHasFileIO());
                ps.setBoolean(18, c.isHasErrorHandling());
                ps.setBoolean(19, c.isShouldEmbed());
                ps.setString(20, c.getEmbeddingText());
                ps.setArray (21, toArray(c.getTags()));
                ps.setArray (22, toArray(c.getFilesRead()));
                ps.setArray (23, toArray(c.getFilesWritten()));
                ps.setArray (24, toArray(c.getExternalProgramsCalled()));
                ps.setArray (25, toArray(c.getCopybooksReferenced()));
                ps.setArray (26, toArray(c.getKeyDataFields()));
                ps.setString(27, c.getContent());
                ps.setString(28, mapper.writeValueAsString(c));
                ps.setString(29, vec != null ? EmbeddingClient.toVectorString(vec) : null);

                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw e;
        }
    }

    private Array toArray(List<String> list) throws SQLException {
        if (list == null || list.isEmpty()) return null;
        return conn.createArrayOf("text", list.toArray());
    }

    @Override
    public void close() {
        try { conn.close(); } catch (Exception ignored) {}
    }

}