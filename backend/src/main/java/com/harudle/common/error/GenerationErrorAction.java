package com.harudle.common.error;

import org.jspecify.annotations.Nullable;

public final class GenerationErrorAction {

    private static final String LOGIN_ACTION = "무료 체험을 이미 사용했어요. 로그인 후 다시 생성해 주세요.";
    private static final String RETRY_ACTION = "생성을 완료하지 못했어요. 잠시 후 다시 생성해 주세요.";
    private static final String TRY_LATER_ACTION = "현재 생성 기능을 이용하기 어려워요. 나중에 다시 이용해 주세요.";
    private static final String CAPACITY_EXCEEDED_ACTION = "현재 요청이 많아요. 잠시 후 다시 시도해 주세요.";
    private static final String CONTENT_BLOCKED_ACTION = "작성한 내용으로 생성할 수 없어요. 내용을 바꿔 다시 시도해 주세요.";
    private static final String GUEST_CONTENT_BLOCKED_ACTION = "작성한 내용으로 생성할 수 없어요. 내용을 바꾼 뒤 로그인해서 다시 생성해 주세요.";
    private static final String CONNECTION_ERROR_ACTION = "생성 서비스에 연결하지 못했어요. 잠시 후 다시 생성해 주세요.";
    private static final String SERVER_ERROR_ACTION = "생성 서비스에 문제가 생겼어요. 잠시 후 다시 생성해 주세요.";
    private static final String RATE_LIMITED_ACTION = "지금은 생성 요청이 제한되고 있어요. 잠시 후 다시 생성해 주세요.";
    private static final String PROVIDER_TIMEOUT_ACTION = "생성 시간이 초과됐어요. 잠시 후 다시 생성해 주세요.";
    private static final String OUTPUT_TRUNCATED_ACTION = "생성 결과를 끝까지 완성하지 못했어요. 잠시 후 다시 생성해 주세요.";
    private static final String GENERATION_INTERRUPTED_ACTION = "생성이 도중에 중단됐어요. 잠시 후 다시 생성해 주세요.";
    private static final String PENDING_TIMEOUT_ACTION = "대기 시간이 지나 생성을 시작하지 못했어요. 잠시 후 다시 생성해 주세요.";
    private static final String IMAGE_STORAGE_ERROR_ACTION = "이미지를 저장하거나 불러오지 못했어요. 잠시 후 다시 시도해 주세요.";
    private static final String DAILY_LIMIT_EXCEEDED_ACTION = "오늘 만들 수 있는 횟수를 모두 사용했어요. 내일 다시 생성해 주세요.";
    private static final String GENERATION_IN_PROGRESS_ACTION = "이미 생성 중이에요. 새로 요청하지 말고 현재 생성이 끝날 때까지 기다려 주세요.";
    private static final String IDEMPOTENCY_KEY_CONFLICT_ACTION = "이전 요청과 내용이 달라요. 기존 요청의 결과를 확인한 뒤 새로 작성해 주세요.";
    private static final String INVALID_IDEMPOTENCY_KEY_ACTION = "요청 정보를 확인하지 못했어요. 작성한 내용을 보관한 뒤 생성 화면을 다시 열어 주세요.";
    private static final String GENERATION_NOT_FOUND_ACTION = "생성 기록을 찾을 수 없어요. 이전 화면으로 돌아가 생성 결과를 확인해 주세요.";
    private static final String GENERATION_FAILED_ACTION = "완성된 그림일기가 없어요. 생성에 성공한 일기를 선택한 뒤 다시 공유해 주세요.";
    private static final String GUEST_SESSION_REQUIRED_ACTION = "체험 정보를 확인할 수 없어요. 체험 화면에 다시 들어가 주세요.";
    private static final String GUEST_SESSION_EXPIRED_ACTION = "체험 정보가 만료됐어요. 체험 화면에 다시 들어가 주세요.";
    private static final String GUEST_GENERATION_FAILED_ACTION = "체험 생성에 실패했어요. 로그인 후 다시 생성해 주세요.";
    private static final String GUEST_PROVIDER_TIMEOUT_ACTION = "체험 생성 시간이 초과됐어요. 로그인 후 다시 생성해 주세요.";
    private static final String GUEST_PENDING_TIMEOUT_ACTION = "대기 시간이 지나 체험 생성을 시작하지 못했어요. 로그인 후 다시 생성해 주세요.";

    private GenerationErrorAction() {
    }

    public static @Nullable String forError(ErrorType errorType) {
        return switch (errorType) {
            case GUEST_TRIAL_ALREADY_USED -> LOGIN_ACTION;
            case GENERATION_CAPACITY_EXCEEDED -> CAPACITY_EXCEEDED_ACTION;
            case AI_CONTENT_BLOCKED -> CONTENT_BLOCKED_ACTION;
            case AI_PROVIDER_ERROR -> RETRY_ACTION;
            case AI_PROVIDER_CONNECTION_ERROR -> CONNECTION_ERROR_ACTION;
            case AI_PROVIDER_SERVER_ERROR -> SERVER_ERROR_ACTION;
            case AI_PROVIDER_CONFIGURATION_ERROR -> TRY_LATER_ACTION;
            case AI_PROVIDER_REQUEST_REJECTED -> TRY_LATER_ACTION;
            case AI_PROVIDER_RATE_LIMITED -> RATE_LIMITED_ACTION;
            case AI_PROVIDER_TIMEOUT -> PROVIDER_TIMEOUT_ACTION;
            case AI_OUTPUT_TRUNCATED -> OUTPUT_TRUNCATED_ACTION;
            case AI_RESPONSE_PROCESSING_ERROR -> RETRY_ACTION;
            case GENERATION_UNAVAILABLE -> TRY_LATER_ACTION;
            case GENERATION_INTERRUPTED -> GENERATION_INTERRUPTED_ACTION;
            case GENERATION_PENDING_TIMEOUT -> PENDING_TIMEOUT_ACTION;
            case IMAGE_STORAGE_ERROR -> IMAGE_STORAGE_ERROR_ACTION;
            case DAILY_GENERATION_LIMIT_EXCEEDED -> DAILY_LIMIT_EXCEEDED_ACTION;
            case GENERATION_IN_PROGRESS -> GENERATION_IN_PROGRESS_ACTION;
            case IDEMPOTENCY_KEY_CONFLICT -> IDEMPOTENCY_KEY_CONFLICT_ACTION;
            case INVALID_IDEMPOTENCY_KEY -> INVALID_IDEMPOTENCY_KEY_ACTION;
            case GENERATION_NOT_FOUND -> GENERATION_NOT_FOUND_ACTION;
            case GENERATION_FAILED -> GENERATION_FAILED_ACTION;
            case GUEST_SESSION_REQUIRED -> GUEST_SESSION_REQUIRED_ACTION;
            case GUEST_SESSION_EXPIRED -> GUEST_SESSION_EXPIRED_ACTION;
            default -> null;
        };
    }

    public static String forFailure(ErrorType errorType, boolean guest) {
        if (guest) {
            return switch (errorType) {
                case AI_CONTENT_BLOCKED -> GUEST_CONTENT_BLOCKED_ACTION;
                case AI_PROVIDER_TIMEOUT -> GUEST_PROVIDER_TIMEOUT_ACTION;
                case GENERATION_PENDING_TIMEOUT -> GUEST_PENDING_TIMEOUT_ACTION;
                default -> GUEST_GENERATION_FAILED_ACTION;
            };
        }
        String action = forError(errorType);
        return action == null ? RETRY_ACTION : action;
    }
}
