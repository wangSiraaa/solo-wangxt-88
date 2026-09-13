package com.example.scheduler.persistence;

import com.example.scheduler.domain.DstGapPolicy;
import com.example.scheduler.domain.DstOverlapPolicy;
import com.example.scheduler.domain.ScheduleType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ScheduleRepository {

    static final RowMapper<ScheduleDefinitionRow> MAPPER = (rs, n) -> new ScheduleDefinitionRow(
            UUID.fromString(rs.getString("id")),
            rs.getString("name"),
            ScheduleType.valueOf(rs.getString("schedule_type")),
            rs.getString("cron_expression"),
            rs.getString("timezone"),
            rs.getObject("interval_seconds", Long.class),
            toInstant(rs.getTimestamp("anchor_at")),
            DstGapPolicy.valueOf(rs.getString("dst_gap_policy")),
            DstOverlapPolicy.valueOf(rs.getString("dst_overlap_policy")),
            rs.getInt("max_retries"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public ScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ScheduleDefinitionRow r) {
        jdbc.update("""
                        INSERT INTO schedule_definition
                          (id, name, schedule_type, cron_expression, timezone, interval_seconds, anchor_at,
                           dst_gap_policy, dst_overlap_policy, max_retries, created_at, updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                r.id().toString(), r.name(), r.type().name(), r.cronExpression(), r.timezone(),
                r.intervalSeconds(), ts(r.anchorAt()), r.dstGapPolicy().name(), r.dstOverlapPolicy().name(),
                r.maxRetries(), ts(r.createdAt()), ts(r.updatedAt()));
    }

    public Optional<ScheduleDefinitionRow> findById(UUID id) {
        List<ScheduleDefinitionRow> rows = jdbc.query(
                "SELECT * FROM schedule_definition WHERE id = ?", MAPPER, id.toString());
        return rows.stream().findFirst();
    }

    public List<ScheduleDefinitionRow> findAll() {
        return jdbc.query("SELECT * FROM schedule_definition ORDER BY created_at, id", MAPPER);
    }

    public int delete(UUID id) {
        return jdbc.update("DELETE FROM schedule_definition WHERE id = ?", id.toString());
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
