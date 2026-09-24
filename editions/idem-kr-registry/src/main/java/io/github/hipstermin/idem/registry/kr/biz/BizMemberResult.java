package io.github.hipstermin.idem.registry.kr.biz;

import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 기업회원 전환/조회 결과 DTO
 *
 * <p>시각 필드는 {@link Instant}(UTC epoch)로 반환한다.
 * API 응답 직렬화는 {@code spring.jackson.serialization.write-dates-as-timestamps=false}
 * 설정에 의해 ISO-8601 UTC 문자열(예: {@code "2026-05-21T06:00:00Z"})로 변환된다.
 * FE 또는 클라이언트에서 KST 표현이 필요한 경우 {@code +09:00} offset 변환을 적용한다.
 */
@Getter
@Builder
public class BizMemberResult {

    private final String  qimUserId;
    private final String  bizRegNo;
    private final String  companyName;
    private final String  repNameMasked;
    private final String  bizType;
    private final String  bizStatus;
    /** 사업자등록번호 인증 완료 시각 (UTC). null = 미인증. */
    private final Instant verifiedAt;
    /** 기업회원 전환 시각 (UTC). */
    private final Instant convertedAt;
}
