-- Target identifiers are submission-time snapshots; later content/user removal must retain reports.
CREATE TABLE reports (
    id UUID NOT NULL,
    reporter_id UUID,
    target_type VARCHAR(20) NOT NULL,
    target_id UUID NOT NULL,
    target_user_id UUID NOT NULL,
    reason VARCHAR(30) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_reports PRIMARY KEY (id),
    CONSTRAINT fk_reports_reporter
        FOREIGN KEY (reporter_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT uq_reports_reporter_target UNIQUE (reporter_id, target_type, target_id),
    CONSTRAINT ck_reports_target_type CHECK (target_type IN ('USER', 'FEED', 'COMMENT')),
    CONSTRAINT ck_reports_reason CHECK (reason IN ('SPAM', 'HARASSMENT', 'INAPPROPRIATE_CONTENT', 'OTHER')),
    CONSTRAINT ck_reports_status CHECK (status = 'RECEIVED'),
    CONSTRAINT ck_reports_description_length CHECK (char_length(description) <= 500),
    CONSTRAINT ck_reports_not_self
        CHECK (reporter_id IS NULL OR reporter_id <> target_user_id)
);

CREATE INDEX idx_reports_status_created_at_id ON reports (status, created_at DESC, id DESC);
