package io.github.hipstermin.idem.authz.application;

import io.github.hipstermin.idem.authz.api.AuthzErrorCode;
import io.github.hipstermin.idem.authz.api.AuthzException;
import io.github.hipstermin.idem.authz.api.dto.AssignRequest;
import io.github.hipstermin.idem.authz.api.dto.AssignmentRuleRequest;
import io.github.hipstermin.idem.authz.api.scim.ScimGroupId;
import io.github.hipstermin.idem.authz.domain.AssignmentRuleType;
import io.github.hipstermin.idem.authz.domain.AssignmentSource;
import io.github.hipstermin.idem.authz.domain.AssignmentStatus;
import io.github.hipstermin.idem.authz.domain.AuditEvent;
import io.github.hipstermin.idem.authz.domain.AuthzAssignmentEntity;
import io.github.hipstermin.idem.authz.domain.AuthzAssignmentRuleEntity;
import io.github.hipstermin.idem.authz.infrastructure.AuthzAssignmentRepository;
import io.github.hipstermin.idem.authz.infrastructure.AuthzAssignmentRuleRepository;
import io.github.hipstermin.idem.authz.infrastructure.AuthzUserRoleRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 1.1 규칙 할당 — 그룹(역할 보유)·속성(발급 컨텍스트) 조건으로 할당을 <b>실체화</b>한다.
 *
 * <p>설계 원칙:
 * <ol>
 *   <li>정본은 여전히 {@code authz_assignment} 다. 규칙은 "언제 할당 행을 만들지" 를 정할 뿐 — 발급·이벤트·감사·SCIM 은 그대로 동작</li>
 *   <li>실체화는 hub 의 접근 평가({@link #evaluate}) 시점 — 규칙을 만들었다고 미리 전 사용자를 훑지 않는다(대량 사용자에서 폭주 없음)</li>
 *   <li>source=RULE 할당은 매 접근 평가마다 <b>그 규칙을 재확인</b>한다: 규칙이 꺼졌거나 더는 맞지 않으면 회수(UNASSIGNED 전파) 후 다른 규칙을 본다</li>
 *   <li>규칙 비활성화는 그 규칙으로 만든 ACTIVE 할당을 즉시 회수한다 — "규칙을 껐는데 여전히 들어온다" 가 없다</li>
 *   <li>직접 할당(CONSOLE·SCIM·API·SELF_SIGNUP·ROLE_GRANT)이 있으면 규칙은 보지 않는다 — 규칙은 직접 할당을 덮어쓰지 않는다.
 *       명시적으로 <b>회수된</b> 직접 할당도 규칙이 되살리지 않는다(거부가 이긴다). 만료된 한시 할당은 규칙 대상이다</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssignmentRuleService {

    static final String RULE_ACTOR_PREFIX = "RULE:";

    private final AuthzAssignmentRuleRepository ruleRepository;
    private final AuthzAssignmentRepository assignmentRepository;
    private final AuthzUserRoleRepository userRoleRepository;
    private final AuthzService authzService;
    private final AuthzAuditService auditService;

    /** 접근 평가 결과 — hub 의 {@code ServiceAccess} 와 같은 모양. */
    public record Access(boolean assigned, String assignmentSource, List<String> roles) {}

    // ── 규칙 관리 ─────────────────────────────────────────────────────────────

    @Transactional
    public AuthzAssignmentRuleEntity create(AssignmentRuleRequest req, String actor, String actorIp, String correlationId) {
        AssignmentRuleType type = parseType(req.ruleType());
        String matchKey = req.matchKey().trim();
        String matchValues = null;
        if (type == AssignmentRuleType.GROUP) {
            ScimGroupId.parse(matchKey);   // 형식 검증 — 없는 역할이어도 만들 수 있다(역할은 나중에 SCIM 으로 올 수 있다)
        } else {
            List<String> values = req.matchValues() == null ? List.of()
                    : req.matchValues().stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
            if (values.isEmpty()) {
                throw new AuthzException(AuthzErrorCode.INVALID_REQUEST, "ATTRIBUTE 규칙은 matchValues 가 필요합니다.");
            }
            if (values.stream().anyMatch(v -> v.contains(","))) {
                throw new AuthzException(AuthzErrorCode.INVALID_REQUEST, "matchValues 값에는 콤마를 쓸 수 없습니다.");
            }
            matchValues = String.join(",", values);
        }
        AuthzAssignmentRuleEntity rule = AuthzAssignmentRuleEntity.builder()
                .id(UUID.randomUUID())
                .agencyCode(req.agencyCode().trim())
                .ruleType(type)
                .matchKey(matchKey)
                .matchValues(matchValues)
                .expiresDays(req.expiresDays())
                .enabled(true)
                .description(req.description())
                .createdBy(actor)
                .createdAt(Instant.now())
                .build();
        ruleRepository.save(rule);
        auditService.record(AuditEvent.RULE_CREATED, null, rule.getAgencyCode(), null, actor, actorIp,
                "rule=" + rule.getId() + " " + rule.summary(), correlationId);
        log.info("[q-authz] 규칙 할당 생성: id={} agency={} {}", rule.getId(), rule.getAgencyCode(), rule.summary());
        return rule;
    }

    @Transactional(readOnly = true)
    public List<AuthzAssignmentRuleEntity> list(String agencyCode) {
        if (agencyCode == null || agencyCode.isBlank()) {
            throw new AuthzException(AuthzErrorCode.INVALID_REQUEST, "agencyCode 쿼리 파라미터가 필요합니다.");
        }
        return ruleRepository.findByAgencyCodeOrderByCreatedAtAsc(agencyCode);
    }

    /**
     * 규칙 비활성화 + 그 규칙이 실체화한 ACTIVE 할당 회수(각각 UNASSIGNED 전파). 멱등 — 이미 꺼진 규칙은 회수 건수 0.
     *
     * @return 회수한 할당 수
     */
    @Transactional
    public int disable(UUID ruleId, String actor, String actorIp, String correlationId) {
        AuthzAssignmentRuleEntity rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new AuthzException(AuthzErrorCode.RULE_NOT_FOUND, "규칙 없음: " + ruleId));
        if (rule.isEnabled()) {
            rule.setEnabled(false);
            rule.setDisabledAt(Instant.now());
            rule.setDisabledBy(actor);
            ruleRepository.save(rule);
            auditService.record(AuditEvent.RULE_DISABLED, null, rule.getAgencyCode(), null, actor, actorIp,
                    "rule=" + ruleId + " " + rule.summary(), correlationId);
        }
        int revoked = 0;
        for (AuthzAssignmentEntity a : assignmentRepository.findByRuleIdAndStatus(ruleId, AssignmentStatus.ACTIVE)) {
            authzService.unassign(a.getQimUserId(), a.getAgencyCode(), actor, actorIp,
                    "규칙 비활성화: " + ruleId, correlationId);
            revoked++;
        }
        log.info("[q-authz] 규칙 할당 비활성화: id={} agency={} 회수={}건", ruleId, rule.getAgencyCode(), revoked);
        return revoked;
    }

    // ── 접근 평가 (hub 발급 경로) ──────────────────────────────────────────────

    /**
     * 할당 여부 + 유효 역할. 직접 할당이 없으면 규칙을 평가해 실체화한다. source=RULE 할당은 규칙을 재확인한다.
     *
     * @param attributes hub 가 보내는 비-PII 발급 컨텍스트 (authLevel·providerCode …). null 허용 = ATTRIBUTE 규칙은 모두 불일치
     */
    @Transactional
    public Access evaluate(String qimUserId, String agencyCode, Map<String, String> attributes,
                           String actorIp, String correlationId) {
        Instant now = Instant.now();
        Map<String, String> attrs = attributes == null ? Map.of() : attributes;
        Optional<AuthzAssignmentEntity> row = assignmentRepository.findByQimUserIdAndAgencyCode(qimUserId, agencyCode);
        if (row.isPresent() && row.get().getStatus() == AssignmentStatus.REVOKED
                && row.get().getSource() != AssignmentSource.RULE) {
            // 운영자가 명시적으로 회수한 직접 할당은 규칙이 되살리지 않는다 — 거부가 이긴다. 다시 넣으려면 직접 할당하거나 회수 행을 규칙으로 바꾼다
            log.debug("[q-authz] 규칙 평가 생략 — 명시 회수된 직접 할당: user={} agency={} source={}", qimUserId, agencyCode, row.get().getSource());
            return access(false, null, qimUserId, agencyCode);
        }
        Optional<AuthzAssignmentEntity> existing = row.filter(a -> a.isEffectiveAt(now));
        if (existing.isPresent()) {
            AuthzAssignmentEntity a = existing.get();
            if (a.getSource() != AssignmentSource.RULE || a.getRuleId() == null) {
                return access(true, a.getSource().name(), qimUserId, agencyCode);
            }
            AuthzAssignmentRuleEntity rule = ruleRepository.findById(a.getRuleId()).orElse(null);
            if (rule != null && rule.isEnabled() && matches(rule, qimUserId, attrs, now)) {
                return access(true, AssignmentSource.RULE.name(), qimUserId, agencyCode);
            }
            // 규칙이 꺼졌거나 더는 맞지 않는다 — 회수하고 다른 규칙을 본다
            authzService.unassign(qimUserId, agencyCode, RULE_ACTOR_PREFIX + a.getRuleId(), actorIp,
                    "규칙 재평가 불일치", correlationId);
        }
        for (AuthzAssignmentRuleEntity rule : ruleRepository.findByAgencyCodeAndEnabledTrueOrderByCreatedAtAsc(agencyCode)) {
            if (!matches(rule, qimUserId, attrs, now)) continue;
            Instant expiresAt = rule.getExpiresDays() != null ? now.plus(Duration.ofDays(rule.getExpiresDays())) : null;
            authzService.assign(new AssignRequest(qimUserId, agencyCode, RULE_ACTOR_PREFIX + rule.getId(), expiresAt,
                    AssignmentSource.RULE.name(), "규칙 실체화: " + rule.summary(), rule.getId().toString()),
                    actorIp, correlationId);
            log.info("[q-authz] 규칙 할당 실체화: user={} agency={} rule={}", qimUserId, agencyCode, rule.getId());
            return access(true, AssignmentSource.RULE.name(), qimUserId, agencyCode);
        }
        return access(false, null, qimUserId, agencyCode);
    }

    /** 규칙 판정 — GROUP: 그 역할이 유효한가, ATTRIBUTE: 보낸 속성 값이 허용 목록에 있는가. */
    boolean matches(AuthzAssignmentRuleEntity rule, String qimUserId, Map<String, String> attrs, Instant now) {
        if (rule.getRuleType() == AssignmentRuleType.GROUP) {
            ScimGroupId gid;
            try {
                gid = ScimGroupId.parse(rule.getMatchKey());
            } catch (AuthzException e) {
                return false;
            }
            return userRoleRepository.findByQimUserIdAndAgencyCodeAndRoleCode(qimUserId, gid.agencyCode(), gid.roleCode())
                    .map(r -> r.isEffectiveAt(now))
                    .orElse(false);
        }
        return rule.matchesAttribute(attrs.get(rule.getMatchKey()));
    }

    private Access access(boolean assigned, String source, String qimUserId, String agencyCode) {
        return new Access(assigned, source, authzService.effectiveRoleCodes(qimUserId, agencyCode));
    }

    private static AssignmentRuleType parseType(String raw) {
        try {
            return AssignmentRuleType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AuthzException(AuthzErrorCode.INVALID_REQUEST, "ruleType 은 GROUP 또는 ATTRIBUTE 여야 합니다: " + raw);
        }
    }
}
