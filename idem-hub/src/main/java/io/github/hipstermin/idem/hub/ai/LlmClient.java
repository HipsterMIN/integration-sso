package io.github.hipstermin.idem.hub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * OpenAI 호환 Chat Completions 클라이언트 ({@code POST {baseUrl}/chat/completions}).
 * 스트리밍은 쓰지 않는다. 실패는 {@code E-IDO-141}, 본문 모양이 다르면 {@code E-IDO-142}.
 */
@Slf4j
@Component
public class LlmClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AiProperties props;

    public LlmClient(@Qualifier("aiRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper, AiProperties props) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /** @param jsonMode true 면 response_format=json_object (JSON 만 돌려달라) */
    public String chat(String system, String user, boolean jsonMode, String correlationId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", props.getModel());
        body.put("temperature", props.getTemperature());
        body.put("max_tokens", props.getMaxTokens());
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);
        if (jsonMode) body.putObject("response_format").put("type", "json_object");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        if (props.getApiKey() != null && !props.getApiKey().isBlank()) headers.setBearerAuth(props.getApiKey());
        if (correlationId != null) headers.set("X-Correlation-Id", correlationId);

        String url = props.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        long t0 = System.currentTimeMillis();
        ResponseEntity<String> res;
        try {
            res = restTemplate.exchange(URI.create(url), HttpMethod.POST, new HttpEntity<>(body.toString(), headers), String.class);
        } catch (RestClientException e) {
            log.warn("[AI] LLM 호출 실패: model={} host={} cid={} : {}", props.getModel(), props.endpointHost(), correlationId, e.getMessage());
            throw new PlatformException(PlatformErrorCode.AI_UPSTREAM_FAILED, correlationId, "LLM 호출 실패 (" + props.endpointHost() + "): " + e.getMessage());
        }
        try {
            JsonNode root = objectMapper.readTree(res.getBody());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new PlatformException(PlatformErrorCode.AI_OUTPUT_INVALID, correlationId, "choices[0].message.content 없음");
            }
            log.info("[AI] LLM 응답: model={} ms={} chars={} cid={}", props.getModel(), System.currentTimeMillis() - t0, content.asText().length(), correlationId);
            return content.asText();
        } catch (java.io.IOException e) {
            throw new PlatformException(PlatformErrorCode.AI_OUTPUT_INVALID, correlationId, "응답 JSON 해석 실패: " + e.getMessage());
        }
    }

    /** LLM 텍스트에서 JSON 객체만 꺼낸다 — ```json 펜스·앞뒤 설명을 벗긴다. 없으면 null. */
    public static String extractJsonObject(String text) {
        if (text == null) return null;
        String t = text.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = nl > 0 ? t.substring(nl + 1) : "";
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end);
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return t.substring(start, end + 1);
    }
}
