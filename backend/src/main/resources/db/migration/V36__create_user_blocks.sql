CREATE TABLE user_blocks (
    id UUID NOT NULL,
    blocker_id UUID NOT NULL,
    blocked_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_user_blocks PRIMARY KEY (id),
    CONSTRAINT uq_user_blocks_direction UNIQUE (blocker_id, blocked_id),
    CONSTRAINT ck_user_blocks_not_self CHECK (blocker_id <> blocked_id),
    CONSTRAINT fk_user_blocks_blocker FOREIGN KEY (blocker_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_blocks_blocked FOREIGN KEY (blocked_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_user_blocks_reverse ON user_blocks (blocked_id, blocker_id);
CREATE INDEX idx_user_blocks_owner_timeline ON user_blocks (blocker_id, created_at DESC, id DESC);
