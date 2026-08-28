UPDATE users
SET daily_generation_limit = 1
WHERE daily_generation_limit < 1;

UPDATE daily_generation_usage
SET limit_count = 1
WHERE usage_date = (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date
  AND limit_count < 1;

ALTER TABLE users
    DROP CONSTRAINT ck_users_daily_generation_limit;

ALTER TABLE users
    ADD CONSTRAINT ck_users_daily_generation_limit
        CHECK (daily_generation_limit >= 1);
