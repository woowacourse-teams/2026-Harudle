package com.harudle.push.infrastructure;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JpaPushRegistrationService {

    private static final String ACTIVE_SESSION = """
            SELECT s.id FROM auth_sessions s JOIN users u ON u.id = s.user_id
            WHERE s.id = :sessionId AND s.user_id = :userId AND s.revoked_at IS NULL
              AND u.deleted_at IS NULL AND EXISTS (
                SELECT 1 FROM refresh_tokens rt WHERE rt.session_id = s.id
                  AND rt.user_id = s.user_id AND rt.revoked_at IS NULL
                  AND rt.expires_at > CURRENT_TIMESTAMP)
            """;
    private static final String ACTIVE_REGISTRATION = """
            SELECT id, user_id, session_id, device_id, recipient_value, version
            FROM push_registrations WHERE %s = :value AND revoked_at IS NULL FOR UPDATE
            """;
    private static final String INSERT_STATE = """
            INSERT INTO push_registration_states (session_id, user_id, device_id)
            VALUES (:sessionId, :userId, :deviceId) ON CONFLICT DO NOTHING
            """;
    private static final String SELECT_STATE = """
            SELECT state_version, enabled FROM push_registration_states
            WHERE session_id = :sessionId AND device_id = :deviceId FOR UPDATE
            """;
    private static final String UPDATE_STATE = """
            UPDATE push_registration_states SET state_version = state_version + 1, enabled = :enabled
            WHERE session_id = :sessionId AND device_id = :deviceId
            """;
    private static final String DISABLE_OLD_STATE = """
            UPDATE push_registration_states SET state_version = state_version + 1, enabled = FALSE
            WHERE session_id = :sessionId AND device_id = :deviceId AND enabled = TRUE
            """;
    private static final String UPDATE_FID = """
            UPDATE push_registrations SET recipient_value = :fid, version = version + 1,
                updated_at = CURRENT_TIMESTAMP, last_seen_at = CURRENT_TIMESTAMP
            WHERE id = :id AND version = :version AND revoked_at IS NULL
            """;
    private static final String INSERT_REGISTRATION = """
            INSERT INTO push_registrations (id, user_id, session_id, device_id, recipient_value)
            VALUES (:id, :userId, :sessionId, :deviceId, :fid)
            ON CONFLICT DO NOTHING
            """;
    private static final String REVOKE_REGISTRATION = """
            UPDATE push_registrations SET revoked_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND revoked_at IS NULL
            """;
    private static final String CANCEL_UNSENT = """
            UPDATE notification_outbox
            SET status = 'CANCELLED', lock_token = NULL, locked_until = NULL,
                sent_at = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE push_registration_id = :id AND status IN ('PENDING', 'PROCESSING')
            """;

    private final EntityManager entityManager;

    public JpaPushRegistrationService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    public Registration register(UUID userId, UUID sessionId, UUID deviceId, String fid,
                                 UUID expectedId, Long expectedVersion, long expectedStateVersion) {
        validate(userId, sessionId, deviceId, fid, expectedId, expectedVersion, expectedStateVersion);
        requireActiveSession(userId, sessionId);
        State state = state(userId, sessionId, deviceId);
        RegistrationRow current = reusableRegistration(deviceId, sessionId);
        if (repeatedPut(current, fid, state, expectedStateVersion)) {
            return new Registration(current.id(), current.version(), state.version());
        }
        requireStateVersion(state, expectedStateVersion);
        Registration saved = saveRegistration(userId, sessionId, deviceId, fid,
                expectedId, expectedVersion, state.version(), current);
        return new Registration(saved.id(), saved.version(), advanceState(sessionId, deviceId, true, state));
    }

    private Registration saveRegistration(UUID userId, UUID sessionId, UUID deviceId, String fid,
                                          UUID expectedId, Long expectedVersion, long stateVersion,
                                          RegistrationRow current) {
        if (current == null) {
            return registerNew(userId, sessionId, deviceId, fid, expectedId, stateVersion);
        }
        return registerExisting(current, sessionId, fid, expectedId, expectedVersion, stateVersion);
    }

    private RegistrationRow reusableRegistration(UUID deviceId, UUID sessionId) {
        RegistrationRow current = activeByDevice(deviceId);
        if (current == null || sessionId.equals(current.sessionId())) {
            return current;
        }
        release(current, sessionId);
        return null;
    }

    private Registration registerNew(UUID userId, UUID sessionId, UUID deviceId,
                                     String fid, UUID expectedId, long stateVersion) {
        if (expectedId != null) {
            throw new StaleRegistration(null);
        }
        releaseFidOwner(fid, sessionId, null);
        return create(userId, sessionId, deviceId, fid, stateVersion);
    }

    private Registration registerExisting(RegistrationRow current, UUID sessionId, String fid,
                                          UUID expectedId, Long expectedVersion, long stateVersion) {
        Registration registration = new Registration(current.id(), current.version(), stateVersion);
        if (fid.equals(current.fid())) {
            return registration;
        }
        if (!current.id().equals(expectedId) || !Objects.equals(current.version(), expectedVersion)) {
            throw new StaleRegistration(registration);
        }
        releaseFidOwner(fid, sessionId, current.id());
        return updateFid(registration, fid);
    }

    @Transactional
    public long revoke(UUID userId, UUID sessionId, UUID deviceId, long expectedStateVersion) {
        validateRevoke(userId, sessionId, deviceId, expectedStateVersion);
        requireActiveSession(userId, sessionId);
        State state = state(userId, sessionId, deviceId);
        if (!state.enabled() && state.version() > 0 && repeatedVersion(state, expectedStateVersion)) {
            return state.version();
        }
        requireStateVersion(state, expectedStateVersion);
        revokeOwnedRegistration(userId, sessionId, deviceId);
        return advanceState(sessionId, deviceId, false, state);
    }

    private void revokeOwnedRegistration(UUID userId, UUID sessionId, UUID deviceId) {
        RegistrationRow current = activeByDevice(deviceId);
        if (current != null && userId.equals(current.userId()) && sessionId.equals(current.sessionId())) {
            revoke(current.id());
        }
    }

    private void validateRevoke(UUID userId, UUID sessionId, UUID deviceId, long stateVersion) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        Objects.requireNonNull(sessionId, "sessionId는 필수입니다.");
        Objects.requireNonNull(deviceId, "deviceId는 필수입니다.");
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion은 0 이상이어야 합니다.");
        }
    }

    private void validate(UUID userId, UUID sessionId, UUID deviceId, String fid,
                          UUID expectedId, Long expectedVersion, long expectedStateVersion) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        Objects.requireNonNull(sessionId, "sessionId는 필수입니다.");
        Objects.requireNonNull(deviceId, "deviceId는 필수입니다.");
        if (fid == null || fid.isBlank()) {
            throw new IllegalArgumentException("FID는 필수입니다.");
        }
        if (expectedStateVersion < 0 || (expectedId == null) != (expectedVersion == null)
                || expectedVersion != null && expectedVersion < 0) {
            throw new IllegalArgumentException("등록 ID와 버전은 함께 전달해야 합니다.");
        }
    }

    private State state(UUID userId, UUID sessionId, UUID deviceId) {
        entityManager.createNativeQuery(INSERT_STATE).setParameter("sessionId", sessionId)
                .setParameter("userId", userId).setParameter("deviceId", deviceId).executeUpdate();
        Object[] row = (Object[]) entityManager.createNativeQuery(SELECT_STATE)
                .setParameter("sessionId", sessionId).setParameter("deviceId", deviceId).getSingleResult();
        return new State(((Number) row[0]).longValue(), (Boolean) row[1]);
    }

    private boolean repeatedPut(RegistrationRow current, String fid, State state, long expected) {
        return current != null && fid.equals(current.fid()) && state.enabled()
                && repeatedVersion(state, expected);
    }

    private boolean repeatedVersion(State state, long expected) {
        return expected == state.version() || state.version() > 0 && expected == state.version() - 1;
    }

    private void requireStateVersion(State state, long expected) {
        if (state.version() != expected) {
            throw new StalePushState(state.version(), state.enabled());
        }
    }

    private long advanceState(UUID sessionId, UUID deviceId, boolean enabled, State state) {
        entityManager.createNativeQuery(UPDATE_STATE).setParameter("enabled", enabled)
                .setParameter("sessionId", sessionId).setParameter("deviceId", deviceId).executeUpdate();
        return state.version() + 1;
    }

    private void releaseFidOwner(String fid, UUID sessionId, UUID currentId) {
        RegistrationRow owner = activeByFid(fid);
        if (owner == null || owner.id().equals(currentId)) {
            return;
        }
        release(owner, sessionId);
    }

    private void release(RegistrationRow registration, UUID sessionId) {
        if (!sessionId.equals(registration.sessionId())
                && activeSession(registration.userId(), registration.sessionId())) {
            throw new RegistrationConflict();
        }
        revoke(registration.id());
        if (registration.sessionId() != null) {
            entityManager.createNativeQuery(DISABLE_OLD_STATE)
                    .setParameter("sessionId", registration.sessionId())
                    .setParameter("deviceId", registration.deviceId()).executeUpdate();
        }
    }

    private Registration updateFid(Registration current, String fid) {
        entityManager.createNativeQuery(UPDATE_FID)
                .setParameter("fid", fid).setParameter("id", current.id())
                .setParameter("version", current.version()).executeUpdate();
        return new Registration(current.id(), current.version() + 1, 0);
    }

    private Registration create(UUID userId, UUID sessionId, UUID deviceId, String fid, long stateVersion) {
        UUID id = UUID.randomUUID();
        int inserted = entityManager.createNativeQuery(INSERT_REGISTRATION)
                .setParameter("id", id).setParameter("userId", userId).setParameter("sessionId", sessionId)
                .setParameter("deviceId", deviceId).setParameter("fid", fid).executeUpdate();
        if (inserted == 1) {
            return new Registration(id, 0, stateVersion);
        }
        return resolveInsertConflict(sessionId, deviceId, fid, stateVersion);
    }

    private Registration resolveInsertConflict(UUID sessionId, UUID deviceId, String fid, long stateVersion) {
        RegistrationRow winner = activeByDevice(deviceId);
        if (winner == null || !sessionId.equals(winner.sessionId())) {
            throw new RegistrationConflict();
        }
        Registration latest = new Registration(winner.id(), winner.version(), stateVersion);
        if (fid.equals(winner.fid())) {
            return latest;
        }
        throw new StaleRegistration(latest);
    }

    private void requireActiveSession(UUID userId, UUID sessionId) {
        if (!activeSession(userId, sessionId, true)) {
            throw new InvalidPushSession();
        }
    }

    private boolean activeSession(UUID userId, UUID sessionId) {
        return activeSession(userId, sessionId, false);
    }

    private boolean activeSession(UUID userId, UUID sessionId, boolean lock) {
        if (sessionId == null) {
            return false;
        }
        String sql = ACTIVE_SESSION;
        if (lock) {
            sql += " FOR UPDATE OF s";
        }
        return !entityManager.createNativeQuery(sql)
                .setParameter("sessionId", sessionId).setParameter("userId", userId)
                .getResultList().isEmpty();
    }

    private RegistrationRow activeByDevice(UUID deviceId) {
        return find("device_id", deviceId);
    }

    private RegistrationRow activeByFid(String fid) {
        return find("recipient_value", fid);
    }

    private RegistrationRow find(String column, Object value) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(ACTIVE_REGISTRATION.formatted(column))
                .setParameter("value", value).getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = rows.getFirst();
        return new RegistrationRow((UUID) row[0], (UUID) row[1], (UUID) row[2], (UUID) row[3],
                (String) row[4], ((Number) row[5]).longValue());
    }

    private void revoke(UUID id) {
        entityManager.createNativeQuery(REVOKE_REGISTRATION).setParameter("id", id).executeUpdate();
        entityManager.createNativeQuery(CANCEL_UNSENT).setParameter("id", id).executeUpdate();
    }

    public record Registration(UUID id, long version, long stateVersion) { }

    private record RegistrationRow(UUID id, UUID userId, UUID sessionId, UUID deviceId, String fid, long version) { }

    private record State(long version, boolean enabled) { }

    public static final class RegistrationConflict extends RuntimeException { }

    public static final class StaleRegistration extends RuntimeException {
        private final Registration current;

        public StaleRegistration(Registration current) {
            this.current = current;
        }

        public Registration current() {
            return current;
        }
    }

    public static final class InvalidPushSession extends RuntimeException { }

    public static final class StalePushState extends RuntimeException {
        private final long stateVersion;
        private final boolean enabled;

        public StalePushState(long stateVersion, boolean enabled) {
            this.stateVersion = stateVersion;
            this.enabled = enabled;
        }

        public long stateVersion() {
            return stateVersion;
        }

        public boolean enabled() {
            return enabled;
        }
    }
}
