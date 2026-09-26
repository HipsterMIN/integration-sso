package io.github.hipstermin.idem.hub.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@DisplayName("hub GlobalExceptionHandler — 1.0.1: 지원하지 않는 메서드·미디어 타입은 500 이 아니라 405·415")
class GlobalExceptionHandlerHttpTest {

    @RestController
    static class Probe {
        @PostMapping(value = "/probe", consumes = MediaType.APPLICATION_JSON_VALUE)
        String probe(@RequestBody String body) { return body; }
    }

    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void methodNotAllowed405() throws Exception {
        mvc.perform(delete("/probe")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
                .andExpect(jsonPath("$.code").value("E-IDO-405"));
    }

    @Test
    void unsupportedMediaType415() throws Exception {
        mvc.perform(post("/probe").contentType(MediaType.TEXT_PLAIN).content("x")).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("E-IDO-415"));
    }
}
