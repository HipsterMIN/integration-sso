package io.github.hipstermin.idem.hub.broker.anyid;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Any-ID 인증 결과 DTO
 *
 * <p>Any-ID 인증 서버({@code /api/v1/verify}) 응답을 정규화한 도메인 객체.
 * {@link AnyIdBrokerAdapter#verifyCallback}에서 생성하여 컨트롤러에 전달한다.
 *
 * <p><b>identifier</b>: CI(연계정보) — 개인 식별 목적, SHA-256 해시하여 DB 저장.<br>
 * <b>dn</b>: DN(이름) — 화면 표시용.<br>
 * <b>authLevel</b>: L1(간편)/L2(신분증·PASS)/L3(인증서).<br>
 * <b>providerId</b>: 민간ID(소셜) 경우에만 소셜 사용자 ID.
 *
 * @see AnyIdBrokerAdapter
 * @see AnyIdController
 */
@Getter
@Builder
@ToString(exclude = {"identifier", "dn", "providerId"})  // 개인정보 로그 제외
public class AnyIdAuthResult {

    /** Any-ID result_code ("0000" = 성공) */
    private final String resultCode;

    /** CI(연계정보) — 개인 식별자, SHA-256 해시 후 사용 */
    private final String identifier;

    /** DN(이름) — 화면 표시용 */
    private final String dn;

    /** 인증 수준: L1 / L2 / L3 */
    private final String authLevel;

    /** Any-ID 트랜잭션 ID */
    private final String txId;

    /** 민간ID 소셜 사용자 ID (민간ID 인증 시에만 값 존재) */
    private final String providerId;

    /** 인증 수단 코드 (MOBILE_ID / EASY_SIGN / JOINT_CERT / FINANCIAL_CERT / PRIVATE_ID) */
    private final String providerCode;

    /** 서비스 번호 (ido.anyid.srvc-no) */
    private final String srvcNo;

    /** 흐름 추적 ID */
    private final String correlationId;

    /** 인증 성공 여부 */
    public boolean isSuccess() {
        return "0000".equals(resultCode);
    }

    /** CI 값 보유 여부 */
    public boolean hasIdentifier() {
        return identifier != null && !identifier.isBlank();
    }

    /** 민간ID 인증 여부 */
    public boolean isPrivateId() {
        return "PRIVATE_ID".equals(providerCode);
    }
}
