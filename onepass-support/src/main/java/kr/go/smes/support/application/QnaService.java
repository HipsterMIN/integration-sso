package kr.go.smes.support.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.go.smes.support.api.SupportApiException;
import kr.go.smes.support.api.dto.AnswerQnaRequest;
import kr.go.smes.support.api.dto.CreateQnaRequest;
import kr.go.smes.support.api.dto.QnaAnswerResponse;
import kr.go.smes.support.api.dto.QnaDetailResponse;
import kr.go.smes.support.api.dto.QnaSummaryResponse;
import kr.go.smes.support.domain.QnaAnswerEntity;
import kr.go.smes.support.domain.QnaPostEntity;
import kr.go.smes.support.domain.QnaPostRepository;
import kr.go.smes.support.domain.Yn;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QnaService {

    private final QnaPostRepository qnaPostRepository;

    @Transactional(readOnly = true)
    public List<QnaSummaryResponse> listForUser(
            SupportRequester requester,
            String tenantId,
            String agencyId
    ) {
        return qnaPostRepository.findVisibleForUser(
                        blankToNull(tenantId),
                        blankToNull(agencyId),
                        requester.authenticated() ? requester.userId() : null
                ).stream()
                .map(entity -> new QnaSummaryResponse(
                        entity.getQnaId(),
                        entity.getQnaTitle(),
                        entity.getQnaStatusCode(),
                        Yn.isYes(entity.getSecretYn()),
                        requester.authenticated() && requester.userId().equals(entity.getWriterUserId()),
                        Yn.isYes(entity.getAnonymousYn()),
                        entity.getFrstRegistPnttm()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public QnaDetailResponse getForUser(SupportRequester requester, UUID qnaId) {
        QnaPostEntity entity = getQnaPost(qnaId);
        boolean canReadSecret = canReadSecretPost(entity, requester);
        if (!canReadSecret) {
            throw new SupportApiException("E-SUPPORT-QNA-403", "비밀글은 작성자 또는 관리자만 조회할 수 있습니다.", HttpStatus.FORBIDDEN);
        }
        return toDetailResponse(entity, requester, false);
    }

    @Transactional
    public QnaDetailResponse create(SupportRequester requester, CreateQnaRequest request) {
        validateCreateRule(requester, request);
        Instant now = Instant.now();
        boolean anonymous = !requester.authenticated();
        QnaPostEntity entity = QnaPostEntity.builder()
                .qnaId(UUID.randomUUID())
                .tenantId(blankToNull(request.tenantId()))
                .agencyId(blankToNull(request.agencyId()))
                .qnaTitle(request.title().trim())
                .qnaContent(request.content().trim())
                .qnaStatusCode("OPEN")
                .secretYn(Yn.fromBoolean(request.secret()))
                .anonymousYn(Yn.fromBoolean(anonymous))
                .writerUserId(anonymous ? null : requester.userId())
                .anonymousDisplayName(anonymous ? request.anonymousDisplayName() : null)
                .anonymousContactEmail(anonymous ? request.anonymousContactEmail() : null)
                .useYn(Yn.YES)
                .frstRegistPnttm(now)
                .frstRegisterId(anonymous ? "ANONYMOUS" : requester.userId())
                .lastUpdtPnttm(now)
                .lastUpdusrId(anonymous ? "ANONYMOUS" : requester.userId())
                .build();
        qnaPostRepository.save(entity);
        return toDetailResponse(entity, requester, false);
    }

    @Transactional(readOnly = true)
    public List<QnaSummaryResponse> listForAdmin(
            SupportRequester requester,
            String tenantId,
            String agencyId
    ) {
        assertAdmin(requester);
        return qnaPostRepository.findAllForAdmin(blankToNull(tenantId), blankToNull(agencyId)).stream()
                .map(entity -> new QnaSummaryResponse(
                        entity.getQnaId(),
                        entity.getQnaTitle(),
                        entity.getQnaStatusCode(),
                        Yn.isYes(entity.getSecretYn()),
                        requester.authenticated() && requester.userId().equals(entity.getWriterUserId()),
                        Yn.isYes(entity.getAnonymousYn()),
                        entity.getFrstRegistPnttm()
                ))
                .toList();
    }

    @Transactional
    public QnaDetailResponse answerByAdmin(
            SupportRequester requester,
            UUID qnaId,
            AnswerQnaRequest request
    ) {
        assertAdmin(requester);
        QnaPostEntity post = getQnaPost(qnaId);
        if (Yn.isYes(post.getAnonymousYn()) && request.secret()) {
            throw new SupportApiException(
                    "E-SUPPORT-QNA-ANON-SECRET-ANSWER",
                    "익명 문의에는 비밀 답변을 등록할 수 없습니다.",
                    HttpStatus.BAD_REQUEST
            );
        }
        boolean secretAnswer = request.secret() || Yn.isYes(post.getSecretYn());
        Instant now = Instant.now();
        QnaAnswerEntity answer = QnaAnswerEntity.builder()
                .qnaAnswerId(UUID.randomUUID())
                .qnaPost(post)
                .answerContent(request.content().trim())
                .secretYn(Yn.fromBoolean(secretAnswer))
                .answeredByUserId(requester.userId())
                .useYn(Yn.YES)
                .frstRegistPnttm(now)
                .frstRegisterId(requester.userId())
                .lastUpdtPnttm(now)
                .lastUpdusrId(requester.userId())
                .build();
        post.getAnswers().add(answer);
        post.setQnaStatusCode("ANSWERED");
        post.setLastUpdtPnttm(now);
        post.setLastUpdusrId(requester.userId());
        qnaPostRepository.save(post);
        return toDetailResponse(post, requester, true);
    }

    private void validateCreateRule(SupportRequester requester, CreateQnaRequest request) {
        if (!requester.authenticated() && request.secret()) {
            throw new SupportApiException(
                    "E-SUPPORT-QNA-ANON-SECRET",
                    "익명 사용자는 비밀글을 작성할 수 없습니다.",
                    HttpStatus.BAD_REQUEST
            );
        }
        if (!requester.authenticated()) {
            if (request.anonymousDisplayName() == null || request.anonymousDisplayName().isBlank()) {
                throw new SupportApiException(
                        "E-SUPPORT-QNA-ANON-NAME",
                        "익명 사용자는 표시 이름을 입력해야 합니다.",
                        HttpStatus.BAD_REQUEST
                );
            }
        }
    }

    private QnaPostEntity getQnaPost(UUID qnaId) {
        return qnaPostRepository.findByQnaIdAndUseYn(qnaId, Yn.YES)
                .orElseThrow(() -> new SupportApiException(
                        "E-SUPPORT-QNA-404",
                        "Q&A 게시글을 찾을 수 없습니다.",
                        HttpStatus.NOT_FOUND));
    }

    private boolean canReadSecretPost(QnaPostEntity entity, SupportRequester requester) {
        if (!Yn.isYes(entity.getSecretYn())) {
            return true;
        }
        if (requester.admin()) {
            return true;
        }
        return requester.authenticated() && requester.userId().equals(entity.getWriterUserId());
    }

    private boolean canReadSecretAnswer(QnaAnswerEntity answer, QnaPostEntity post, SupportRequester requester) {
        if (!Yn.isYes(answer.getSecretYn())) {
            return true;
        }
        if (requester.admin()) {
            return true;
        }
        return requester.authenticated() && requester.userId().equals(post.getWriterUserId());
    }

    private QnaDetailResponse toDetailResponse(QnaPostEntity post, SupportRequester requester, boolean adminView) {
        boolean canReadPostContent = canReadSecretPost(post, requester);
        List<QnaAnswerResponse> answers = post.getAnswers().stream()
                .filter(answer -> Yn.YES.equals(answer.getUseYn()))
                .map(answer -> {
                    boolean canRead = canReadSecretAnswer(answer, post, requester);
                    return new QnaAnswerResponse(
                            answer.getQnaAnswerId(),
                            Yn.isYes(answer.getSecretYn()),
                            !canRead,
                            canRead ? answer.getAnswerContent() : null,
                            adminView ? answer.getAnsweredByUserId() : null,
                            answer.getFrstRegistPnttm()
                    );
                })
                .toList();

        return new QnaDetailResponse(
                post.getQnaId(),
                post.getTenantId(),
                post.getAgencyId(),
                post.getQnaTitle(),
                Yn.isYes(post.getSecretYn()),
                !canReadPostContent,
                canReadPostContent ? post.getQnaContent() : null,
                Yn.isYes(post.getAnonymousYn()),
                post.getWriterUserId(),
                post.getAnonymousDisplayName(),
                post.getQnaStatusCode(),
                post.getFrstRegistPnttm(),
                post.getLastUpdtPnttm(),
                answers
        );
    }

    private void assertAdmin(SupportRequester requester) {
        if (!requester.admin()) {
            throw new SupportApiException("E-SUPPORT-AUTH-403", "관리자 권한이 필요합니다.", HttpStatus.FORBIDDEN);
        }
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}

