ALTER TABLE push_registrations
    DROP CONSTRAINT ck_push_registrations_kind;

ALTER TABLE push_registrations
    ALTER COLUMN recipient_kind SET DEFAULT 'FID',
    ADD CONSTRAINT ck_push_registrations_kind CHECK (recipient_kind = 'FID');
