package io.github.hipstermin.idem.registry.consent;

import java.util.List;

/**
 * 개인정보 동의 서비스 인터페이스 (P2 §12.3)
 */
public interface ConsentService {

    /**
     * 동의 기록 — 사용자가 지정 버전에 동의
     *
     * @param qimUserId   동의 사용자 ID
     * @param request     동의 요청 (유형, 버전ID, 경로 등)
     * @return 동의 처리 결과
     */
    ConsentResult agree(String qimUserId, ConsentRequest request);

    /**
     * 동의 철회 — 선택 동의(required=false)만 철회 가능
     *
     * @param qimUserId   철회 사용자 ID
     * @param consentType 철회할 동의 유형
     * @param reason      철회 사유
     * @param correlationId 흐름 추적 ID
     * @return 철회 처리 결과
     */
    ConsentResult withdraw(String qimUserId, String consentType,
                            String reason, String correlationId);

    /**
     * 사용자 동의 현황 조회
     *
     * @param qimUserId 조회 사용자 ID
     * @return 동의 유형별 최신 상태 목록
     */
    List<ConsentResult> getConsentStatus(String qimUserId);

    /**
     * 최신 동의 버전 목록 조회 (회원가입/재동의 화면 로딩)
     */
    List<ConsentVersionInfo> getActiveVersions();
}
