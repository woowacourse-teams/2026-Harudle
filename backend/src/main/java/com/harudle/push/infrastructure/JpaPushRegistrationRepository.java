package com.harudle.push.infrastructure;

import com.harudle.push.repository.PushRegistrationRepository;
import com.harudle.push.repository.PushRegistrationRepository.RegistrationRow;
import com.harudle.push.repository.PushRegistrationRepository.State;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaPushRegistrationRepository implements PushRegistrationRepository {

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

    JpaPushRegistrationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public boolean activeSession(UUID userId, UUID sessionId, boolean lock) {
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

    @Override
    public State state(UUID userId, UUID sessionId, UUID deviceId) {
        entityManager.createNativeQuery(INSERT_STATE).setParameter("sessionId", sessionId)
                .setParameter("userId", userId).setParameter("deviceId", deviceId).executeUpdate();
        Object[] row = (Object[]) entityManager.createNativeQuery(SELECT_STATE)
                .setParameter("sessionId", sessionId).setParameter("deviceId", deviceId).getSingleResult();
        return new State(((Number) row[0]).longValue(), (Boolean) row[1]);
    }

    @Override
    public void advanceState(UUID sessionId, UUID deviceId, boolean enabled) {
        entityManager.createNativeQuery(UPDATE_STATE).setParameter("enabled", enabled)
                .setParameter("sessionId", sessionId).setParameter("deviceId", deviceId).executeUpdate();
    }

    @Override
    public void disableOldState(UUID sessionId, UUID deviceId) {
        entityManager.createNativeQuery(DISABLE_OLD_STATE)
                .setParameter("sessionId", sessionId).setParameter("deviceId", deviceId).executeUpdate();
    }

    @Override
    public RegistrationRow activeByDevice(UUID deviceId) {
        return find("device_id", deviceId);
    }

    @Override
    public RegistrationRow activeByFid(String fid) {
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

    @Override
    public void updateFid(UUID id, long version, String fid) {
        entityManager.createNativeQuery(UPDATE_FID)
                .setParameter("fid", fid).setParameter("id", id)
                .setParameter("version", version).executeUpdate();
    }

    @Override
    public boolean insert(UUID id, UUID userId, UUID sessionId, UUID deviceId, String fid) {
        return entityManager.createNativeQuery(INSERT_REGISTRATION)
                .setParameter("id", id).setParameter("userId", userId).setParameter("sessionId", sessionId)
                .setParameter("deviceId", deviceId).setParameter("fid", fid).executeUpdate() == 1;
    }

    @Override
    public void revoke(UUID id) {
        entityManager.createNativeQuery(REVOKE_REGISTRATION).setParameter("id", id).executeUpdate();
        entityManager.createNativeQuery(CANCEL_UNSENT).setParameter("id", id).executeUpdate();
    }

}
