package kr.go.smes.qim.consent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * 동의 버전 정보 응답 DTO (회원가입/재동의 화면 로딩용)
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConsentVersionInfo {

    private final String  versionId;
    private final String  consentType;
    private final String  versionTag;
    private final String  title;
    private final String  contentUrl;
    private final boolean required;
    private final Instant effectiveAt;
}
