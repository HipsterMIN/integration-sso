package com.onepass.fe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Onepass FE — 외부 사용자 접점 / 외부 채널 세션 오너
 * 설계서 12장 참조
 *
 * 책임:
 *   - 외부 사용자 접점 / 로그인 UX 단일화
 *   - 외부 채널 세션 오너십 (단독 만료·종료 권한)
 *   - 기관 진입 시 IdO와의 redirect 매개
 *   - returnUrl / deepLink 화이트리스트 검증
 *   - 다중 채널(웹/모바일웹/앱) UX 일관성
 */
@SpringBootApplication
@EnableKafka
public class OnepassFeApplication {

    public static void main(String[] args) {
        SpringApplication.run(OnepassFeApplication.class, args);
    }
}
