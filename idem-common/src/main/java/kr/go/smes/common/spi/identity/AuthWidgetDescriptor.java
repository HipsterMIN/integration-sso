package kr.go.smes.common.spi.identity;

import java.util.Collections;
import java.util.Map;

/**
 * FE 위젯 기술자 — 위젯형 제공자(간편인증 JS 등)가 스크립트 위치와 초기화 계약을 코어에 알린다.
 * FE 는 {@code GET /api/v1/auth/providers} 로 이 값을 받아 스크립트를 동적으로 로드한다.
 *
 * @param scriptUrl  스크립트 URL (플러그인 jar 의 static 자산 경로 권장, 예: /plugins/nice-oacx/EzAuth.bundle.js)
 * @param globalName 스크립트가 등록하는 전역 객체명 (예: EzAuth)
 * @param initParams 초기화 파라미터 — null 이면 빈 맵
 */
public record AuthWidgetDescriptor(String scriptUrl, String globalName, Map<String, Object> initParams) {
    public AuthWidgetDescriptor {
        initParams = initParams == null ? Collections.emptyMap() : Map.copyOf(initParams);
    }
}
