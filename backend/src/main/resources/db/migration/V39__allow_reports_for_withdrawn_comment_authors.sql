ALTER TABLE reports ALTER COLUMN target_user_id DROP NOT NULL;
ALTER TABLE reports ADD CONSTRAINT ck_reports_target_user_required
    CHECK (target_type = 'COMMENT' OR target_user_id IS NOT NULL);
