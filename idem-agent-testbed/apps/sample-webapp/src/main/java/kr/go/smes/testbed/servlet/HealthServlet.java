package kr.go.smes.testbed.servlet;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 헬스체크 엔드포인트 — Agent 인증 제외 대상 (exclude path)
 * GET /health → 200 OK {"status":"UP"}
 */
public class HealthServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json;charset=UTF-8");
        try (PrintWriter w = resp.getWriter()) {
            w.print("{\"status\":\"UP\",\"app\":\"sample-webapp\",\"timestamp\":" + System.currentTimeMillis() + "}");
        }
    }
}
