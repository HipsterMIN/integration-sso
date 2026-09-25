package io.github.hipstermin.idem.registry.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.common.error.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 없는 경로는 404 표준 오류 본문 — 코어 registry 에 KR 전용 경로({@code /api/v1/internal/biz-members/**})가 없는 것은
 * 정상 상태이며, 종전처럼 catch-all 이 500("내부 서버 오류")으로 바꿔서는 안 된다.
 */
@DisplayName("GlobalExceptionHandler — 없는 경로 → 404, 미처리 예외 → 500")
class GlobalExceptionHandlerTest {

    @RestController
    static class PingController {
        @GetMapping("/api/v1/internal/ping")
        String ping() { return "pong"; }

        @GetMapping("/api/v1/internal/boom")
        String boom() { throw new IllegalStateException("boom"); }
    }

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("알 수 없는 /api/v1/internal/** 경로 → 404 + E-IM-404 (500 아님)")
    void unknownInternalPath_returns404() throws Exception {
        mvc.perform(post("/api/v1/internal/biz-members/convert")
                        .header("X-Internal-Api-Key", "any")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("E-IM-404"))
                .andExpect(jsonPath("$.message").value("요청한 경로가 없습니다."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("알 수 없는 일반 경로도 404 표준 본문")
    void unknownOtherPath_returns404() throws Exception {
        mvc.perform(get("/no/such/path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("E-IM-404"));
    }

    @Test
    @DisplayName("기본 리소스 핸들러가 던지는 NoResourceFoundException 도 404 로 매핑")
    void noResourceFound_maps404() {
        ResponseEntity<ErrorResponse> res = new GlobalExceptionHandler()
                .handleNotFound(new NoResourceFoundException(HttpMethod.POST, "api/v1/internal/biz-members/convert"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getCode()).isEqualTo("E-IM-404");
    }

    @Test
    @DisplayName("매핑된 경로는 그대로 200")
    void mappedPath_ok() throws Exception {
        mvc.perform(get("/api/v1/internal/ping")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("미처리 예외는 여전히 500 + E-QIM-500")
    void unhandled_returns500() throws Exception {
        mvc.perform(get("/api/v1/internal/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("E-QIM-500"));
    }
}
