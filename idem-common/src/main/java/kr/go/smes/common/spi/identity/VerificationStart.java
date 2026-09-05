package kr.go.smes.common.spi.identity;

import java.util.Collections;
import java.util.Map;

/**
 * 인증 시작 결과.
 *
 * @param providerCode 제공자 코드
 * @param txId         제공자 트랜잭션 ID — {@link VerificationCallback#txId()} 로 되돌아온다
 * @param redirectUrl  브라우저를 보낼 URL (위젯형 제공자는 null 가능)
 * @param params       FE 가 위젯/폼에 넘길 파라미터 (없으면 빈 맵)
 */
public record VerificationStart(String providerCode, String txId, String redirectUrl, Map<String, String> params) {
    public VerificationStart {
        params = params == null ? Collections.emptyMap() : Map.copyOf(params);
    }
}
