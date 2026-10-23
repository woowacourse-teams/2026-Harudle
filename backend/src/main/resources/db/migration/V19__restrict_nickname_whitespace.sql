-- Enforce whitespace and allowed-character rules without scanning existing users under an exclusive lock.
SET LOCAL lock_timeout = '2s';

ALTER TABLE users
    ADD CONSTRAINT ck_users_nickname_whitespace
        CHECK (
            nickname IS NULL
            OR (
                position('  ' IN nickname) = 0
                AND position(chr(9) IN nickname) = 0
                AND position(chr(10) IN nickname) = 0
                AND position(chr(13) IN nickname) = 0
            )
        ) NOT VALID,
    ADD CONSTRAINT ck_users_nickname_characters
        CHECK (
            nickname IS NULL
            OR nickname ~ '^[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9 ]+$'
        ) NOT VALID;
