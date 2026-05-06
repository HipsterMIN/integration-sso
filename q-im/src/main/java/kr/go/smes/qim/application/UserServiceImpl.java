package kr.go.smes.qim.application;

import kr.go.smes.common.domain.UserStatus;
import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import kr.go.smes.common.event.UserEvent;
import kr.go.smes.qim.domain.AuthMeanMapping;
import kr.go.smes.qim.domain.QimUser;
import kr.go.smes.qim.infrastructure.UserRepository;
import kr.go.smes.qim.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
                .qimUserId(UUID.randomUUID().toString())
                .status(UserStatus.ACTIVE)
                .authMeanMappings(List.of(AuthMeanMapping.builder()
                        .mappingId(UUID.randomUUID().toString())
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
        // TODO: 실제 구현 시 매핑 목록에 추가 후 저장
        log.info("[Q-IM] 인증수단 추가 qimUserId={} provider={}", qimUserId, providerCode);
        return user;
    }

    // ── private ─────────────────────────────────────────────────────────────

    private UserEvent buildUserEvent(String eventType, QimUser user, String correlationId,
                                     long version, String reason, boolean needsSync) {
        return new UserEvent(eventType, SOURCE_SYSTEM, correlationId,
                user.getQimUserId(), version,
                user.getStatus().name(), reason, needsSync);
    }
}
