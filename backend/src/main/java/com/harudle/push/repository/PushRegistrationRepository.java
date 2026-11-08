package com.harudle.push.repository;

import java.util.UUID;

public interface PushRegistrationRepository {

    boolean activeSession(UUID userId, UUID sessionId, boolean lock);

    State state(UUID userId, UUID sessionId, UUID deviceId);

    void advanceState(UUID sessionId, UUID deviceId, boolean enabled);

    void disableOldState(UUID sessionId, UUID deviceId);

    RegistrationRow activeByDevice(UUID deviceId);

    RegistrationRow activeByFid(String fid);

    void updateFid(UUID id, long version, String fid);

    boolean insert(UUID id, UUID userId, UUID sessionId, UUID deviceId, String fid);

    void revoke(UUID id);

    record RegistrationRow(UUID id, UUID userId, UUID sessionId, UUID deviceId, String fid, long version) { }

    record State(long version, boolean enabled) { }
}
