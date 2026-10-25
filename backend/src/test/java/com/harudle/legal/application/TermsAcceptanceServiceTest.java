package com.harudle.legal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.legal.domain.LegalDocument;
import com.harudle.legal.domain.LegalDocumentType;
import com.harudle.legal.domain.LegalSection;
import com.harudle.legal.infrastructure.LegalProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;

class TermsAcceptanceServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-25T06:00:00Z");
    private static final LegalDocument TERMS = document(LegalDocumentType.TERMS, "2026-10-22", "a".repeat(64));
    private static final LegalDocument PRIVACY = document(LegalDocumentType.PRIVACY, "2026-08-24", "b".repeat(64));
    private final LegalDocumentCatalog catalog = mock(LegalDocumentCatalog.class);
    private final Acceptances acceptances = new Acceptances();
    private TermsAcceptanceService service;

    @BeforeEach
    void setUp() {
        when(catalog.current(LegalDocumentType.TERMS)).thenReturn(TERMS);
        when(catalog.current(LegalDocumentType.PRIVACY)).thenReturn(PRIVACY);
        when(catalog.acceptedTerms()).thenReturn(List.of(TermsDocumentReference.from(TERMS)));
        service = service(true, NOW);
    }

    @Test
    @DisplayName("표시한 현재 문서를 명시적으로 수락하면 서버 시각을 저장하고 같은 버전 재수락은 최초 시각을 유지한다")
    void recordsCurrentDocumentsAndPreservesFirstAcceptanceTime() {
        TermsAcceptanceStatus first = service.accept(USER_ID, currentCommand());
        TermsAcceptanceStatus repeated = service(true, NOW.plusSeconds(60)).accept(USER_ID, currentCommand());

        assertThat(first.enforcementEnabled()).isTrue();
        assertThat(first.requiresAcceptance()).isFalse();
        assertThat(first.acceptedAt()).isEqualTo(NOW);
        assertThat(first.minimumAge()).isEqualTo(14);
        assertThat(first.terms()).isEqualTo(new TermsAcceptanceStatus.DocumentReference(
                TERMS.version(), TERMS.contentHash()));
        assertThat(first.privacyNotice()).isEqualTo(new TermsAcceptanceStatus.DocumentReference(
                PRIVACY.version(), PRIVACY.contentHash()));
        assertThat(repeated).isEqualTo(first);
        assertThat(acceptances.inserts).containsExactly(new TermsAcceptance(
                TERMS.version(), TERMS.contentHash(), PRIVACY.version(), PRIVACY.contentHash(), NOW));
        assertThat(service.status(USER_ID)).isEqualTo(first);
    }

    @Test
    @DisplayName("동시 제출의 다른 요청이 먼저 저장했다면 후속 조회한 승자의 최초 수락 시각을 반환한다")
    void returnsTheFirstRecordSavedByAConcurrentSubmission() {
        TermsAcceptance winner = new TermsAcceptance(TERMS.version(), TERMS.contentHash(),
                PRIVACY.version(), PRIVACY.contentHash(), NOW.minusSeconds(1));
        acceptances.concurrentWinner = winner;

        TermsAcceptanceStatus result = service.accept(USER_ID, currentCommand());

        assertThat(result.acceptedAt()).isEqualTo(winner.acceptedAt());
        assertThat(acceptances.saved).contains(winner);
        assertThat(acceptances.reads).isEqualTo(2);
        assertThat(acceptances.inserts).hasSize(1);
    }

    @Test
    @DisplayName("공지성 개정은 이전 수락으로 접근을 유지하면서 현재 열람 문서와 실제 수락 시각을 반환한다")
    void preservesAccessForAnExplicitlyRetainedAcceptance() {
        var previous = document(LegalDocumentType.TERMS, "2026-10-21", "c".repeat(64));
        var receipt = receipt(previous, NOW.minusSeconds(60));
        acceptances.saved.add(receipt);
        when(catalog.acceptedTerms()).thenReturn(List.of(
                TermsDocumentReference.from(TERMS), TermsDocumentReference.from(previous)));

        var status = service.status(USER_ID);

        assertThat(status.requiresAcceptance()).isFalse();
        assertThat(status.acceptedAt()).isEqualTo(receipt.acceptedAt());
        assertThat(status.terms().version()).isEqualTo(TERMS.version());
        assertThatCode(() -> service.requireAccepted(USER_ID)).doesNotThrowAnyException();
        assertThat(acceptances.inserts).isEmpty();
    }

    @Test
    @DisplayName("이전 수락이 유효해도 현재 버전을 직접 수락하면 버전별로 기록하고 현재 수락을 우선 반환한다")
    void recordsTheCurrentVersionSeparatelyAndPreservesEachFirstTimestamp() {
        var previous = document(LegalDocumentType.TERMS, "2026-10-21", "c".repeat(64));
        // 시계 차이로 이전 기록의 시각이 더 늦어도 현재 버전을 우선한다.
        var previousReceipt = receipt(previous, NOW.plusSeconds(120));
        acceptances.saved.add(previousReceipt);
        when(catalog.acceptedTerms()).thenReturn(List.of(
                TermsDocumentReference.from(TERMS), TermsDocumentReference.from(previous)));

        var first = service.accept(USER_ID, currentCommand());
        var repeated = service(true, NOW.plusSeconds(60)).accept(USER_ID, currentCommand());

        assertThat(first.acceptedAt()).isEqualTo(NOW);
        assertThat(repeated).isEqualTo(first);
        assertThat(service.status(USER_ID)).isEqualTo(first);
        assertThat(acceptances.saved).containsExactly(previousReceipt, receipt(TERMS, NOW));
        assertThat(acceptances.inserts).containsExactly(receipt(TERMS, NOW));
    }

    @Test
    @DisplayName("중요 개정으로 이전 버전을 인정 목록에서 제외하면 기존 기록은 보존하고 재수락을 요구한다")
    void requiresAcceptanceWhenThePreviousVersionIsNoLongerAllowed() {
        var previousReceipt = receipt(document(LegalDocumentType.TERMS, "2026-10-21", "c".repeat(64)),
                NOW.minusSeconds(60));
        acceptances.saved.add(previousReceipt);

        assertThat(service.status(USER_ID).requiresAcceptance()).isTrue();
        assertThat(service.status(USER_ID).acceptedAt()).isNull();
        assertThatThrownBy(() -> service.requireAccepted(USER_ID)).isInstanceOf(TermsAcceptanceRequiredException.class);

        service.accept(USER_ID, currentCommand());

        assertThatCode(() -> service.requireAccepted(USER_ID)).doesNotThrowAnyException();
        assertThat(acceptances.saved).containsExactly(previousReceipt, receipt(TERMS, NOW));
    }

    @Test
    @DisplayName("이전 수락을 접근에 인정해도 이전 문서를 새로 제출하면 현재 문서 재확인을 요구한다")
    void neverAcceptsAnOldSubmissionEvenWhenItsPreviousReceiptWouldBeAllowed() {
        var previous = document(LegalDocumentType.TERMS, "2026-10-21", "c".repeat(64));
        when(catalog.acceptedTerms()).thenReturn(List.of(
                TermsDocumentReference.from(TERMS), TermsDocumentReference.from(previous)));
        var command = new TermsAcceptanceCommand(previous.version(), previous.contentHash(),
                PRIVACY.version(), PRIVACY.contentHash(), true, true);

        assertThatThrownBy(() -> service.accept(USER_ID, command)).isInstanceOf(LegalDocumentChangedException.class);
        assertThat(acceptances.saved).isEmpty();
    }

    @Test
    @DisplayName("개인정보 고지만 개정하면 기존 약관 수락과 당시 고지 기록은 유지하고 현재 고지를 안내한다")
    void privacyNoticeChangesDoNotInvalidateTermsOrRewriteTheOriginalNotice() {
        var receipt = receipt(TERMS, NOW.minusSeconds(60));
        acceptances.saved.add(receipt);
        var privacy = document(LegalDocumentType.PRIVACY, "2026-10-25", "d".repeat(64));
        when(catalog.current(LegalDocumentType.PRIVACY)).thenReturn(privacy);

        var status = service.status(USER_ID);

        assertThat(status.requiresAcceptance()).isFalse();
        assertThat(status.acceptedAt()).isEqualTo(receipt.acceptedAt());
        assertThat(status.privacyNotice().version()).isEqualTo(privacy.version());
        assertThat(acceptances.saved).containsExactly(receipt);
        assertThat(acceptances.inserts).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("changedDocuments")
    @DisplayName("약관이나 개인정보 고지문의 버전·본문 식별값이 현재 문서와 다르면 수락을 저장하지 않는다")
    void rejectsChangedOrForgedDocumentReferences(TermsAcceptanceCommand command) {
        assertThatThrownBy(() -> service.accept(USER_ID, command)).isInstanceOf(LegalDocumentChangedException.class);
        assertThat(acceptances.inserts).isEmpty();
        assertThat(acceptances.saved).isEmpty();
    }

    @Test
    @DisplayName("동의하지 않았다는 요청은 문서 참조가 정확해도 기록하지 않는다")
    void rejectsAnExplicitlyUnacceptedSubmission() {
        var command = new TermsAcceptanceCommand(TERMS.version(), TERMS.contentHash(),
                PRIVACY.version(), PRIVACY.contentHash(), false, true);

        assertThatThrownBy(() -> service.accept(USER_ID, command)).isInstanceOf(InvalidTermsAcceptanceException.class);
        assertThat(acceptances.inserts).isEmpty();
    }

    @Test
    @DisplayName("약관에 동의해도 만 14세 이상이라는 자가 확인이 없으면 수락을 기록하지 않는다")
    void rejectsAcceptanceWithoutTheMinimumAgeDeclaration() {
        var command = new TermsAcceptanceCommand(TERMS.version(), TERMS.contentHash(),
                PRIVACY.version(), PRIVACY.contentHash(), true, false);

        assertThatThrownBy(() -> service.accept(USER_ID, command)).isInstanceOf(InvalidTermsAcceptanceException.class);
        assertThat(acceptances.inserts).isEmpty();
        assertThat(acceptances.saved).isEmpty();
    }

    @Test
    @DisplayName("활성 OAuth 회원이 아니면 상태 조회와 수락 및 필수 동의 검사를 인증 오류로 거절한다")
    void rejectsInactiveOrUnlinkedMembership() {
        acceptances.activeMember = false;

        assertThatThrownBy(() -> service.status(USER_ID)).isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> service.accept(USER_ID, currentCommand()))
                .isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> service.requireAccepted(USER_ID)).isInstanceOf(AuthenticationRequiredException.class);
        assertThat(acceptances.inserts).isEmpty();
    }

    @Test
    @DisplayName("회원 ID가 없으면 저장소를 조회하거나 수락을 기록하지 않는다")
    void rejectsMissingAuthenticationBeforeReadingTheRepository() {
        assertThatThrownBy(() -> service.status(null)).isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> service.accept(null, currentCommand()))
                .isInstanceOf(AuthenticationRequiredException.class);
        assertThat(acceptances.reads).isZero();
        assertThat(acceptances.inserts).isEmpty();
    }

    @Test
    @DisplayName("필수 적용 중 현재 약관 수락이 없으면 거절하고 수락 후에는 같은 회원을 허용한다")
    void requiresCurrentAcceptanceUntilTheMemberAccepts() {
        TermsAcceptanceStatus initial = service.status(USER_ID);
        assertThat(initial.requiresAcceptance()).isTrue();
        assertThat(initial.acceptedAt()).isNull();
        assertThatThrownBy(() -> service.requireAccepted(USER_ID)).isInstanceOf(TermsAcceptanceRequiredException.class);

        service.accept(USER_ID, currentCommand());

        assertThatCode(() -> service.requireAccepted(USER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("필수 적용이 꺼져 있으면 미수락 상태도 차단하지 않고 일반 요청 검사에서 저장소를 조회하지 않는다")
    void keepsExistingAccessWhenEnforcementIsDisabled() {
        TermsAcceptanceService disabled = service(false, NOW);
        TermsAcceptanceStatus status = disabled.status(USER_ID);
        assertThat(status.enforcementEnabled()).isFalse();
        assertThat(status.minimumAge()).isEqualTo(14);
        assertThat(status.requiresAcceptance()).isFalse();
        assertThat(status.acceptedAt()).isNull();

        int readsBeforeGuard = acceptances.reads;
        acceptances.unavailable = true;
        assertThatCode(() -> disabled.requireAccepted(USER_ID)).doesNotThrowAnyException();
        assertThat(acceptances.reads).isEqualTo(readsBeforeGuard);
    }

    @Test
    @DisplayName("저장소 장애를 미수락이나 정상 상태로 바꾸지 않고 그대로 전파한다")
    void propagatesStorageFailureWithoutWritingAnAcceptance() {
        acceptances.unavailable = true;

        assertThatThrownBy(() -> service.status(USER_ID)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> service.accept(USER_ID, currentCommand()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(() -> service.requireAccepted(USER_ID)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(acceptances.inserts).isEmpty();
    }

    private TermsAcceptanceService service(boolean enforced, Instant now) {
        return new TermsAcceptanceService(catalog, acceptances, new LegalProperties(enforced),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private static TermsAcceptanceCommand currentCommand() {
        return new TermsAcceptanceCommand(TERMS.version(), TERMS.contentHash(),
                PRIVACY.version(), PRIVACY.contentHash(), true, true);
    }

    private static TermsAcceptance receipt(LegalDocument terms, Instant time) {
        return new TermsAcceptance(terms.version(), terms.contentHash(), PRIVACY.version(), PRIVACY.contentHash(), time);
    }

    private static Stream<TermsAcceptanceCommand> changedDocuments() {
        return Stream.of(
                new TermsAcceptanceCommand("2026-10-21", TERMS.contentHash(),
                        PRIVACY.version(), PRIVACY.contentHash(), true, true),
                new TermsAcceptanceCommand(TERMS.version(), "c".repeat(64),
                        PRIVACY.version(), PRIVACY.contentHash(), true, true),
                new TermsAcceptanceCommand(TERMS.version(), TERMS.contentHash(),
                        "2026-08-23", PRIVACY.contentHash(), true, true),
                new TermsAcceptanceCommand(TERMS.version(), TERMS.contentHash(),
                        PRIVACY.version(), "d".repeat(64), true, true));
    }

    private static LegalDocument document(LegalDocumentType type, String version, String hash) {
        return new LegalDocument(type, version, type.name(), LocalDate.parse(version), hash,
                URI.create("https://harudle.com/legal/" + type.path()),
                List.of(new LegalSection("이용 안내", List.of("테스트 고정 문서"))));
    }

    private static class Acceptances implements TermsAcceptanceRepository {
        private boolean activeMember = true;
        private boolean unavailable;
        private int reads;
        private final List<TermsAcceptance> saved = new ArrayList<>();
        private final List<TermsAcceptance> inserts = new ArrayList<>();
        private TermsAcceptance concurrentWinner;

        @Override
        public Optional<MemberState> findMember(UUID userId, List<TermsDocumentReference> acceptedTerms) {
            reads++;
            if (unavailable) throw new DataAccessResourceFailureException("test unavailable");
            if (!activeMember) return Optional.empty();
            return Optional.of(new MemberState(acceptedTerms.stream()
                    .flatMap(terms -> saved.stream().filter(terms::matches)).findFirst()));
        }

        @Override
        public void insert(UUID userId, TermsAcceptance acceptance) {
            inserts.add(acceptance);
            if (saved.stream().noneMatch(receipt -> receipt.termsVersion().equals(acceptance.termsVersion()))) {
                saved.add(concurrentWinner == null ? acceptance : concurrentWinner);
            }
        }
    }
}
