ALTER TABLE reports DROP CONSTRAINT ck_reports_target_user_required;
ALTER TABLE reports ALTER COLUMN target_id DROP NOT NULL;
ALTER TABLE reports ADD CONSTRAINT ck_reports_target_required
    CHECK (target_type = 'USER' OR target_id IS NOT NULL);
