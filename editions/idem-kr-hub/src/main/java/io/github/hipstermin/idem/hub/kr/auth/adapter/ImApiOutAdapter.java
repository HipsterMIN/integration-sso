package io.github.hipstermin.idem.hub.kr.auth.adapter;

import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.hub.identity.SubjectRegistration;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.QimMemberInfo;
import io.github.hipstermin.idem.hub.infrastructure.QimRegisterResponse;
import io.github.hipstermin.idem.hub.kr.auth.dto.AuthResult;
import io.github.hipstermin.idem.hub.kr.auth.port.ImApiOutPort;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * KR 에디션: CI(연계정보) 기반 registry 등록·조회 어댑터.
 *
 * <p>S8-a: 코어 {@link QimClient} 는 CI 를 모른다(스킴 중립 {@code registerSubject}/{@code findByIdentifierHash}).
 * CI 스킴 해시 계산과 NICE 결과 → {@link SubjectRegistration} 변환은 여기서 한다. 회원 유형(memberType)은
 * registry 코어 계약에 없으므로 조회 결과에 싣지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImApiOutAdapter implements ImApiOutPort {

    private static final String PROVIDER_CODE = "NICE";

    private final QimClient qimClient;

    @Override
    public QimRegisterResponse register(AuthResult authResult, String correlationId) {
        if (authResult == null || authResult.getCi() == null || authResult.getCi().isBlank()) {
            throw new IllegalArgumentException("[ImApiOutAdapter] CI가 null/blank — register() 호출 전 CI 검증 필요");
        }
        log.info("[ImApiOutAdapter] registry 사용자 등록 요청(CI 스킴): ci={} correlationId={}", mask(authResult.getCi()), correlationId);
        SubjectRegistration reg = SubjectRegistration.builder()
                .scheme(SubjectScheme.CI)
                .subjectKey(authResult.getCi())
                .providerCode(PROVIDER_CODE)
                .name(authResult.getName())
                .phone(authResult.getMobile())
                .birthDate(authResult.getBirthday())
                .gender(authResult.getGender())
                .correlationId(correlationId)
                .build();
        QimRegisterResponse response = qimClient.registerSubject(reg);
        if (Boolean.TRUE.equals(response.getIsNew())) {
            log.info("[ImApiOutAdapter] registry 신규 사용자 등록 완료: qimUserId={}", response.getQimUserId());
        } else {
            log.info("[ImApiOutAdapter] registry 기존 사용자 인증 갱신: qimUserId={}", response.getQimUserId());
        }
        return response;
    }

    @Override
    public Optional<QimMemberInfo> findByCi(String ci, String memberType, String correlationId) {
        if (ci == null || ci.isBlank()) {
            throw new IllegalArgumentException("[ImApiOutAdapter] CI가 null/blank — findByCi() 호출 전 CI 검증 필요");
        }
        log.debug("[ImApiOutAdapter] registry CI 조회: ci={} memberType={}", mask(ci), memberType);
        Optional<QimMemberInfo> result = qimClient.findByIdentifierHash(SubjectScheme.CI.identifierHash(ci), correlationId);
        if (result.isPresent()) {
            log.info("[ImApiOutAdapter] registry CI 조회 성공: qimUserId={} memberType={}", result.get().getQimUserId(), memberType);
        } else {
            log.info("[ImApiOutAdapter] registry CI 미등록 사용자: ci={} memberType={}", mask(ci), memberType);
        }
        return result;
    }

    private static String mask(String ci) {
        return ci.length() > 8 ? ci.substring(0, 8) + "..." : "****";
    }
}
