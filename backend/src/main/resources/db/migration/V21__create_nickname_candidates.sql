CREATE TABLE nickname_candidates (
    nickname VARCHAR(30) COLLATE "C" PRIMARY KEY,
    allocation_order BIGINT NOT NULL UNIQUE,
    assigned_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uq_nickname_candidates_assigned_user UNIQUE (assigned_user_id),
    CONSTRAINT ck_nickname_candidates_allocation_order CHECK (allocation_order > 0),
    CONSTRAINT ck_nickname_candidates_nickname CHECK (
        char_length(nickname) BETWEEN 1 AND 10
        AND nickname IS NFC NORMALIZED
        AND nickname !~ '^[[:space:]]|[[:space:]]$'
        AND position('  ' IN nickname) = 0
        AND nickname ~ '^[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9 ]+$'
    )
);

-- 사용 가능한 후보만 배정 순서대로 찾는다.
CREATE INDEX idx_nickname_candidates_available
    ON nickname_candidates (allocation_order)
    WHERE assigned_user_id IS NULL;
