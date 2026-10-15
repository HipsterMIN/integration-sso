package io.github.hipstermin.idem.hub.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 1.1.2 G2-8: 호출자의 X-Correlation-Id 는 규격([A-Za-z0-9._:-] 1~36자) 안일 때만 쓴다 — 종전에는 긴 값이 감사 INSERT(VARCHAR(36))를
 * 깨뜨려 감사·outbox 가 조용히 유실됐다. 대체한 id 는 응답 헤더·ThreadLocal·컨트롤러가 읽는 요청 헤더에 모두 같은 값으로 보인다.
 */
@DisplayName("TraceparentFilter — X-Correlation-Id 규격")
class TraceparentFilterTest {

    private static final String HEADER = "X-Correlation-Id";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final TraceparentFilter sut = new TraceparentFilter();

    /** 체인 안에서 컨트롤러가 보는 것: 요청 헤더 값과 ThreadLocal 값 */
    private record Seen(String requestHeader, String holder) {}

    private Seen run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<Seen> seen = new AtomicReference<>();
        sut.doFilter(request, response, (req, res) ->
                seen.set(new Seen(((HttpServletRequest) req).getHeader(HEADER), CorrelationIdHolder.get())));
        return seen.get();
    }

    @Test
    @DisplayName("규격 안의 값(UUID·k6-smoke-001·install-smoke-<epoch>)은 그대로 — 요청 헤더·ThreadLocal·응답 헤더가 같다")
    void validValuesPassThrough() throws Exception {
        for (String cid : new String[] {"7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", "k6-smoke-001", "install-smoke-1792029015", "a", "x".repeat(36)}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/handoff/issue");
            req.addHeader(HEADER, cid);
            MockHttpServletResponse res = new MockHttpServletResponse();
            Seen seen = run(req, res);
            assertThat(seen.requestHeader()).as(cid).isEqualTo(cid);
            assertThat(seen.holder()).isEqualTo(cid);
            assertThat(res.getHeader(HEADER)).isEqualTo(cid);
            assertThat(res.getHeader("traceparent")).startsWith("00-").hasSize(55);
        }
    }

    @Test
    @DisplayName("37자 이상이면 새 UUID 로 대체 — 컨트롤러의 @RequestHeader 도 대체된 값을 본다(래퍼)")
    void tooLongIsReplaced() throws Exception {
        String longCid = "k6-issue-sustain-" + "x".repeat(40);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/handoff/issue");
        req.addHeader(HEADER, longCid);
        MockHttpServletResponse res = new MockHttpServletResponse();

        Seen seen = run(req, res);

        assertThat(seen.requestHeader()).isNotEqualTo(longCid).matches(UUID_PATTERN);
        assertThat(seen.holder()).isEqualTo(seen.requestHeader());
        assertThat(res.getHeader(HEADER)).isEqualTo(seen.requestHeader());
    }

    @Test
    @DisplayName("허용 문자 밖(공백·줄바꿈·따옴표)이면 대체 — 로그·감사에 그대로 들어가지 않는다")
    void badCharactersAreReplaced() throws Exception {
        for (String cid : new String[] {"abc def", "line1\nline2", "a\"b", "한글", "x\u0000y", "  "}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/auth/providers");
            req.addHeader(HEADER, cid);
            MockHttpServletResponse res = new MockHttpServletResponse();
            Seen seen = run(req, res);
            assertThat(seen.requestHeader()).as("'%s'", cid).isNotEqualTo(cid).matches(UUID_PATTERN);
            assertThat(res.getHeader(HEADER)).isEqualTo(seen.requestHeader());
        }
    }

    @Test
    @DisplayName("헤더가 없으면 생성하고, traceparent 만 있으면 그 traceId 를 UUID 형식으로 쓴다")
    void missingIsGeneratedOrTakenFromTraceparent() throws Exception {
        MockHttpServletRequest none = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        Seen seen = run(none, res);
        assertThat(seen.requestHeader()).isNull();            // 대체가 아니라 생성 — 요청 헤더는 원래 없다
        assertThat(seen.holder()).matches(UUID_PATTERN);
        assertThat(res.getHeader(HEADER)).isEqualTo(seen.holder());

        MockHttpServletRequest tp = new MockHttpServletRequest("GET", "/actuator/health");
        tp.addHeader("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        Seen seen2 = run(tp, res2);
        assertThat(seen2.holder()).isEqualTo("0af76519-16cd-43dd-8448-eb211c80319c");
        assertThat(res2.getHeader("traceparent")).isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
    }

    @Test
    @DisplayName("규격 판정 자체")
    void validation() {
        assertThat(TraceparentFilter.isValidCorrelationId("k6-smoke-001")).isTrue();
        assertThat(TraceparentFilter.isValidCorrelationId("a.b:c_d-e")).isTrue();
        assertThat(TraceparentFilter.isValidCorrelationId("x".repeat(36))).isTrue();
        assertThat(TraceparentFilter.isValidCorrelationId("x".repeat(37))).isFalse();
        assertThat(TraceparentFilter.isValidCorrelationId("")).isFalse();
        assertThat(TraceparentFilter.isValidCorrelationId(null)).isFalse();
        assertThat(TraceparentFilter.isValidCorrelationId("a b")).isFalse();
        assertThat(TraceparentFilter.isValidCorrelationId("a/b")).isFalse();
    }
}
