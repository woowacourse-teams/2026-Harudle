ALTER TABLE users
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';

ALTER TABLE users
    ADD CONSTRAINT ck_users_role
        CHECK (role IN ('USER', 'ADMIN'));
