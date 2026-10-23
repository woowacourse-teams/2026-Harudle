-- Preserve existing image assignments; NULL means no uploaded profile photo.
SET LOCAL lock_timeout = '2s';

ALTER TABLE users
    ADD COLUMN profile_image_object_key VARCHAR(1024),
    DROP CONSTRAINT ck_users_profile_image_code,
    ADD CONSTRAINT ck_users_profile_image_code
        CHECK (
            profile_image_code IS NULL
            OR profile_image_code BETWEEN 1 AND 10
        ) NOT VALID;
