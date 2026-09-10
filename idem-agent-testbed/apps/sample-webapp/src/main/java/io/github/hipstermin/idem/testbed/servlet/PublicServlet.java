package io.github.hipstermin.idem.testbed.servlet;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 공개 리소스 엔드포인트 — 인증 없이 접근 가능해야 함.
 *
 * <p>Agent의 exclude.paths 설정에 /public/** 포함 여부를 검증하는 데 활용.
 * 이 엔드포인트에 인증 없이 접근 시 200이면 exclude 설정 정상.
 * 401이면 Agent exclude 경로 설정 오류.
 */
public class PublicServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json;charset=UTF-8");
        try (PrintWriter w = resp.getWriter()) {
            w.print("{" +
                "\"message\":\"Public endpoint — no auth required\"," +
                "\"timestamp\":" + System.currentTimeMillis() + "," +
                "\"serverInfo\":\"" + esc(req.getServletContext().getServerInfo()) + "\"" +
                "}");
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
