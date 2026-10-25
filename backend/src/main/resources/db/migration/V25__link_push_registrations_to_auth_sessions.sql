CREATE TABLE auth_sessions (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,

    CONSTRAINT pk_auth_sessions PRIMARY KEY (id),
    CONSTRAINT uq_auth_sessions_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_auth_sessions_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_auth_sessions_user_id ON auth_sessions (user_id);

ALTER TABLE refresh_tokens
    ADD COLUMN session_id UUID,
    ADD CONSTRAINT fk_refresh_tokens_session_user
        FOREIGN KEY (session_id, user_id)
        REFERENCES auth_sessions (id, user_id) ON DELETE CASCADE;

ALTER TABLE push_registrations
    ADD COLUMN session_id UUID,
    ADD CONSTRAINT fk_push_registrations_session_user
        FOREIGN KEY (session_id, user_id)
        REFERENCES auth_sessions (id, user_id) ON DELETE CASCADE;

CREATE INDEX idx_refresh_tokens_session_id ON refresh_tokens (session_id);
CREATE INDEX idx_push_registrations_session_id ON push_registrations (session_id);

-- Existing active tokens cannot be grouped into their original rotation chains.
CREATE TEMP TABLE legacy_refresh_sessions ON COMMIT DROP AS
SELECT rt.id AS token_id, rt.user_id, rt.created_at, gen_random_uuid() AS session_id
FROM refresh_tokens rt
JOIN users u ON u.id = rt.user_id
WHERE rt.revoked_at IS NULL
  AND rt.expires_at > CURRENT_TIMESTAMP
  AND u.deleted_at IS NULL;

INSERT INTO auth_sessions (id, user_id, created_at)
SELECT session_id, user_id, created_at FROM legacy_refresh_sessions;

UPDATE refresh_tokens rt
SET session_id = legacy.session_id
FROM legacy_refresh_sessions legacy
WHERE rt.id = legacy.token_id;

CREATE OR REPLACE FUNCTION enforce_push_registration_identity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.device_id IS DISTINCT FROM OLD.device_id
       OR (OLD.session_id IS NOT NULL AND NEW.session_id IS DISTINCT FROM OLD.session_id) THEN
        RAISE EXCEPTION 'Push registration ownership is immutable; revoke and create a new row'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_push_registrations_identity';
    END IF;
    IF OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS NULL THEN
        RAISE EXCEPTION 'A revoked push registration cannot be reactivated'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_push_registrations_revocation';
    END IF;
    RETURN NEW;
END;
$function$;

DROP TRIGGER trg_push_registrations_identity ON push_registrations;
CREATE TRIGGER trg_push_registrations_identity
    BEFORE UPDATE OF user_id, device_id, session_id, revoked_at ON push_registrations
    FOR EACH ROW EXECUTE FUNCTION enforce_push_registration_identity();
