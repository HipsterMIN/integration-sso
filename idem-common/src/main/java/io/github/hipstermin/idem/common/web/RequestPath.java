package io.github.hipstermin.idem.common.web;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * 요청 경로 정규화 (1.0.1 보안, 3차 적대적 점검 H1·H2).
 *
 * <p>서블릿 컨테이너는 라우팅 때 경로 파라미터({@code ;jsessionid=…})를 떼고 퍼센트 인코딩을 풀고 {@code ..} 를 접지만,
 * {@link jakarta.servlet.http.HttpServletRequest#getRequestURI()} 는 원본을 돌려준다. 그 원본으로 "보호 경로인가" 를 판단하면
 * {@code /api/v1/admin;x/…}·{@code /api/v1/%61dmin/…}·{@code /resources/../admin/…} 가 검사를 지나친다. 이 클래스는 원본 URI 를
 * <b>같은 규칙</b>으로 정규화하고, 정규화 결과와 원본이 다르면(= 위장 시도) 호출 쪽이 거부할 수 있게 한다.
 *
 * <p>규칙: 퍼센트 디코딩(UTF-8, 잘못된 시퀀스는 거부) → 세그먼트별 {@code ;} 뒤 파라미터 제거 → 빈 세그먼트({@code //})·{@code .}·
 * {@code ..} 처리(루트 위로 올라가면 거부) → 제어 문자·백슬래시·NUL 거부.
 */
public final class RequestPath {

    private RequestPath() {}

    /** 정규화된 경로. 안전하게 정규화할 수 없으면(잘못된 인코딩·루트 탈출·금지 문자) 비어 있다. */
    public static Optional<String> canonical(String rawUri) {
        if (rawUri == null || rawUri.isEmpty() || rawUri.charAt(0) != '/') return Optional.empty();
        String decoded = decode(rawUri);
        if (decoded == null) return Optional.empty();
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '\\') return Optional.empty();
        }
        Deque<String> out = new ArrayDeque<>();
        for (String seg : decoded.substring(1).split("/", -1)) {
            int semi = seg.indexOf(';');
            if (semi >= 0) seg = seg.substring(0, semi);
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (out.isEmpty()) return Optional.empty();
                out.removeLast();
                continue;
            }
            out.addLast(seg);
        }
        return Optional.of("/" + String.join("/", out));
    }

    /** 원본이 이미 정규형인가 — 인코딩·경로 파라미터·점 세그먼트·중복 슬래시가 전혀 없는 경우에만 true. */
    public static boolean isCanonical(String rawUri) {
        Optional<String> c = canonical(rawUri);
        return c.isPresent() && c.get().equals(rawUri);
    }

    /** 정규형이면 그 경로, 아니면 empty — "정규형 경로가 아니면 거부" 를 한 줄로. */
    public static Optional<String> canonicalIfSafe(String rawUri) {
        return isCanonical(rawUri) ? Optional.of(rawUri) : Optional.empty();
    }

    private static String decode(String s) {
        if (s.indexOf('%') < 0) return s;
        byte[] buf = new byte[s.length()];
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) return null;
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) return null;
                buf[n++] = (byte) ((hi << 4) | lo);
                i += 2;
            } else if (c < 0x80) {
                buf[n++] = (byte) c;
            } else {
                byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                if (n + utf8.length > buf.length) {
                    byte[] bigger = new byte[buf.length * 2 + utf8.length];
                    System.arraycopy(buf, 0, bigger, 0, n);
                    buf = bigger;
                }
                System.arraycopy(utf8, 0, buf, n, utf8.length);
                n += utf8.length;
            }
        }
        String decoded = new String(buf, 0, n, StandardCharsets.UTF_8);
        return decoded.indexOf('�') >= 0 && s.indexOf('�') < 0 ? null : decoded;
    }
}
