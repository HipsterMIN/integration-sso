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
import kr.go.smes.support.api.dto.AnswerQnaRequest;
import kr.go.smes.support.api.dto.QnaDetailResponse;
import kr.go.smes.support.api.dto.QnaSummaryResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/support")
@RequiredArgsConstructor
public class AdminSupportController {

    private final QnaService qnaService;

    @GetMapping("/qna")
    @Operation(
            summary = "관리자 Q&A 목록 조회",
            security = {
                    @SecurityRequirement(name = "supportUser"),
                    @SecurityRequirement(name = "supportUserRole")
            }
    )
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            array = @ArraySchema(schema = @Schema(implementation = QnaSummaryResponse.class))
    ))
    public List<QnaSummaryResponse> listQna(
            @Parameter(description = "관리자 사용자 ID") @RequestHeader(value = "X-User-Id", required = false) String userId,
            @Parameter(description = "관리자 역할 (ADMIN 포함)") @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "agencyId", required = false) String agencyId
    ) {
        return qnaService.listForAdmin(SupportRequester.of(userId, userRole), tenantId, agencyId);
    }

    @PostMapping("/qna/{id}/answer")
    @Operation(
            summary = "관리자 Q&A 답변 등록",
            description = "비밀글 답변은 기본 비밀 답변으로 처리됩니다. 익명 문의에는 비밀 답변을 허용하지 않습니다.",
            security = {
                    @SecurityRequirement(name = "supportUser"),
                    @SecurityRequirement(name = "supportUserRole")
            }
    )
    @ResponseStatus(HttpStatus.CREATED)
    public QnaDetailResponse answer(
            @PathVariable UUID id,
            @Valid @RequestBody AnswerQnaRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole
    ) {
        return qnaService.answerByAdmin(SupportRequester.of(userId, userRole), id, request);
    }
}
