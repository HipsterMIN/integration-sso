package io.github.hipstermin.idem.hub.login;

import io.github.hipstermin.idem.hub.consent.ConsentItem;
import java.util.List;

/**
 * 1.1 코어 로그인 프런트의 최소 HTML — 제공자 선택·동의·오류 세 화면.
 *
 * <p>hub 의 CSP 는 {@code default-src 'none'} 이라 인라인 스타일·스크립트를 쓰지 않는다(링크·form 만). 값은 모두 이스케이프한다.
 * 운영기관이 자기 화면을 원하면 이 화면들 대신 자기 프런트에서 같은 URL 계약을 쓰면 된다(개발자 가이드 §6.1).
 */
final class LoginPages {

    private LoginPages() {}

    record ProviderLink(String code, String label, String href) {}

    static String chooser(String serviceName, List<ProviderLink> providers) {
        StringBuilder sb = new StringBuilder();
        sb.append(head("Idem 로그인"))
          .append("<h1>Idem 로그인</h1>")
          .append("<p>").append(esc(serviceName)).append(" 에 로그인합니다. 인증 방법을 고르세요.</p><ul>");
        for (ProviderLink p : providers) {
            sb.append("<li><a href=\"").append(esc(p.href())).append("\">").append(esc(p.label())).append("</a></li>");
        }
        sb.append("</ul></body></html>");
        return sb.toString();
    }

    /**
     * 1.1 동의 카탈로그 — 미동의 항목을 보여 주고 같은 경로({@code actionUrl})로 form POST 한다.
     * 체크박스 {@code agree=<versionId>}(여러 개), 숨은 {@code req}, 거부는 {@code decline=1}. 링크는 http(s) 만 그린다.
     */
    static String consent(String serviceName, String actionUrl, String requestId, List<ConsentItem> items, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append(head("Idem 서비스 이용 동의"))
          .append("<h1>서비스 이용 동의</h1>")
          .append("<p>").append(esc(serviceName)).append(" 을(를) 이용하려면 아래 항목에 동의해야 합니다. 필수 항목에 동의하지 않으면 로그인이 완료되지 않습니다.</p>");
        if (error != null && !error.isBlank()) {
            sb.append("<p><strong>").append(esc(error)).append("</strong></p>");
        }
        sb.append("<form method=\"post\" action=\"").append(esc(actionUrl)).append("\">")
          .append("<input type=\"hidden\" name=\"req\" value=\"").append(esc(requestId)).append("\">")
          .append("<ul>");
        for (ConsentItem it : items) {
            sb.append("<li><label><input type=\"checkbox\" name=\"agree\" value=\"").append(esc(it.versionId())).append("\"> ")
              .append(esc(it.title() != null ? it.title() : it.consentType()))
              .append(it.required() ? " (필수)" : " (선택)");
            if (it.versionTag() != null && !it.versionTag().isBlank()) {
                sb.append(" <small>").append(esc(it.versionTag())).append("</small>");
            }
            sb.append("</label>");
            if (isHttpUrl(it.contentUrl())) {
                sb.append(" <a href=\"").append(esc(it.contentUrl())).append("\" target=\"_blank\" rel=\"noopener\">전문 보기</a>");
            }
            sb.append("</li>");
        }
        sb.append("</ul>")
          .append("<p><button type=\"submit\">동의하고 계속</button> ")
          .append("<button type=\"submit\" name=\"decline\" value=\"1\">동의하지 않음</button></p>")
          .append("</form></body></html>");
        return sb.toString();
    }

    static String error(String code, String message, String correlationId) {
        return head("Idem 로그인 오류")
                + "<h1>로그인을 진행할 수 없습니다</h1>"
                + "<p>" + esc(message) + "</p>"
                + "<p><small>오류 코드 " + esc(code) + (correlationId != null ? " · 추적 ID " + esc(correlationId) : "") + "</small></p>"
                + "</body></html>";
    }

    private static String head(String title) {
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + esc(title) + "</title></head><body>";
    }

    static boolean isHttpUrl(String s) {
        if (s == null) return false;
        String lower = s.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
