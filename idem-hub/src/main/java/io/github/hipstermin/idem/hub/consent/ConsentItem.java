package io.github.hipstermin.idem.hub.consent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** registry 동의 카탈로그 항목(버전) — {@code ConsentVersionInfo} 와 같은 모양. serviceCode null = 플랫폼 공통 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsentItem(String versionId, String consentType, String serviceCode, String status, String versionTag,
                          String title, String contentUrl, boolean required, Instant effectiveAt) {
    public boolean platformItem() { return serviceCode == null || serviceCode.isBlank(); }
}
