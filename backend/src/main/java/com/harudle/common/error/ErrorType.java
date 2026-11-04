package com.harudle.common.error;

import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.service.exception.AiGenerationException;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

public enum ErrorType {

    VALIDATION_ERROR(
            HttpStatus.BAD_REQUEST,
            "Validation failed",
            "요청 값이 올바르지 않습니다."
    ),
    INVALID_CURSOR(
            HttpStatus.BAD_REQUEST,
            "Invalid cursor",
            "페이지 커서가 올바르지 않거나 조회 조건과 일치하지 않습니다."
    ),
    INVALID_IDEMPOTENCY_KEY(
            HttpStatus.BAD_REQUEST,
            "Invalid idempotency key",
            "Idempotency-Key는 UUID 형식의 필수 헤더입니다."
    ),
    UNAUTHORIZED(
            HttpStatus.UNAUTHORIZED,
            "Unauthorized",
            "인증 정보가 필요합니다."
    ),
    INVALID_REFRESH_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "Invalid refresh token",
            "Refresh Token이 유효하지 않습니다."
    ),
    INVALID_CURRENT_USER(
            HttpStatus.UNAUTHORIZED,
            "Invalid current user",
            "현재 로그인 사용자를 확인할 수 없습니다."
    ),
    INVALID_PUSH_SESSION(
            HttpStatus.UNAUTHORIZED,
            "Invalid push session",
            "현재 로그인 세션으로 푸시를 등록할 수 없습니다."
    ),
    PUSH_REGISTRATION_CONFLICT(
            HttpStatus.CONFLICT,
            "Push registration conflict",
            "다른 로그인 세션에서 사용 중인 푸시 등록입니다."
    ),
    STALE_PUSH_REGISTRATION(
            HttpStatus.CONFLICT,
            "Stale push registration",
            "푸시 등록 상태가 변경되었습니다. 현재 등록 상태를 확인해 주세요."
    ),
    STALE_PUSH_STATE(
            HttpStatus.CONFLICT,
            "Stale push state",
            "푸시 수신 선택이 변경되었습니다. 현재 상태를 확인해 주세요."
    ),
    NICKNAME_ALREADY_IN_USE(
            HttpStatus.CONFLICT,
            "Nickname already in use",
            "이미 사용 중인 닉네임입니다."
    ),
    PUBLIC_PROFILE_NOT_ASSIGNED(
            HttpStatus.CONFLICT,
            "Public profile not assigned",
            "공개 프로필을 먼저 배정해야 합니다."
    ),
    GUEST_SESSION_REQUIRED(
            HttpStatus.UNAUTHORIZED,
            "Guest session required",
            "게스트 세션이 필요합니다."
    ),
    GUEST_SESSION_EXPIRED(
            HttpStatus.UNAUTHORIZED,
            "Guest session expired",
            "게스트 세션이 만료되었습니다."
    ),
    LEGAL_DOCUMENT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Legal document not found",
            "해당 버전의 문서를 찾을 수 없습니다."
    ),
    TERMS_ACCEPTANCE_REQUIRED(
            HttpStatus.FORBIDDEN,
            "Terms acceptance required",
            "서비스 이용 전에 현재 이용약관을 확인하고 동의해 주세요."
    ),
    LEGAL_DOCUMENT_CHANGED(
            HttpStatus.CONFLICT,
            "Legal document changed",
            "문서가 변경되었습니다. 최신 내용을 확인한 뒤 다시 동의해 주세요."
    ),
    INVALID_TERMS_ACCEPTANCE(
            HttpStatus.BAD_REQUEST,
            "Invalid terms acceptance",
            "약관 동의 여부와 문서 버전을 확인해 주세요."
    ),
    ACCOUNT_SUSPENDED(
            HttpStatus.FORBIDDEN,
            "Account suspended",
            "관리자에 의해 이용이 제한된 계정입니다."
    ),
    ADMIN_REPORT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Report not found",
            "신고를 찾을 수 없습니다."
    ),
    ADMIN_REPORT_REVIEW_CONFLICT(
            HttpStatus.CONFLICT,
            "Report review conflict",
            "이미 종결된 신고입니다. 최신 처리 결과를 확인해 주세요."
    ),
    INVALID_ADMIN_REPORT_REVIEW(
            HttpStatus.BAD_REQUEST,
            "Invalid report review",
            "심사 상태와 사유를 확인해 주세요."
    ),
    MODERATION_TARGET_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Moderation target not found",
            "조치할 수 있는 대상을 찾을 수 없습니다."
    ),
    MODERATION_CONFLICT(
            HttpStatus.CONFLICT,
            "Moderation conflict",
            "다른 관리자가 조치 상태를 변경했습니다. 최신 상태를 확인해 주세요."
    ),
    INVALID_MODERATION_CHANGE(
            HttpStatus.BAD_REQUEST,
            "Invalid moderation change",
            "조치 사유와 버전을 확인해 주세요."
    ),
    DATABASE_LOCK_TIMEOUT(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Database lock timeout",
            "다른 요청을 처리하고 있어요. 잠시 후 다시 시도해 주세요."
    ),
    FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "Forbidden",
            "해당 리소스에 접근할 권한이 없습니다."
    ),
    INVALID_CSRF_TOKEN(
            HttpStatus.FORBIDDEN,
            "Invalid CSRF token",
            "CSRF Token이 유효하지 않습니다."
    ),
    DIARY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Diary not found",
            "일기를 찾을 수 없습니다."
    ),
    GENERATION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Generation not found",
            "생성 작업을 찾을 수 없습니다."
    ),
    CONTENT_RESTRICTED(
            HttpStatus.FORBIDDEN,
            "Content restricted",
            "관리자에 의해 숨겨진 콘텐츠는 다시 게시할 수 없습니다."
    ),
    FEED_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Feed not found",
            "피드를 찾을 수 없습니다."
    ),
    INVALID_REPORT(
            HttpStatus.BAD_REQUEST,
            "Invalid report",
            "신고 대상이나 내용이 올바르지 않습니다."
    ),
    REPORT_TARGET_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Report target not found",
            "신고할 대상을 찾을 수 없습니다."
    ),
    INVALID_BLOCK(
            HttpStatus.BAD_REQUEST,
            "Invalid block",
            "차단 대상이 올바르지 않습니다."
    ),
    BLOCK_TARGET_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Block target not found",
            "차단할 회원을 찾을 수 없습니다."
    ),
    BLOCK_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Block unavailable",
            "차단한 회원 정보를 조회할 수 없습니다."
    ),
    COMMENT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Comment not found",
            "댓글을 찾을 수 없습니다."
    ),
    COMMENT_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Comment unavailable",
            "댓글 작성자 정보를 조회할 수 없습니다."
    ),
    NOTIFICATION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Notification not found",
            "알림을 찾을 수 없습니다."
    ),
    NOTIFICATION_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Notification unavailable",
            "알림의 회원 정보를 조회할 수 없습니다."
    ),
    CATEGORY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Category not found",
            "카테고리를 찾을 수 없습니다."
    ),
    DIARY_NOT_PUBLISHABLE(
            HttpStatus.CONFLICT,
            "Diary not publishable",
            "이미지 생성이 완료된 일기만 게시할 수 있습니다."
    ),
    DIARY_ALREADY_PUBLISHED(
            HttpStatus.CONFLICT,
            "Diary already published",
            "이미 피드로 게시된 일기입니다."
    ),
    CATEGORY_INACTIVE(
            HttpStatus.CONFLICT,
            "Inactive category",
            "비활성 카테고리에는 피드를 게시할 수 없습니다."
    ),
    CATEGORY_NAME_ALREADY_USED(
            HttpStatus.CONFLICT,
            "Category name already used",
            "이미 사용 중인 카테고리 이름입니다."
    ),
    FEED_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Feed unavailable",
            "피드 연동 기능이 아직 구성되지 않았습니다."
    ),
    SHARE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Share not found",
            "공유 링크를 찾을 수 없습니다."
    ),
    USER_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "User not found",
            "사용자를 찾을 수 없습니다."
    ),
    INACTIVE_USER(
            HttpStatus.CONFLICT,
            "Inactive user",
            "탈퇴한 사용자는 관리자 변경 작업의 대상이 될 수 없습니다."
    ),
    GENERATION_USAGE_CONFLICT(
            HttpStatus.CONFLICT,
            "Generation usage conflict",
            "현재 생성 사용량을 기준으로 요청을 다시 확인해 주세요."
    ),
    GENERATION_LIMIT_BELOW_USAGE(
            HttpStatus.CONFLICT,
            "Generation limit below usage",
            "현재 사용량보다 작은 생성 한도로 변경할 수 없습니다."
    ),
    GENERATION_IN_PROGRESS(
            HttpStatus.CONFLICT,
            "Generation in progress",
            "동일한 만화 생성 요청이 처리 중입니다."
    ),
    GENERATION_FAILED(
            HttpStatus.CONFLICT,
            "Generation failed",
            "공유할 그림일기 생성 결과가 없습니다."
    ),
    IDEMPOTENCY_KEY_CONFLICT(
            HttpStatus.CONFLICT,
            "Idempotency key conflict",
            "동일한 멱등성 키가 다른 요청에 사용되었습니다."
    ),
    GUEST_TRIAL_ALREADY_USED(
            HttpStatus.CONFLICT,
            "Guest trial already used",
            "로그인 전 무료 사용 기회를 이미 사용했습니다."
    ),
    DAILY_GENERATION_LIMIT_EXCEEDED(
            HttpStatus.TOO_MANY_REQUESTS,
            "Daily generation limit exceeded",
            "오늘 생성 가능한 횟수를 모두 사용했습니다."
    ),
    AI_PROVIDER_ERROR(
            HttpStatus.BAD_GATEWAY,
            "AI provider error",
            "AI 생성 중 원인을 확인하지 못한 문제가 발생했어요."
    ),
    AI_PROVIDER_CONNECTION_ERROR(
            HttpStatus.BAD_GATEWAY,
            "AI provider connection error",
            "우리 서버가 AI 서비스에 연결하지 못했어요."
    ),
    AI_PROVIDER_SERVER_ERROR(
            HttpStatus.BAD_GATEWAY,
            "AI provider server error",
            "AI 서비스 내부에 문제가 생겨 생성하지 못했어요."
    ),
    AI_PROVIDER_CONFIGURATION_ERROR(
            HttpStatus.SERVICE_UNAVAILABLE,
            "AI provider configuration error",
            "서비스의 AI 연결 설정에 문제가 있어 생성하지 못했어요."
    ),
    AI_PROVIDER_REQUEST_REJECTED(
            HttpStatus.BAD_GATEWAY,
            "AI provider request rejected",
            "AI 서비스가 생성 요청을 받아들이지 못했어요."
    ),
    AI_PROVIDER_RATE_LIMITED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "AI provider rate limited",
            "AI 서비스의 이용 제한으로 생성하지 못했어요."
    ),
    AI_CONTENT_BLOCKED(
            HttpStatus.BAD_REQUEST,
            "AI content blocked",
            "AI 서비스의 콘텐츠 기준에 따라 생성이 중단됐어요."
    ),
    AI_OUTPUT_TRUNCATED(
            HttpStatus.BAD_GATEWAY,
            "AI output truncated",
            "AI가 생성 결과를 끝까지 완성하지 못했어요."
    ),
    AI_RESPONSE_PROCESSING_ERROR(
            HttpStatus.BAD_GATEWAY,
            "AI response processing error",
            "AI가 보내온 결과를 처리하지 못했어요."
    ),
    GENERATION_CAPACITY_EXCEEDED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Generation capacity exceeded",
            "현재 생성 요청이 많습니다. 잠시 후 다시 시도해 주세요."
    ),
    GENERATION_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Generation unavailable",
            "생성 기능을 사용할 수 없습니다."
    ),
    GENERATION_INTERRUPTED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Generation interrupted",
            "생성 처리가 완료되지 못했습니다."
    ),
    GENERATION_PENDING_TIMEOUT(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Generation pending timeout",
            "생성 대기 시간이 초과되었습니다."
    ),
    IMAGE_STORAGE_ERROR(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Image storage error",
            "생성 이미지를 저장하거나 불러오지 못했습니다."
    ),
    PROFILE_IMAGE_CAPACITY_EXCEEDED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Profile image capacity exceeded",
            "현재 사진을 처리 중입니다. 잠시 후 다시 시도해 주세요."
    ),
    PROFILE_IMAGE_STORAGE_ERROR(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Profile image storage error",
            "프로필 이미지를 저장하거나 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
    ),
    AI_PROVIDER_TIMEOUT(
            HttpStatus.GATEWAY_TIMEOUT,
            "AI provider timeout",
            "AI 서비스가 제한 시간 안에 응답하지 못했어요."
    ),
    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal server error",
            "서버에서 요청을 처리하지 못했습니다."
    );

    private static final String PROBLEM_TYPE_URN_PREFIX = "urn:harudle:problem:";

    private final HttpStatus status;
    private final String title;
    private final String detail;

    ErrorType(HttpStatus status, String title, String detail) {
        this.status = status;
        this.title = title;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    String title() {
        return title;
    }

    String detail() {
        return detail;
    }

    String code() {
        return name();
    }

    URI problemType() {
        return problemType(code());
    }

    static URI problemType(String code) {
        String slug = code.toLowerCase(Locale.ROOT).replace('_', '-');
        return URI.create(PROBLEM_TYPE_URN_PREFIX + slug);
    }

    public static ErrorType from(GenerationErrorCode errorCode) {
        return switch (errorCode) {
            case AI_PROVIDER_ERROR -> AI_PROVIDER_ERROR;
            case AI_PROVIDER_CONNECTION_ERROR -> AI_PROVIDER_CONNECTION_ERROR;
            case AI_PROVIDER_SERVER_ERROR -> AI_PROVIDER_SERVER_ERROR;
            case AI_PROVIDER_CONFIGURATION_ERROR -> AI_PROVIDER_CONFIGURATION_ERROR;
            case AI_PROVIDER_REQUEST_REJECTED -> AI_PROVIDER_REQUEST_REJECTED;
            case AI_PROVIDER_RATE_LIMITED -> AI_PROVIDER_RATE_LIMITED;
            case AI_CONTENT_BLOCKED -> AI_CONTENT_BLOCKED;
            case AI_OUTPUT_TRUNCATED -> AI_OUTPUT_TRUNCATED;
            case AI_RESPONSE_PROCESSING_ERROR -> AI_RESPONSE_PROCESSING_ERROR;
            case GENERATION_INTERNAL_ERROR -> INTERNAL_SERVER_ERROR;
            case AI_PROVIDER_TIMEOUT -> AI_PROVIDER_TIMEOUT;
            case GENERATION_INTERRUPTED -> GENERATION_INTERRUPTED;
            case GENERATION_PENDING_TIMEOUT -> GENERATION_PENDING_TIMEOUT;
            case IMAGE_STORAGE_ERROR -> IMAGE_STORAGE_ERROR;
        };
    }

    static ErrorType from(AiGenerationException exception) {
        return from(exception.errorCode());
    }
}
