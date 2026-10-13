-- NULL nickname preserves the legacy name fallback, including duplicate/long names.
-- Keep this migration short: scans, index creation and data backfill run separately.
SET LOCAL lock_timeout = '2s';

ALTER TABLE users
    ADD COLUMN nickname VARCHAR(30) COLLATE "C",
    ADD COLUMN profile_image_code SMALLINT;

-- NOT VALID skips scanning existing users; new INSERT/UPDATE still enforces CHECK.
ALTER TABLE users
    ADD CONSTRAINT ck_users_nickname
        CHECK (
            nickname IS NULL
            OR (
                char_length(nickname) BETWEEN 1 AND 10
                AND nickname !~ '^[[:space:]]|[[:space:]]$'
                AND nickname IS NFC NORMALIZED
            )
        ) NOT VALID,
    ADD CONSTRAINT ck_users_profile_image_code
        CHECK (profile_image_code IS NULL OR profile_image_code BETWEEN 1 AND 5) NOT VALID;
