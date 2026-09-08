package io.github.hipstermin.idem.agent.core;

import io.github.hipstermin.idem.agent.config.AgentConfig;
import io.github.hipstermin.idem.agent.was.WasDetector;
import io.github.hipstermin.idem.agent.was.WasType;
import io.github.hipstermin.idem.agent.weaving.WeavingInstallException;
import io.github.hipstermin.idem.agent.weaving.WeavingStrategy;
import io.github.hipstermin.idem.agent.weaving.WeavingStrategyFactory;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;

/**
 * OnePass Agency Java Agent 진입점.
 *
 * <h2>기동 방식 A: 정적 어태치 (premain)</h2>
 * <pre>
 *   java -javaagent:/opt/onepass/onepass-agent-1.0.0-all.jar=config=/etc/onepass/onepass-agent.properties \
 *        -jar agency-was.jar
 * </pre>
 * JVM 기동 시 {@code premain()}이 {@code main()} 보다 먼저 호출된다.
 *
 * <h2>기동 방식 B: 동적 어태치 (agentmain)</h2>
 * <pre>
 *   // 운영 중인 JVM에 동적으로 Agent 로드 (Attach API)
 *   VirtualMachine vm = VirtualMachine.attach(pid);
 *   vm.loadAgent("/opt/onepass/onepass-agent-1.0.0-all.jar",
 *                "config=/etc/onepass/onepass-agent.properties");
 * </pre>
 * 이미 실행 중인 WAS에 무중단으로 Agent를 주입한다.
 * {@code agentmain()}은 {@code premain()}과 동일한 로직을 수행한다.
 *
 * <h2>초기화 흐름</h2>
 * <pre>
 *   premain(agentArgs, inst)
 *     ├─ 1. AgentConfig.load()        — 외부 설정 파일 또는 -D 옵션 로드 + 검증
 *     ├─ 2. enabled 체크              — false이면 즉시 반환 (JVM 영향 0)
 *     ├─ 3. WasDetector.detect()      — 클래스패스/시스템 프로퍼티로 WAS 유형 판정
 *     ├─ 4. WeavingStrategyFactory    — WAS 유형에 맞는 전략 선택
 *     ├─ 5. strategy.install(inst)    — byte-buddy AgentBuilder로 위빙 설치
 *     └─ 6. 헬스체크 (비동기)          — OnePass 서버 연결 확인 (Warn 수준, 비치명적)
 * </pre>
 *
 * <h2>에러 처리 철학</h2>
 * <ul>
 *   <li>설정 오류({@link AgentConfig.AgentConfigException}): WARN 로그 후 Agent만 중단
 *       → 유관기관 WAS는 정상 기동 (보안 실패보다 서비스 가용성 우선)</li>
 *   <li>위빙 실패: WARN 로그 후 계속 진행 — byte-buddy가 해당 클래스만 위빙 건너뜀</li>
 *   <li>네트워크 오류: 초기 헬스체크 실패 시 WARN만 출력 — 요청 단계에서 재시도</li>
 * </ul>
 *
 * <h2>클래스로더 격리</h2>
 * onepass-agent-all.jar는 bootstrap classpath가 아닌 일반 classpath에 위치.
 * byte-buddy shading({@code net.bytebuddy} → {@code io.github.hipstermin.idem.agent.shaded.bytebuddy})으로
 * 유관기관 WAS의 기존 byte-buddy 라이브러리와 네임스페이스 충돌 방지.
 *
 * <h2>JDK 8 호환</h2>
 * 이 클래스는 JDK 8 소스 호환성으로 컴파일됨:
 * var, text-block, instanceof pattern, record 미사용.
 */
public final class OnePassAgentMain {

    private static final String AGENT_BANNER =
            "╔══════════════════════════════════════════════════════════╗\n" +
            "║     OnePass Agency Java Agent v1.0.0 — Starting         ║\n" +
            "║     © 2025 행정안전부 OnePass 통합인증 플랫폼             ║\n" +
            "╚══════════════════════════════════════════════════════════╝";

    private OnePassAgentMain() {
        // 유틸리티 클래스 — 인스턴스화 금지
    }

    // ── JVM 진입점 ───────────────────────────────────────────────────────────────

    /**
     * 정적 어태치 진입점 (JVM 기동 시 main() 이전에 호출).
     *
     * @param agentArgs -javaagent 옵션의 '=' 이후 문자열. {@code null} 허용.
     * @param inst      JVM이 제공하는 Instrumentation 인스턴스. null이면 기능 불가.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        doInstall(agentArgs, inst, System.err);
    }

    /**
     * 동적 어태치 진입점 (Attach API로 런타임 주입 시 호출).
     *
     * <p>premain과 동일한 초기화 흐름을 수행.
     * 이미 로드된 클래스에 대한 위빙은 {@code Can-Retransform-Classes: true} 설정으로 지원.
     *
     * @param agentArgs Attach API 호출 시 전달된 옵션 문자열. {@code null} 허용.
     * @param inst      JVM이 제공하는 Instrumentation 인스턴스.
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        doInstall(agentArgs, inst, System.err);
    }

    // ── 내부 초기화 로직 ─────────────────────────────────────────────────────────

    /**
     * Agent 초기화 공통 로직.
     *
     * <p>premain/agentmain 양쪽에서 호출하며, {@code log} 파라미터는
     * 테스트에서 커스텀 PrintStream으로 교체 가능하도록 분리됨.
     *
     * @param agentArgs agentArgs (null 허용)
     * @param inst      Instrumentation (null이면 초기화 중단)
     * @param log       출력 스트림 (System.err)
     */
    static void doInstall(String agentArgs, Instrumentation inst, PrintStream log) {
        log.println(AGENT_BANNER);
        log.println("[OnePassAgent] 초기화 시작. agentArgs=" + agentArgs);

        // ── Instrumentation null 체크 ──────────────────────────────────────────
        if (inst == null) {
            log.println("[ERROR] [OnePassAgent] Instrumentation이 null입니다. "
                    + "JVM이 -javaagent 옵션을 지원하는지 확인하세요.");
            return;
        }

        // ── 1. 설정 로드 ────────────────────────────────────────────────────────
        AgentConfig config;
        try {
            config = AgentConfig.load(agentArgs, log);
        } catch (AgentConfig.AgentConfigException e) {
            log.println("[ERROR] [OnePassAgent] 설정 로드 실패 — Agent 비활성화: " + e.getMessage());
            // 유관기관 WAS 기동은 계속 진행 (보안 Agent만 비활성화)
            return;
        }

        // ── 2. enabled 체크 ──────────────────────────────────────────────────────
        if (!config.isEnabled()) {
            log.println("[OnePassAgent] onepass.agent.enabled=false — 위빙 건너뜀");
            return;
        }

        // ── 3. WAS 유형 감지 ─────────────────────────────────────────────────────
        WasType wasType = WasDetector.detect(log);
        log.println("[OnePassAgent] WAS 유형 감지: " + wasType);

        // ── 4. 위빙 전략 선택 ────────────────────────────────────────────────────
        WeavingStrategy strategy = WeavingStrategyFactory.create(wasType, config, log);

        // ── 5. 위빙 설치 ─────────────────────────────────────────────────────────
        try {
            strategy.install(inst);
            log.println("[OnePassAgent] 위빙 설치 완료: " + strategy.name());
        } catch (WeavingInstallException e) {
            log.println("[ERROR] [OnePassAgent] 위빙 설치 실패 (비치명적) — "
                    + strategy.name() + ": " + e.getMessage());
            // 위빙 실패해도 WAS 기동 중단하지 않음
            return;
        }

        // ── 6. 비동기 헬스체크 ──────────────────────────────────────────────────
        // premain 단계에서 네트워크 I/O를 동기로 실행하면 WAS 기동이 지연될 수 있으므로
        // 별도 데몬 스레드에서 비동기 실행.
        startHealthCheckThread(config, log);

        log.println("[OnePassAgent] 초기화 완료. WAS=" + wasType
                + " / 전략=" + strategy.name()
                + " / endpoint=" + config.endpoint());
    }

    /**
     * OnePass 서버 연결 확인을 비동기 데몬 스레드에서 실행.
     *
     * <p>WAS 기동이 완전히 완료된 후 연결을 확인하기 위해 30초 대기 후 시도.
     * 실패해도 WARN만 출력 — 개별 요청에서 재시도.
     */
    private static void startHealthCheckThread(final AgentConfig config, final PrintStream log) {
        Thread healthThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // WAS 기동 완료 대기
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    io.github.hipstermin.idem.agent.http.OnePassHttpClient client =
                            new io.github.hipstermin.idem.agent.http.OnePassHttpClient(config);
                    io.github.hipstermin.idem.agent.http.OnePassHttpClient.HttpResponse resp = client.healthCheck();
                    if (resp.isSuccess()) {
                        log.println("[OnePassAgent] OnePass 서버 연결 확인: HTTP " + resp.statusCode());
                    } else {
                        log.println("[WARN] [OnePassAgent] OnePass 서버 헬스체크 실패: HTTP "
                                + resp.statusCode() + " — 개별 요청에서 재시도");
                    }
                } catch (Exception e) {
                    log.println("[WARN] [OnePassAgent] OnePass 서버 연결 실패 (비치명적): "
                            + e.getMessage());
                }
            }
        });
        healthThread.setName("onepass-healthcheck");
        healthThread.setDaemon(true); // WAS 종료 시 함께 종료
        healthThread.start();
    }
}
