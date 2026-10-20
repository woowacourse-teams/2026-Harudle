package com.harudle.auth.presentation;

import com.harudle.auth.application.CurrentUserResult;
import com.harudle.auth.application.CurrentUserService;
import com.harudle.auth.application.InvalidCurrentUserException;
import com.harudle.auth.application.NicknameChangeService;
import com.harudle.auth.application.ProfileImageUploadService;
import com.harudle.common.error.ApiErrorResponses;
import com.harudle.common.error.ApiFrameworkError;
import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Auth")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me")
public class CurrentUserController {

    private final CurrentUserService currentUserService;
    private final NicknameChangeService nicknameChangeService;
    private final ProfileImageUploadService profileImageUploadService;
    private final ProblemDetailFactory problemDetailFactory;

    public CurrentUserController(
            CurrentUserService currentUserService,
            NicknameChangeService nicknameChangeService,
            ProfileImageUploadService profileImageUploadService,
            ProblemDetailFactory problemDetailFactory
    ) {
        this.currentUserService = currentUserService;
        this.nicknameChangeService = nicknameChangeService;
        this.profileImageUploadService = profileImageUploadService;
        this.problemDetailFactory = problemDetailFactory;
    }

    @Operation(
            summary = "내 프로필 조회",
            description = "인증된 사용자의 프로필과 연결된 OAuth Provider를 조회합니다. "
                    + "개인 사진이 있으면 서명 URL과 만료 시각을 반환하며, 기본 이미지는 만료 시각이 null입니다."
    )
    @ApiErrorResponses({
            ErrorType.UNAUTHORIZED,
            ErrorType.INVALID_CURRENT_USER,
            ErrorType.PROFILE_IMAGE_STORAGE_ERROR
    })
    @GetMapping
    public ResponseEntity<CurrentUserResponse> find(
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = extractUserId(jwt);
        CurrentUserResult result = currentUserService.find(userId);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(CurrentUserResponse.from(result));
    }

    @Operation(summary = "내 닉네임 변경")
    @ApiErrorResponses({
            ErrorType.UNAUTHORIZED,
            ErrorType.INVALID_CURRENT_USER,
            ErrorType.INVALID_CSRF_TOKEN,
            ErrorType.VALIDATION_ERROR,
            ErrorType.NICKNAME_ALREADY_IN_USE,
            ErrorType.PUBLIC_PROFILE_NOT_ASSIGNED
    })
    @PatchMapping("/nickname")
    public ResponseEntity<Void> changeNickname(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangeNicknameRequest request
    ) {
        nicknameChangeService.change(extractUserId(jwt), request.nickname());

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @Operation(
            summary = "내 기본 프로필 이미지 변경",
            description = "1~10번 중 선택한 기본 이미지로 변경합니다. 닉네임은 유지합니다. "
                    + "성공 후 GET /api/v1/me로 변경된 이미지 URL을 조회합니다."
    )
    @ApiResponse(responseCode = "204", description = "기본 프로필 이미지 변경 완료")
    @ApiErrorResponses({
            ErrorType.UNAUTHORIZED,
            ErrorType.INVALID_CURRENT_USER,
            ErrorType.VALIDATION_ERROR,
            ErrorType.PUBLIC_PROFILE_NOT_ASSIGNED
    })
    @PatchMapping("/profile-image")
    public ResponseEntity<Void> changeDefaultProfileImage(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangeDefaultProfileImageRequest request
    ) {
        profileImageUploadService.changeDefault(extractUserId(jwt), request.imageCode());

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @Operation(
            summary = "내 개인 프로필 이미지 업로드",
            description = "image 항목으로 5MiB 이하의 JPEG 또는 PNG 한 장을 전송합니다. "
                    + "가운데를 정사각형으로 자르고 최대 512픽셀의 WebP로 저장합니다. 닉네임은 유지합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true)
    )
    @ApiResponse(responseCode = "204", description = "개인 프로필 이미지 저장 완료")
    @ApiErrorResponses(value = {
            ErrorType.UNAUTHORIZED,
            ErrorType.INVALID_CURRENT_USER,
            ErrorType.VALIDATION_ERROR,
            ErrorType.PUBLIC_PROFILE_NOT_ASSIGNED,
            ErrorType.PROFILE_IMAGE_STORAGE_ERROR
    }, framework = {
            @ApiFrameworkError(status = 413, name = "원본 이미지 크기 초과", detail = "프로필 이미지는 5MiB 이하여야 합니다."),
            @ApiFrameworkError(status = 415, name = "미지원 이미지 형식", detail = "프로필 이미지는 JPEG 또는 PNG만 지원합니다.")
    })
    @PostMapping(value = "/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> uploadProfileImage(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("image") MultipartFile image
    ) {
        profileImageUploadService.upload(extractUserId(jwt), image);

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @ExceptionHandler(InvalidCurrentUserException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCurrentUser(HttpServletRequest request) {
        return ResponseEntity
                .status(ErrorType.INVALID_CURRENT_USER.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore())
                .body(problemDetailFactory.create(ErrorType.INVALID_CURRENT_USER, request));
    }

    private UUID extractUserId(Jwt jwt) {
        Objects.requireNonNull(jwt, "jwt는 필수입니다.");
        String subject = jwt.getSubject();

        if (subject == null || subject.isBlank()) {
            throw new InvalidCurrentUserException();
        }

        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            throw new InvalidCurrentUserException();
        }
    }
}
