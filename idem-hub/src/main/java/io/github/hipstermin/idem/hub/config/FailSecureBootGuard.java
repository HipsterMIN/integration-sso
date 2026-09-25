package io.github.hipstermin.idem.hub.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 fail-secure 가드 (범용화 D2, {@code docs/generalization-plan.md} D2).
 *
 * <p>원칙: 외부 의존 실패 = 거부 + 감사. 그 원칙을 무력화하는 "탈출구(escape hatch)" 는 로컬·테스트에서만 켤 수 있고,
 * <b>운영·스테이지 프로파일({@code prod}, {@code stage})에서는 어느 하나라도 켜져 있으면 기동을 거부한다.</b>
 * 환경변수 한 줄로 운영에서 감사·인증·서명을 끄던 경로를 물리적으로 막는다.
 *
 * <p>모든 프로파일 공통: 내부 서명 비밀키({@code IDEM_HUB_INTERNAL_SIG_SECRET})가 비어 있으면 기동 거부
 * ({@code idem.hub.internal.allow-empty-sig-secret=true} 는 로컬·테스트 전용).
 *
 * <p>검사 항목은 아래 {@link #PROD_FORBIDDEN_TRUE} / {@link #PROD_REQUIRED_TRUE} 에 있다. 새 탈출구를 추가하면 여기에도 넣는다
 * ({@code FailSecureBootGuardTest} 가 목록을 고정한다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FailSecureBootGuard {

    /** 운영·스테이지에서 true 이면 기동 거부 */
    static final List<String> PROD_FORBIDDEN_TRUE = List.of(
            "idem.hub.webhook.allow-empty-secret",
            "idem.hub.internal.allow-empty-callers",
            "idem.hub.internal.allow-empty-sig-secret",
            "idem.hub.keycloak.allow-empty-client-secret",
            "idem.hub.ticket.allow-empty-fallback-keys",
            "idem.hub.broker.allow-ciless-identity",
            "idem.hub.cast.allow-generated-keys",
            "idem.hub.registry.allow-empty-aes-key",
            "idem.hub.authz.allow-empty-api-key",
            "idem.hub.kms.local.allow-in-prod",
            "idem.hub.kms.vault.allow-empty-token",
            "idem.plugins.mock-auth.enabled",
            "idem.hub.admin.allow-derived-secret-key"          // S7: 관리자 TOTP 봉인 키 파생은 로컬 전용
    );

    /** 운영·스테이지에서 false 이면 기동 거부 */
    static final List<String> PROD_REQUIRED_TRUE = List.of(
            "idem.hub.audit.db-save-enabled",
            "idem.hub.security-headers.enabled",
            "idem.hub.auth.rate-limit.enabled",
            "idem.hub.rate-limit.enabled",
            "idem.hub.redisson.enabled",
            "idem.hub.admin.cookie.secure",                   // S7: 관리자 세션 쿠키는 운영에서 Secure
            "idem.hub.admin.mfa.required",                    // S7: 운영은 2단계 인증 필수
            "idem.hub.admin.bootstrap.require-password-change"
    );

    static final Set<String> HARDENED_PROFILES = Set.of("prod", "stage");

    private final Environment environment;

    @PostConstruct
    void verify() {
        List<String> violations = new ArrayList<>();

        String sigSecret = environment.getProperty("idem.hub.gate.internal-sig-secret", "");
        boolean allowEmptySig = environment.getProperty("idem.hub.internal.allow-empty-sig-secret", Boolean.class, false);
        if (sigSecret.isBlank() && !allowEmptySig) {
            violations.add("IDEM_HUB_INTERNAL_SIG_SECRET(idem.hub.gate.internal-sig-secret) 이 비어 있습니다 — gate↔hub 내부 서명 불가. "
                    + "로컬·테스트에서만 idem.hub.internal.allow-empty-sig-secret=true");
        }

        boolean hardened = false;
        for (String profile : environment.getActiveProfiles()) {
            if (HARDENED_PROFILES.contains(profile)) hardened = true;
        }
        if (hardened) {
            for (String key : PROD_FORBIDDEN_TRUE) {
                if (environment.getProperty(key, Boolean.class, false)) {
                    violations.add(key + "=true 는 운영·스테이지에서 금지");
                }
            }
            for (String key : PROD_REQUIRED_TRUE) {
                if (!environment.getProperty(key, Boolean.class, true)) {
                    violations.add(key + "=false 는 운영·스테이지에서 금지");
                }
            }
        }

        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder("[FailSecureBootGuard] 기동 거부 — fail-secure 위반 ")
                    .append(violations.size()).append("건:");
            for (String v : violations) sb.append("\n  - ").append(v);
            throw new IllegalStateException(sb.toString());
        }
        log.info("[FailSecureBootGuard] fail-secure 검사 통과 (hardened={}, escape hatch {}개 감시)", hardened, PROD_FORBIDDEN_TRUE.size());
    }
}
