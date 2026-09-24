package io.github.hipstermin.idem.hub.identity;

import io.github.hipstermin.idem.common.spi.identity.VerifiedIdentity;
import io.github.hipstermin.idem.hub.infrastructure.QimClient;
import io.github.hipstermin.idem.hub.infrastructure.QimRegisterResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 본인인증 SPI 결과({@link VerifiedIdentity}) 를 registry 사용자로 확정한다 (S4 — 로드맵의 "IdentityResolver").
 *
 * <p>제공자 종류와 무관하게 {@code subjectScheme + subjectKey} 만으로 등록·조회하므로 CI 가 없는 스킴(EMAIL 등)도
 * 같은 경로를 탄다. 개인정보 원문(이름·전화)은 registry 가 마스킹해 저장하고 hub 는 보관하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubjectRegistrationService {

    private final QimClient qimClient;

    /** 등록 결과 — {@code qimUserId} 와 신규 여부. */
    public record Result(String qimUserId, boolean newUser) {}

    public Result register(VerifiedIdentity identity, String correlationId) {
        SubjectRegistration reg = SubjectRegistration.builder()
                .scheme(identity.subjectScheme())
                .subjectKey(identity.subjectKey())
                .providerCode(identity.providerCode())
                .authResultId(identity.txId())
                .authLevel(identity.level())
                .name(identity.name())
                .phone(identity.phone())
                .birthDate(identity.birthDate())
                .gender(identity.gender())
                .nationalityType(identity.attributes().get("nationalityType"))
                .correlationId(correlationId)
                .build();
        QimRegisterResponse res = qimClient.registerSubject(reg);
        boolean isNew = Boolean.TRUE.equals(res.getIsNew());
        log.info("[SubjectRegistration] provider={} scheme={} qimUserId={} new={} cid={}",
                identity.providerCode(), identity.subjectScheme(), res.getQimUserId(), isNew, correlationId);
        return new Result(res.getQimUserId(), isNew);
    }
}
