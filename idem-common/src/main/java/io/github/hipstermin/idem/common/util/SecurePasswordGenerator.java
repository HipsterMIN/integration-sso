package io.github.hipstermin.idem.common.util;

import io.github.hipstermin.idem.common.crypto.CryptoProviders;

/**
 * 암호학적으로 안전한 임시 비밀번호 생성 유틸리티
 *
 * <p><b>배경 (운영 보안 요구사항):</b>
 * 기업 관리자 계정 초기화 시 임시 비밀번호가 필요하다.
 * {@code Math.random()} 기반 PRNG는 암호학적으로 안전하지 않으므로(CSPRNG 미충족)
 * 예측 가능한 비밀번호가 생성될 위험이 있다.
 * 68개 유관기관 관리자 계정 초기 비밀번호에 이 취약점이 적용되면
 * 전체 기관 관리자 계정이 위협받을 수 있다.
 *
 * <p><b>구현 방식:</b>
 * {@code CryptoProvider.randomInt}은 JVM이 제공하는 CSPRNG(Cryptographically Secure PRNG)로,
 * OS 엔트로피 소스({@code /dev/urandom}, Windows CryptGenRandom)를 사용한다.
 * {@code Math.random()}과 달리 예측 불가능한 무작위성을 보장한다.
 *
 * <p><b>비밀번호 정책 (기본값):</b>
 * <ul>
 *   <li>길이: 12자 (대문자 2개 이상, 소문자 2개 이상, 숫자 2개 이상, 특수문자 2개 이상)</li>
 *   <li>대문자: A-Z</li>
 *   <li>소문자: a-z</li>
 *   <li>숫자: 0-9</li>
 *   <li>특수문자: {@code !@#$%^&*} (Keycloak 정책 허용 범위 내)</li>
 * </ul>
 *
 * <p><b>스레드 안전성:</b>
 * {@code CryptoProvider.randomInt}은 스레드 안전하다. 단일 인스턴스를 공유 사용한다.
 *
 * <p><b>사용 시나리오:</b>
 * <ul>
 *   <li>기업 관리자 계정 초기화 시 임시 비밀번호 생성</li>
 *   <li>Keycloak 사용자 생성 시 초기 비밀번호 (첫 로그인 후 변경 강제 권장)</li>
 * </ul>
 *
 * <p><b>FE 대응:</b>
 * FE Step5에서 {@code Math.random()} 기반으로 임시 비밀번호를 생성하여
 * {@code POST /api/ext/provision/enterprises}에 전달하는 흐름을 개선하기 위해,
 * 향후 이 유틸리티를 사용하는 BFF 엔드포인트를 통해 서버 사이드 생성을 권장한다.
 *
 * @see io.github.hipstermin.idem.common.crypto.CryptoProvider
 */
public final class SecurePasswordGenerator {


    /** 비밀번호 구성 문자 집합 */
    private static final String UPPERCASE   = "ABCDEFGHJKLMNPQRSTUVWXYZ"; // I, O 제외 (혼동 방지)
    private static final String LOWERCASE   = "abcdefghjkmnpqrstuvwxyz";  // i, l, o 제외 (혼동 방지)
    private static final String DIGITS      = "23456789";                  // 0, 1 제외 (혼동 방지)
    private static final String SPECIALS    = "!@#$%^&*";                  // Keycloak 허용 특수문자

    /** 기본 비밀번호 길이 */
    private static final int DEFAULT_LENGTH = 12;

    /** 각 문자 유형별 최소 개수 */
    private static final int MIN_PER_TYPE = 2;

    private SecurePasswordGenerator() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * 기본 정책(12자, 각 유형 2개 이상)의 임시 비밀번호를 생성한다.
     *
     * <p>생성된 비밀번호는 대문자·소문자·숫자·특수문자를 각 2개 이상 포함하며,
     * {@code CryptoProvider.randomInt}으로 예측 불가능한 순서로 섞인다.
     *
     * <p><b>주의:</b> 이 비밀번호는 임시 비밀번호이며, 첫 로그인 후 사용자가 반드시
     * 변경하도록 Keycloak의 {@code requiredAction: UPDATE_PASSWORD}를 설정해야 한다.
     *
     * @return 12자 임시 비밀번호 (CSPRNG 생성)
     */
    public static String generate() {
        return generate(DEFAULT_LENGTH);
    }

    /**
     * 지정 길이의 임시 비밀번호를 생성한다.
     *
     * <p>각 유형(대/소문자, 숫자, 특수문자)에서 {@value #MIN_PER_TYPE}개씩 선택 후
     * 나머지를 전체 문자 집합에서 무작위 선택하여 섞는다.
     *
     * @param length 비밀번호 길이 (최소 8자, 최대 32자)
     * @return 지정 길이의 임시 비밀번호 (CSPRNG 생성)
     * @throws IllegalArgumentException length가 8 미만이거나 32 초과인 경우
     */
    public static String generate(int length) {
        if (length < 8 || length > 32) {
            throw new IllegalArgumentException(
                    "비밀번호 길이는 8자 이상 32자 이하이어야 합니다: length=" + length);
        }

        char[] password = new char[length];
        int idx = 0;

        // 각 문자 유형별 최소 개수 보장
        for (int i = 0; i < MIN_PER_TYPE; i++) {
            password[idx++] = randomChar(UPPERCASE);
            password[idx++] = randomChar(LOWERCASE);
            password[idx++] = randomChar(DIGITS);
            password[idx++] = randomChar(SPECIALS);
        }

        // 나머지 자리는 전체 문자 집합에서 선택
        String all = UPPERCASE + LOWERCASE + DIGITS + SPECIALS;
        while (idx < length) {
            password[idx++] = randomChar(all);
        }

        // Fisher-Yates 셔플로 순서 무작위화 (CSPRNG 기반)
        for (int i = length - 1; i > 0; i--) {
            int j = CryptoProviders.current().randomInt(i + 1);
            char tmp = password[i];
            password[i] = password[j];
            password[j] = tmp;
        }

        return new String(password);
    }

    /**
     * 주어진 문자 집합에서 임의의 문자 하나를 선택한다.
     *
     * @param charSet 문자 집합 문자열
     * @return 무작위 선택된 문자
     */
    private static char randomChar(String charSet) {
        return charSet.charAt(CryptoProviders.current().randomInt(charSet.length()));
    }
}
