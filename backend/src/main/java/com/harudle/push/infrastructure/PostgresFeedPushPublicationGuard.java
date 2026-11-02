package com.harudle.push.infrastructure;

import com.harudle.push.service.port.FeedPushPublicationGuard;
import jakarta.persistence.EntityManager;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL의 두 int 키 공간은 회원 쌍에 쓰는 bigint 키 공간과 겹치지 않는다.
 * 공유 잠금은 게시끼리 병행하게 하고, 배타 잠금은 차단이 미커밋 예약을 놓치지 않게 한다.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class PostgresFeedPushPublicationGuard implements FeedPushPublicationGuard {
    private static final String SHARED = "SELECT 1 FROM pg_advisory_xact_lock_shared(:high, :low)";
    private static final String EXCLUSIVE = "SELECT 1 FROM pg_advisory_xact_lock(:high, :low)";
    private final EntityManager entityManager;

    public PostgresFeedPushPublicationGuard(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void lockForPublication(UUID authorId) {
        lock(SHARED, key(authorId));
    }

    @Override
    public void lockForBlock(UUID firstAuthorId, UUID secondAuthorId) {
        // UUID 순서와 해시 키 순서는 다르므로 실제 DB 키의 공통 순서로 잠근다.
        List.of(key(firstAuthorId), key(secondAuthorId)).stream().distinct().sorted()
                .forEach(value -> lock(EXCLUSIVE, value));
    }

    private void lock(String sql, Key key) {
        entityManager.createNativeQuery(sql).setParameter("high", key.high()).setParameter("low", key.low())
                .getSingleResult();
    }

    private static Key key(UUID authorId) {
        Objects.requireNonNull(authorId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("harudle.push.publication:v1|" + authorId).getBytes(StandardCharsets.US_ASCII));
            ByteBuffer bytes = ByteBuffer.wrap(digest);
            return new Key(bytes.getInt(), bytes.getInt());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("푸시 게시 잠금 키를 계산할 수 없습니다.");
        }
    }

    // 해시 충돌은 무관한 작성자의 예약도 잠시 기다리게 할 뿐, 차단 판정은 원래 UUID로 수행한다.
    private record Key(int high, int low) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int highOrder = Integer.compare(high, other.high);
            return highOrder == 0 ? Integer.compare(low, other.low) : highOrder;
        }
    }
}
