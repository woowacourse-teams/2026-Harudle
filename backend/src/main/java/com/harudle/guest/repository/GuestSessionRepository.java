package com.harudle.guest.repository;

import com.harudle.guest.domain.GuestSession;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuestSessionRepository extends JpaRepository<GuestSession, UUID> {

    Optional<GuestSession> findByTokenHash(String tokenHash);

    boolean existsByGuestUserId(UUID guestUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT session FROM GuestSession session WHERE session.tokenHash = :tokenHash")
    Optional<GuestSession> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
