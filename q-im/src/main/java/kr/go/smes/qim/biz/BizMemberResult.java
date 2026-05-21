package kr.go.smes.qim.biz;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 기업회원 전환/조회 결과 DTO
 */
@Getter
@Builder
public class BizMemberResult {

    private final String qimUserId;
    private final String bizRegNo;
    private final String companyName;
    private final String repNameMasked;
    private final String bizType;
    private final String bizStatus;
    private final LocalDateTime verifiedAt;
    private final LocalDateTime convertedAt;
}
