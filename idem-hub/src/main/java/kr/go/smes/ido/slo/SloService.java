package kr.go.smes.ido.slo;

import kr.go.smes.ido.fe.session.FeSession;

/**
 * Single Logout (SLO) 오케스트레이션 서비스 인터페이스
 * 설계서 §13.3 / Sprint 2 P1-01~03
 *
 * <p><b>SLO 완전 흐름</b>:
 * <pre>
 *   FE 로그아웃 버튼
 *     → POST /api/v1/slo/initiate (SloController)
 *       → SloServiceImpl.executeSlo(session, correlationId)
 *           ① feSession 만료 (Redis 삭제) — SloController 책임
 *           ② Q-Sign → Keycloak 세션 종료 (비치명적)
 *           ③ 기관 로그아웃 Webhook Outbox 적재 (비치명적)
 *           ④ 감사 로그 기록
 *     → 204 No Content
 * </pre>
 */
public interface SloService {

    /**
     * SLO 핵심 오케스트레이션 실행
     *
     * <p>feSession은 호출자(SloController)가 이미 만료 처리한 상태.
     * 이 메서드는 Keycloak 세션 종료 + 기관 Webhook + 감사 로그를 담당한다.
     *
     * @param session       로그아웃할 FE 세션 (qimUserId, sub 포함)
     * @param correlationId 분산 추적 ID
     */
    void executeSlo(FeSession session, String correlationId);
}
