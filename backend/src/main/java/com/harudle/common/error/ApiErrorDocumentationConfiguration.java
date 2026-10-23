package com.harudle.common.error;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Configuration(proxyBeanMethods = false)
class ApiErrorDocumentationConfiguration {

    private static final String PROBLEM_SCHEMA_NAME = "HarudleProblemDetail";
    private static final String FIELD_ERROR_SCHEMA_NAME = "HarudleFieldValidationError";
    private static final String PROBLEM_SCHEMA_REF = "#/components/schemas/" + PROBLEM_SCHEMA_NAME;
    private static final String FIELD_ERROR_SCHEMA_REF = "#/components/schemas/" + FIELD_ERROR_SCHEMA_NAME;
    private static final String EXAMPLE_TRACE_ID = "019d71beebed75b19e45f9c51863bcbd";
    private static final String EXAMPLE_PATH_PARAMETER = "123e4567-e89b-12d3-a456-426614174000";

    @Bean
    OperationCustomizer apiErrorResponseCustomizer() {
        return (operation, handlerMethod) -> {
            ApiErrorResponses declared = handlerMethod.getMethodAnnotation(ApiErrorResponses.class);
            Map<Integer, List<ErrorExample>> byStatus = new LinkedHashMap<>();
            LinkedHashSet<ErrorType> serviceErrors = new LinkedHashSet<>();
            if (declared != null) {
                serviceErrors.addAll(List.of(declared.value()));
            }
            serviceErrors.add(ErrorType.INTERNAL_SERVER_ERROR);
            for (ErrorType errorType : serviceErrors) {
                addExample(byStatus, new ErrorExample(
                        errorType.status().value(),
                        errorType.code(),
                        errorType.code(),
                        errorType.title(),
                        errorType.detail()
                ));
            }
            if (declared != null) {
                for (ApiFrameworkError frameworkError : declared.framework()) {
                    int status = frameworkError.status();
                    if (status < 400 || status > 599) {
                        throw new IllegalArgumentException("문서화할 프레임워크 오류는 4xx/5xx여야 합니다: " + status);
                    }
                    String code = FrameworkErrorType.codeFor(HttpStatusCode.valueOf(status));
                    HttpStatus knownStatus = HttpStatus.resolve(status);
                    String title = knownStatus == null ? "HTTP " + status : knownStatus.getReasonPhrase();
                    String name = frameworkError.name().isBlank() ? code : frameworkError.name();
                    addExample(byStatus, new ErrorExample(
                            status,
                            code,
                            name,
                            title,
                            frameworkError.detail()
                    ));
                }
            }

            ApiResponses responses = operation.getResponses();
            if (responses == null) {
                responses = new ApiResponses();
                operation.setResponses(responses);
            }
            for (Map.Entry<Integer, List<ErrorExample>> entry : byStatus.entrySet()) {
                int status = entry.getKey();
                responses.addApiResponse(Integer.toString(status), errorResponse(status, entry.getValue()));
            }
            return operation;
        };
    }

    @Bean
    OpenApiCustomizer apiProblemSchemaCustomizer() {
        return openApi -> {
            Components components = openApi.getComponents();
            if (components == null) {
                components = new Components();
                openApi.setComponents(components);
            }
            components.addSchemas(FIELD_ERROR_SCHEMA_NAME, fieldValidationErrorSchema());
            components.addSchemas(PROBLEM_SCHEMA_NAME, problemDetailSchema());

            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, pathItem) ->
                    pathItem.readOperations().forEach(operation -> setExampleInstance(operation, path))
            );
        };
    }

    private static void addExample(Map<Integer, List<ErrorExample>> byStatus, ErrorExample example) {
        byStatus.computeIfAbsent(example.status(), ignored -> new ArrayList<>()).add(example);
    }

    private static ApiResponse errorResponse(int status, List<ErrorExample> errors) {
        MediaType mediaType = new MediaType().schema(new Schema<>().$ref(PROBLEM_SCHEMA_REF));
        Map<String, Integer> usedNames = new LinkedHashMap<>();
        for (ErrorExample error : errors) {
            int occurrence = usedNames.merge(error.name(), 1, Integer::sum);
            String exampleName = occurrence == 1 ? error.name() : error.name() + "-" + occurrence;
            mediaType.addExamples(exampleName, new Example()
                    .summary(error.code())
                    .description(error.detail())
                    .value(exampleValue(error)));
        }
        ApiResponse response = new ApiResponse()
                .description(errorResponseDescription(status, errors))
                .content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE, mediaType));
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            response.addHeaderObject("Retry-After", new Header()
                    .description("다음 KST 자정까지 남은 초")
                    .schema(new IntegerSchema().format("int64")));
        }
        return response;
    }

    private static String errorResponseDescription(int status, List<ErrorExample> errors) {
        String summary = switch (status) {
            case 400 -> "잘못된 요청";
            case 401 -> "인증 정보가 없거나 유효하지 않음";
            case 403 -> "요청이 허용되지 않음";
            case 404 -> "요청한 대상을 찾을 수 없음";
            case 409 -> "요청이 현재 상태와 충돌함";
            case 413 -> "요청 본문 크기 초과";
            case 415 -> "지원하지 않는 미디어 형식";
            case 429 -> "오늘 이미지 생성 한도 초과";
            case 500 -> "서버 내부 오류";
            case 502 -> "외부 서비스 오류";
            case 503 -> "서비스 이용 불가";
            case 504 -> "외부 서비스 응답 시간 초과";
            default -> "오류 응답";
        };
        StringBuilder description = new StringBuilder(summary)
                .append("\n\n| 오류 코드 | 예시 메시지 |\n| --- | --- |\n");
        for (ErrorExample error : errors) {
            description.append("| `").append(error.code()).append("` | ")
                    .append(error.detail().replace("|", "\\|"))
                    .append(" |\n");
        }
        return description.toString();
    }

    private static Map<String, Object> exampleValue(ErrorExample error) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", ErrorType.problemType(error.code()).toString());
        value.put("title", error.title());
        value.put("status", error.status());
        value.put("detail", error.detail());
        value.put("instance", "/");
        value.put("code", error.code());
        value.put("traceId", EXAMPLE_TRACE_ID);
        return value;
    }

    private static void setExampleInstance(Operation operation, String path) {
        if (operation.getResponses() == null) {
            return;
        }
        for (ApiResponse response : operation.getResponses().values()) {
            if (response.getContent() == null) {
                continue;
            }
            MediaType mediaType = response.getContent().get(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            if (mediaType == null || mediaType.getExamples() == null) {
                continue;
            }
            for (Example example : mediaType.getExamples().values()) {
                if (example.getValue() instanceof Map<?, ?> fields) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    fields.forEach((key, fieldValue) -> {
                        if (key instanceof String fieldName) {
                            value.put(fieldName, fieldValue);
                        }
                    });
                    value.put("instance", path.replaceAll("\\{[^/{}]+}", EXAMPLE_PATH_PARAMETER));
                    example.setValue(value);
                }
            }
        }
    }

    private static Schema<?> fieldValidationErrorSchema() {
        Schema<?> schema = new ObjectSchema();
        schema.addProperty("field", new StringSchema().description("검증에 실패한 필드"));
        schema.addProperty("reason", new StringSchema().description("검증 실패 이유"));
        schema.setRequired(List.of("field", "reason"));
        return schema;
    }

    private static Schema<?> problemDetailSchema() {
        Schema<?> schema = new ObjectSchema();
        schema.addProperty("type", new StringSchema().description("오류 코드로 만든 URN"));
        schema.addProperty("title", new StringSchema().description("오류 유형의 제목"));
        schema.addProperty("status", new IntegerSchema().format("int32").description("HTTP 상태 코드"));
        schema.addProperty("detail", new StringSchema().description("오류 상세 메시지"));
        schema.addProperty("instance", new StringSchema().description("오류가 발생한 요청 경로"));
        schema.addProperty("code", new StringSchema().description("클라이언트 분기용 오류 코드"));
        schema.addProperty("traceId", new StringSchema().description("로그 추적 ID"));
        schema.addProperty("errors", new ArraySchema()
                .items(new Schema<>().$ref(FIELD_ERROR_SCHEMA_REF))
                .description("필드 검증 오류가 있을 때만 포함"));
        schema.setRequired(List.of("type", "title", "status", "detail", "instance", "code", "traceId"));
        return schema;
    }

    private record ErrorExample(int status, String code, String name, String title, String detail) {
    }
}
