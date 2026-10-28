package com.harudle.legal.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TermsAcceptanceRepository {
    // 바깥 Optional은 활성 OAuth 회원 여부다. 안쪽은 전달한 순서에서 처음 일치하는 버전의 수락이다.
    Optional<MemberState> findMember(UUID userId, List<TermsDocumentReference> acceptedTerms);
    void insert(UUID userId, TermsAcceptance acceptance);

    record MemberState(Optional<TermsAcceptance> acceptance) {
    }
}
