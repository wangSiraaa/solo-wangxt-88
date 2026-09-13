-- Schema for the periodic scheduler. Written in the common SQL subset of
-- PostgreSQL and H2 so the same migration runs in production and in tests.

CREATE TABLE schedule_definition (
    id                 VARCHAR(36) PRIMARY KEY,
    name               VARCHAR(200) NOT NULL,
    schedule_type      VARCHAR(20)  NOT NULL,           -- FIXED_INTERVAL | CALENDAR
    cron_expression    VARCHAR(200),                    -- CALENDAR only
    timezone           VARCHAR(64),                     -- CALENDAR only
    interval_seconds   BIGINT,                          -- FIXED_INTERVAL only
    anchor_at          TIMESTAMP WITH TIME ZONE,        -- FIXED_INTERVAL only
    dst_gap_policy     VARCHAR(20)  NOT NULL,           -- SHIFT_FORWARD | SKIP
    dst_overlap_policy VARCHAR(20)  NOT NULL,           -- FIRST | SECOND | BOTH
    max_retries        INT          NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE pause_window (
    id            VARCHAR(36) PRIMARY KEY,
    definition_id VARCHAR(36) NOT NULL REFERENCES schedule_definition (id) ON DELETE CASCADE,
    from_ts       TIMESTAMP WITH TIME ZONE NOT NULL,
    to_ts         TIMESTAMP WITH TIME ZONE              -- NULL = paused indefinitely
);
CREATE INDEX idx_pause_window_definition ON pause_window (definition_id);

CREATE TABLE trigger_instance (
    id               VARCHAR(36) PRIMARY KEY,
    definition_id    VARCHAR(36) NOT NULL REFERENCES schedule_definition (id) ON DELETE CASCADE,
    occurrence_key   VARCHAR(120) NOT NULL,             -- stable identity of the occurrence
    scheduled_at_utc TIMESTAMP WITH TIME ZONE,          -- NULL for DST_GAP skips (no such instant)
    sort_at          TIMESTAMP WITH TIME ZONE NOT NULL, -- window queries / ordering
    original_zone    VARCHAR(64)  NOT NULL,             -- e.g. Europe/Berlin, UTC for intervals
    local_time       VARCHAR(40)  NOT NULL,             -- wall-clock time in original_zone
    utc_offset       VARCHAR(10),                       -- offset actually used, NULL for gaps
    status           VARCHAR(20)  NOT NULL,             -- PLANNED|SKIPPED|LEASED|SUCCEEDED|FAILED
    skip_reason      VARCHAR(30),                       -- DST_GAP | PAUSED
    retry_count      INT NOT NULL DEFAULT 0,
    next_retry_at    TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Idempotent materialization: N planner processes may race, the row is born once.
    CONSTRAINT uq_instance_occurrence UNIQUE (definition_id, occurrence_key)
);
CREATE INDEX idx_instance_due ON trigger_instance (status, scheduled_at_utc);
CREATE INDEX idx_instance_window ON trigger_instance (definition_id, sort_at);

-- One row per instance => at most one lease record can ever exist per instance.
-- Mutual exclusion is enforced by locking the instance row while acquiring.
CREATE TABLE execution_lease (
    instance_id   VARCHAR(36) PRIMARY KEY REFERENCES trigger_instance (id) ON DELETE CASCADE,
    owner_node    VARCHAR(120) NOT NULL,
    fencing_token BIGINT NOT NULL,
    status        VARCHAR(20)  NOT NULL,                -- ACTIVE | RELEASED
    acquired_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Monotonic fencing token source (portable across PostgreSQL / H2).
CREATE TABLE fencing_counter (
    id            INT PRIMARY KEY,
    counter_value BIGINT NOT NULL
);
INSERT INTO fencing_counter (id, counter_value) VALUES (1, 0);
