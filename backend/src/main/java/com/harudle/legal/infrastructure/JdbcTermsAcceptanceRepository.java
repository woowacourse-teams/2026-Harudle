package com.harudle.legal.infrastructure;

import com.harudle.legal.application.TermsAcceptance;
import com.harudle.legal.application.TermsAcceptanceRepository;
import com.harudle.legal.application.TermsDocumentReference;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcTermsAcceptanceRepository implements TermsAcceptanceRepository {
    private final JdbcTemplate jdbc;

    JdbcTermsAcceptanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<MemberState> findMember(UUID userId, List<TermsDocumentReference> acceptedTerms) {
        if (acceptedTerms.isEmpty()) throw new IllegalArgumentException("인정할 약관 버전은 필수입니다.");
        String values = IntStream.range(0, acceptedTerms.size())
                .mapToObj(index -> "(?, ?, " + index + ")").collect(Collectors.joining(", "));
        List<Object> parameters = new ArrayList<>();
        acceptedTerms.forEach(terms -> {
            parameters.add(terms.version());
            parameters.add(terms.contentHash());
        });
        parameters.add(userId);
        return jdbc.query("""
                WITH accepted_terms (version, content_hash, priority) AS (VALUES %s)
                SELECT a.terms_version, a.terms_content_hash, a.privacy_notice_version,
                       a.privacy_notice_content_hash, a.accepted_at
                FROM users u
                LEFT JOIN LATERAL (
                    SELECT receipt.* FROM user_terms_acceptances receipt
                    JOIN accepted_terms allowed
                      ON receipt.terms_version = allowed.version AND receipt.terms_content_hash = allowed.content_hash
                    WHERE receipt.user_id = u.id
                    ORDER BY allowed.priority
                    LIMIT 1
                ) a ON TRUE
                WHERE u.id = ? AND u.deleted_at IS NULL
                  AND EXISTS (SELECT 1 FROM oauth_accounts o WHERE o.user_id = u.id)
                """.formatted(values), (rs, row) -> {
                    if (rs.getTimestamp("accepted_at") == null) {
                        return new MemberState(Optional.empty());
                    }
                    return new MemberState(Optional.of(new TermsAcceptance(
                            rs.getString("terms_version"), rs.getString("terms_content_hash"),
                            rs.getString("privacy_notice_version"), rs.getString("privacy_notice_content_hash"),
                            rs.getTimestamp("accepted_at").toInstant())));
                }, parameters.toArray()).stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(UUID userId, TermsAcceptance acceptance) {
        jdbc.update("""
                INSERT INTO user_terms_acceptances
                    (user_id, terms_version, terms_content_hash, privacy_notice_version,
                     privacy_notice_content_hash, accepted_at, minimum_age_at_acceptance)
                VALUES (?, ?, ?, ?, ?, ?, 14)
                ON CONFLICT (user_id, terms_version) DO NOTHING
                """, userId, acceptance.termsVersion(), acceptance.termsContentHash(),
                acceptance.privacyNoticeVersion(), acceptance.privacyNoticeContentHash(),
                OffsetDateTime.ofInstant(acceptance.acceptedAt(), ZoneOffset.UTC));
    }
}
