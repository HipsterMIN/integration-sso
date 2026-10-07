package io.github.hipstermin.idem.hub.ai;

import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 1.1 AI 운영 보조 설정 ({@code idem.hub.ai.*}) — 선택 컨테이너. 기본 설치에서는 꺼져 있다.
 *
 * <p>LLM 은 OpenAI 호환 Chat Completions API({@code {baseUrl}/chat/completions}) 로 부른다 — Ollama·vLLM·LM Studio 등
 * 온프레미스 서버가 모두 이 모양을 낸다. 엔드포인트 호스트가 사설망·루프백이 아니면(공개 LLM) {@code allowPublicEndpoint}
 * 없이는 켜지지 않는다 — 감사 요약·프로파일 초안이 설치본 밖으로 나가지 않게(D2 fail-secure 와 같은 원칙).
 */
@Component
@ConfigurationProperties(prefix = "idem.hub.ai")
@Getter
@Setter
public class AiProperties {

    private boolean enabled = false;
    /** OpenAI 호환 베이스 URL — 예: http://idem-ai:11434/v1 (Ollama) */
    private String baseUrl = "http://idem-ai:11434/v1";
    private String model = "qwen2.5:7b";
    /** 선택 — Bearer 토큰 (vLLM·게이트웨이 등). 비밀(환경변수 IDEM_HUB_AI_API_KEY) */
    private String apiKey = "";
    private boolean allowPublicEndpoint = false;
    private long connectTimeoutMs = 3000;
    private long readTimeoutMs = 90000;
    private int maxTokens = 1500;
    private double temperature = 0.2;
    /** 감사 요약에 LLM 으로 보내는 표본 행 수 (집계는 전부, 행은 표본만) */
    private int auditSampleRows = 40;

    public String endpointHost() {
        try {
            return URI.create(baseUrl).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
