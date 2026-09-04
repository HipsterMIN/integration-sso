package kr.go.smes.ido.qim.sp.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Q-IM → IdO 회원 조회 수신 요청 (MEMBER_QUERY)
 * Q-IM 명세서 v1.52 §4.2
 */
@Getter
@NoArgsConstructor
public class QimSpMemberQueryRequest {

    /** AES 암호화된 본인인증 CI (개인회원) */
    @JsonProperty("encCi")
    private String encCi;

    /** 사업자등록번호 (기업회원) */
    @JsonProperty("brno")
    private String brno;

    /** 회원 유형: IND(개인) | ENT(기업) */
    @JsonProperty("regType")
    private String regType;

    public boolean isPersonal() {
        return "IND".equalsIgnoreCase(regType);
    }

    public boolean isCorporate() {
        return "ENT".equalsIgnoreCase(regType);
    }
}
