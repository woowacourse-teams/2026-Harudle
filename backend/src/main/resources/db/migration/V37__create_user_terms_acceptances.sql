CREATE TABLE user_terms_acceptances (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    terms_version VARCHAR(64) NOT NULL,
    terms_content_hash VARCHAR(64) NOT NULL CHECK (terms_content_hash ~ '^[0-9a-f]{64}$'),
    privacy_notice_version VARCHAR(64) NOT NULL,
    privacy_notice_content_hash VARCHAR(64) NOT NULL CHECK (privacy_notice_content_hash ~ '^[0-9a-f]{64}$'),
    accepted_at TIMESTAMPTZ NOT NULL,
    minimum_age_at_acceptance SMALLINT NOT NULL CHECK (minimum_age_at_acceptance = 14),
    PRIMARY KEY (user_id, terms_version)
);

COMMENT ON TABLE user_terms_acceptances IS '회원이 직접 수락한 약관 버전과 서버 시각. 기존 회원의 과거 동의를 추정하여 채우지 않는다.';
COMMENT ON COLUMN user_terms_acceptances.privacy_notice_version IS '수락 당시 제시한 개인정보처리방침 참조. 개인정보 처리 전체에 대한 별도 동의 기록이 아니다.';

COMMENT ON COLUMN user_terms_acceptances.minimum_age_at_acceptance IS '수락 시 이용자가 확인한 최소 연령 기준. 생년월일 또는 신원·연령 인증 결과가 아니다.';
