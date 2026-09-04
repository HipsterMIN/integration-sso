package kr.go.smes.qsign;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Q-Sign — 인증 SoR (Source of Record)
 * 설계서 9장 참조
 *
 * 책임:
 *   - 본인확인 / 전자서명 / SSO 인증 결과의 정본 발급
 *   - 인증 수준(Auth Level) 정의 및 부여
 *   - 잠금 / 재시도 정책 운영
 *   - 외부 인증사업자 연동 단일 창구
 *   - 비OIDC/반표준 인증은 IdO 브로커로부터 IdOAuthInput 수신
 */
@SpringBootApplication
@EnableKafka
@EnableScheduling
public class QSignApplication {

    public static void main(String[] args) {
        SpringApplication.run(QSignApplication.class, args);
    }
}
