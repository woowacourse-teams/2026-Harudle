ALTER TABLE reports DROP CONSTRAINT ck_reports_status;
ALTER TABLE reports ADD CONSTRAINT ck_reports_status CHECK (status IN ('RECEIVED', 'DISMISSED', 'RESOLVED'));
ALTER TABLE reports ADD COLUMN reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE reports ADD COLUMN reviewed_at TIMESTAMPTZ;
ALTER TABLE reports ADD COLUMN review_reason TEXT;
ALTER TABLE reports ADD CONSTRAINT ck_reports_review CHECK (
    (status = 'RECEIVED' AND reviewed_by IS NULL AND reviewed_at IS NULL AND review_reason IS NULL)
    OR (status IN ('DISMISSED', 'RESOLVED') AND reviewed_at IS NOT NULL
        AND review_reason IS NOT NULL AND char_length(review_reason) BETWEEN 1 AND 500)
);

-- Target/actor deletion must not silently erase moderation history.
CREATE TABLE moderation_states (
    target_type VARCHAR(20) NOT NULL CHECK (target_type IN ('USER', 'FEED', 'COMMENT')),
    target_id UUID NOT NULL,
    restricted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    reason TEXT,
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    changed_at TIMESTAMPTZ,
    PRIMARY KEY (target_type, target_id),
    CONSTRAINT ck_moderation_state_change CHECK (
        (version = 0 AND NOT restricted AND reason IS NULL AND actor_id IS NULL AND changed_at IS NULL)
        OR (version > 0 AND reason IS NOT NULL AND char_length(reason) BETWEEN 1 AND 500 AND changed_at IS NOT NULL)
    )
);

CREATE TABLE moderation_events (
    id UUID PRIMARY KEY,
    target_type VARCHAR(20) NOT NULL,
    target_id UUID NOT NULL,
    restricted BOOLEAN NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    reason TEXT NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 500),
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_moderation_event_state FOREIGN KEY (target_type, target_id)
        REFERENCES moderation_states(target_type, target_id),
    CONSTRAINT uq_moderation_event_version UNIQUE (target_type, target_id, version)
);
