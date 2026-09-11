package io.github.hipstermin.idem.common.spi.broker;

/**
 * 직접 브로커 인증 완료 포트 — 플러그인이 벤더 인증 결과(식별자·인증 수준)를 확정하면 코어가 뒷일을 맡는다.
 *
 * <p>코어({@code idem-hub})가 구현하고 플러그인이 호출한다. 코어는 AuthResult 저장·감사·이벤트 발행·FE 세션 발급을 하고,
 * 플러그인은 벤더 프로토콜(복호화·서명 검증)만 책임진다. 플러그인은 코어 서비스 클래스를 직접 참조하지 않는다.
 */
public interface BrokerAuthCompletion {

    /**
     * 벤더 인증 결과를 코어 인증 결과로 확정한다.
     *
     * @throws RuntimeException 코어 처리 실패(저장·이벤트) — 플러그인은 그대로 실패 응답으로 바꾼다
     */
    Result complete(Command command);

    /**
     * @param correlationId  흐름 추적 ID
     * @param providerCode   정규화된 provider 코드 (예: {@code MOBILE_ID})
     * @param providerTxId   벤더 거래 ID
     * @param rawIdentifier  벤더가 준 원문 식별자(CI 등). 코어가 해시해 저장하며 평문은 남기지 않는다
     * @param authLevel      정규화된 인증 수준 (L1/L2/L3)
     * @param returnUrl      인증 완료 후 귀환 URL (null 허용)
     */
    record Command(String correlationId,
                   String providerCode,
                   String providerTxId,
                   String rawIdentifier,
                   String authLevel,
                   String returnUrl) {
    }

    /**
     * @param authResultId 코어가 저장한 AuthResult ID
     * @param feSessionId  발급된 FE 세션 ID (세션 발급 실패 시 임시 ID — 코어가 로그로 남긴다)
     */
    record Result(String authResultId, String feSessionId) {
    }
}
