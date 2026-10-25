package com.harudle.legal.application;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.legal.domain.LegalDocument;
import com.harudle.legal.domain.LegalDocumentType;
import com.harudle.legal.infrastructure.LegalProperties;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TermsAcceptanceService {
    private final LegalDocumentCatalog catalog;
    private final TermsAcceptanceRepository acceptances;
    private final LegalProperties properties;
    private final Clock clock;

    public TermsAcceptanceService(LegalDocumentCatalog catalog, TermsAcceptanceRepository acceptances,
            LegalProperties properties, @Qualifier("authClock") Clock clock) {
        this.catalog = catalog;
        this.acceptances = acceptances;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public TermsAcceptanceStatus status(UUID userId) {
        return response(member(userId, catalog.acceptedTerms()).acceptance());
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public void requireAccepted(UUID userId) {
        if (properties.requireTermsAcceptance() && status(userId).requiresAcceptance()) {
            throw new TermsAcceptanceRequiredException();
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TermsAcceptanceStatus accept(UUID userId, TermsAcceptanceCommand command) {
        Objects.requireNonNull(command, "수락 내용은 필수입니다.");
        LegalDocument terms = catalog.current(LegalDocumentType.TERMS);
        // 과거 수락을 접근에 인정하더라도, 현재 문서를 명시적으로 수락한 기록은 별도로 남긴다.
        var currentTerms = List.of(TermsDocumentReference.from(terms));
        var state = member(userId, currentTerms);
        if (!command.accepted() || !command.ageConfirmed()) {
            throw new InvalidTermsAcceptanceException();
        }
        LegalDocument privacy = catalog.current(LegalDocumentType.PRIVACY);
        if (!terms.version().equals(command.termsVersion())
                || !terms.contentHash().equals(command.termsContentHash())
                || !privacy.version().equals(command.privacyNoticeVersion())
                || !privacy.contentHash().equals(command.privacyNoticeContentHash())) {
            throw new LegalDocumentChangedException();
        }
        if (state.acceptance().isEmpty()) {
            acceptances.insert(userId, new TermsAcceptance(terms.version(), terms.contentHash(),
                    privacy.version(), privacy.contentHash(), clock.instant()));
        }
        // 동시 요청이 먼저 저장했다면 READ COMMITTED의 후속 조회로 최초 수락 시각을 반환한다.
        var saved = member(userId, currentTerms).acceptance().orElseThrow(
                () -> new IllegalStateException("수락 기록을 확인할 수 없습니다."));
        return response(Optional.of(saved));
    }

    private TermsAcceptanceRepository.MemberState member(UUID userId, List<TermsDocumentReference> acceptedTerms) {
        if (userId == null) {
            throw new AuthenticationRequiredException();
        }
        return acceptances.findMember(userId, acceptedTerms)
                .orElseThrow(AuthenticationRequiredException::new);
    }

    private TermsAcceptanceStatus response(Optional<TermsAcceptance> acceptance) {
        LegalDocument terms = catalog.current(LegalDocumentType.TERMS);
        LegalDocument privacy = catalog.current(LegalDocumentType.PRIVACY);
        return new TermsAcceptanceStatus(properties.requireTermsAcceptance(),
                properties.requireTermsAcceptance() && acceptance.isEmpty(),
                new TermsAcceptanceStatus.DocumentReference(terms.version(), terms.contentHash()),
                new TermsAcceptanceStatus.DocumentReference(privacy.version(), privacy.contentHash()),
                acceptance.map(TermsAcceptance::acceptedAt).orElse(null), 14);
    }
}
