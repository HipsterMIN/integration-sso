package kr.go.smes.qim.user;

import kr.go.smes.qim.api.dto.UserRegisterRequest;
import kr.go.smes.qim.api.dto.UserResponse;

/**
 * 사용자 등록 서비스 인터페이스
 * 설계서 §10.3 사용자 생명주기 관리
 */
public interface UserRegistrationService {

    /**
     * 인증 결과로부터 사용자 등록 (Upsert)
     * - 동일 identifierHash 존재 시 기존 사용자 반환
     * - 신규 사용자는 생성 후 UserEvent Kafka 발행
     */
    UserResponse registerOrGet(UserRegisterRequest request);

    /**
     * 사용자 상태 변경 (ACTIVE → SUSPENDED → WITHDRAWN)
     */
    void updateStatus(String qimUserId, String newStatus, String changedBy, String reason);

    /**
     * 사용자 탈퇴 처리 (Right to be Forgotten)
     * - status → WITHDRAWN
     * - user_profile PII 즉시 삭제
     * - Kafka UserEvent.USER_WITHDRAWN 발행
     */
    void withdraw(String qimUserId, String reason);
}
