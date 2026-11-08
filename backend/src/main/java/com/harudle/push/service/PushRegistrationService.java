package com.harudle.push.service;

import com.harudle.push.repository.PushRegistrationRepository;
import com.harudle.push.repository.PushRegistrationRepository.RegistrationRow;
import com.harudle.push.repository.PushRegistrationRepository.State;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PushRegistrationService {

    private final PushRegistrationRepository repository;

    public PushRegistrationService(PushRegistrationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Registration register(UUID userId, UUID sessionId, UUID deviceId, String fid,
                                 UUID expectedId, Long expectedVersion, long expectedStateVersion) {
        validate(userId, sessionId, deviceId, fid, expectedId, expectedVersion, expectedStateVersion);
        requireActiveSession(userId, sessionId);
        State state = repository.state(userId, sessionId, deviceId);
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
        RegistrationRow current = repository.activeByDevice(deviceId);
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
        State state = repository.state(userId, sessionId, deviceId);
        if (!state.enabled() && state.version() > 0 && repeatedVersion(state, expectedStateVersion)) {
            return state.version();
        }
        requireStateVersion(state, expectedStateVersion);
        revokeOwnedRegistration(userId, sessionId, deviceId);
        return advanceState(sessionId, deviceId, false, state);
    }

    private void revokeOwnedRegistration(UUID userId, UUID sessionId, UUID deviceId) {
        RegistrationRow current = repository.activeByDevice(deviceId);
        if (current != null && userId.equals(current.userId()) && sessionId.equals(current.sessionId())) {
            repository.revoke(current.id());
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
        validateRevoke(userId, sessionId, deviceId, expectedStateVersion);
        if (fid == null || fid.isBlank()) {
            throw new IllegalArgumentException("FID는 필수입니다.");
        }
        if ((expectedId == null) != (expectedVersion == null)
                || expectedVersion != null && expectedVersion < 0) {
            throw new IllegalArgumentException("등록 ID와 버전은 함께 전달해야 합니다.");
        }
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
        repository.advanceState(sessionId, deviceId, enabled);
        return state.version() + 1;
    }

    private void releaseFidOwner(String fid, UUID sessionId, UUID currentId) {
        RegistrationRow owner = repository.activeByFid(fid);
        if (owner == null || owner.id().equals(currentId)) {
            return;
        }
        release(owner, sessionId);
    }

    private void release(RegistrationRow registration, UUID sessionId) {
        if (!sessionId.equals(registration.sessionId())
                && repository.activeSession(registration.userId(), registration.sessionId(), false)) {
            throw new RegistrationConflict();
        }
        repository.revoke(registration.id());
        if (registration.sessionId() != null) {
            repository.disableOldState(registration.sessionId(), registration.deviceId());
        }
    }

    private Registration updateFid(Registration current, String fid) {
        repository.updateFid(current.id(), current.version(), fid);
        return new Registration(current.id(), current.version() + 1, 0);
    }

    private Registration create(UUID userId, UUID sessionId, UUID deviceId, String fid, long stateVersion) {
        UUID id = UUID.randomUUID();
        if (repository.insert(id, userId, sessionId, deviceId, fid)) {
            return new Registration(id, 0, stateVersion);
        }
        return resolveInsertConflict(sessionId, deviceId, fid, stateVersion);
    }

    private Registration resolveInsertConflict(UUID sessionId, UUID deviceId, String fid, long stateVersion) {
        RegistrationRow winner = repository.activeByDevice(deviceId);
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
        if (!repository.activeSession(userId, sessionId, true)) {
            throw new InvalidPushSession();
        }
    }

    public record Registration(UUID id, long version, long stateVersion) { }

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
