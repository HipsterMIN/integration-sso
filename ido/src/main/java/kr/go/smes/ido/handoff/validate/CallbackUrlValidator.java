package kr.go.smes.ido.handoff.validate;

import kr.go.smes.common.error.PlatformErrorCode;
import kr.go.smes.common.error.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

/**
 * Callback URL 화이트리스트 검증기
 *
 * <p>검증 전략 (우선순위 순):
 * <ol>
 *   <li>완전 일치 — whitelist에 URL이 그대로 있으면 통과</li>
 *   <li>도메인 접두사 — whitelist 항목이 {@code https://domain.com} 형태이면
 *       요청 URL이 해당 도메인으로 시작하면 통과</li>
 *   <li>와일드카드 — {@code *.domain.com} 패턴 지원</li>
 * </ol>
 *
 * <p>whitelist가 null이거나 비어있으면 검증 스킵 (PoC 하위호환)
 */
@Slf4j
@Component
public class CallbackUrlValidator {

    /**
     * Callback URL 검증
     *
     * @param requestedUrl 요청된 redirect URI (null이면 검증 스킵)
     * @param whitelist    허용 목록 (null/empty면 검증 스킵)
     * @param correlationId 추적 ID
     * @throws PlatformException AGENCY_CALLBACK_BLOCKED
     */
    public void validate(String requestedUrl, List<String> whitelist, String correlationId) {
        if (requestedUrl == null || requestedUrl.isBlank()) return;
        if (whitelist == null || whitelist.isEmpty()) {
            log.debug("[CallbackValidator] whitelist 미설정 — 검증 스킵: {}", requestedUrl);
            return;
        }

        for (String allowed : whitelist) {
            if (matches(requestedUrl, allowed)) {
                log.debug("[CallbackValidator] 허용된 Callback URL: {} (rule={})", requestedUrl, allowed);
                return;
            }
        }

        log.warn("[CallbackValidator] 차단된 Callback URL: {} (whitelist={})", requestedUrl, whitelist);
        throw new PlatformException(PlatformErrorCode.AGENCY_CALLBACK_BLOCKED, correlationId);
    }

    // ── private ────────────────────────────────────────────────────────────

    private boolean matches(String requested, String allowedPattern) {
        if (allowedPattern == null || allowedPattern.isBlank()) return false;

        // 1. 완전 일치
        if (requested.equals(allowedPattern)) return true;

        // 2. 와일드카드 패턴 (*.domain.com)
        if (allowedPattern.startsWith("*.")) {
            String suffix = allowedPattern.substring(1); // .domain.com
            try {
                URI uri = URI.create(requested);
                String host = uri.getHost();
                return host != null && (host.endsWith(suffix) || host.equals(suffix.substring(1)));
            } catch (Exception e) {
                return false;
            }
        }

        // 3. 접두사 일치 (https://domain.com → https://domain.com/path도 허용)
        if (requested.startsWith(allowedPattern)) {
            // 경로 구분자 확인 (https://evil.com.hack.com 방지)
            String remainder = requested.substring(allowedPattern.length());
            return remainder.isEmpty() || remainder.startsWith("/") || remainder.startsWith("?");
        }

        return false;
    }
}
