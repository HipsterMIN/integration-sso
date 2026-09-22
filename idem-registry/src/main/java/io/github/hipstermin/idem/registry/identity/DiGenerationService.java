package io.github.hipstermin.idem.registry.identity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DI(중복가입확인정보) 생성 서비스
 *
 * <p>DI = HMAC-SHA256({siteCode}:{qimUserId}:{serviceSecret}) → Base64URL
 *
 * <p>특성:
 * <ul>
 *   <li>기관별 DI 독립 — 기관 A의 DI ≠ 기관 B의 DI</li>
 *   <li>동일 기관+사용자 조합 → 항상 동일 DI (결정론적)</li>
 *   <li>DI로부터 qimUserId 역산 불가 (단방향 HMAC)</li>
 *   <li>di_map JSON 컬럼에 {"AGENCY_CODE": "di_value"} 형태로 저장</li>
 * </ul>
 *
 * <p>환경변수: {@code QIM_DI_SECRET} (서비스 공통 비밀키, 운영 시 반드시 교체)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiGenerationService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * 절대 통과 금지 placeholder 목록 (Sprint γ-1 / F3.4).
     * 과거 default 값을 운영에 그대로 주입했을 때 즉시 차단한다.
     */
    private static final Set<String> FORBIDDEN_PLACEHOLDERS = Set.of(
            "default-di-secret-change-in-production",
            "change-me",
            "changeme",
            "default",
            "secret",
            "di-secret",
            "test"
    );

    /**
     * DI HMAC-SHA256 공유 비밀키.
     * 환경변수: {@code QIM_DI_SECRET} — 운영 환경 필수.
     *
     * <p>[Sprint γ-1 / F3.4] 기본값 "default-di-secret-change-in-production" 제거.
     * 부팅 시 {@link #validateDiSecret()} 가 placeholder 또는 비어있을 경우 즉시 실패.
     */
    @Value("${qim.crypto.di.secret:}")
    private String diSecret;

    /**
     * 부팅 검증 우회 escape hatch — 테스트/로컬 한정.
     * 운영 환경에서는 절대 true 설정 금지.
     * <p>활성화 방법(테스트 한정): {@code qim.crypto.di.allow-empty-secret=true}
     */
    @Value("${qim.crypto.di.allow-empty-secret:false}")
    private boolean allowEmptyDiSecret;

    private final ObjectMapper objectMapper;

    // ── 부팅 시 안전 검증 (Sprint γ-1 / F3.4) ─────────────────────────────────

    /**
     * 부팅 시 DI 비밀키 안전성 검증.
     *
     * <p>실패 조건:
     * <ul>
     *   <li>{@code diSecret} 이 null/blank 인데 {@code allow-empty-secret} 미설정</li>
     *   <li>{@code diSecret} 이 알려진 placeholder</li>
     *   <li>{@code diSecret} 이 16자 미만 (HMAC-SHA256 최소 보안 강도 미달)</li>
     * </ul>
     *
     * <p>실패 시 {@link IllegalStateException} 으로 컨텍스트 기동 자체를 중단한다.
     * 이는 의도된 동작 — 평문 placeholder 가 운영에 그대로 주입되어
     * 모든 기관의 DI 가 예측 가능한 값으로 생성되는 사고를 막기 위함.
     */
    @PostConstruct
    void validateDiSecret() {
        if (diSecret == null || diSecret.isBlank()) {
            if (allowEmptyDiSecret) {
                log.warn("[DiGenerationService] qim.crypto.di.secret 미설정 — "
                        + "allow-empty-secret=true 로 우회 (테스트/로컬 한정)");
                return;
            }
            throw new IllegalStateException(
                    "qim.crypto.di.secret 환경변수 QIM_DI_SECRET 가 설정되지 않았습니다. "
                            + "운영에서는 반드시 32자 이상의 무작위 비밀키를 주입하십시오 "
                            + "(예: openssl rand -hex 32). "
                            + "테스트/로컬에서만 qim.crypto.di.allow-empty-secret=true 로 우회 가능.");
        }
        String normalized = diSecret.trim().toLowerCase();
        if (FORBIDDEN_PLACEHOLDERS.contains(normalized)) {
            throw new IllegalStateException(
                    "qim.crypto.di.secret 가 안전하지 않은 placeholder('" + diSecret + "') 입니다. "
                            + "운영용 비밀키를 주입하십시오. "
                            + "(이 값으로 운영하면 모든 기관의 DI 가 공개 사전 공격에 노출됩니다.)");
        }
        if (diSecret.length() < 16) {
            throw new IllegalStateException(
                    "qim.crypto.di.secret 가 너무 짧습니다 (length=" + diSecret.length() + "). "
                            + "HMAC-SHA256 보안 강도를 위해 최소 16자 (권장 32자) 이상으로 주입하십시오.");
        }
        log.info("[DiGenerationService] DI secret 검증 통과 (length={})", diSecret.length());
    }

    /**
     * 기관별 DI 조회 또는 신규 생성
     *
     * @param qimUserId   Q-IM 사용자 ID (정본)
     * @param agencyCode  기관 코드
     * @param diMapJson   현재 저장된 di_map JSON (null이면 빈 맵으로 처리)
     * @return DI 값 (Base64URL, 44자)
     */
    public String getOrCreateDi(String qimUserId, String agencyCode, String diMapJson) {
        Map<String, String> diMap = parseDiMap(diMapJson);

        // 이미 존재하는 DI 반환 (결정론적이므로 동일하지만 저장값 우선)
        if (diMap.containsKey(agencyCode)) {
            log.debug("[DiGeneration] 기존 DI 반환: qimUserId={} agencyCode={}", qimUserId, agencyCode);
            return diMap.get(agencyCode);
        }

        // 신규 DI 생성
        String di = generateDi(qimUserId, agencyCode);
        log.info("[DiGeneration] 신규 DI 생성: qimUserId={} agencyCode={}", qimUserId, agencyCode);
        return di;
    }

    /**
     * 기관별 DI 생성 (순수 생성, 저장 없음)
     * HMAC-SHA256(agencyCode:qimUserId, secret) → Base64URL
     */
    public String generateDi(String qimUserId, String agencyCode) {
        try {
            String input = agencyCode + ":" + qimUserId;
            return CryptoProviders.current().hmacSha256Base64Url(diSecret.getBytes(StandardCharsets.UTF_8), input);
        } catch (Exception e) {
            log.error("[DiGeneration] DI 생성 실패: qimUserId={} agencyCode={}", qimUserId, agencyCode, e);
            throw new RuntimeException("DI 생성 실패", e);
        }
    }

    /**
     * di_map에 기관 DI 추가 후 JSON 직렬화 반환
     *
     * @param currentDiMapJson 현재 di_map JSON
     * @param agencyCode       기관 코드
     * @param di               생성된 DI 값
     * @return 갱신된 di_map JSON 문자열
     */
    public String addDiToMap(String currentDiMapJson, String agencyCode, String di) {
        Map<String, String> diMap = new HashMap<>(parseDiMap(currentDiMapJson));
        diMap.put(agencyCode, di);
        try {
            return objectMapper.writeValueAsString(diMap);
        } catch (Exception e) {
            log.error("[DiGeneration] di_map 직렬화 실패", e);
            throw new RuntimeException("di_map 직렬화 실패", e);
        }
    }

    /**
     * di_map JSON 파싱
     */
    public Map<String, String> parseDiMap(String diMapJson) {
        if (diMapJson == null || diMapJson.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(diMapJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            // D2 fail-secure: 손상된 di_map 을 빈 맵으로 읽으면 기존 DI 가 유실·재발급된다 — 데이터 오류로 전파
            throw new IllegalStateException("user_profile.di_map JSON 손상: " + e.getMessage(), e);
        }
    }
}
