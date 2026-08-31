UPDATE daily_generation_usage
SET limit_count = 1
WHERE limit_count < 1;

ALTER TABLE daily_generation_usage
    DROP CONSTRAINT ck_daily_generation_usage_count;

ALTER TABLE daily_generation_usage
    ADD CONSTRAINT ck_daily_generation_usage_count
        CHECK (
            used_count >= 0
            AND limit_count >= 1
            AND used_count <= limit_count
        );
