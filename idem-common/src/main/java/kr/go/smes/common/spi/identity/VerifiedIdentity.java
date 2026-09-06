package kr.go.smes.common.spi.identity;

import kr.go.smes.common.domain.AuthResult;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 표준화된 본인인증 결과. 벤더 형식(NICE ResultData 등)은 여기로 정규화된다.
 *
 * @param providerCode 제공자 코드
 * @param txId         제공자 트랜잭션 ID
 * @param subjectKey   동일인 판정 키. KR 에디션은 CI(또는 DI), 범용 코어는 이메일·전화·외부 sub 등 — 판정 로직은
 *                     범용화 로드맵의 IdentityResolver 가 담당하고 여기서는 값만 전달한다
 * @param name         성명
 * @param birthDate    생년월일 (YYYYMMDD, 제공자 원문 형식 유지)
 * @param gender       성별 코드 (제공자 원문)
 * @param phone        휴대전화 번호
 * @param phoneCarrier 통신사 코드
 * @param level        보증 등급
 * @param verifiedAt   인증 시각
 * @param attributes   제공자별 부가 속성 (내·외국인 구분 등) — null 이면 빈 맵. 개인정보 원문(CI 등)은 넣지 않는다
 */
public record VerifiedIdentity(
        String providerCode,
        String txId,
        String subjectKey,
        String name,
        String birthDate,
        String gender,
        String phone,
        String phoneCarrier,
        AuthResult.AuthLevel level,
        Instant verifiedAt,
        Map<String, String> attributes) {

    public VerifiedIdentity {
        attributes = attributes == null ? Collections.emptyMap() : Map.copyOf(attributes);
    }
}
