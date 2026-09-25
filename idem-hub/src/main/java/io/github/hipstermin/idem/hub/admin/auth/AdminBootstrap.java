package io.github.hipstermin.idem.hub.admin.auth;

import io.github.hipstermin.idem.common.util.UuidV7;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 첫 관리자 — 관리자가 하나도 없을 때 {@code ido.admin.bootstrap.*}({@code IDEM_ADMIN_BOOTSTRAP_PASSWORD}) 로 SYSTEM_ADMIN 을 만든다.
 * 비밀번호가 비면: 운영(hardened)에서는 기동 거부(관리자 없는 설치본은 관리 API 를 아무도 못 쓴다), 그 외는 경고만.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    public static final String ACTION_BOOTSTRAPPED = "ADMIN_BOOTSTRAPPED";
    private static final Set<String> HARDENED = Set.of("prod", "stage");

    private final AdminUserRepository users;
    private final AdminProperties props;
    private final PasswordPolicy passwordPolicy;
    private final AdminAuditor auditor;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            log.info("[AdminBootstrap] 관리자 {}명 — 부트스트랩 생략", users.count());
            return;
        }
        String password = props.getBootstrap().getPassword();
        if (password == null || password.isBlank()) {
            boolean hardened = false;
            for (String p : environment.getActiveProfiles()) if (HARDENED.contains(p)) hardened = true;
            if (hardened) {
                throw new IllegalStateException("[AdminBootstrap] 관리자가 없고 IDEM_ADMIN_BOOTSTRAP_PASSWORD 도 비어 있습니다 — 운영·스테이지에서는 기동을 거부합니다");
            }
            log.warn("[AdminBootstrap] 관리자가 없고 IDEM_ADMIN_BOOTSTRAP_PASSWORD 가 비어 있다 — 관리 API 를 쓸 수 없다");
            return;
        }
        var v = passwordPolicy.violations(password, props.getBootstrap().getUsername(), null);
        if (!v.isEmpty()) {
            throw new IllegalStateException("[AdminBootstrap] IDEM_ADMIN_BOOTSTRAP_PASSWORD 가 비밀번호 정책을 만족하지 않습니다: " + String.join(", ", v));
        }
        AdminUserEntity u = AdminUserEntity.builder()
                .adminId(UuidV7.generate())
                .username(props.getBootstrap().getUsername())
                .displayName("Bootstrap administrator")
                .passwordHash(passwordPolicy.hash(password))
                .role(AdminRole.SYSTEM_ADMIN)
                .mustChangePassword(props.getBootstrap().isRequirePasswordChange())
                .createdBy("bootstrap")
                .build();
        users.save(u);
        auditor.success(ACTION_BOOTSTRAPPED, "bootstrap", "ADMIN", u.getAdminId(), null);
        log.warn("[AdminBootstrap] 첫 SYSTEM_ADMIN '{}' 생성 — 첫 로그인에서 비밀번호 변경{}", u.getUsername(),
                u.isMustChangePassword() ? "을 요구한다" : " 요구 없음(테스트 설정)");
    }
}
