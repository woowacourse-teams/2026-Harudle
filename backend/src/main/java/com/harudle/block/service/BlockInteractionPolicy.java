package com.harudle.block.service;

import com.harudle.block.repository.BlockRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 차단 변경과 접촉 쓰기가 같은 정규화된 회원 쌍을 트랜잭션 종료까지 잠근다. */
@Service
public class BlockInteractionPolicy {
    private final BlockRepository repository;

    public BlockInteractionPolicy(BlockRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPair(UUID first, UUID second) {
        Objects.requireNonNull(first);
        Objects.requireNonNull(second);
        if (!first.equals(second)) {
            repository.lockPair(pairKey(first, second));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockAndIsBlocked(UUID first, UUID second) {
        lockPair(first, second);
        return !first.equals(second) && repository.existsBetween(first, second);
    }

    // 해시 충돌은 다른 쌍도 직렬화할 뿐이며 차단 판정에는 실제 두 UUID를 사용한다.
    static long pairKey(UUID first, UUID second) {
        String left = first.toString();
        String right = second.toString();
        String pair = left.compareTo(right) < 0 ? left + "|" + right : right + "|" + left;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("harudle.block.pair:v1|" + pair).getBytes(StandardCharsets.US_ASCII));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("차단 동시성 정책을 초기화할 수 없습니다.");
        }
    }
}
