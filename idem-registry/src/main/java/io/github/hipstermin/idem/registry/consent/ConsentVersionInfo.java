package io.github.hipstermin.idem.registry.consent;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.Builder;
import lombok.Getter;

/**
 * 동의 버전 정보 응답 DTO (회원가입/재동의 화면 로딩용)
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConsentVersionInfo {

    private final String  versionId;
    private final String  consentType;
    /** 1.1 카탈로그 범위 — null 이면 플랫폼 공통 */
    private final String  serviceCode;
    /** DRAFT / ACTIVE / SUPERSEDED (1.1 — 관리 목록용) */
    private final String  status;
    private final String  versionTag;
    private final String  title;
    private final String  contentUrl;
    private final boolean required;
    private final Instant effectiveAt;
}
