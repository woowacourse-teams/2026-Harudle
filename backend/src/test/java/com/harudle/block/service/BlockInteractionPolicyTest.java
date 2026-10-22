package com.harudle.block.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.block.repository.BlockRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlockInteractionPolicyTest {
    private final BlockRepository repository = mock(BlockRepository.class);
    private final BlockInteractionPolicy policy = new BlockInteractionPolicy(repository);

    @Test
    @DisplayName("회원 순서가 바뀌어도 같은 잠금 키를 쓰고 다른 쌍과는 구분한다")
    void normalizesPairOrder() {
        UUID first = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
        assertThat(BlockInteractionPolicy.pairKey(first, second))
                .isEqualTo(BlockInteractionPolicy.pairKey(second, first))
                .isNotEqualTo(BlockInteractionPolicy.pairKey(first, UUID.randomUUID()));
    }

    @Test
    @DisplayName("잠금을 먼저 얻은 뒤 실제 양방향 차단 여부를 조회한다")
    void readsBlockAfterAcquiringPairLock() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.existsBetween(first, second)).thenReturn(true);
        assertThat(policy.lockAndIsBlocked(first, second)).isTrue();
        var order = inOrder(repository);
        order.verify(repository).lockPair(BlockInteractionPolicy.pairKey(first, second));
        order.verify(repository).existsBetween(first, second);
    }

    @Test
    @DisplayName("본인 피드의 반응은 회원 쌍 잠금이나 차단 조회 없이 유지한다")
    void allowsOwnInteractionWithoutPairLock() {
        UUID actor = UUID.randomUUID();
        assertThat(policy.lockAndIsBlocked(actor, actor)).isFalse();
        verifyNoInteractions(repository);
    }
}
