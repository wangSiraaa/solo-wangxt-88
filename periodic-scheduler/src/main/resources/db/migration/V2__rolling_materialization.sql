-- Rolling materialization: the planner remembers per definition how far into the
-- future instances have been materialized, and advances this watermark in batches
-- instead of materializing the whole horizon in one run.
ALTER TABLE schedule_definition ADD COLUMN materialized_until TIMESTAMP WITH TIME ZONE;
