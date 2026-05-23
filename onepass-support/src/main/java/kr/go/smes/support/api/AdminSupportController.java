package kr.go.smes.support.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
import kr.go.smes.support.application.CsRequester;
import kr.go.smes.support.application.QnaService;
import kr.go.smes.support.application.SupportTicketService;
import kr.go.smes.support.api.dto.AnswerQnaRequest;
import kr.go.smes.support.api.dto.CreateInternalNoteRequest;
import kr.go.smes.support.api.dto.CreatePhoneConsultationRequest;
import kr.go.smes.support.api.dto.CsTicketDetailResponse;
import kr.go.smes.support.api.dto.CsTicketSummaryResponse;
import kr.go.smes.support.api.dto.QnaDetailResponse;
import kr.go.smes.support.api.dto.QnaSummaryResponse;
import kr.go.smes.support.api.dto.UpdateTicketAssignmentRequest;
import kr.go.smes.support.api.dto.UpdateTicketStatusRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/support")
@RequiredArgsConstructor
public class AdminSupportController {

    private final QnaService qnaService;
    private final SupportTicketService supportTicketService;

    @GetMapping("/qna")
    @Operation(
            summary = "관리자 Q&A 목록 조회",
            security = {
                    @SecurityRequirement(name = "supportCsAgent"),
                    @SecurityRequirement(name = "supportCsRole")
            }
    )
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            array = @ArraySchema(schema = @Schema(implementation = QnaSummaryResponse.class))
    ))
    public List<QnaSummaryResponse> listQna(
            @Parameter(description = "CS 상담원 ID") @RequestHeader(value = "X-CS-Agent-Id", required = false) String agentId,
            @Parameter(description = "CS 역할 (CS_AGENT, CS_LEAD, SUPPORT_ADMIN, AUDITOR)") @RequestHeader(value = "X-CS-Agent-Role", required = false) String role,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "agencyId", required = false) String agencyId
    ) {
        return qnaService.listForAdmin(CsRequester.of(agentId, role), tenantId, agencyId);
    }

    @PostMapping("/qna/{id}/answer")
    @Operation(
            summary = "관리자 Q&A 답변 등록",
            description = "비밀글 답변은 기본 비밀 답변으로 처리됩니다. 익명 문의에는 비밀 답변을 허용하지 않습니다.",
            security = {
                    @SecurityRequirement(name = "supportCsAgent"),
                    @SecurityRequirement(name = "supportCsRole")
            }
    )
    @ResponseStatus(HttpStatus.CREATED)
    public QnaDetailResponse answer(
            @PathVariable UUID id,
            @Valid @RequestBody AnswerQnaRequest request,
            @RequestHeader(value = "X-CS-Agent-Id", required = false) String agentId,
            @RequestHeader(value = "X-CS-Agent-Role", required = false) String role
    ) {
        return qnaService.answerByAdmin(CsRequester.of(agentId, role), id, request);
    }

    @GetMapping("/tickets")
    @Operation(
            summary = "CS 통합 티켓 큐 조회",
            description = "Q&A, 전화 민원 등 모든 접수 채널을 하나의 큐에서 조회합니다.",
            security = {
                    @SecurityRequirement(name = "supportCsAgent"),
                    @SecurityRequirement(name = "supportCsRole")
            }
    )
    @ApiResponse(responseCode = "200", description = "OK", content = @Content(
            array = @ArraySchema(schema = @Schema(implementation = CsTicketSummaryResponse.class))
    ))
    public List<CsTicketSummaryResponse> listTickets(
            @RequestHeader(value = "X-CS-Agent-Id", required = false) String agentId,
            @RequestHeader(value = "X-CS-Agent-Role", required = false) String role,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "channel", required = false) String channel,
            @RequestParam(value = "assignedAgentId", required = false) String assignedAgentId,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "agencyId", required = false) String agencyId
    ) {
        return supportTicketService.listQueue(
                CsRequester.of(agentId, role),
                status,
                channel,
                assignedAgentId,
                tenantId,
                agencyId
        );
    }

    @GetMapping("/tickets/{id}")
    @Operation(summary = "CS 티켓 상세 조회")
    public CsTicketDetailResponse getTicket(
            @PathVariable UUID id,
            @RequestHeader(value = "X-CS-Agent-Id", required = false) String agentId,
            @RequestHeader(value = "X-CS-Agent-Role", required = false) String role
    ) {
        return supportTicketService.getTicket(CsRequester.of(agentId, role), id);
    }

    @PostMapping("/phone-consultations")
    @Operation(summary = "전화 민원 접수", description = "상담원이 전화 상담 내용을 정리하고 티켓 타임라인에 기록합니다.")
    @ResponseStatus(HttpStatus.CREATED)
    public CsTicketDetailResponse createPhoneConsultation(
            @Valid @RequestBody CreatePhoneConsultationRequest request,
            @RequestHeader(value = "X-CS-Agent-Id", required = false) String agentId,
            @RequestHeader(value = "X-CS-Agent-Role", required = false) String role
    ) {
        return supportTicketService.createPhoneConsultation(CsRequester.of(agentId, role), request);
    }

    @PostMapping("/tickets/{id}/internal-notes")
    @Operation(summary = "CS 내부 메모 추가")
    @ResponseStatus(HttpStatus.CREATED)
    public CsTicketDetailResponse addInternalNote(
            @PathVariable UUID id,
            @Valid @RequestBody CreateInternalNoteRequest request,
            @RequestHeader(value = "X-CS-Agent-Id", required = false) String agentId,
            @RequestHeader(value = "X-CS-Agent-Role", required = false) String role
    ) {
        return supportTicketService.addInternalNote(CsRequester.of(agentId, role), id, request);
    }

    @PatchMapping("/tickets/{id}/assignment")
    @Operation(summary = "CS 티켓 담당자 배정")
    public CsTicketDetailResponse updateAssignment(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTicketAssignmentRequest request,
            @RequestHeader(value = "X-CS-Agent-Id", required = false) String agentId,
            @RequestHeader(value = "X-CS-Agent-Role", required = false) String role
    ) {
        return supportTicketService.updateAssignment(CsRequester.of(agentId, role), id, request);
    }

    @PatchMapping("/tickets/{id}/status")
    @Operation(summary = "CS 티켓 상태 변경")
    public CsTicketDetailResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTicketStatusRequest request,
            @RequestHeader(value = "X-CS-Agent-Id", required = false) String agentId,
            @RequestHeader(value = "X-CS-Agent-Role", required = false) String role
    ) {
        return supportTicketService.updateStatus(CsRequester.of(agentId, role), id, request);
    }
}
