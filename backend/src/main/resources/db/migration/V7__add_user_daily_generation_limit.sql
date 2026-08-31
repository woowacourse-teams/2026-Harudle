ALTER TABLE users
    ADD COLUMN daily_generation_limit INTEGER NOT NULL DEFAULT 3;

ALTER TABLE users
    ADD CONSTRAINT ck_users_daily_generation_limit
        CHECK (daily_generation_limit >= 0);
