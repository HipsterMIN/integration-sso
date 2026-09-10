package io.github.hipstermin.idem.hub.policy.rule;

import java.util.Map;

/**
 * 정책 규칙 SPI (S3, {@code docs/generalization-plan.md} §3 S3).
 *
 * <p>Handoff 발급 등 접근 결정 시점에 {@link PolicyContext} 를 받아 {@link PolicyDecision} 을 돌려준다.
 * 내장 규칙(MAINTENANCE · MIN_AUTH_LEVEL · ALLOWED_PROVIDERS · USER_STATUS)은 항상 평가되며, 기관 프로파일에
 * 해당 설정이 없으면 SKIP 한다. 그 밖의 {@code PolicyRule} 빈은 프로파일 {@code policy.rules[].type} 으로 지정한
 * 기관에만 적용된다(에디션 플러그인의 특수 규칙 — 코어 코드 분기 대신 이 SPI 로 붙인다).
 *
 * <p>규칙은 순수 함수여야 한다 — 외부 호출이 필요하면 {@link PolicyContext} 의 지연 공급자를 쓴다
 * (예: 사용자 상태는 Q-IM 호출이므로 {@code Supplier}).
 */
public interface PolicyRule {

    /** 규칙 식별자. 프로파일 {@code policy.rules[].type} 과 감사 로그에 쓰인다. 대문자·숫자·밑줄. */
    String type();

    /** 내장 규칙은 항상 평가된다. 커스텀 규칙은 프로파일이 지정한 기관에서만. */
    default boolean builtIn() {
        return false;
    }

    /** 평가 순서 — 작을수록 먼저. 비용이 큰 규칙(외부 조회)은 뒤로. */
    default int order() {
        return 100;
    }

    /**
     * @param ctx    평가 컨텍스트 (기관 프로파일·요청 속성·시각)
     * @param params 프로파일 {@code policy.rules[].params} — 지정이 없으면 빈 맵
     */
    PolicyDecision evaluate(PolicyContext ctx, Map<String, Object> params);
}
