package io.github.hipstermin.idem.authz.application;

import io.github.hipstermin.idem.authz.domain.AuditEvent;
import io.github.hipstermin.idem.authz.domain.AuthzGrantAuditEntity;
import io.github.hipstermin.idem.authz.infrastructure.AuthzGrantAuditRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 인가 부여/회수 append-only 감사 기록. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthzAuditService {

    private final AuthzGrantAuditRepository auditRepository;

    public void record(AuditEvent event, String qimUserId, String agencyCode, String roleCode,
                       String actor, String actorIp, String reason, String correlationId) {
        AuthzGrantAuditEntity audit = AuthzGrantAuditEntity.builder()
                .id(UUID.randomUUID())
                .event(event)
                .qimUserId(qimUserId)
                .agencyCode(agencyCode)
                .roleCode(roleCode)
                .actor(actor)
                .actorIp(actorIp)
                .reason(reason)
                .correlationId(correlationId)
                .at(Instant.now())
                .build();
        auditRepository.save(audit);
        log.info("[q-authz][audit] {} agency={} role={} user={} actor={} cid={}",
                event, agencyCode, roleCode, qimUserId, actor, correlationId);
    }
}
