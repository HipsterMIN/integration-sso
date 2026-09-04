package kr.go.smes.ido.qim.sp.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Q-IM → IdO 회원 탈퇴 수신 요청 (MEMBER_WITHDRAW)
 * Q-IM 명세서 v1.52 §4.4
 */
@Getter
@NoArgsConstructor
public class QimSpMemberWithdrawRequest {

    /** AES 암호화된 CI */
    @JsonProperty("encCi")
    private String encCi;

    /** SP 자체 회원 식별자 (= instMbrId) */
    @JsonProperty("mbrId")
    private String mbrId;

    /** Q-IM 회원 UUID */
    @JsonProperty("mbrUuid")
    private String mbrUuid;

    /** 탈퇴 사유 코드: USER_REQUEST | ADMIN_FORCE | DORMANT */
    @JsonProperty("withdrawalReason")
    private String withdrawalReason;
}
