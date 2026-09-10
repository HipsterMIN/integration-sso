package io.github.hipstermin.idem.registry.application;

import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.event.UserEvent;
import io.github.hipstermin.idem.common.util.UuidV7;
import io.github.hipstermin.idem.registry.domain.AuthMeanMapping;
import io.github.hipstermin.idem.registry.domain.QimUser;
import io.github.hipstermin.idem.registry.infrastructure.UserRepository;
import io.github.hipstermin.idem.registry.outbox.OutboxService;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Q-IM UserService 구현체
 * 설계서 10.3 / 10.5절 참조
 *
 * Transactional Outbox 패턴:
 *   - 사용자 테이블 변경 + OUTBOX 레코드 적재를 단일 DB 트랜잭션으로 묶음
 *   - 별도 Relay가 OUTBOX → Kafka 발행 (DB 커밋은 됐지만 이벤트 유실 방지)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String SOURCE_SYSTEM = "q-im";

    private final UserRepository  userRepository;
    private final OutboxService   outboxService;

    @Override
    public Optional<QimUser> findByIdentifierHash(String identifierHash, String correlationId) {
        return userRepository.findByIdentifierHash(identifierHash);
    }

    @Override
    public QimUser findById(String qimUserId, String correlationId) {
        return userRepository.findById(qimUserId)
                .orElseThrow(() -> new PlatformException(
                        PlatformErrorCode.IM_USER_NOT_FOUND, correlationId));
    }

    @Override
    @Transactional
    public QimUser registerUser(String identifierHash, String providerCode, String correlationId) {
        log.info("[Q-IM] 신규 사용자 등록 identifierHash={} provider={}", identifierHash, providerCode);

        // 중복 매핑 방지: 동일 identifierHash가 이미 다른 사용자에 매핑되어 있으면 거부
        userRepository.findByIdentifierHash(identifierHash).ifPresent(existing -> {
            throw new PlatformException(PlatformErrorCode.IM_IDENTIFIER_CONFLICT, correlationId);
        });

        long nextVersion = 1L;
        QimUser newUser = QimUser.builder()
                .qimUserId(UuidV7.generate())
                .status(UserStatus.ACTIVE)
                .authMeanMappings(List.of(AuthMeanMapping.builder()
                        .mappingId(UuidV7.generate())
                        .providerCode(providerCode)
                        .identifierHash(identifierHash)
                        .status(AuthMeanMapping.MappingStatus.ACTIVE)
                        .build()))
                .eventVersion(nextVersion)
                .build();

        // ① 사용자 저장 + ② Outbox 적재 (단일 트랜잭션)
        userRepository.save(newUser);
        outboxService.publishInTx(buildUserEvent(
                UserEvent.TYPE_UPDATED, newUser, correlationId, nextVersion, "신규 등록", true));

        return newUser;
    }

    @Override
    @Transactional
    public QimUser suspendUser(String qimUserId, String reason, String correlationId) {
        QimUser user = findById(qimUserId, correlationId);
        long nextVersion = user.getEventVersion() + 1;

        QimUser suspended = QimUser.builder()
                .qimUserId(user.getQimUserId())
                .status(UserStatus.SUSPENDED)
                .authMeanMappings(user.getAuthMeanMappings())
                .profile(user.getProfile())
                .eventVersion(nextVersion)
                .build();

        userRepository.save(suspended);
        outboxService.publishInTx(buildUserEvent(
                UserEvent.TYPE_SUSPENDED, suspended, correlationId, nextVersion, reason, true));

        log.info("[Q-IM] 사용자 정지 qimUserId={} reason={}", qimUserId, reason);
        return suspended;
    }

    @Override
    @Transactional
    public QimUser withdrawUser(String qimUserId, String reason, String correlationId) {
        QimUser user = findById(qimUserId, correlationId);
        long nextVersion = user.getEventVersion() + 1;

        QimUser withdrawn = QimUser.builder()
                .qimUserId(user.getQimUserId())
                .status(UserStatus.WITHDRAWN)
                .authMeanMappings(user.getAuthMeanMappings())
                .profile(user.getProfile())
                .eventVersion(nextVersion)
                .build();

        userRepository.save(withdrawn);
        outboxService.publishInTx(buildUserEvent(
                UserEvent.TYPE_WITHDRAWN, withdrawn, correlationId, nextVersion, reason, true));

        log.info("[Q-IM] 사용자 탈퇴 qimUserId={}", qimUserId);
        return withdrawn;
    }

    @Override
    @Transactional
    public QimUser addAuthMeanMapping(String qimUserId, String identifierHash,
                                      String providerCode, String correlationId) {
        QimUser user = findById(qimUserId, correlationId);

        // 중복 매핑 방지
        userRepository.findByIdentifierHash(identifierHash).ifPresent(existing -> {
            if (!existing.getQimUserId().equals(qimUserId)) {
                throw new PlatformException(PlatformErrorCode.IM_IDENTIFIER_CONFLICT, correlationId);
            }
        });

        long nextVersion = user.getEventVersion() + 1;

        // 신규 매핑 생성
        AuthMeanMapping newMapping = AuthMeanMapping.builder()
                .mappingId(UuidV7.generate())
                .qimUserId(qimUserId)
                .providerCode(providerCode)
                .identifierHash(identifierHash)
                .status(AuthMeanMapping.MappingStatus.ACTIVE)
                .linkedAt(java.time.Instant.now())
                .build();

        // 기존 매핑 목록에 추가 (불변 List 대비 새 List 생성)
        List<AuthMeanMapping> updatedMappings = new java.util.ArrayList<>(
                user.getAuthMeanMappings() != null ? user.getAuthMeanMappings() : List.of());
        updatedMappings.add(newMapping);

        QimUser updated = QimUser.builder()
                .qimUserId(user.getQimUserId())
                .status(user.getStatus())
                .authMeanMappings(updatedMappings)
                .profile(user.getProfile())
                .eventVersion(nextVersion)
                .build();

        // 저장 + Outbox 이벤트 발행 (단일 트랜잭션)
        userRepository.save(updated);
        outboxService.publishInTx(buildUserEvent(
                UserEvent.TYPE_UPDATED, updated, correlationId, nextVersion,
                "인증수단 추가: " + providerCode, true));

        log.info("[Q-IM] 인증수단 추가 완료 qimUserId={} provider={} mappingId={}",
                qimUserId, providerCode, newMapping.getMappingId());
        return updated;
    }

    // ── private ─────────────────────────────────────────────────────────────

    private UserEvent buildUserEvent(String eventType, QimUser user, String correlationId,
                                     long version, String reason, boolean needsSync) {
        return new UserEvent(eventType, SOURCE_SYSTEM, correlationId,
                user.getQimUserId(), version,
                user.getStatus().name(), reason, needsSync);
    }
}
