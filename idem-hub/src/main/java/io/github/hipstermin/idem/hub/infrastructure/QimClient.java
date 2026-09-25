package io.github.hipstermin.idem.hub.infrastructure;

import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
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
 * <p><b>S8-a</b>: CI 전용 registerUser/findByCi 를 없앴다. 등록은 {@link #registerSubject},
 * 조회는 {@link #findByIdentifierHash} — 스킴은 호출자가 {@link SubjectScheme} 로 정한다.
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
     * 기존 사용자면 {@code isNew=false} 로 돌려준다.
     */
    QimRegisterResponse registerSubject(SubjectRegistration registration);

    /**
     * S8-a: 식별자 해시로 사용자 조회 — {@code GET /api/v1/internal/users/by-hash?identifierHash=}.
     *
     * <p>해시는 호출자가 {@link SubjectScheme#identifierHash(String)} 로 만든다(스킴 중립).
     * 없으면 {@code Optional.empty()} (404). 장애는
     * {@link io.github.hipstermin.idem.common.error.PlatformErrorCode#IDO_QIM_UNREACHABLE}.
     */
    Optional<QimMemberInfo> findByIdentifierHash(String identifierHash, String correlationId);

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

    /**
     * D3: registry 아웃박스의 사용자 이벤트 피드 — {@code GET /api/v1/internal/events} (Kafka 없는 설치의 상태 전파).
     * 커서 {@code (afterCreatedAt, afterEventId)} 뒤의 이벤트를 생성순으로 최대 {@code limit} 건.
     *
     * @throws io.github.hipstermin.idem.common.error.PlatformException IDO_QIM_UNREACHABLE (호출 실패)
     */
    java.util.List<QimUserEventRecord> fetchUserEvents(java.time.Instant afterCreatedAt, String afterEventId,
                                                       int limit, String correlationId);
}
