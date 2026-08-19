package com.harudle.guest.repository;

import com.harudle.guest.domain.GuestSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestSessionRepository extends JpaRepository<GuestSession, UUID> {

    Optional<GuestSession> findByTokenHash(String tokenHash);
}
