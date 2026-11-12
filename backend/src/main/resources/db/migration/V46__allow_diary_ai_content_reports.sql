-- Personal (unpublished) diaries are visible only to their owner, so a DIARY report is the owner
-- flagging the AI-generated comic. Every other target type still forbids reporting one's own content.
ALTER TABLE reports DROP CONSTRAINT ck_reports_target_type;
ALTER TABLE reports ADD CONSTRAINT ck_reports_target_type
    CHECK (target_type IN ('USER', 'FEED', 'COMMENT', 'DIARY'));

ALTER TABLE reports DROP CONSTRAINT ck_reports_not_self;
ALTER TABLE reports ADD CONSTRAINT ck_reports_not_self CHECK (
    reporter_id IS NULL OR target_user_id IS NULL
    OR (target_type = 'DIARY' AND reporter_id = target_user_id)
    OR (target_type <> 'DIARY' AND reporter_id <> target_user_id)
);
