package com.harudle.legal.infrastructure;

import com.harudle.legal.application.TermsAcceptance;
import com.harudle.legal.application.TermsAcceptanceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
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
    public Optional<MemberState> findMember(UUID userId, String version, String contentHash) {
        return jdbc.query("""
                SELECT a.terms_version, a.terms_content_hash, a.privacy_notice_version,
                       a.privacy_notice_content_hash, a.accepted_at
                FROM users u
                LEFT JOIN user_terms_acceptances a
                  ON a.user_id = u.id AND a.terms_version = ? AND a.terms_content_hash = ?
                WHERE u.id = ? AND u.deleted_at IS NULL
                  AND EXISTS (SELECT 1 FROM oauth_accounts o WHERE o.user_id = u.id)
                """, (rs, row) -> {
                    if (rs.getTimestamp("accepted_at") == null) {
                        return new MemberState(Optional.empty());
                    }
                    return new MemberState(Optional.of(new TermsAcceptance(
                            rs.getString("terms_version"), rs.getString("terms_content_hash"),
                            rs.getString("privacy_notice_version"), rs.getString("privacy_notice_content_hash"),
                            rs.getTimestamp("accepted_at").toInstant())));
                }, version, contentHash, userId).stream().findFirst();
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
