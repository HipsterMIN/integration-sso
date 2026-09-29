package io.github.hipstermin.idem.hub.login;

import java.util.List;

/**
 * 1.1 코어 로그인 프런트의 최소 HTML — 제공자 선택·오류 두 화면.
 *
 * <p>hub 의 CSP 는 {@code default-src 'none'} 이라 인라인 스타일·스크립트를 쓰지 않는다(링크만). 값은 모두 이스케이프한다.
 * 운영기관이 자기 화면을 원하면 이 두 화면 대신 자기 프런트에서 같은 URL 계약을 쓰면 된다(개발자 가이드 §6.1).
 */
final class LoginPages {

    private LoginPages() {}

    record ProviderLink(String code, String label, String href) {}

    static String chooser(String serviceName, List<ProviderLink> providers) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
          .append("<title>Idem 로그인</title></head><body>")
          .append("<h1>Idem 로그인</h1>")
          .append("<p>").append(esc(serviceName)).append(" 에 로그인합니다. 인증 방법을 고르세요.</p><ul>");
        for (ProviderLink p : providers) {
            sb.append("<li><a href=\"").append(esc(p.href())).append("\">").append(esc(p.label())).append("</a></li>");
        }
        sb.append("</ul></body></html>");
        return sb.toString();
    }

    static String error(String code, String message, String correlationId) {
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Idem 로그인 오류</title></head><body>"
                + "<h1>로그인을 진행할 수 없습니다</h1>"
                + "<p>" + esc(message) + "</p>"
                + "<p><small>오류 코드 " + esc(code) + (correlationId != null ? " · 추적 ID " + esc(correlationId) : "") + "</small></p>"
                + "</body></html>";
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
