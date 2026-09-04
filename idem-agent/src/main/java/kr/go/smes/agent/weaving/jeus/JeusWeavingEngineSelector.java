package kr.go.smes.agent.weaving.jeus;

import kr.go.smes.agent.was.WasDetector;
import kr.go.smes.agent.was.WasType;

import java.io.PrintStream;

/**
 * JEUS 버전 + 런타임 JDK 버전을 합산하여 최적 위빙 엔진 종류를 결정하는 선택기.
 *
 * <h2>설계 목적</h2>
 * <p>{@link WasType}은 WAS 계열을 나타내지만, 실제 운영 서버의 런타임 JDK 버전은
 * 그것과 독립적으로 결정된다. 예를 들어 JEUS 7은 JDK 1.6~1.8을 지원하므로
 * JDK 1.7 환경이면 Javassist를 써야 하고, JDK 1.8 환경이면 byte-buddy도 가능하다.
 *
 * <p>이 클래스는 두 가지 정보를 합산하여 최종 엔진 타입을 결정한다:
 * <pre>
 *  WasType         런타임 JDK   → 위빙 엔진
 *  ─────────────────────────────────────────
 *  JEUS_LEGACY     JDK 1.4~1.5  → JAVASSIST  (byte-buddy 사용 불가)
 *  JEUS_6          JDK 1.5      → JAVASSIST  (byte-buddy 사용 불가)
 *  JEUS_6          JDK 1.6~1.7  → JAVASSIST  (안전을 위해 통일)
 *  JEUS_7          JDK 1.6~1.7  → JAVASSIST  (byte-buddy = JDK 8 미만)
 *  JEUS_7          JDK 1.8+     → BYTE_BUDDY
 *  JEUS_8          JDK 1.7      → JAVASSIST  (드문 경우)
 *  JEUS_8          JDK 1.8+     → BYTE_BUDDY
 *  JEUS_8_5        JDK 8 or 11  → BYTE_BUDDY
 *  JEUS_9_PLUS     JDK 11+      → BYTE_BUDDY
 *  기타 WAS        JDK 8+       → BYTE_BUDDY (원칙적으로 JDK 8+ 환경만 지원)
 * </pre>
 *
 * <h2>보수적 설계 원칙</h2>
 * <p>byte-buddy가 JDK 8 미만 환경에서 로드되면 {@link NoClassDefFoundError}가 발생하여
 * Agent 자체가 실패한다. Javassist는 JDK 1.3+에서 동작하므로 불확실한 경우
 * Javassist 쪽으로 폴백하는 것이 안전하다.
 */
public final class JeusWeavingEngineSelector {

    /** 위빙 엔진 종류 */
    public enum EngineType {
        /** Javassist 3.x 기반 — JDK 1.3+ 호환, 레거시 JEUS 대응 */
        JAVASSIST,
        /** byte-buddy 1.17.x 기반 — JDK 8+ 필요, 모던 JEUS 대응 */
        BYTE_BUDDY
    }

    private JeusWeavingEngineSelector() {}

    /**
     * JEUS WasType과 런타임 JDK 버전을 합산하여 최적 엔진 타입을 결정한다.
     *
     * @param wasType  감지된 JEUS WasType
     * @param log      로그 스트림 (null 허용)
     * @return 선택된 {@link EngineType}
     */
    public static EngineType select(WasType wasType, PrintStream log) {
        int jdkMajor = WasDetector.getRuntimeJdkMajor();
        return selectWithJdk(wasType, jdkMajor, log);
    }

    /**
     * 테스트 가능한 오버로드 — JDK 버전을 직접 지정.
     *
     * @param wasType   감지된 JEUS WasType
     * @param jdkMajor  런타임 JDK major 버전 (예: 5, 7, 8, 11, 21)
     * @param log       로그 스트림
     * @return 선택된 {@link EngineType}
     */
    static EngineType selectWithJdk(WasType wasType, int jdkMajor, PrintStream log) {

        // JEUS_LEGACY (JDK 1.4~1.5): byte-buddy 절대 불가 → 무조건 Javassist
        if (wasType == WasType.JEUS_LEGACY) {
            logInfo(log, "[EngineSelector] JEUS_LEGACY + JDK " + jdkMajor
                    + " → JAVASSIST (byte-buddy 사용 불가)");
            return EngineType.JAVASSIST;
        }

        // JEUS_6 (JDK 1.5~1.7): Javassist 통일 (JDK 1.6이어도 안전을 위해)
        if (wasType == WasType.JEUS_6) {
            logInfo(log, "[EngineSelector] JEUS_6 + JDK " + jdkMajor
                    + " → JAVASSIST (Javassist 통일 정책)");
            return EngineType.JAVASSIST;
        }

        // JEUS_7 (JDK 1.6~1.8): JDK 8 이상이면 byte-buddy 사용 가능
        if (wasType == WasType.JEUS_7) {
            if (jdkMajor >= 8) {
                logInfo(log, "[EngineSelector] JEUS_7 + JDK " + jdkMajor + " → BYTE_BUDDY");
                return EngineType.BYTE_BUDDY;
            }
            logInfo(log, "[EngineSelector] JEUS_7 + JDK " + jdkMajor
                    + " → JAVASSIST (JDK 8 미만)");
            return EngineType.JAVASSIST;
        }

        // JEUS_8 (JDK 1.7~1.8): JDK 8 이상이면 byte-buddy 사용 가능
        if (wasType == WasType.JEUS_8) {
            if (jdkMajor >= 8) {
                logInfo(log, "[EngineSelector] JEUS_8 + JDK " + jdkMajor + " → BYTE_BUDDY");
                return EngineType.BYTE_BUDDY;
            }
            logInfo(log, "[EngineSelector] JEUS_8 + JDK " + jdkMajor
                    + " → JAVASSIST (JDK 7 감지 — 드문 케이스)");
            return EngineType.JAVASSIST;
        }

        // JEUS_8_5 (JDK 8 or 11): 항상 byte-buddy
        if (wasType == WasType.JEUS_8_5) {
            logInfo(log, "[EngineSelector] JEUS_8_5 + JDK " + jdkMajor + " → BYTE_BUDDY");
            return EngineType.BYTE_BUDDY;
        }

        // JEUS_9_PLUS (JDK 11+): 항상 byte-buddy
        if (wasType == WasType.JEUS_9_PLUS) {
            logInfo(log, "[EngineSelector] JEUS_9_PLUS + JDK " + jdkMajor + " → BYTE_BUDDY");
            return EngineType.BYTE_BUDDY;
        }

        // 비-JEUS WAS: 기본 byte-buddy (원칙적으로 JDK 8+ 환경)
        logInfo(log, "[EngineSelector] " + wasType + " + JDK " + jdkMajor + " → BYTE_BUDDY (기본)");
        return EngineType.BYTE_BUDDY;
    }

    private static void logInfo(PrintStream log, String msg) {
        if (log != null) log.println(msg);
    }
}
