package kr.go.smes.ido.auth.adapter;

import kr.go.smes.ido.auth.dto.AuthResult;
import kr.go.smes.ido.auth.dto.im.QimMemberInfo;
import kr.go.smes.ido.auth.dto.im.QimRegisterResponse;
import kr.go.smes.ido.auth.port.ImApiOutPort;
import kr.go.smes.ido.infrastructure.QimClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * ImApiOutPort 구현체 — Q-IM 내부 API 아웃바운드 어댑터 (S7-T6)
 *
 * <p>헥사고날 아키텍처의 Secondary Adapter로, {@link ImApiOutPort} 포트를
 * {@link QimClient} HTTP 클라이언트를 통해 Q-IM 내부 API에 연결한다.
 *
 * <p><b>책임:</b>
 * <ul>
 *   <li>포트 인터페이스 → QimClient 메서드 위임 (단순 위임 패턴)</li>
 *   <li>CI 마스킹 로그 (보안: 앞 8자만 표시)</li>
 *   <li>비즈니스 유효성 검증 (ci null/blank 사전 검사)</li>
 * </ul>
 *
 * <p><b>의존성 방향:</b>
 * {@code AuthService} → {@code ImApiOutPort} ← {@code ImApiOutAdapter} → {@code QimClient}
 *
 * <p><b>오류 전파 전략:</b>
 * {@code QimClient}에서 발생한 {@link kr.go.smes.common.error.PlatformException}을
 * 그대로 상위로 전파한다. 어댑터에서 별도 복구 로직을 추가하지 않는다.
 * 인증 플로우에서 Q-IM 장애는 치명적 오류로 취급한다.
 *
 * @see ImApiOutPort
 * @see QimClient
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImApiOutAdapter implements ImApiOutPort {

    private final QimClient qimClient;

    /**
     * {@inheritDoc}
     *
     * <p>구현 상세:
     * <ol>
     *   <li>CI null/blank 검증 (PlatformException 대신 IllegalArgumentException — 프로그래밍 오류)</li>
     *   <li>{@link QimClient#registerUser(AuthResult, String)} 위임</li>
     *   <li>등록 결과 로그 (isNew 여부 — 신규 등록 vs 기존 회원 갱신)</li>
     * </ol>
     */
    @Override
    public QimRegisterResponse register(AuthResult authResult, String correlationId) {
        if (authResult == null || authResult.getCi() == null || authResult.getCi().isBlank()) {
            throw new IllegalArgumentException("[ImApiOutAdapter] CI가 null/blank — register() 호출 전 CI 검증 필요");
        }

        String ciMasked = authResult.getCi().length() > 8
                ? authResult.getCi().substring(0, 8) + "..."
                : "****";
        log.info("[ImApiOutAdapter] Q-IM 사용자 등록 요청: ci={} correlationId={}", ciMasked, correlationId);

        QimRegisterResponse response = qimClient.registerUser(authResult, correlationId);

        if (Boolean.TRUE.equals(response.getIsNew())) {
            log.info("[ImApiOutAdapter] Q-IM 신규 사용자 등록 완료: qimUserId={}", response.getQimUserId());
        } else {
            log.info("[ImApiOutAdapter] Q-IM 기존 사용자 인증 갱신: qimUserId={}", response.getQimUserId());
        }

        return response;
    }

    /**
     * {@inheritDoc}
     *
     * <p>구현 상세:
     * <ol>
     *   <li>CI null/blank 검증</li>
     *   <li>{@link QimClient#findByCi(String, String, String)} 위임</li>
     *   <li>미등록 사용자는 {@code Optional.empty()} 반환 (예외 없음)</li>
     * </ol>
     */
    @Override
    public Optional<QimMemberInfo> findByCi(String ci, String memberType, String correlationId) {
        if (ci == null || ci.isBlank()) {
            throw new IllegalArgumentException("[ImApiOutAdapter] CI가 null/blank — findByCi() 호출 전 CI 검증 필요");
        }

        String ciMasked = ci.length() > 8 ? ci.substring(0, 8) + "..." : "****";
        log.debug("[ImApiOutAdapter] Q-IM CI 조회: ci={} memberType={}", ciMasked, memberType);

        Optional<QimMemberInfo> result = qimClient.findByCi(ci, memberType, correlationId);

        if (result.isPresent()) {
            log.info("[ImApiOutAdapter] Q-IM CI 조회 성공: qimUserId={} memberType={}",
                    result.get().getQimUserId(), memberType);
        } else {
            log.info("[ImApiOutAdapter] Q-IM CI 미등록 사용자: ci={} memberType={}", ciMasked, memberType);
        }

        return result;
    }
}
