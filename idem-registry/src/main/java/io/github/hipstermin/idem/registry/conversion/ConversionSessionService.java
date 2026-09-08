package io.github.hipstermin.idem.registry.conversion;

import java.util.List;

/**
 * 통합계정 전환 세션 서비스 인터페이스 (P2 §12.1)
 *
 * <p>CI 기반으로 68개 유관 시스템 회원을 찾아
 * 단일 Q-IM 통합계정(UUID)으로 연결하는 상태 기계를 관리한다.
 *
 * <p><b>전체 흐름</b>:
 * <pre>
 * 1. initiate()          — CI 확인 후 세션 생성 (INITIATED)
 * 2. fetchCandidates()   — 유관 시스템 회원 목록 조회 (MEMBERS_FETCHED)
 * 3. selectAccounts()    — 사용자 연결 대상 선택 (ACCOUNT_SELECTED)
 * 4. link()              — 계정 연결 실행 (LINKING → COMPLETED)
 *    또는
 *    cancel()            — 사용자 취소 (CANCELLED)
 * </pre>
 */
public interface ConversionSessionService {

    /**
     * 전환 세션 시작 — INITIATED
     *
     * <p>사용자가 본인인증(OACX/NICE) 완료 후 전환을 시작할 때 호출.
     * 이미 활성 세션이 있으면 기존 세션 반환.
     *
     * @param qimUserId     Q-IM 사용자 ID
     * @param correlationId 흐름 추적 ID
     * @return 생성된 전환 세션 정보
     */
    ConversionSessionResult initiate(String qimUserId, String correlationId);

    /**
     * 유관 시스템 회원 후보 목록 조회 — INITIATED → MEMBERS_FETCHED
     *
     * <p>AgencyMemberLookupService를 통해 68개 유관 시스템에서
     * CI 해시로 기존 회원을 조회하고 후보 목록을 세션에 저장.
     *
     * @param sessionId     전환 세션 ID
     * @param correlationId 흐름 추적 ID
     * @return 갱신된 세션 (candidateMembers 포함)
     */
    ConversionSessionResult fetchCandidates(String sessionId, String correlationId);

    /**
     * 연결 대상 계정 선택 — MEMBERS_FETCHED → ACCOUNT_SELECTED
     *
     * @param sessionId        전환 세션 ID
     * @param selectedAgencies 사용자가 선택한 기관 코드 목록
     * @param correlationId    흐름 추적 ID
     * @return 갱신된 세션
     */
    ConversionSessionResult selectAccounts(String sessionId,
                                            List<String> selectedAgencies,
                                            String correlationId);

    /**
     * 계정 연결 실행 — ACCOUNT_SELECTED → LINKING → COMPLETED
     *
     * <p>선택된 기관 계정들을 Q-IM 통합계정에 연결(mapping) 처리.
     * 각 기관의 agencySubjectId를 auth_mean_mapping에 등록.
     *
     * @param sessionId     전환 세션 ID
     * @param correlationId 흐름 추적 ID
     * @return 완료된 세션 (linkedAgencyCodes 포함)
     */
    ConversionSessionResult link(String sessionId, String correlationId);

    /**
     * 세션 취소 — any → CANCELLED
     *
     * @param sessionId     전환 세션 ID
     * @param reason        취소 사유
     * @param correlationId 흐름 추적 ID
     * @return 취소된 세션
     */
    ConversionSessionResult cancel(String sessionId, String reason, String correlationId);

    /**
     * 세션 상태 조회
     */
    ConversionSessionResult getSession(String sessionId, String correlationId);

    /**
     * 만료된 세션 일괄 처리 (스케줄러 호출)
     *
     * @return 처리된 세션 수
     */
    int expireStale();
}
