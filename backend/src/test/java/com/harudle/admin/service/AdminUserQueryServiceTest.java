package com.harudle.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.admin.repository.AdminUserPage;
import com.harudle.admin.repository.AdminUserQueryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminUserQueryServiceTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 25);

    @Mock
    private AdminUserQueryRepository adminUserQueryRepository;

    private AdminUserQueryService adminUserQueryService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-25T10:00:00Z"),
                ZoneId.of("Asia/Seoul")
        );
        adminUserQueryService = new AdminUserQueryService(adminUserQueryRepository, clock);
    }

    @Test
    @DisplayName("검색어를 정리하여 이름과 이메일 검색에 사용한다")
    void normalizesQuery() {
        AdminUserPage expected = new AdminUserPage(List.of(), 0);
        when(adminUserQueryRepository.search("하루", Optional.empty(), TODAY, 0, 20))
                .thenReturn(expected);

        AdminUserPage actual = adminUserQueryService.search("  하루  ", 0, 20);

        assertThat(actual).isSameAs(expected);
    }

    @Test
    @DisplayName("정규 UUID 검색어는 사용자 ID 정확 일치 검색에도 사용한다")
    void recognizesExactUserId() {
        when(adminUserQueryRepository.search(
                USER_ID.toString(),
                Optional.of(USER_ID),
                TODAY,
                2,
                10
        )).thenReturn(new AdminUserPage(List.of(), 0));

        adminUserQueryService.search(USER_ID.toString().toUpperCase(), 2, 10);

        verify(adminUserQueryRepository).search(
                USER_ID.toString(),
                Optional.of(USER_ID),
                TODAY,
                2,
                10
        );
    }
}
