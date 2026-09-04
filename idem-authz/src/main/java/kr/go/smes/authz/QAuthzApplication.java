package kr.go.smes.authz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Q-Authz — 연합 인가(Federated Authorization) 서비스.
 *
 * <h3>책임 (설계 헌법)</h3>
 * <ul>
 *   <li><b>부여는 중앙, 해석은 지역</b>: 본 서비스는 "누가 어느 기관에서 어떤 역할을 갖는가"(assignment)만
 *       SoR(Source of Record)로 소유한다. "그 역할로 무엇을 할 수 있는가"(decision/enforcement)는
 *       각 유관기관이 자기 컨텍스트에서 집행한다.</li>
 *   <li><b>의미를 통일하지 않는다</b>: 역할 코드는 기관별 불투명 문자열. 60+ 이질 기관의 권한 의미론을
 *       하나로 모델링하지 않는다(복잡도 발산 방지).</li>
 *   <li><b>단일 멀티테넌트</b>: 한 벌 스키마 + {@code agency_code} 테넌트 컬럼 + PostgreSQL RLS.
 *       기관별 물리 스키마는 운영 폭발이므로 금지.</li>
 * </ul>
 *
 * <p>본 L1 코어 범위: 역할 카탈로그 + 사용자 역할 부여(grant/revoke) + append-only 감사 + 내부 API 보안.
 * 토큰 클레임 주입(Handoff/CAST {@code roles[]})·PEP 강제는 ido 측 후속 증분에서 본 서비스를 호출한다.
 */
@SpringBootApplication
@EnableScheduling
public class QAuthzApplication {

    public static void main(String[] args) {
        SpringApplication.run(QAuthzApplication.class, args);
    }
}
