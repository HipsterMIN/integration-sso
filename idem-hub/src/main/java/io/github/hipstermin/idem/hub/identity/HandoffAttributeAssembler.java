package io.github.hipstermin.idem.hub.identity;

import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.identity.AttributeCatalog;
import io.github.hipstermin.idem.common.identity.AttributeDefinition;
import io.github.hipstermin.idem.common.identity.MaskingRule;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.tenant.TenantProfile;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Tenant Profile {@code identity.attributes/attributeMapping} 으로 Handoff {@code attributes} 를 만든다 (S4).
 *
 * <p>규칙:
 * <ul>
 *   <li>선언이 없거나 비어 있으면 빈 맵 — 최소 권한. (종전 {@code allowed_attributes} 와 같은 의미)</li>
 *   <li>값의 원천은 카탈로그가 정한다: TICKET 은 티켓에서, PROFILE 은 registry 프로필(선언이 있을 때만 1회 조회),
 *       SUBJECT 는 registry 주체 키(스킴별 1회 조회)</li>
 *   <li>마스킹은 선택 항목의 {@code masking}, 없으면 카탈로그 기본값</li>
 *   <li>출력 키는 {@code attributeMapping}(정규 이름·선언 이름 어느 쪽으로든), 없으면 <b>선언한 이름 그대로</b> —
 *       별칭(camelCase)으로 선언한 기존 기관은 종전 페이로드 키를 그대로 받는다</li>
 *   <li>{@code required:true} 인데 값이 없으면 E-IDO-114 — Handoff 를 거부한다</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandoffAttributeAssembler {

    private final QimClient qimClient;
    private final SubjectIdentifierResolver subjectResolver;

    public Map<String, Object> assemble(TenantProfile.Identity identity, HandoffTicket ticket, String correlationId) {
        if (identity == null || identity.attributes() == null || identity.attributes().isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> mapping = identity.attributeMapping() != null ? identity.attributeMapping() : Map.of();
        Map<String, Object> ticketValues = ticketValues(ticket);
        Map<String, Object> profile = null;                       // 지연 조회
        Map<SubjectScheme, Optional<String>> subjectKeys = new EnumMap<>(SubjectScheme.class);

        Map<String, Object> out = new LinkedHashMap<>();
        List<String> missingRequired = new ArrayList<>();
        for (TenantProfile.AttributeSelection sel : identity.attributes()) {
            if (sel == null || sel.name() == null) continue;
            Optional<AttributeDefinition> found = AttributeCatalog.find(sel.name());
            if (found.isEmpty()) {
                // 검증(E-IDO-113)이 막지만, 검증 전에 저장된 레거시 컬럼 값일 수 있다 — 조용히 넘기지 않고 경고
                log.warn("[AttrAssembler] 카탈로그에 없는 속성 선언 무시: tenant={} name={}", ticket.getAgencyCode(), sel.name());
                continue;
            }
            AttributeDefinition def = found.get();
            Object value = switch (def.source()) {
                case TICKET -> ticketValues.get(def.sourceKey());
                case PROFILE -> {
                    if (profile == null) profile = fetchProfile(ticket.getQimUserId(), correlationId);
                    yield profile.get(def.sourceKey());
                }
                case SUBJECT -> subjectKeys.computeIfAbsent(def.subjectScheme(), scheme ->
                        subjectResolver.resolve(scheme, SubjectResolutionContext.builder()
                                .qimUserId(ticket.getQimUserId()).tenantCode(ticket.getAgencyCode())
                                .correlationId(correlationId).build())).orElse(null);
            };
            if (value == null) {
                if (sel.isRequired()) missingRequired.add(sel.name());
                continue;
            }
            MaskingRule rule = sel.masking() != null ? sel.masking() : def.defaultMasking();
            if (value instanceof String s) value = rule.apply(s);
            String outKey = mapping.getOrDefault(def.name(), mapping.getOrDefault(sel.name(), sel.name()));
            out.put(outKey, value);
        }
        if (!missingRequired.isEmpty()) {
            throw new PlatformException(PlatformErrorCode.IDO_REQUIRED_ATTRIBUTE_MISSING, correlationId,
                    "tenant=" + ticket.getAgencyCode() + " missing=" + missingRequired);
        }
        return out;
    }

    private Map<String, Object> fetchProfile(String qimUserId, String correlationId) {
        Map<String, Object> p = qimClient.getUserById(qimUserId, correlationId);
        if (p == null) {
            // registry 장애를 "속성 없음" 으로 삼키면 required 판정이 틀어진다 — 안전 우선 거부(F4.6)
            throw new PlatformException(PlatformErrorCode.IDO_QIM_UNREACHABLE, correlationId, "사용자 프로필 조회 실패");
        }
        return p;
    }

    private static Map<String, Object> ticketValues(HandoffTicket t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("qimUserId", t.getQimUserId());
        m.put("authLevel", t.getAuthLevel() != null ? t.getAuthLevel().name() : null);
        m.put("authResultId", t.getAuthResultId());
        m.put("agencyCode", t.getAgencyCode());
        m.put("issuedAt", t.getIssuedAt() != null ? t.getIssuedAt().toString() : null);
        return m;
    }
}
