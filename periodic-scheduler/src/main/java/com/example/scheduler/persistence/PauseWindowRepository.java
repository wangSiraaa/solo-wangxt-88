package com.example.scheduler.persistence;

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
public class PauseWindowRepository {

    private static final RowMapper<PauseWindowRow> MAPPER = (rs, n) -> new PauseWindowRow(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("definition_id")),
            rs.getTimestamp("from_ts").toInstant(),
            toInstant(rs.getTimestamp("to_ts")));

    private final JdbcTemplate jdbc;

    public PauseWindowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PauseWindowRow row) {
        jdbc.update("INSERT INTO pause_window (id, definition_id, from_ts, to_ts) VALUES (?,?,?,?)",
                row.id().toString(), row.definitionId().toString(), ts(row.fromTs()), ts(row.toTs()));
    }

    public List<PauseWindowRow> findByDefinition(UUID definitionId) {
        return jdbc.query("SELECT * FROM pause_window WHERE definition_id = ? ORDER BY from_ts",
                MAPPER, definitionId.toString());
    }

    /** The window pausing the schedule right now, if any. */
    public Optional<PauseWindowRow> findActive(UUID definitionId, Instant now) {
        List<PauseWindowRow> rows = jdbc.query("""
                        SELECT * FROM pause_window
                        WHERE definition_id = ? AND from_ts <= ? AND (to_ts IS NULL OR to_ts > ?)
                        ORDER BY from_ts DESC LIMIT 1""",
                MAPPER, definitionId.toString(), ts(now), ts(now));
        return rows.stream().findFirst();
    }

    /** Closes the currently active window(s); returns how many were closed. */
    public int closeActive(UUID definitionId, Instant now) {
        return jdbc.update("""
                        UPDATE pause_window SET to_ts = ?
                        WHERE definition_id = ? AND from_ts <= ? AND (to_ts IS NULL OR to_ts > ?)""",
                ts(now), definitionId.toString(), ts(now), ts(now));
    }
}
