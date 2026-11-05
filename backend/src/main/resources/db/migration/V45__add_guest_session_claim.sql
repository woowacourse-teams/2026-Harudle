ALTER TABLE guest_sessions
    ADD COLUMN claimed_by_user_id UUID,
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD CONSTRAINT fk_guest_sessions_claimed_by_user
        FOREIGN KEY (claimed_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    -- 회원 탈퇴로 claimed_by_user_id만 NULL이 될 수 있으므로 claimed_at은 남겨 둔다.
    ADD CONSTRAINT ck_guest_sessions_claimed_at
        CHECK (claimed_by_user_id IS NULL OR claimed_at IS NOT NULL);

CREATE UNIQUE INDEX uq_guest_sessions_claimed_by_user
    ON guest_sessions (claimed_by_user_id)
    WHERE claimed_by_user_id IS NOT NULL;
