package kr.go.smes.sdk.agency.http;

import kr.go.smes.sdk.agency.model.GatewayResponse;

import java.util.Map;

/**
 * OnePass Agency SDK HTTP 어댑터 인터페이스
 *
 * <p>SDK의 HTTP 구현을 분리하는 핵심 추상화.
 * 이 인터페이스를 구현함으로써 <b>JDK 버전과 무관하게</b> 임의의 HTTP 클라이언트를
 * SDK에 연결할 수 있다.
 *
 * <h3>기본 제공 구현체</h3>
 * <ul>
 *   <li>{@link HttpUrlConnectionAdapter} — {@code HttpURLConnection} 기반, <b>JDK 1.1+, 의존성 없음</b></li>
 *   <li>{@link OkHttpAgencyAdapter} — OkHttp3 기반 (OkHttp 클래스패스 필요)</li>
 *   <li>{@link ApacheHttpAgencyAdapter} — Apache HttpClient 5.x 기반 (httpclient5 클래스패스 필요)</li>
 * </ul>
 *
 * <h3>커스텀 구현 예시 (Spring RestTemplate 연동)</h3>
 * <pre>{@code
 * AgencyHttpAdapter adapter = (method, url, headers, body) -> {
 *     HttpHeaders httpHeaders = new HttpHeaders();
 *     headers.forEach(httpHeaders::add);
 *     HttpEntity<String> entity = new HttpEntity<>(body, httpHeaders);
 *     ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.resolve(method), entity, String.class);
 *     return GatewayResponse.of(resp.getStatusCode().value(), resp.getBody(), null, null);
 * };
 * }</pre>
 *
 * <p><b>JDK 버전 호환: Java 8+</b>
 */
public interface AgencyHttpAdapter {

    /**
     * HTTP 요청을 실행하고 응답을 반환한다.
     *
     * @param method  HTTP 메서드 문자열 ({@code "POST"}, {@code "PATCH"}, {@code "GET"})
     * @param url     요청 대상 URL (완전한 형태: {@code https://onepass.go.kr/api/v1/...})
     * @param headers 요청 헤더 맵 ({@code Content-Type}, {@code X-Api-Key}, {@code X-Internal-Sig} 등)
     * @param body    요청 본문 JSON 문자열 (GET/DELETE 시 {@code null} 가능)
     * @return {@link GatewayResponse} 응답 래퍼
     * @throws kr.go.smes.sdk.agency.exception.AgencyHttpException HTTP 오류(4xx/5xx) 또는 네트워크 오류 시
     */
    GatewayResponse execute(String method, String url,
                             Map<String, String> headers, String body);
}
