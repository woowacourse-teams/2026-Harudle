package com.harudle.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.admin.repository.AdminUserQueryRepository;
import com.harudle.admin.query.AdminUserPage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
    @DisplayName("검색어 앞뒤 공백을 제거하고 소문자로 정규화한다")
    void normalizesQuery() {
        var pageable = PageRequest.of(0, 20);
        when(adminUserQueryRepository.search("하루", null, TODAY, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        AdminUserPage actual = adminUserQueryService.search("  하루  ", 0, 20);

        assertThat(actual.content()).isEmpty();
        assertThat(actual.totalElements()).isZero();
    }

    @Test
    @DisplayName("UUID 형식 검색어는 사용자 ID 정확 일치 조건으로 전달한다")
    void recognizesExactUserId() {
        when(adminUserQueryRepository.search(
                USER_ID.toString(),
                USER_ID,
                TODAY,
                PageRequest.of(2, 10)
        )).thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 10), 0));

        adminUserQueryService.search(USER_ID.toString().toUpperCase(), 2, 10);

        verify(adminUserQueryRepository).search(
                USER_ID.toString(),
                USER_ID,
                TODAY,
                PageRequest.of(2, 10)
        );
    }
}
