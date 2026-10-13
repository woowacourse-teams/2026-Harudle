-- Validate after V19 committed so ordinary reads and writes can continue during the scan.
SET LOCAL lock_timeout = '2s';

ALTER TABLE users
    VALIDATE CONSTRAINT ck_users_nickname_whitespace,
    VALIDATE CONSTRAINT ck_users_nickname_characters;
