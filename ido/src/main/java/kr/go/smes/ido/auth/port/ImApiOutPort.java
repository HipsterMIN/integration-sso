package kr.go.smes.ido.auth.port;

import kr.go.smes.ido.auth.dto.AuthResult;
import kr.go.smes.ido.auth.dto.im.QimMemberInfo;
import kr.go.smes.ido.auth.dto.im.QimRegisterResponse;

import java.util.Optional;

/**
 * IM API 아웃바운드 포트 — CI를 Q-IM(Identity Manager)에 등록/조회
 *
 * <p>헥사고날 아키텍처의 Secondary Port (OutPort)로, {@code ido} 모듈에서
 * Q-IM 내부 API를 호출하는 추상 경계를 정의한다.
 *
 * <p><b>설계 의도 (S7-T6):</b>
 * <ul>
 *   <li>NICE 휴대폰 인증 완료 → CI를 Q-IM에 등록 ({@link #register})</li>
 *   <li>OACX 간편서명 완료 → CI를 Q-IM에 등록 ({@link #register})</li>
 *   <li>NICE CI 확인 요청 → CI로 기존 회원 조회 ({@link #findByCi})</li>
 * </ul>
 *
 * <p><b>보안 원칙 (Q3=B):</b>
 * CI는 PII(개인식별정보)이며, 이 포트를 통해서만 Q-IM으로 전달된다.
 * FE 응답 DTO에는 절대 CI를 포함하지 않는다.
 *
 * <p><b>Q-IM 엔드포인트 매핑:</b>
 * <ul>
 *   <li>{@code register} → {@code POST /api/v1/internal/users/register}</li>
 *   <li>{@code findByCi} → {@code POST /api/v1/internal/users/find-by-ci}</li>
 * </ul>
 *
 * @see kr.go.smes.ido.auth.adapter.ImApiOutAdapter
 * @see kr.go.smes.ido.infrastructure.QimClient
 */
public interface ImApiOutPort {

    /**
     * Q-IM에 본인인증 결과(CI 포함)를 등록한다.
     *
     * <p>신규 사용자인 경우 Q-IM이 CI 기반으로 사용자 레코드를 생성하고,
     * 기존 사용자인 경우 마지막 인증 시각을 갱신한다.
     *
     * <p><b>호출 시점:</b>
     * <ul>
     *   <li>{@code AuthService.handleOacxEasysign()} — OACX 간편서명 완료 후</li>
     *   <li>{@code NiceAuthService.getNicePhoneAuthResult()} — NICE 인증 결과 수신 후</li>
     * </ul>
     *
     * <p><b>오류 처리:</b>
     * Q-IM 서버 장애 시 {@link kr.go.smes.common.error.PlatformException}을 던지며,
     * 인증 플로우 전체가 롤백된다 (CI 미등록 상태로 세션 진행 불가).
     *
     * @param authResult    본인인증 결과 DTO (ci, di, name, birthday, gender, mobile, mobileCorp)
     * @param correlationId 요청 추적 ID (X-Correlation-Id 헤더로 Q-IM에 전달)
     * @return Q-IM 등록 결과 (qimUserId, status, isNew 포함)
     * @throws kr.go.smes.common.error.PlatformException Q-IM 통신 오류 또는 등록 실패
     */
    QimRegisterResponse register(AuthResult authResult, String correlationId);

    /**
     * CI로 Q-IM 사용자를 조회한다.
     *
     * <p>NICE CI 확인 요청({@code POST /api/v1/auth/nice/ci-check}) 처리 시
     * CI가 Q-IM에 이미 등록된 사용자인지 확인한다.
     *
     * <p><b>호출 시점:</b>
     * <ul>
     *   <li>{@code AuthService.checkNiceCi()} — CI 기반 회원 조회/등록</li>
     * </ul>
     *
     * <p><b>반환값 의미:</b>
     * <ul>
     *   <li>{@code Optional.of(info)} — CI에 해당하는 Q-IM 사용자 존재</li>
     *   <li>{@code Optional.empty()} — 미등록 사용자 (신규 등록 필요)</li>
     * </ul>
     *
     * <p><b>오류 처리:</b>
     * Q-IM 서버 장애 시 {@link kr.go.smes.common.error.PlatformException}을 던지며,
     * 빈 Optional이 아닌 예외를 사용하여 장애와 미조회를 구분한다.
     *
     * @param ci            CI (88자, NICE/OACX 인증 결과에서 추출)
     * @param memberType    회원 유형 코드 (A101: 개인, A102: 기업)
     * @param correlationId 요청 추적 ID
     * @return Q-IM 사용자 정보 (없으면 empty)
     * @throws kr.go.smes.common.error.PlatformException Q-IM 통신 오류
     */
    Optional<QimMemberInfo> findByCi(String ci, String memberType, String correlationId);
}
