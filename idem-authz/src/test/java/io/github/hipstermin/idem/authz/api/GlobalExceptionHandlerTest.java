package io.github.hipstermin.idem.authz.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@DisplayName("authz GlobalExceptionHandler — 1.0.1: 404·405 도 플랫폼 본문(error·message·timestamp), 요청 경로 반사 없음")
class GlobalExceptionHandlerTest {

    @RestController
    static class Probe {
        @GetMapping("/probe")
        String probe() { return "ok"; }

        @GetMapping("/missing")
        String missing() throws NoResourceFoundException { throw new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/missing/secret"); }

        @GetMapping("/no-handler")
        String noHandler() throws NoHandlerFoundException { throw new NoHandlerFoundException("GET", "/no-handler/secret", new org.springframework.http.HttpHeaders()); }
    }

    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void notFound_platformBody() throws Exception {
        mvc.perform(get("/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("E-AUTHZ-404")).andExpect(jsonPath("$.message").exists()).andExpect(jsonPath("$.timestamp").exists())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
        mvc.perform(get("/no-handler")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("E-AUTHZ-404"));
    }

    @Test
    void methodNotAllowed_platformBody() throws Exception {
        mvc.perform(delete("/probe").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("E-AUTHZ-405"));
    }
}
