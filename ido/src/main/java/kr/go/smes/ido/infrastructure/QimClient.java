package kr.go.smes.ido.infrastructure;

import kr.go.smes.common.domain.UserStatus;
import kr.go.smes.ido.auth.dto.AuthResult;
import kr.go.smes.ido.auth.dto.im.QimMemberInfo;
import kr.go.smes.ido.auth.dto.im.QimRegisterResponse;

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
 *   {@link kr.go.smes.ido.auth.adapter.ImApiOutAdapter}에서 직접 사용.
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

    /** 기관별 DI 조회/생성 (agencySubjectId 연계용) */
    String getDi(String qimUserId, String agencyCode, String correlationId);

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
     * @throws kr.go.smes.common.error.PlatformException Q-IM 통신 오류
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
     * @throws kr.go.smes.common.error.PlatformException Q-IM 통신 오류
     */
    Optional<QimMemberInfo> findByCi(String ci, String memberType, String correlationId);
}
