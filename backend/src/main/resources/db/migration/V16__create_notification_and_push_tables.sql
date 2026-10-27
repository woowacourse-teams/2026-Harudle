CREATE TABLE notifications (
    id UUID NOT NULL,
    recipient_user_id UUID NOT NULL,
    actor_user_id UUID,
    feed_id UUID NOT NULL,
    type VARCHAR(20) NOT NULL,
    source_action_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMPTZ,

    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT uq_notifications_source
        UNIQUE (recipient_user_id, type, source_action_id),
    CONSTRAINT ck_notifications_type CHECK (type IN ('LIKE', 'COMMENT')),
    CONSTRAINT fk_notifications_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_actor
        FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_notifications_feed
        FOREIGN KEY (feed_id) REFERENCES feeds (id) ON DELETE CASCADE
);

-- source_action_id refers to a like or comment depending on type. It is not an
-- FK: notifications survive the physical removal of a like on cancellation.
CREATE INDEX idx_notifications_recipient_timeline
    ON notifications (recipient_user_id, created_at DESC, id DESC);
CREATE INDEX idx_notifications_recipient_unread
    ON notifications (recipient_user_id, feed_id)
    WHERE read_at IS NULL;
CREATE INDEX idx_notifications_actor_id ON notifications (actor_user_id);
CREATE INDEX idx_notifications_feed_id ON notifications (feed_id);

CREATE TABLE push_registrations (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    device_id UUID NOT NULL,
    recipient_kind VARCHAR(20) NOT NULL DEFAULT 'FCM_TOKEN',
    recipient_value TEXT COLLATE "C" NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,

    CONSTRAINT pk_push_registrations PRIMARY KEY (id),
    CONSTRAINT uq_push_registrations_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_push_registrations_kind CHECK (recipient_kind = 'FCM_TOKEN'),
    CONSTRAINT ck_push_registrations_value
        CHECK (recipient_value !~ '^[[:space:]]*$'),
    CONSTRAINT fk_push_registrations_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_push_registrations_active_device
    ON push_registrations (device_id)
    WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX uq_push_registrations_active_recipient
    ON push_registrations (recipient_kind, recipient_value)
    WHERE revoked_at IS NULL;
CREATE INDEX idx_push_registrations_user_id ON push_registrations (user_id);
CREATE INDEX idx_push_registrations_active_last_seen
    ON push_registrations (last_seen_at, id)
    WHERE revoked_at IS NULL;

-- Account/device reassignment creates a new registration ID. Token refresh for
-- the same user/device may update recipient_value on the current active row.
CREATE FUNCTION enforce_push_registration_identity()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.device_id IS DISTINCT FROM OLD.device_id THEN
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

CREATE TRIGGER trg_push_registrations_identity
    BEFORE UPDATE OF user_id, device_id, revoked_at ON push_registrations
    FOR EACH ROW EXECUTE FUNCTION enforce_push_registration_identity();

CREATE TABLE notification_outbox (
    id UUID NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL DEFAULT 'FEED_PUBLISHED',
    feed_id UUID NOT NULL,
    recipient_user_id UUID NOT NULL,
    push_registration_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_until TIMESTAMPTZ,
    lock_token UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    last_error_code VARCHAR(100),

    CONSTRAINT pk_notification_outbox PRIMARY KEY (id),
    CONSTRAINT uq_notification_outbox_event_registration
        UNIQUE (event_id, push_registration_id),
    CONSTRAINT ck_notification_outbox_event_type
        CHECK (event_type = 'FEED_PUBLISHED'),
    CONSTRAINT ck_notification_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'CANCELLED', 'FAILED')),
    CONSTRAINT ck_notification_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_notification_outbox_state_fields
        CHECK (
            (
                status = 'PROCESSING'
                AND lock_token IS NOT NULL
                AND locked_until IS NOT NULL
                AND sent_at IS NULL
            )
            OR (
                status = 'SENT'
                AND lock_token IS NULL
                AND locked_until IS NULL
                AND sent_at IS NOT NULL
            )
            OR (
                status IN ('PENDING', 'CANCELLED', 'FAILED')
                AND lock_token IS NULL
                AND locked_until IS NULL
                AND sent_at IS NULL
            )
        ),
    CONSTRAINT fk_notification_outbox_feed
        FOREIGN KEY (feed_id) REFERENCES feeds (id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_outbox_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_outbox_registration_recipient
        FOREIGN KEY (push_registration_id, recipient_user_id)
        REFERENCES push_registrations (id, user_id) ON DELETE CASCADE
);

CREATE INDEX idx_notification_outbox_pending
    ON notification_outbox (available_at, id)
    WHERE status = 'PENDING';
CREATE INDEX idx_notification_outbox_expired_processing
    ON notification_outbox (locked_until, id)
    WHERE status = 'PROCESSING';
CREATE INDEX idx_notification_outbox_feed_id ON notification_outbox (feed_id);
CREATE INDEX idx_notification_outbox_recipient_id ON notification_outbox (recipient_user_id);
CREATE INDEX idx_notification_outbox_registration_id ON notification_outbox (push_registration_id);

-- A publication transaction inserts one event ID shared by all device jobs.
-- Workers claim in a short transaction, commit before FCM I/O, and update results
-- only with a matching lock_token. SENT means FCM accepted the request.
-- Expired leases may be reclaimed; retries return to PENDING with a later
-- available_at. Clients suppress duplicate delivery using event_id.
-- Revocation/account change and logical feed deletion cancel unsent jobs in the
-- service transaction. Workers recheck registration ownership and active state.
-- In-app reads/unread counts exclude logically deleted feeds via a join to feeds.
