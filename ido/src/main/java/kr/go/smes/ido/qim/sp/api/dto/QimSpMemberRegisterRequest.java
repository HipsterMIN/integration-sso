package kr.go.smes.ido.qim.sp.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Q-IM → IdO 회원 등록 수신 요청 (MEMBER_REGISTER)
 * Q-IM 명세서 v1.52 §4.3
 *
 * regMode=NEW  : 신규 가입
 * regMode=TRANSFER : 전환 (기존 SP에서 본 SP로 이동)
 */
@Getter
@NoArgsConstructor
public class QimSpMemberRegisterRequest {

    // ── 공통 ─────────────────────────────────────────────────────────────────

    /** 회원 식별번호 (개인) */
    @JsonProperty("mbrNo")
    private String mbrNo;

    /** 회원 UUID (개인) */
    @JsonProperty("mbrUuid")
    private String mbrUuid;

    /** 기업 회원 식별번호 */
    @JsonProperty("entMbrNo")
    private String entMbrNo;

    /** 기업 회원 UUID */
    @JsonProperty("entMbrUuid")
    private String entMbrUuid;

    /** 등록 모드: NEW(신규) | TRANSFER(전환) */
    @JsonProperty("regMode")
    private String regMode;

    /** AES 암호화된 CI */
    @JsonProperty("encCi")
    private String encCi;

    // ── 개인회원 정보 ─────────────────────────────────────────────────────────

    /** 성명 */
    @JsonProperty("mbrNm")
    private String mbrNm;

    /** 연락처 */
    @JsonProperty("phone")
    private String phone;

    /** 이메일 */
    @JsonProperty("email")
    private String email;

    /** 주소 */
    @JsonProperty("addr")
    private String addr;

    /** 생년월일 (yyyyMMdd) */
    @JsonProperty("birthDate")
    private String birthDate;

    /** 성별: M | F */
    @JsonProperty("gender")
    private String gender;

    /** 국적: DOMESTIC | FOREIGN */
    @JsonProperty("nationality")
    private String nationality;

    // ── 기업회원 정보 ─────────────────────────────────────────────────────────

    /** 사업체명 */
    @JsonProperty("entNm")
    private String entNm;

    /** 대표자명 */
    @JsonProperty("rprsNm")
    private String rprsNm;

    /** 사업자등록번호 */
    @JsonProperty("brno")
    private String brno;

    /** 담당자 정보 */
    @JsonProperty("pic")
    private Map<String, String> pic;

    // ── 공통 설정 ─────────────────────────────────────────────────────────────

    /** 알림 채널 설정 */
    @JsonProperty("notiPrefs")
    private Map<String, Boolean> notiPrefs;

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    public boolean isTransfer() {
        return "TRANSFER".equalsIgnoreCase(regMode);
    }

    public boolean isCorporate() {
        return entMbrUuid != null || entMbrNo != null;
    }

    /** 유효한 회원 UUID 반환 (개인/기업 통합) */
    public String getEffectiveMbrUuid() {
        return mbrUuid != null ? mbrUuid : entMbrUuid;
    }

    /** 유효한 회원 번호 반환 (개인/기업 통합) */
    public String getEffectiveMbrNo() {
        return mbrNo != null ? mbrNo : entMbrNo;
    }
}
