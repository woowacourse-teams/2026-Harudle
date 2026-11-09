-- 우테코 캠퍼스 연결 코드: 하루들 회원이 캠퍼스에 신원을 한 번 알려 줄 때 쓰는 60초짜리 일회용 코드.
-- 코드와 state는 원본 대신 SHA-256 해시만 저장한다.
CREATE TABLE campus_link_codes (
    id         BIGSERIAL PRIMARY KEY,
    user_id    UUID        NOT NULL,
    code_hash  CHAR(64)    NOT NULL,
    state_hash CHAR(64)    NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_campus_link_codes_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_campus_link_codes_code_hash UNIQUE (code_hash)
);

CREATE INDEX idx_campus_link_codes_expires_at
    ON campus_link_codes (expires_at);
