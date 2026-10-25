package com.harudle.legal.application;

import java.util.Optional;
import java.util.UUID;

public interface TermsAcceptanceRepository {
    // 바깥 Optional은 활성 OAuth 회원 여부, 안쪽 Optional은 현재 버전 수락 여부다.
    Optional<MemberState> findMember(UUID userId, String termsVersion, String termsContentHash);
    void insert(UUID userId, TermsAcceptance acceptance);

    record MemberState(Optional<TermsAcceptance> acceptance) {
    }
}
