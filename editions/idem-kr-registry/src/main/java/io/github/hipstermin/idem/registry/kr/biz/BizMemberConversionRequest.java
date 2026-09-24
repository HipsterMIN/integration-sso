package io.github.hipstermin.idem.registry.kr.biz;

import lombok.Builder;
import lombok.Getter;

/**
 * 기업회원 전환 요청 DTO
 */
@Getter
@Builder
public class BizMemberConversionRequest {

    /** 전환 대상 개인 회원 qim_user_id */
    private final String qimUserId;

    /** 사업자등록번호 (숫자만 10자리 — 예: "1234567890") */
    private final String bizRegNo;

    /** 법인/상호명 */
    private final String companyName;

    /** 대표자명 (마스킹 처리 후 저장) */
    private final String repName;

    /** 업태 (선택) */
    private final String bizType;
}
