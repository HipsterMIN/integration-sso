package kr.go.smes.support.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import kr.go.smes.support.application.QnaService;
import kr.go.smes.support.application.SupportRequester;
import kr.go.smes.support.api.dto.CreateQnaRequest;
import kr.go.smes.support.api.dto.QnaDetailResponse;
import kr.go.smes.support.api.dto.QnaSummaryResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/support/qna")
@RequiredArgsConstructor
public class QnaController {

    private final QnaService qnaService;

    @GetMapping
    @Operation(
            summary = "Q&A 목록 조회",
            description = "비로그인 사용자는 공개글만, 로그인 사용자는 공개글 + 본인 비밀글을 조회합니다."
    )
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            array = @ArraySchema(schema = @Schema(implementation = QnaSummaryResponse.class))
    ))
    public List<QnaSummaryResponse> list(
            @Parameter(description = "로그인 사용자 ID (통합인증 완료 시 전달)") @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(description = "사용자 역할 (예: USER, ADMIN)") @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "agencyId", required = false) String agencyId
    ) {
        return qnaService.listForUser(SupportRequester.of(userId, userRole), tenantId, agencyId);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Q&A 단건 조회",
            description = "비밀글은 작성자/관리자만 조회 가능합니다. 비밀 답변도 동일 정책이 적용됩니다."
    )
    public QnaDetailResponse get(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole
    ) {
        return qnaService.getForUser(SupportRequester.of(userId, userRole), id);
    }

    @PostMapping
    @Operation(
            summary = "Q&A 글 작성",
            description = "익명 사용자는 공개글만 작성 가능하며, 비밀글은 로그인 사용자만 작성할 수 있습니다.",
            security = {
                    @SecurityRequirement(name = "supportUser"),
                    @SecurityRequirement(name = "supportUserRole")
            }
    )
    @ResponseStatus(HttpStatus.CREATED)
    public QnaDetailResponse create(
            @Valid @RequestBody CreateQnaRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole
    ) {
        return qnaService.create(SupportRequester.of(userId, userRole), request);
    }
}
