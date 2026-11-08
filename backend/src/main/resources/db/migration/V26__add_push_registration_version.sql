ALTER TABLE push_registrations
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_push_registrations_version CHECK (version >= 0);
