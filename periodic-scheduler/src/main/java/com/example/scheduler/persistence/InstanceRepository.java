package com.example.scheduler.persistence;

import com.example.scheduler.domain.InstanceStatus;
import com.example.scheduler.domain.LeaseStatus;
import com.example.scheduler.domain.SkipReason;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.example.scheduler.persistence.ScheduleRepository.toInstant;
import static com.example.scheduler.persistence.ScheduleRepository.ts;

@Repository
public class InstanceRepository {

    private static final RowMapper<TriggerInstanceRow> MAPPER = (rs, n) -> new TriggerInstanceRow(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("definition_id")),
            rs.getString("occurrence_key"),
            toInstant(rs.getTimestamp("scheduled_at_utc")),
            rs.getTimestamp("sort_at").toInstant(),
            rs.getString("original_zone"),
            rs.getString("local_time"),
            rs.getString("utc_offset"),
            InstanceStatus.valueOf(rs.getString("status")),
            rs.getString("skip_reason") == null ? null : SkipReason.valueOf(rs.getString("skip_reason")),
            rs.getInt("retry_count"),
            toInstant(rs.getTimestamp("next_retry_at")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<InstanceWithLease> WITH_LEASE_MAPPER = (rs, n) -> {
        TriggerInstanceRow instance = MAPPER.mapRow(rs, n);
        LeaseRow lease = rs.getString("lease_instance_id") == null ? null : new LeaseRow(
                UUID.fromString(rs.getString("lease_instance_id")),
                rs.getString("owner_node"),
                rs.getLong("fencing_token"),
                LeaseStatus.valueOf(rs.getString("lease_status")),
                rs.getTimestamp("acquired_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant());
        return new InstanceWithLease(instance, lease);
    };

    private static final String WITH_LEASE_SELECT = """
            SELECT ti.*, el.instance_id AS lease_instance_id, el.owner_node, el.fencing_token,
                   el.status AS lease_status, el.acquired_at, el.expires_at
            FROM trigger_instance ti
            LEFT JOIN execution_lease el ON el.instance_id = ti.id
            """;

    private final JdbcTemplate jdbc;

    public InstanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Idempotent materialization: returns true when the row was inserted, false when another
     * (concurrent) planner had already materialized the same occurrence.
     */
    public boolean insertIgnore(TriggerInstanceRow r) {
        try {
            jdbc.update("""
                            INSERT INTO trigger_instance
                              (id, definition_id, occurrence_key, scheduled_at_utc, sort_at, original_zone,
                               local_time, utc_offset, status, skip_reason, retry_count, next_retry_at,
                               created_at, updated_at)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    r.id().toString(), r.definitionId().toString(), r.occurrenceKey(), ts(r.scheduledAtUtc()),
                    ts(r.sortAt()), r.originalZone(), r.localTime(), r.utcOffset(), r.status().name(),
                    r.skipReason() == null ? null : r.skipReason().name(), r.retryCount(), ts(r.nextRetryAt()),
                    ts(r.createdAt()), ts(r.updatedAt()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<TriggerInstanceRow> findById(UUID id) {
        List<TriggerInstanceRow> rows = jdbc.query(
                "SELECT * FROM trigger_instance WHERE id = ?", MAPPER, id.toString());
        return rows.stream().findFirst();
    }

    /** Locks the instance row until the end of the transaction (serializes lease contenders). */
    public Optional<TriggerInstanceRow> lockById(UUID id) {
        List<TriggerInstanceRow> rows = jdbc.query(
                "SELECT * FROM trigger_instance WHERE id = ? FOR UPDATE", MAPPER, id.toString());
        return rows.stream().findFirst();
    }

    public Optional<InstanceWithLease> findWithLease(UUID id) {
        List<InstanceWithLease> rows = jdbc.query(
                WITH_LEASE_SELECT + " WHERE ti.id = ?", WITH_LEASE_MAPPER, id.toString());
        return rows.stream().findFirst();
    }

    public List<InstanceWithLease> findWindowWithLease(UUID definitionId, Instant from, Instant to) {
        return jdbc.query(WITH_LEASE_SELECT + """
                        WHERE ti.definition_id = ? AND ti.sort_at >= ? AND ti.sort_at <= ?
                        ORDER BY ti.sort_at, ti.occurrence_key""",
                WITH_LEASE_MAPPER, definitionId.toString(), ts(from), ts(to));
    }

    /** Due for dispatch: planned, due time reached, retry backoff (if any) elapsed. */
    public List<TriggerInstanceRow> findDue(Instant now, int limit) {
        return jdbc.query("""
                        SELECT * FROM trigger_instance
                        WHERE status = 'PLANNED'
                          AND scheduled_at_utc IS NOT NULL
                          AND scheduled_at_utc <= ?
                          AND (next_retry_at IS NULL OR next_retry_at <= ?)
                        ORDER BY scheduled_at_utc
                        LIMIT ?""",
                MAPPER, ts(now), ts(now), limit);
    }

    public long countByDefinition(UUID definitionId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trigger_instance WHERE definition_id = ?", Long.class, definitionId.toString());
        return count == null ? 0 : count;
    }

    /** Guarded status transition; returns 1 iff this call performed the flip. */
    public int transitionStatus(UUID id, InstanceStatus expected, InstanceStatus target, Instant now) {
        return jdbc.update("UPDATE trigger_instance SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                target.name(), ts(now), id.toString(), expected.name());
    }

    /** Failure with retry: SAME instance goes back to PLANNED; no new instance is created. */
    public int markRetry(UUID id, Instant nextRetryAt, Instant now) {
        return jdbc.update("""
                        UPDATE trigger_instance
                        SET status = 'PLANNED', retry_count = retry_count + 1, next_retry_at = ?, updated_at = ?
                        WHERE id = ? AND status = 'LEASED'""",
                ts(nextRetryAt), ts(now), id.toString());
    }

    public int markFailed(UUID id, Instant now) {
        return jdbc.update("""
                        UPDATE trigger_instance
                        SET status = 'FAILED', retry_count = retry_count + 1, updated_at = ?
                        WHERE id = ? AND status = 'LEASED'""",
                ts(now), id.toString());
    }

    /** Requeue instances whose lease died (owner crashed / lease expired) — same instance id. */
    public int requeueExpiredLeases(Instant now) {
        return jdbc.update("""
                UPDATE trigger_instance SET status = 'PLANNED', updated_at = ?
                WHERE status = 'LEASED' AND NOT EXISTS (
                    SELECT 1 FROM execution_lease el
                    WHERE el.instance_id = trigger_instance.id
                      AND el.status = 'ACTIVE' AND el.expires_at > ?)""",
                ts(now), ts(now));
    }

    /** Flip still-planned instances inside a pause window to SKIPPED/PAUSED. */
    public int reconcilePaused(UUID definitionId, Instant now) {
        return jdbc.update("""
                UPDATE trigger_instance SET status = 'SKIPPED', skip_reason = 'PAUSED', updated_at = ?
                WHERE definition_id = ? AND status = 'PLANNED' AND EXISTS (
                    SELECT 1 FROM pause_window pw
                    WHERE pw.definition_id = trigger_instance.definition_id
                      AND trigger_instance.scheduled_at_utc >= pw.from_ts
                      AND (pw.to_ts IS NULL OR trigger_instance.scheduled_at_utc < pw.to_ts))""",
                ts(now), definitionId.toString());
    }
}
