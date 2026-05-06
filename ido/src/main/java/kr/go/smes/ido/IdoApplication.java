package kr.go.smes.ido;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * IdO — Identity Orchestrator
 * 오케스트레이션 / 정책 SoR (Source of Record)
 * 설계서 11장 참조
 *
 * 책임:
 *   - 모든 기관 진입에서 정책 적용 및 Handoff 검증
 *   - 기관 식별자 변환 (Subject Identifier Projection)
 *   - Handoff Ticket 발급 / 재사용 방지 / Revoke
 *   - 비OIDC/반표준 인증 정규화 브로커 (→ Q-Sign 전달)
 *   - Q-IM 캐시 (≤5분 TTL) 및 무효화 이벤트 소비
 *   - Kafka Compacted Snapshot Topic 기반 상태 최신성 유지
 *
 * [BFF 이관] onepass-fe Spring Boot BFF 제거 후 추가 책임:
 *   - FE 세션 발급/확인/로그아웃 (/api/v1/fe-session/**)
 *   - platform.session.advisory Kafka 소비 → FE 세션 처리
 *   - returnUrl 화이트리스트 검증 (§12.6)
 *   - React SPA 에 대한 CORS 설정
 */
@SpringBootApplication
@EnableKafka
@EnableScheduling
public class IdoApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdoApplication.class, args);
    }
}
