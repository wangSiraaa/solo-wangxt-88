package com.example.scheduler.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.example.scheduler.persistence.ScheduleRepository.ts;

@Repository
public class LeaseRepository {

    private static final RowMapper<LeaseRow> MAPPER = (rs, n) -> new LeaseRow(
            UUID.fromString(rs.getString("instance_id")),
            rs.getString("owner_node"),
            rs.getLong("fencing_token"),
            com.example.scheduler.domain.LeaseStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("acquired_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant());

    private final JdbcTemplate jdbc;

    public LeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LeaseRow> find(UUID instanceId) {
        List<LeaseRow> rows = jdbc.query(
                "SELECT * FROM execution_lease WHERE instance_id = ?", MAPPER, instanceId.toString());
        return rows.stream().findFirst();
    }

    public Optional<LeaseRow> lockById(UUID instanceId) {
        List<LeaseRow> rows = jdbc.query(
                "SELECT * FROM execution_lease WHERE instance_id = ? FOR UPDATE", MAPPER, instanceId.toString());
        return rows.stream().findFirst();
    }

    public void insert(LeaseRow row) {
        jdbc.update("""
                        INSERT INTO execution_lease
                          (instance_id, owner_node, fencing_token, status, acquired_at, expires_at)
                        VALUES (?,?,?,?,?,?)""",
                row.instanceId().toString(), row.ownerNode(), row.fencingToken(), row.status().name(),
                ts(row.acquiredAt()), ts(row.expiresAt()));
    }

    public int update(LeaseRow row) {
        return jdbc.update("""
                        UPDATE execution_lease
                        SET owner_node = ?, fencing_token = ?, status = ?, acquired_at = ?, expires_at = ?
                        WHERE instance_id = ?""",
                row.ownerNode(), row.fencingToken(), row.status().name(),
                ts(row.acquiredAt()), ts(row.expiresAt()), row.instanceId().toString());
    }

    public int release(UUID instanceId) {
        return jdbc.update("UPDATE execution_lease SET status = 'RELEASED' WHERE instance_id = ?",
                instanceId.toString());
    }

    /** Monotonic fencing token; safe because callers hold the instance row lock. */
    public long nextFencingToken() {
        jdbc.update("UPDATE fencing_counter SET counter_value = counter_value + 1 WHERE id = 1");
        Long value = jdbc.queryForObject("SELECT counter_value FROM fencing_counter WHERE id = 1", Long.class);
        if (value == null) {
            throw new IllegalStateException("fencing_counter not initialized");
        }
        return value;
    }
}
