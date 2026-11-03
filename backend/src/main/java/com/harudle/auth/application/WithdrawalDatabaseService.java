package com.harudle.auth.application;

import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.WithdrawalImageRepository;
import com.harudle.auth.infrastructure.WithdrawalImageRepository.ImageTargets;
import com.harudle.auth.infrastructure.WithdrawalModerationRepository;
import com.harudle.auth.infrastructure.WithdrawalReportRepository;
import com.harudle.comment.repository.CommentRepository;
import com.harudle.generation.diary.service.DiaryGenerationStatusQueryService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WithdrawalDatabaseService {

    private final UserRepository users;
    private final OAuthAccountRepository accounts;
    private final DiaryGenerationStatusQueryService generations;
    private final CommentRepository comments;
    private final WithdrawalImageRepository images;
    private final WithdrawalReportRepository reports;
    private final WithdrawalModerationRepository moderation;

    public WithdrawalDatabaseService(UserRepository users, OAuthAccountRepository accounts,
            DiaryGenerationStatusQueryService generations,
            CommentRepository comments, WithdrawalImageRepository images, WithdrawalReportRepository reports,
            WithdrawalModerationRepository moderation) {
        this.users = users;
        this.accounts = accounts;
        this.generations = generations;
        this.comments = comments;
        this.images = images;
        this.reports = reports;
        this.moderation = moderation;
    }

    @Transactional
    public ImageTargets delete(UUID userId) {
        var user = users.findByIdForUpdate(userId).orElseThrow(InvalidCurrentUserException::new);
        if (generations.hasUnfinishedGeneration(userId)) {
            throw new WithdrawalGenerationInProgressException();
        }
        return deleteMember(userId, user);
    }

    @Transactional
    public Optional<ImageTargets> deleteAfterAppleRevocation(UUID userId, String subject, Instant eventTime) {
        var user = users.findByIdForUpdate(userId);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        if (!accounts.existsByUser_IdAndProviderAndProviderSubjectAndCreatedAtBefore(
                userId, OAuthProvider.APPLE, subject, eventTime)) {
            return Optional.empty();
        }
        return Optional.of(deleteMember(userId, user.get()));
    }

    private ImageTargets deleteMember(UUID userId, User user) {
        ImageTargets targets = images.findTargets(userId);
        images.saveCleanupTasks(targets.deletions());
        comments.preserveBlockedVisibility(userId);
        reports.anonymizeTarget(userId);
        moderation.deleteRestrictions(userId);
        images.deleteCleanupTasks(userId);
        users.delete(user);
        users.flush();
        return targets;
    }

    public static final class WithdrawalGenerationInProgressException extends RuntimeException {}
}
