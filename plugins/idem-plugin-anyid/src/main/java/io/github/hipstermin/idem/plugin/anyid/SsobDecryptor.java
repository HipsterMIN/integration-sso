package io.github.hipstermin.idem.plugin.anyid;

import java.util.Map;

/**
 * Any-ID ssob(암호화된 인증 결과) 복호화 포트 — 플러그인 내부 계약.
 *
 * <p>기본 구현은 {@code sdk} 패키지의 SDK 래퍼({@code kr.or.anyid.util.AnyidCertRef})이며 SDK jar 가 있을 때만 빈으로 등록된다.
 * 테스트·대체 구현은 이 인터페이스로 갈아끼운다.
 */
public interface SsobDecryptor {

    /**
     * @param ssob 암호화된 ssob 문자열 (FE 가 전송)
     * @param tag  암호화 태그 (= txId)
     * @return 복호화된 ssob 필드 ({@code ci, authLvl, name, brdt, …})
     * @throws io.github.hipstermin.idem.common.error.PlatformException 복호화 실패
     */
    Map<String, Object> decrypt(String ssob, String tag);
}
