package io.github.amishpr.ledger.accounting.repository;

import io.github.amishpr.ledger.platform.id.Uuids;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * A plain trail of what happened and when, kept apart from the entries
 * themselves. Rows are written in the same transaction as the change they
 * describe, so the log can never claim something that was rolled back.
 */
@Repository
public class AuditLog {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public AuditLog(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public void record(String entityType, UUID entityId, String action, UUID transactionId, Map<String, ?> metadata, Instant at) {
        jdbc.sql("""
                INSERT INTO audit_log (id, entity_type, entity_id, action, metadata, transaction_id, created_at)
                VALUES (:id, :entityType, :entityId, :action, CAST(:metadata AS JSONB), :transactionId, :createdAt)
                """)
                .param("id", Uuids.v7())
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("action", action)
                .param("metadata", jsonMapper.writeValueAsString(metadata))
                .param("transactionId", transactionId)
                .param("createdAt", Timestamp.from(at))
                .update();
    }
}
