package io.github.hipstermin.idem.hub.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * registry(Q-IM) 사용자 조회 결과 — 코어는 식별자와 상태만 안다.
 *
 * <p>S8-a: 종전 {@code auth.dto.im.QimMemberInfo} 의 SMES 회원 유형·개인/기업 회원 ID 는 코어 계약에서 뺐다.
 * registry 는 그 값을 돌려준 적이 없고(항상 null), 회원 유형은 KR 에디션(idem-kr-hub) 의 개념이다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QimMemberInfo {

    @JsonProperty("qimUserId")
    private String qimUserId;

    @JsonProperty("status")
    private String status;
}
