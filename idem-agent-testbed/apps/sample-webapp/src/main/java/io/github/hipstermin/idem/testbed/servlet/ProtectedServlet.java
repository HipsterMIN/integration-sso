package io.github.hipstermin.idem.testbed.servlet;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Enumeration;

/**
 * 보호된 리소스 엔드포인트.
 *
 * <p>OnePass Agent가 올바르게 위빙되었다면:
 * <ul>
 *   <li>Authorization 헤더 미포함 → 401 (Agent가 가로채어 거부)</li>
 *   <li>유효 토큰 포함 → 200 (Agent 검증 통과 후 서블릿 실행)</li>
 *   <li>무효 토큰 포함 → 401 (Agent가 가로채어 거부)</li>
 * </ul>
 *
 * <p>Agent 위빙이 없는 경우: 항상 200 반환 (기준값)
 */
public class ProtectedServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json;charset=UTF-8");

        // 요청 메타 정보 수집
        String remoteAddr = req.getRemoteAddr();
        String userAgent  = req.getHeader("User-Agent");
        String authHeader = req.getHeader("Authorization");
        String token      = (authHeader != null && authHeader.startsWith("Bearer "))
                            ? authHeader.substring(7)
                            : "none";

        // Agent가 주입한 속성 확인 (Agent가 위빙 성공 시 Request attribute 설정 가능)
        Object agentValidated = req.getAttribute("onepass.validated");
        Object agentPrincipal = req.getAttribute("onepass.principal");

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"message\": \"Protected resource accessed\",\n");
        sb.append("  \"status\": \"OK\",\n");
        sb.append("  \"timestamp\": ").append(System.currentTimeMillis()).append(",\n");
        sb.append("  \"request\": {\n");
        sb.append("    \"remoteAddr\": \"").append(esc(remoteAddr)).append("\",\n");
        sb.append("    \"userAgent\": \"").append(esc(userAgent)).append("\",\n");
        sb.append("    \"token\": \"").append(maskToken(token)).append("\"\n");
        sb.append("  },\n");
        sb.append("  \"agent\": {\n");
        sb.append("    \"validated\": ").append(agentValidated != null ? agentValidated : "null").append(",\n");
        sb.append("    \"principal\": \"").append(agentPrincipal != null ? esc(agentPrincipal.toString()) : "null").append("\"\n");
        sb.append("  },\n");
        sb.append("  \"env\": {\n");
        sb.append("    \"javaVersion\": \"").append(System.getProperty("java.version", "?")).append("\",\n");
        sb.append("    \"serverInfo\": \"").append(esc(req.getServletContext().getServerInfo())).append("\"\n");
        sb.append("  }\n");
        sb.append("}");

        try (PrintWriter w = resp.getWriter()) {
            w.print(sb.toString());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        doGet(req, resp);
    }

    private static String maskToken(String token) {
        if (token == null || token.equals("none") || token.length() < 8) return token;
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }

    private static String esc(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
