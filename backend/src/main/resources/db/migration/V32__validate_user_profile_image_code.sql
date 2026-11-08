-- Validate after V28 commits so the table scan uses a weaker lock.
SET LOCAL lock_timeout = '2s';

ALTER TABLE users
    VALIDATE CONSTRAINT ck_users_profile_image_code;
