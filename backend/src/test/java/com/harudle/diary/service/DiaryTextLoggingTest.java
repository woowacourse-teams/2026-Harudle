package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.OutputStreamAppender;
import com.harudle.diary.presentation.CreateDiaryRequest;
import com.harudle.diary.presentation.CreateDiaryResponse;
import com.harudle.diary.presentation.DiaryDetailResponse;
import com.harudle.diary.presentation.DiaryGenerationResponse;
import com.harudle.diary.presentation.GuestDiaryResponse;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.CreateGuestDiaryCommand;
import com.harudle.diary.service.dto.CreateGuestDiaryResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.generation.adapter.out.qwen.QwenChatClient;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import com.harudle.generation.diary.service.port.dto.StoryboardGenerationRequest;
import com.harudle.generation.usage.domain.GenerationUsage;
import com.harudle.generation.usage.presentation.GenerationUsageResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;

class DiaryTextLoggingTest {

    private static final String PRIVATE_SOURCE = "나만 읽어야 하는 상담 내용 private-diary-source-422";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DIARY_ID = UUID.randomUUID();
    private static final UUID GENERATION_ID = UUID.randomUUID();
    private static final UUID IDEMPOTENCY_KEY = UUID.randomUUID();
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 10, 20);
    private static final Instant CREATED_AT = Instant.parse("2026-10-20T01:00:00Z");
    private static final OffsetDateTime RESPONSE_TIME = CREATED_AT.atOffset(ZoneOffset.ofHours(9));
    private static final GenerationUsage USAGE = GenerationUsage.empty(DIARY_DATE);
    private static final DiaryGenerationResult GENERATION = new DiaryGenerationResult(
            GENERATION_ID, GenerationStatus.SUCCEEDED, "그림일기 제목", "generated/image.webp", CREATED_AT);
    private static final DiaryGenerationResponse GENERATION_RESPONSE = new DiaryGenerationResponse(
            GENERATION_ID, GenerationStatus.SUCCEEDED, "그림일기 제목", "https://example.com/image.webp",
            RESPONSE_TIME.plusMinutes(15), RESPONSE_TIME, null);

    @Test
    @DisplayName("회원·게스트 작성 요청과 생성 명령을 로그 인수로 전달해도 원문을 남기지 않는다")
    void redactRequestAndGenerationCommandLogs() {
        CreateDiaryRequest request = new CreateDiaryRequest(DIARY_DATE, PRIVATE_SOURCE);
        CreateGuestDiaryCommand guestCommand = new CreateGuestDiaryCommand(
                DIARY_DATE, PRIVATE_SOURCE, IDEMPOTENCY_KEY);
        GenerateDiaryImageCommand generationCommand = new GenerateDiaryImageCommand(
                USER_ID, DIARY_ID, DIARY_DATE, PRIVATE_SOURCE, IDEMPOTENCY_KEY);
        StoryboardGenerationRequest storyboardRequest = new StoryboardGenerationRequest(
                PRIVATE_SOURCE, "storyboard system prompt");
        QwenChatClient.Request providerRequest = new QwenChatClient.Request(
                "model", "system prompt", "일기 원문:\n" + PRIVATE_SOURCE, 8192,
                false, null, false, Map.of());

        assertRedactedLogs(request, createCommand(), guestCommand, guestCommand.toDiaryCommand(USER_ID),
                generationCommand, storyboardRequest, providerRequest);
        assertThat(request.sourceText()).isEqualTo(PRIVATE_SOURCE);
        assertThat(generationCommand.diaryText()).isEqualTo(PRIVATE_SOURCE);
        assertThat(providerRequest.payload().toString()).contains(PRIVATE_SOURCE);
    }

    @Test
    @DisplayName("회원·게스트 선점 결과를 중첩 객체로 로깅해도 원문을 남기지 않는다")
    void redactNestedCreationClaimAndExecutionLogs() {
        DiaryCreationClaim claim = new DiaryCreationClaim(
                DIARY_ID, DIARY_DATE, PRIVATE_SOURCE, CREATED_AT, GENERATION_ID,
                GenerationStatus.SUCCEEDED, "그림일기 제목", "generated/image.webp", CREATED_AT, null, true);
        MemberDiaryCreationClaim memberClaim = new MemberDiaryCreationClaim(claim, USAGE);
        GuestDiaryCreationClaim guestClaim = new GuestDiaryCreationClaim(createCommand(), claim);
        DiaryCreationExecution execution = new DiaryCreationExecution(
                DIARY_ID, DIARY_DATE, PRIVATE_SOURCE, CREATED_AT, GENERATION, true);

        assertRedactedLogs(claim, memberClaim, guestClaim, execution);
        assertThat(memberClaim.claim().sourceText()).isEqualTo(PRIVATE_SOURCE);
        assertThat(guestClaim.command().sourceText()).isEqualTo(PRIVATE_SOURCE);
        assertThat(execution.sourceText()).isEqualTo(PRIVATE_SOURCE);
    }

    @Test
    @DisplayName("작성·상세 응답은 로그에서 원문을 숨기면서 API JSON에는 원문을 유지한다")
    void redactResultsAndResponsesWithoutChangingApiJson() {
        CreateDiaryResult memberResult = new CreateDiaryResult(
                DIARY_ID, DIARY_DATE, PRIVATE_SOURCE, CREATED_AT, GENERATION, USAGE, true);
        CreateGuestDiaryResult guestResult = new CreateGuestDiaryResult(
                DIARY_ID, DIARY_DATE, PRIVATE_SOURCE, CREATED_AT, GENERATION, true);
        DiaryDetailResult detailResult = new DiaryDetailResult(
                DIARY_ID, DIARY_DATE, PRIVATE_SOURCE, CREATED_AT, GENERATION, null);
        CreateDiaryResponse memberResponse = new CreateDiaryResponse(
                DIARY_ID, DIARY_DATE, PRIVATE_SOURCE, RESPONSE_TIME, GENERATION_RESPONSE,
                GenerationUsageResponse.from(USAGE));
        GuestDiaryResponse guestResponse = new GuestDiaryResponse(
                DIARY_ID, DIARY_DATE, PRIVATE_SOURCE, RESPONSE_TIME, GENERATION_RESPONSE);
        DiaryDetailResponse detailResponse = new DiaryDetailResponse(
                DIARY_ID, DIARY_DATE, PRIVATE_SOURCE, RESPONSE_TIME, GENERATION_RESPONSE, null);

        assertRedactedLogs(memberResult, guestResult, detailResult, memberResponse, guestResponse, detailResponse);
        JsonMapper mapper = JsonMapper.builder().build();
        for (Object response : List.of(memberResponse, guestResponse, detailResponse)) {
            assertThat(mapper.readTree(mapper.writeValueAsString(response)).path("sourceText").asString())
                    .isEqualTo(PRIVATE_SOURCE);
        }
    }

    private static CreateDiaryCommand createCommand() {
        return new CreateDiaryCommand(USER_ID, DIARY_DATE, PRIVATE_SOURCE, IDEMPOTENCY_KEY);
    }

    private static void assertRedactedLogs(Object... diaryContexts) {
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        context.start();
        context.putObject(Environment.class.getName(), new StandardEnvironment());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("logstash");
        encoder.start();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(output);
        appender.start();
        Logger logger = context.getLogger(DiaryTextLoggingTest.class);
        logger.addAppender(appender);
        try {
            for (Object diaryContext : diaryContexts) {
                logger.atInfo().addKeyValue("diaryContext", diaryContext)
                        .log("diaryContext={}", diaryContext);
            }
            String logs = output.toString(StandardCharsets.UTF_8);
            assertThat(logs).doesNotContain(PRIVATE_SOURCE, "private-diary-source-422");
            assertThat(logs.lines().toList()).hasSize(diaryContexts.length)
                    .allSatisfy(line -> assertThat(line).contains("<redacted>"));
        } finally {
            appender.stop();
            context.stop();
        }
    }
}
