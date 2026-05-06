package com.onepass.qim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Q-IM — 식별·매핑 SoR (Source of Record)
 * 설계서 10장 참조
 *
 * 책임:
 *   - 사용자 객체 / 속성 / 다중 인증수단 매핑의 정본 보유
 *   - 사용자 상태(ACTIVE / SUSPENDED / WITHDRAWN) 정본 관리
 *   - IdO 및 기관에 대한 조회 인터페이스 제공
 *   - 사용자 상태·매핑 변경 이벤트 발행 (Transactional Outbox 패턴)
 */
@SpringBootApplication
@EnableKafka
@EnableScheduling
public class QImApplication {

    public static void main(String[] args) {
        SpringApplication.run(QImApplication.class, args);
    }
}
