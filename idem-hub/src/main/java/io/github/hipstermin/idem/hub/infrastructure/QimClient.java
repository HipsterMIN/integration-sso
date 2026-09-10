package io.github.hipstermin.idem.hub.infrastructure;

import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.hub.auth.dto.AuthResult;
import io.github.hipstermin.idem.hub.auth.dto.im.QimMemberInfo;
import io.github.hipstermin.idem.hub.auth.dto.im.QimRegisterResponse;
import io.github.hipstermin.idem.hub.identity.SubjectRegistration;
import java.util.Map;
import java.util.Optional;

/**
 * Q-IM HTTP 클라이언트 인터페이스 (IdO → Q-IM 조회/등록)
 * 설계서 10.4 / 11.5절 참조
 *
 * <p><b>GAP-QIM-01 (v1.9.4)</b>: getUserById() 추가
 * — needsSync=true 수신 시 사용자 전체 정보(상태+프로필)를 Pull하여
 *   UserStatusCache 및 부가 캐시를 완전 갱신한다.
 *
 * <p><b>S7-T6 (v1.2.0)</b>: registerUser(), findByCi() 추가
 * — CI를 Q-IM에 등록/조회하는 내부 API 클라이언트 메서드.
 *   {@link io.github.hipstermin.idem.hub.auth.adapter.ImApiOutAdapter}에서 직접 사용.
 */
public interface QimClient {
    /** 사용자 상태 조회 (Cache miss 시) */
    UserStatus getUserStatus(String qimUserId, String correlationId);

    /**
     * GAP-QIM-01: 사용자 전체 정보 조회 (needsSync=true Pull용)
     * GET /api/v1/internal/users/{qimUserId}
     *
     * <p>반환 맵 키 (Q-IM UserResponse 필드):<br>
     * {@code qimUserId}, {@code status}, {@code nameMasked},
     * {@code mobileMasked}, {@code nationalityType}, {@code birthYear},
     * {@code gender}, {@code createdAt}, {@code updatedAt}
     *
     * @return UserResponse 필드 맵, 조회 실패 시 null
     */
    Map<String, Object> getUserById(String qimUserId, String correlationId);

    /** 기관별 DI 조회/생성 (agencySubjectId 연계용 — {@link io.github.hipstermin.idem.common.identity.SubjectScheme#PAIRWISE_HMAC}) */
    String getDi(String qimUserId, String agencyCode, String correlationId);

    /**
     * S4: 사용자의 주체 키 조회 — {@code GET /api/v1/internal/users/{qimUserId}/subject?scheme=}.
     *
     * <p>empty = 사용자가 없거나 그 스킴으로 등록되지 않았다(404 — 정당한 GUEST). 장애는
     * {@link io.github.hipstermin.idem.common.error.PlatformErrorCode#IDO_QIM_UNREACHABLE}.
     */
    Optional<String> getSubjectKey(String qimUserId, SubjectScheme scheme, String correlationId);

    /**
     * S4: 스킴 중립 사용자 등록 — {@code POST /api/v1/internal/users/register-subject}.
     * 기존 사용자면 {@code isNew=false} 로 돌려준다. {@link #registerUser} 는 이 계약으로 위임한다.
     */
    QimRegisterResponse registerSubject(SubjectRegistration registration);

    /**
     * S7-T6: CI를 Q-IM에 등록 (신규 사용자 생성 또는 기존 사용자 인증 시각 갱신)
     *
     * <p>POST /api/v1/internal/users/register
     *
     * <p>Q-IM은 CI를 AES-256-GCM으로 암호화하여 저장하고, CI 해시 기반으로
     * 중복 등록을 방지한다. 기존 사용자인 경우 {@code isNew=false}로 응답.
     *
     * @param authResult    본인인증 결과 DTO (ci, di, name, birthday, gender, mobile, mobileCorp)
     * @param correlationId 요청 추적 ID
     * @return Q-IM 등록 결과 (qimUserId, status, isNew)
     * @throws io.github.hipstermin.idem.common.error.PlatformException Q-IM 통신 오류
     */
    QimRegisterResponse registerUser(AuthResult authResult, String correlationId);

    /**
     * S7-T6: CI로 Q-IM 사용자 조회
     *
     * <p>POST /api/v1/internal/users/find-by-ci
     *
     * <p>Q-IM은 전달된 CI를 해시하여 내부 CI 레코드와 비교한다.
     * 매칭되는 사용자가 없으면 {@code Optional.empty()} 반환.
     *
     * @param ci            CI (88자)
     * @param memberType    회원 유형 코드 (A101: 개인, A102: 기업)
     * @param correlationId 요청 추적 ID
     * @return Q-IM 사용자 정보 (없으면 empty)
     * @throws io.github.hipstermin.idem.common.error.PlatformException Q-IM 통신 오류
     */
    Optional<QimMemberInfo> findByCi(String ci, String memberType, String correlationId);

    /**
     * 소셜 로그인(Keycloak) sub 기반 Q-IM 사용자 조회
     *
     * <p>POST /api/v1/internal/users/find-by-social-sub
     *
     * <p>Keycloak 콜백에서 CI 없이 소셜 계정(sub)만 있을 때 사용.
     * Q-IM이 (providerCode + sub) 조합으로 기존 등록 사용자를 조회한다.
     * 매칭되는 사용자가 없으면 {@code Optional.empty()} 반환.
     *
     * @param sub           Keycloak id_token sub 클레임
     * @param providerCode  소셜 제공자 코드 (KAKAO_OIDC, NAVER_OIDC 등)
     * @param correlationId 요청 추적 ID
     * @return Q-IM 사용자 정보 (없으면 empty)
     */
    Optional<QimMemberInfo> findBySocialSub(String sub, String providerCode, String correlationId);

    /**
     * 소셜 로그인 신규 사용자 Q-IM 등록
     *
     * <p>POST /api/v1/internal/users/register-social
     *
     * <p>Keycloak 콜백에서 CI 없이 소셜 계정(sub)만 있는 신규 사용자를
     * Q-IM에 등록한다. Q-IM은 (providerCode + sub) 조합으로 qimUserId를 생성한다.
     *
     * @param sub            Keycloak id_token sub 클레임
     * @param providerCode   소셜 제공자 코드 (KAKAO_OIDC, NAVER_OIDC 등)
     * @param identifierHash SHA-256(sub) — Q-IM 내부 추적용
     * @param correlationId  요청 추적 ID
     * @return Q-IM 등록 결과 (qimUserId, isNew)
     */
    QimRegisterResponse registerSocialUser(String sub, String providerCode,
                                           String identifierHash, String correlationId);
}
