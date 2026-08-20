package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.generation.domain.GenerationStatus;
import com.harudle.guest.application.GuestSessionResolver;
import com.harudle.guest.domain.GuestSession;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GuestDiaryQueryServiceTest {

    private static final UUID GUEST_USER_ID = UUID.fromString("a321bb09-816a-4941-906c-07b9c60db382");
    private static final UUID DIARY_ID = UUID.fromString("593363cb-1dc3-46bc-a858-5926f7601ca9");
    private static final UUID ANOTHER_DIARY_ID = UUID.fromString("5b751eed-6b64-4e86-b9a6-af9e90b9a03a");
    private static final UUID GENERATION_ID = UUID.fromString("17ac16ef-c45a-40bb-92ea-aed37659ef1c");
    private static final String RAW_TOKEN = "guest-session-token";
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final Instant CREATED_AT = Instant.parse("2026-08-19T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-19T00:00:00Z");

    @Mock
    private GuestSessionResolver guestSessionResolver;

    @Mock
    private DiaryQueryService diaryQueryService;

    private GuestDiaryQueryService guestDiaryQueryService;

    @BeforeEach
    void setUp() {
        guestDiaryQueryService = new GuestDiaryQueryService(
                guestSessionResolver,
                diaryQueryService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("게스트 세션에 연결된 일기 결과를 조회한다")
    void getGuestDiaryDetail() {
        GuestSession session = createUsedSession();
        DiaryDetailResult detailResult = createDetailResult();
        when(guestSessionResolver.resolve(RAW_TOKEN, NOW)).thenReturn(session);
        when(diaryQueryService.getDetail(GUEST_USER_ID, DIARY_ID)).thenReturn(detailResult);

        DiaryDetailResult result = guestDiaryQueryService.getDetail(RAW_TOKEN, DIARY_ID);

        assertThat(result).isSameAs(detailResult);
        verify(diaryQueryService).getDetail(GUEST_USER_ID, DIARY_ID);
    }

    @Test
    @DisplayName("게스트 세션에 연결되지 않은 일기는 존재를 노출하지 않는다")
    void rejectUnlinkedGuestDiary() {
        GuestSession session = createUsedSession();
        when(guestSessionResolver.resolve(RAW_TOKEN, NOW)).thenReturn(session);

        assertThatThrownBy(() -> guestDiaryQueryService.getDetail(RAW_TOKEN, ANOTHER_DIARY_ID))
                .isInstanceOf(DiaryNotFoundException.class);

        verify(diaryQueryService, never()).getDetail(GUEST_USER_ID, ANOTHER_DIARY_ID);
    }

    private GuestSession createUsedSession() {
        GuestSession session = GuestSession.create(
                GUEST_USER_ID,
                TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        );
        session.useForDiary(DIARY_ID, NOW.minusSeconds(1));
        return session;
    }

    private DiaryDetailResult createDetailResult() {
        return new DiaryDetailResult(
                DIARY_ID,
                LocalDate.of(2026, 8, 20),
                "오늘 친구와 카페에 갔다.",
                NOW.minusSeconds(60),
                new DiaryGenerationResult(
                        GENERATION_ID,
                        GenerationStatus.SUCCEEDED,
                        "친구와 보낸 하루",
                        "generated/comic.png",
                        NOW
                )
        );
    }
}
