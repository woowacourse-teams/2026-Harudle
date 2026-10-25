-- Existing rows remain explicitly marked as legacy plaintext until issue #423 backfills them.
-- New application writes always set version 1. Version 0 also allows the old writer to finish
-- before the application is replaced; old readers must not run after the first encrypted write.
ALTER TABLE diaries ALTER COLUMN source_text TYPE TEXT;
ALTER TABLE diaries ADD COLUMN source_text_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE diaries ADD CONSTRAINT ck_diaries_source_text_version
    CHECK (source_text_version IN (0, 1));

COMMENT ON COLUMN diaries.source_text_version IS '0: legacy plaintext; 1: AES-256-GCM key-id envelope';
