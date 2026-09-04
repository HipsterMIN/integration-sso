package kr.go.smes.ido.qim.sp.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Q-IM SP 연동 — instMbrId 매핑 도메인 객체
 *
 * Q-IM 명세서 v1.52 §4.3:
 *   SP는 자체 회원 식별자(instMbrId)를 응답에 포함하여
 *   Q-IM이 추후 송수신에서 매핑을 유지할 수 있도록 한다.
 *
 * 설계 결정:
 *   instMbrId = qimUserId (UUID 1:1 매핑)
 *   이유: qimUserId가 이미 전역 유일 UUID이므로 별도 ID 체계 불필요.
 *   향후 다른 ID 체계 요구 시 이 매핑 테이블을 통해 전환 가능.
 *
 * @see <a href="docs/qim-ido-integration-architecture.md">§6 식별자 매핑 체계</a>
 */
@Getter
@Builder(toBuilder = true)
public class InstMbrIdMapping {

    private final String instMbrId;        // SP 내부 식별자 (= qimUserId)
    private final String qimUserId;        // Q-IM 내부 UUID
    private final String mbrUuid;          // Q-IM 발행 mbrUuid (MEMBER_REGISTER 수신 시)
    private final String mbrNo;            // Q-IM 발행 mbrNo
    private final String identifierHash;   // SHA-256(CI) — 조회 최적화용
    private final MemberType memberType;
    private final MappingStatus status;
    private final RegMode regMode;
    private final Instant registeredAt;
    private final Instant withdrawnAt;

    public enum MemberType {
        PERSONAL, CORPORATE
    }

    public enum MappingStatus {
        ACTIVE, WITHDRAWN, SUSPENDED
    }

    public enum RegMode {
        NEW, TRANSFER
    }

    /** 이미 탈퇴된 회원 여부 */
    public boolean isWithdrawn() {
        return this.status == MappingStatus.WITHDRAWN;
    }

    /** MEMBER_QUERY 응답용 — 회원 존재 여부와 instMbrId 반환 */
    public static InstMbrIdMapping newPersonal(String qimUserId, String identifierHash, RegMode regMode) {
        return InstMbrIdMapping.builder()
                .instMbrId(qimUserId)          // instMbrId = qimUserId
                .qimUserId(qimUserId)
                .identifierHash(identifierHash)
                .memberType(MemberType.PERSONAL)
                .status(MappingStatus.ACTIVE)
                .regMode(regMode)
                .registeredAt(Instant.now())
                .build();
    }

    public static InstMbrIdMapping newCorporate(String qimUserId, String identifierHash, RegMode regMode) {
        return InstMbrIdMapping.builder()
                .instMbrId(qimUserId)
                .qimUserId(qimUserId)
                .identifierHash(identifierHash)
                .memberType(MemberType.CORPORATE)
                .status(MappingStatus.ACTIVE)
                .regMode(regMode)
                .registeredAt(Instant.now())
                .build();
    }
}
