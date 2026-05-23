package kr.go.smes.support.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.go.smes.support.api.SupportApiException;
import kr.go.smes.support.api.dto.CreateInternalNoteRequest;
import kr.go.smes.support.api.dto.CreatePhoneConsultationRequest;
import kr.go.smes.support.api.dto.CsTicketDetailResponse;
import kr.go.smes.support.api.dto.CsTicketEventResponse;
import kr.go.smes.support.api.dto.CsTicketSummaryResponse;
import kr.go.smes.support.api.dto.UpdateTicketAssignmentRequest;
import kr.go.smes.support.api.dto.UpdateTicketStatusRequest;
import kr.go.smes.support.domain.QnaPostEntity;
import kr.go.smes.support.domain.SupportPhoneConsultationEntity;
import kr.go.smes.support.domain.SupportPhoneConsultationRepository;
import kr.go.smes.support.domain.SupportTicketEntity;
import kr.go.smes.support.domain.SupportTicketEventEntity;
import kr.go.smes.support.domain.SupportTicketRepository;
import kr.go.smes.support.domain.Yn;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SupportTicketService {

    private static final String CHANNEL_QNA = "QNA";
    private static final String CHANNEL_PHONE = "PHONE";
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_ANSWERED = "ANSWERED";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String PRIORITY_NORMAL = "NORMAL";
    private static final String VISIBILITY_INTERNAL = "INTERNAL";
    private static final String VISIBILITY_CUSTOMER = "CUSTOMER";

    private final SupportTicketRepository ticketRepository;
    private final SupportPhoneConsultationRepository phoneConsultationRepository;

    @Transactional(readOnly = true)
    public List<CsTicketSummaryResponse> listQueue(
            CsRequester requester,
            String status,
            String channel,
            String assignedAgentId,
            String tenantId,
            String agencyId
    ) {
        assertStaff(requester);
        return ticketRepository.findBackofficeQueue(
                        normalizeUpper(status),
                        normalizeUpper(channel),
                        blankToNull(assignedAgentId),
                        blankToNull(tenantId),
                        blankToNull(agencyId)
                ).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public CsTicketDetailResponse getTicket(CsRequester requester, UUID ticketId) {
        assertStaff(requester);
        return toDetail(getTicketEntity(ticketId));
    }

    @Transactional
    public CsTicketDetailResponse createPhoneConsultation(
            CsRequester requester,
            CreatePhoneConsultationRequest request
    ) {
        assertWritable(requester);
        Instant now = Instant.now();
        SupportTicketEntity ticket = SupportTicketEntity.builder()
                .ticketId(UUID.randomUUID())
                .channelCode(CHANNEL_PHONE)
                .statusCode(STATUS_OPEN)
                .priorityCode(defaultUpper(request.priority(), PRIORITY_NORMAL))
                .categoryCode(normalizeUpper(request.category()))
                .tenantId(blankToNull(request.tenantId()))
                .agencyId(blankToNull(request.agencyId()))
                .title(request.title().trim())
                .requesterName(blankToNull(request.requesterName()))
                .requesterPhone(blankToNull(request.requesterPhone()))
                .requesterEmail(blankToNull(request.requesterEmail()))
                .callbackRequiredYn(Yn.fromBoolean(request.callbackRequired()))
                .callbackDueAt(request.callbackDueAt())
                .useYn(Yn.YES)
                .frstRegistPnttm(now)
                .frstRegisterId(requester.agentId())
                .lastUpdtPnttm(now)
                .lastUpdusrId(requester.agentId())
                .build();

        SupportPhoneConsultationEntity consultation = SupportPhoneConsultationEntity.builder()
                .phoneConsultationId(UUID.randomUUID())
                .ticketId(ticket.getTicketId())
                .callDirectionCode(defaultUpper(request.callDirection(), "INBOUND"))
                .callStartedAt(request.callStartedAt())
                .callEndedAt(request.callEndedAt())
                .callerPhone(blankToNull(request.requesterPhone()))
                .identityVerifiedYn(Yn.fromBoolean(request.identityVerified()))
                .summary(request.summary().trim())
                .details(trimToNull(request.details()))
                .requestedAction(trimToNull(request.requestedAction()))
                .callbackRequiredYn(Yn.fromBoolean(request.callbackRequired()))
                .callbackDueAt(request.callbackDueAt())
                .frstRegistPnttm(now)
                .frstRegisterId(requester.agentId())
                .build();

        addEvent(ticket, "PHONE_CALL", VISIBILITY_INTERNAL, request.summary().trim(), requester.agentId(), now);
        SupportTicketEntity saved = ticketRepository.save(ticket);
        phoneConsultationRepository.save(consultation);
        return toDetail(saved);
    }

    @Transactional
    public CsTicketDetailResponse addInternalNote(
            CsRequester requester,
            UUID ticketId,
            CreateInternalNoteRequest request
    ) {
        assertWritable(requester);
        SupportTicketEntity ticket = getTicketEntity(ticketId);
        Instant now = Instant.now();
        addEvent(ticket, "INTERNAL_NOTE", VISIBILITY_INTERNAL, request.content().trim(), requester.agentId(), now);
        touch(ticket, requester.agentId(), now);
        return toDetail(ticketRepository.save(ticket));
    }

    @Transactional
    public CsTicketDetailResponse updateAssignment(
            CsRequester requester,
            UUID ticketId,
            UpdateTicketAssignmentRequest request
    ) {
        assertLead(requester);
        SupportTicketEntity ticket = getTicketEntity(ticketId);
        Instant now = Instant.now();
        ticket.setAssignedAgentId(request.assignedAgentId().trim());
        addEvent(ticket, "ASSIGNED", VISIBILITY_INTERNAL, "assignedAgentId=" + ticket.getAssignedAgentId(), requester.agentId(), now);
        touch(ticket, requester.agentId(), now);
        return toDetail(ticketRepository.save(ticket));
    }

    @Transactional
    public CsTicketDetailResponse updateStatus(
            CsRequester requester,
            UUID ticketId,
            UpdateTicketStatusRequest request
    ) {
        assertWritable(requester);
        String nextStatus = normalizeUpper(request.status());
        if (!List.of(STATUS_OPEN, STATUS_ANSWERED, STATUS_CLOSED, "PENDING", "ESCALATED").contains(nextStatus)) {
            throw new SupportApiException("E-SUPPORT-TICKET-STATUS", "지원 티켓 상태값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST);
        }
        SupportTicketEntity ticket = getTicketEntity(ticketId);
        Instant now = Instant.now();
        ticket.setStatusCode(nextStatus);
        ticket.setClosedAt(STATUS_CLOSED.equals(nextStatus) ? now : null);
        addEvent(ticket, "STATUS_CHANGED", VISIBILITY_INTERNAL, nextStatus, requester.agentId(), now);
        touch(ticket, requester.agentId(), now);
        return toDetail(ticketRepository.save(ticket));
    }

    @Transactional
    public void createFromQna(QnaPostEntity post, String actorId) {
        ticketRepository.findByLinkedQnaIdAndUseYn(post.getQnaId(), Yn.YES)
                .orElseGet(() -> {
                    Instant now = Instant.now();
                    SupportTicketEntity ticket = SupportTicketEntity.builder()
                            .ticketId(UUID.randomUUID())
                            .channelCode(CHANNEL_QNA)
                            .statusCode(STATUS_OPEN)
                            .priorityCode(PRIORITY_NORMAL)
                            .tenantId(post.getTenantId())
                            .agencyId(post.getAgencyId())
                            .title(post.getQnaTitle())
                            .requesterName(post.getAnonymousDisplayName())
                            .requesterEmail(post.getAnonymousContactEmail())
                            .linkedQnaId(post.getQnaId())
                            .callbackRequiredYn(Yn.NO)
                            .useYn(Yn.YES)
                            .frstRegistPnttm(now)
                            .frstRegisterId(actorId)
                            .lastUpdtPnttm(now)
                            .lastUpdusrId(actorId)
                            .build();
                    addEvent(ticket, "QNA_CREATED", VISIBILITY_CUSTOMER, post.getQnaTitle(), actorId, now);
                    return ticketRepository.save(ticket);
                });
    }

    @Transactional
    public void recordQnaAnswer(UUID qnaId, String actorId, String content) {
        ticketRepository.findByLinkedQnaIdAndUseYn(qnaId, Yn.YES).ifPresent(ticket -> {
            Instant now = Instant.now();
            ticket.setStatusCode(STATUS_ANSWERED);
            addEvent(ticket, "PUBLIC_REPLY", VISIBILITY_CUSTOMER, content, actorId, now);
            touch(ticket, actorId, now);
            ticketRepository.save(ticket);
        });
    }

    private SupportTicketEntity getTicketEntity(UUID ticketId) {
        return ticketRepository.findByTicketIdAndUseYn(ticketId, Yn.YES)
                .orElseThrow(() -> new SupportApiException(
                        "E-SUPPORT-TICKET-404",
                        "지원 티켓을 찾을 수 없습니다.",
                        HttpStatus.NOT_FOUND));
    }

    private void addEvent(SupportTicketEntity ticket, String eventType, String visibility, String content, String actorId, Instant now) {
        SupportTicketEventEntity event = SupportTicketEventEntity.builder()
                .ticketEventId(UUID.randomUUID())
                .ticket(ticket)
                .eventTypeCode(eventType)
                .visibilityCode(visibility)
                .content(content)
                .actorId(actorId)
                .useYn(Yn.YES)
                .frstRegistPnttm(now)
                .frstRegisterId(actorId)
                .build();
        ticket.getEvents().add(event);
    }

    private void touch(SupportTicketEntity ticket, String actorId, Instant now) {
        ticket.setLastUpdtPnttm(now);
        ticket.setLastUpdusrId(actorId);
    }

    private CsTicketSummaryResponse toSummary(SupportTicketEntity entity) {
        return new CsTicketSummaryResponse(
                entity.getTicketId(),
                entity.getChannelCode(),
                entity.getStatusCode(),
                entity.getPriorityCode(),
                entity.getCategoryCode(),
                entity.getTenantId(),
                entity.getAgencyId(),
                entity.getTitle(),
                entity.getRequesterName(),
                entity.getAssignedAgentId(),
                Yn.isYes(entity.getCallbackRequiredYn()),
                entity.getCallbackDueAt(),
                entity.getLastUpdtPnttm()
        );
    }

    private CsTicketDetailResponse toDetail(SupportTicketEntity entity) {
        List<CsTicketEventResponse> events = entity.getEvents().stream()
                .filter(event -> Yn.YES.equals(event.getUseYn()))
                .map(event -> new CsTicketEventResponse(
                        event.getTicketEventId(),
                        event.getEventTypeCode(),
                        event.getVisibilityCode(),
                        event.getContent(),
                        event.getActorId(),
                        event.getFrstRegistPnttm()
                ))
                .toList();
        return new CsTicketDetailResponse(
                entity.getTicketId(),
                entity.getChannelCode(),
                entity.getStatusCode(),
                entity.getPriorityCode(),
                entity.getCategoryCode(),
                entity.getTenantId(),
                entity.getAgencyId(),
                entity.getTitle(),
                entity.getRequesterName(),
                entity.getRequesterPhone(),
                entity.getRequesterEmail(),
                entity.getAssignedAgentId(),
                entity.getLinkedQnaId(),
                Yn.isYes(entity.getCallbackRequiredYn()),
                entity.getCallbackDueAt(),
                entity.getClosedAt(),
                entity.getFrstRegistPnttm(),
                entity.getLastUpdtPnttm(),
                events
        );
    }

    private void assertStaff(CsRequester requester) {
        if (!requester.staff()) {
            throw new SupportApiException("E-SUPPORT-CS-403", "CS 백오피스 권한이 필요합니다.", HttpStatus.FORBIDDEN);
        }
    }

    private void assertWritable(CsRequester requester) {
        if (!requester.writable()) {
            throw new SupportApiException("E-SUPPORT-CS-WRITE-403", "CS 처리 권한이 필요합니다.", HttpStatus.FORBIDDEN);
        }
    }

    private void assertLead(CsRequester requester) {
        if (!requester.lead()) {
            throw new SupportApiException("E-SUPPORT-CS-LEAD-403", "CS 리드 권한이 필요합니다.", HttpStatus.FORBIDDEN);
        }
    }

    private String defaultUpper(String value, String defaultValue) {
        String normalized = normalizeUpper(value);
        return normalized == null ? defaultValue : normalized;
    }

    private String normalizeUpper(String value) {
        String trimmed = blankToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase();
    }

    private String trimToNull(String value) {
        return blankToNull(value);
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
