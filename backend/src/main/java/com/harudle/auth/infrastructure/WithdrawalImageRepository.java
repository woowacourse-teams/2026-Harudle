package com.harudle.auth.infrastructure;

import com.harudle.auth.domain.WithdrawalImageDeletion;
import com.harudle.auth.domain.WithdrawalImageDeletion.Storage;
import com.harudle.generation.diary.domain.ImageVariantKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class WithdrawalImageRepository {

    private final JdbcTemplate jdbcTemplate;

    public WithdrawalImageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public ImageTargets findTargets(UUID userId) {
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");
        List<String> diaryImages = jdbcTemplate.queryForList("""
                SELECT DISTINCT generation.image_object_key
                FROM diary_generations generation
                JOIN diaries diary ON diary.id = generation.diary_id
                WHERE diary.user_id = ? AND generation.image_object_key IS NOT NULL
                """, String.class, userId);
        List<String> profileImages = jdbcTemplate.queryForList("""
                SELECT profile_image_object_key FROM users
                WHERE id = ? AND profile_image_object_key IS NOT NULL
                UNION
                SELECT object_key FROM profile_image_cleanup_tasks WHERE user_id = ?
                """, String.class, userId, userId);
        return new ImageTargets(diaryImages, profileImages);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveCleanupTasks(List<WithdrawalImageDeletion> tasks) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO withdrawal_image_cleanup_tasks (storage, object_key)
                VALUES (?, ?) ON CONFLICT (storage, object_key) DO NOTHING
                """, tasks.stream().map(task -> new Object[] { task.storage().name(), task.objectKey() }).toList());
    }

    public void removeCleanupTask(WithdrawalImageDeletion task) {
        jdbcTemplate.update("DELETE FROM withdrawal_image_cleanup_tasks WHERE storage = ? AND object_key = ?",
                task.storage().name(), task.objectKey());
    }

    public void deleteCleanupTasks(UUID userId) {
        jdbcTemplate.update("DELETE FROM profile_image_cleanup_tasks WHERE user_id = ?", userId);
    }

    public record ImageTargets(List<String> diaryImages, List<String> profileImages) {
        public ImageTargets {
            diaryImages = List.copyOf(diaryImages);
            profileImages = List.copyOf(profileImages);
        }

        public List<WithdrawalImageDeletion> deletions() {
            List<WithdrawalImageDeletion> tasks = new ArrayList<>();
            for (String key : diaryImages) {
                tasks.add(new WithdrawalImageDeletion(Storage.S3_DIARY, key));
                ImageVariantKeys.originalImageKeyCandidatesForBackup(key).forEach(original ->
                        tasks.add(new WithdrawalImageDeletion(Storage.R2, original)));
            }
            profileImages.forEach(key -> tasks.add(new WithdrawalImageDeletion(Storage.S3_PROFILE, key)));
            return tasks.stream().distinct().toList();
        }

        @Override
        public String toString() {
            return "ImageTargets[diaryCount=" + diaryImages.size() + ", profileCount=" + profileImages.size() + "]";
        }
    }
}
