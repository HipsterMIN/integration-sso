package io.github.hipstermin.idem.hub.admin.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 관리자 인증 설정 {@code ido.admin.*} (S7). 값의 의미는 {@code docs/admin-auth.md}. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ido.admin")
public class AdminProperties {

    private Bootstrap bootstrap = new Bootstrap();
    private Session session = new Session();
    private Cookie cookie = new Cookie();
    private Lock lock = new Lock();
    private Mfa mfa = new Mfa();
    private Password password = new Password();

    /** TOTP 비밀 봉인용 AES-256 키 (base64 32바이트, {@code IDEM_ADMIN_SECRET_KEY}). 비면 Handoff AES 키에서 파생(운영 금지) */
    private String secretKey = "";
    /** 비어 있을 때 파생을 허용 — 로컬·테스트 한정(hardened 프로파일에서 true 금지) */
    private boolean allowDerivedSecretKey = false;

    @Getter @Setter
    public static class Bootstrap {
        /** 관리자가 하나도 없을 때 만드는 첫 SYSTEM_ADMIN */
        private String username = "admin";
        /** {@code IDEM_ADMIN_BOOTSTRAP_PASSWORD} — 비면 관리자를 만들지 않는다(hardened 는 기동 거부) */
        private String password = "";
        /** 첫 로그인 후 비밀번호 변경 강제 — 운영 필수 */
        private boolean requirePasswordChange = true;
    }

    @Getter @Setter
    public static class Session {
        private long idleMinutes = 15;
        private long absoluteMinutes = 480;
        /** 관리자당 동시 세션 수 — 새 로그인이 이전 세션을 끝낸다 */
        private int concurrent = 1;
        /** 2단계 인증 대기 토큰 TTL */
        private long mfaTokenSeconds = 300;
    }

    @Getter @Setter
    public static class Cookie {
        private String name = "idemAdminSid";
        private boolean secure = true;
        private String sameSite = "Strict";
    }

    @Getter @Setter
    public static class Lock {
        private int maxAttempts = 5;
        private long durationMinutes = 15;
    }

    @Getter @Setter
    public static class Mfa {
        /** true 면 첫 로그인에서 TOTP 등록을 강제하고, 이후 매 로그인에 코드를 요구한다 */
        private boolean required = true;
        private String issuer = "Idem";
        /** 허용 시간 창(±스텝) */
        private int window = 1;
    }

    @Getter @Setter
    public static class Password {
        private int minLength = 10;
        /** 대문자·소문자·숫자·특수문자 중 몇 종류 이상 */
        private int minClasses = 3;
        /** 재사용 금지 최근 개수 */
        private int history = 3;
    }
}
