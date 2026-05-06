package kr.go.smes.agency;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Agency-Stub — 기관 연계 PoC 스텁
 * 설계서 14 / 15장 참조 (Direct Integration 패턴)
 *
 * 책임:
 *   - IdO Verify API 호출 → 기관 로컬 세션(AGSID) 생성
 *   - Session Fixation 방지 (Verify 성공 직후 AGSID 재발급)
 *   - 권고 이벤트(Advisory) 수신 → 세션 종료 여부 자체 결정
 *   - Idempotent Consumer 구현 (중복 이벤트 방지)
 */
@SpringBootApplication
@EnableKafka
public class AgencyStubApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgencyStubApplication.class, args);
    }
}
