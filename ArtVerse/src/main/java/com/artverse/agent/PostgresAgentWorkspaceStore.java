package com.artverse.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Multi-instance AgentScope RemoteFilesystem store backed by PostgreSQL. */
@Repository
public class PostgresAgentWorkspaceStore implements BaseStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresAgentWorkspaceStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public StoreItem get(List<String> namespace, String key) {
        List<StoreItem> items = jdbcTemplate.query("""
                SELECT item_key, value_json::text, version
                FROM agent_workspace_items
                WHERE namespace_key = ? AND item_key = ?
                """, (rs, row) -> new StoreItem(
                rs.getString("item_key"),
                readValue(rs.getString("value_json")),
                rs.getLong("version")), namespaceKey(namespace), key);
        return items.isEmpty() ? null : items.get(0);
    }

    @Override
    @Transactional
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        jdbcTemplate.update("""
                INSERT INTO agent_workspace_items(namespace_key, item_key, value_json, version)
                VALUES (?, ?, CAST(? AS jsonb), 1)
                ON CONFLICT (namespace_key, item_key)
                DO UPDATE SET value_json = EXCLUDED.value_json,
                              version = agent_workspace_items.version + 1,
                              updated_at = now()
                """, namespaceKey(namespace), key, writeValue(value));
    }

    @Override
    @Transactional
    public boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value,
                                long expectedVersion) {
        String namespaceKey = namespaceKey(namespace);
        int changed = jdbcTemplate.update("""
                INSERT INTO agent_workspace_items(namespace_key, item_key, value_json, version)
                SELECT ?, ?, CAST(? AS jsonb), 1
                WHERE ? = 0 OR EXISTS (
                    SELECT 1 FROM agent_workspace_items
                    WHERE namespace_key = ? AND item_key = ? AND version = ?
                )
                ON CONFLICT (namespace_key, item_key)
                DO UPDATE SET value_json = EXCLUDED.value_json,
                              version = agent_workspace_items.version + 1,
                              updated_at = now()
                WHERE agent_workspace_items.version = ?
                """, namespaceKey, key, writeValue(value), expectedVersion,
                namespaceKey, key, expectedVersion, expectedVersion);
        return changed == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreItem> search(List<String> namespace, int limit, int offset) {
        return jdbcTemplate.query("""
                SELECT item_key, value_json::text, version
                FROM agent_workspace_items
                WHERE namespace_key = ?
                ORDER BY item_key
                LIMIT ? OFFSET ?
                """, (rs, row) -> new StoreItem(
                rs.getString("item_key"),
                readValue(rs.getString("value_json")),
                rs.getLong("version")),
                namespaceKey(namespace), Math.max(0, limit), Math.max(0, offset));
    }

    @Override
    @Transactional
    public void delete(List<String> namespace, String key) {
        jdbcTemplate.update("""
                DELETE FROM agent_workspace_items
                WHERE namespace_key = ? AND item_key = ?
                """, namespaceKey(namespace), key);
    }

    private String namespaceKey(List<String> namespace) {
        try {
            return objectMapper.writeValueAsString(namespace == null ? List.of() : namespace);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to serialize AgentScope workspace namespace", error);
        }
    }

    private String writeValue(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to serialize AgentScope workspace value", error);
        }
    }

    private Map<String, Object> readValue(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception error) {
            throw new IllegalStateException("Failed to deserialize AgentScope workspace value", error);
        }
    }
}
