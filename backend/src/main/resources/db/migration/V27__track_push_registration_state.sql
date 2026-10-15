CREATE TABLE push_registration_states (
    session_id UUID NOT NULL,
    user_id UUID NOT NULL,
    device_id UUID NOT NULL,
    state_version BIGINT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_push_registration_states PRIMARY KEY (session_id, device_id),
    CONSTRAINT ck_push_registration_states_version CHECK (state_version >= 0),
    CONSTRAINT fk_push_registration_states_session_user
        FOREIGN KEY (session_id, user_id)
        REFERENCES auth_sessions (id, user_id) ON DELETE CASCADE
);

INSERT INTO push_registration_states (session_id, user_id, device_id, state_version, enabled)
SELECT session_id, user_id, device_id, 1, TRUE
FROM push_registrations
WHERE session_id IS NOT NULL AND revoked_at IS NULL;
