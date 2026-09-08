package io.github.hipstermin.idem.registry.conversion;

import java.util.List;

/**
 * 유관 시스템(68개 기관) CI 기반 기존 회원 조회 서비스 인터페이스
 *
 * <p>설계서 PPTX 2.1 프로세스 — 통합계정 전환 시 각 기관의 기존 회원 계정을 조회하여
 * 사용자에게 연결 후보를 제시한다.
 *
 * <p><b>CI(Connecting Information)</b>: 본인확인기관이 발급하는 연계정보.
 * Q-IM은 CI를 직접 노출하지 않고 SHA-256 파생 identifierHash를 통해 조회를 중개한다.
 *
 * <h3>조회 흐름</h3>
 * <pre>
 *   1. ConversionSession.fetchCandidates() 호출
 *   2. Q-IM 에서 qimUserId → CI 역산 불가 — identifierHash 로만 조회
 *   3. 각 기관 Stub API / 실 기관 API 에 identifierHash 전달 → 회원 존재 여부 응답
 *   4. CandidateMember 목록 반환 (PII 마스킹 적용)
 * </pre>
 *
 * <h3>동시성 전략</h3>
 * <ul>
 *   <li>단일 Q-IM 인스턴스에서 최대 68개 기관 API를 순차 호출하면 타임아웃 리스크 과다
 *   <li>실 연동 시 Virtual Thread(JDK 21) 또는 CompletableFuture 병렬 호출 권장
 *   <li>현재 구현체({@link AgencyMemberLookupServiceImpl})는 병렬 호출 + 개별 타임아웃 적용
 * </ul>
 */
public interface AgencyMemberLookupService {

    /**
     * 주어진 사용자의 identifierHash 를 이용해 유관 기관 전체를 조회하여
     * 기존 회원 후보 목록을 반환한다.
     *
     * @param qimUserId      Q-IM 사용자 ID (로그/감사용)
     * @param identifierHash 기관 API 전달용 식별자 해시 (SHA-256)
     * @param correlationId  요청 추적 ID
     * @return 기존 회원이 존재하는 기관의 후보 목록 (없으면 빈 List)
     */
    List<CandidateMember> lookupByIdentifierHash(String qimUserId,
                                                  String identifierHash,
                                                  String correlationId);

    /**
     * 선택된 기관 코드 목록에 대해 실제 계정 연결(auth_mean_mapping 추가)을 수행한다.
     *
     * <p>연결 성공한 기관 코드만 반환하며, 실패 기관은 로그에 기록하고 건너뛴다.
     * (부분 성공 허용 — 실패 기관이 있더라도 성공 기관은 즉시 반영)
     *
     * @param qimUserId          Q-IM 사용자 ID
     * @param identifierHash     식별자 해시
     * @param selectedAgencyCodes 사용자가 선택한 기관 코드 목록
     * @param correlationId      요청 추적 ID
     * @return 연결 성공한 기관 코드 목록
     */
    List<String> performLinking(String qimUserId,
                                 String identifierHash,
                                 List<String> selectedAgencyCodes,
                                 String correlationId);
}
