-- Validate after V14 committed. SHARE UPDATE EXCLUSIVE permits ordinary reads/writes.
SET LOCAL lock_timeout = '2s';

ALTER TABLE users
    VALIDATE CONSTRAINT ck_users_nickname,
    VALIDATE CONSTRAINT ck_users_profile_image_code;
