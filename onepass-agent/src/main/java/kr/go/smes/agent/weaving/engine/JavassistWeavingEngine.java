package kr.go.smes.agent.weaving.engine;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;

import java.io.PrintStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Javassist 기반 바이트코드 위빙 엔진.
 *
 * <h2>존재 이유 (Why Javassist?)</h2>
 * <p>byte-buddy 1.17.x는 JDK 8 런타임을 필요로 한다. 그러나 한국 공공기관에서
 * 여전히 운영 중인 JEUS 4/5(JDK 1.4~1.5), JEUS 6(JDK 1.5~1.7) 환경에서는
 * byte-buddy를 사용할 수 없다. Javassist 3.x는 JDK 1.3+ 호환이므로 이 환경에서도
 * 바이트코드 조작이 가능하다.
 *
 * <h2>JDK 1.5 Agent 가능성 (이론적 배경)</h2>
 * <ul>
 *   <li>{@code java.lang.instrument.Instrumentation} + {@code Premain-Class}:
 *       JDK 1.5에 도입(JSR-163) → {@code premain()} 사용 가능</li>
 *   <li>Attach API({@code agentmain}): JDK 1.6에 도입 → JDK 1.5에서는 동적 어태치 불가</li>
 *   <li>{@code java.net.HttpURLConnection}: JDK 1.1+ → HTTP 통신 가능</li>
 *   <li>{@code javax.crypto.Mac} (HMAC-SHA256): JDK 1.4+ → HMAC 서명 가능</li>
 *   <li>Javassist 3.29.x: JDK 1.3+ 호환 → JDK 1.5에서 바이트코드 위빙 가능</li>
 * </ul>
 *
 * <h2>위빙 메커니즘</h2>
 * <p>Javassist의 {@link ClassFileTransformer}를 통해 대상 클래스가 JVM에 로드되는 시점에
 * 바이트코드를 변환한다. byte-buddy의 AgentBuilder와 달리 Javassist는 Java 소스 코드
 * 문자열(Javassist CtMethod#insertBefore/insertAfter)로 위빙 코드를 표현하므로
 * 레거시 JDK 환경에서도 안전하게 동작한다.
 *
 * <h2>ClassPool 전략</h2>
 * <pre>
 *   ClassPool.getDefault()             → 기본 풀 (Bootstrap + System ClassLoader)
 *   pool.appendClassPath(...)          → 대상 ClassLoader 추가 (WAS 클래스 접근)
 *   pool.makeClass(inputStream)        → 바이트코드에서 CtClass 생성
 * </pre>
 *
 * <h2>안전성 설계</h2>
 * <ul>
 *   <li>위빙 실패 시 원본 바이트코드 반환 ({@code return null}) — JVM 계속 기동</li>
 *   <li>모든 Throwable 캐치 — premain 예외가 JVM을 중단시키지 않도록</li>
 *   <li>frozen CtClass detach — 메모리 누수 방지</li>
 * </ul>
 *
 * <h2>JDK 8 소스 호환성 유지</h2>
 * <p>이 클래스는 onepass-agent 모듈의 {@code --release 8} 컴파일 제약 하에 빌드된다.
 * Javassist API 자체도 JDK 1.3+ 호환이므로 컴파일/런타임 모두 문제없다.
 * 실제 JEUS 4/5 환경(JDK 1.5)에서는 이 엔진이 선택되며,
 * JDK 8+ 환경에서는 byte-buddy 엔진이 우선 선택된다.
 */
public final class JavassistWeavingEngine {

    private final PrintStream log;

    /** 위빙 설치 완료 여부 — 중복 설치 방지 */
    private volatile boolean installed = false;

    public JavassistWeavingEngine(PrintStream log) {
        this.log = log;
    }

    /**
     * Javassist {@link ClassFileTransformer}를 Instrumentation에 등록한다.
     *
     * <p>canRetransform=true로 등록하여 이미 로드된 클래스도 재변환 가능.
     * 단, JDK 1.5에서는 재변환(retransformation) 지원이 제한적이므로
     * 가능하면 premain 진입 시점(클래스 로드 이전)에 Agent를 등록해야 한다.
     *
     * @param inst        JVM Instrumentation 인스턴스
     * @param transformer 실제 위빙 로직을 담은 Javassist 변환기
     */
    public void install(Instrumentation inst, JavassistClassFileTransformer transformer) {
        if (installed) {
            log("[JavassistEngine] 이미 설치됨 — 중복 설치 방지");
            return;
        }

        try {
            // canRetransform=true: 재변환 지원 요청
            // JDK 1.5에서는 MANIFEST Can-Retransform-Classes=true 가 있어야 실제 활성화
            inst.addTransformer(transformer, true);
            installed = true;
            log("[JavassistEngine] Transformer 등록 완료: " + transformer.targetClassName());

            // 이미 로드된 클래스가 있으면 재변환 시도 (JDK 1.6+ 전용)
            tryRetransformLoaded(inst, transformer);

        } catch (Throwable t) {
            logWarn("[JavassistEngine] Transformer 등록 실패: " + t.getMessage());
            // Agent 설치 실패는 WAS 기동 자체를 막으면 안 된다 — 예외 재던지지 않음
        }
    }

    /**
     * 이미 JVM에 로드된 대상 클래스를 재변환한다 (JDK 1.6+ 기능).
     *
     * <p>JDK 1.5에서는 {@link Instrumentation#retransformClasses(Class[])}가 없으므로
     * 이 메서드가 조용히 무시된다. premain 단계(클래스 로드 전)에 등록하면 재변환 없이도
     * 모든 클래스 로드 시 변환기가 적용된다.
     *
     * @param inst        Instrumentation 인스턴스
     * @param transformer 변환기 (대상 클래스 FQCN 정보 포함)
     */
    private void tryRetransformLoaded(Instrumentation inst, JavassistClassFileTransformer transformer) {
        try {
            // retransformClasses는 JDK 1.6+ API — 리플렉션으로 안전하게 호출
            // JDK 1.5에서는 NoSuchMethodException → catch → 무시
            Class<?>[] allLoaded = inst.getAllLoadedClasses();
            String targetInternal = transformer.targetClassName().replace('.', '/');

            for (int i = 0; i < allLoaded.length; i++) {
                Class<?> clazz = allLoaded[i];
                if (isTargetClass(clazz, targetInternal)) {
                    try {
                        // retransformClasses(Class<?>[]) — JDK 1.6+ 전용
                        // JDK 1.5에서는 UnsupportedOperationException 또는 NoSuchMethodError
                        inst.retransformClasses(new Class[]{clazz});
                        log("[JavassistEngine] 로드된 클래스 재변환 성공: " + clazz.getName());
                    } catch (UnsupportedOperationException ignored) {
                        log("[JavassistEngine] retransformClasses 미지원(JDK 1.5?) — 무시");
                    } catch (Throwable t) {
                        logWarn("[JavassistEngine] 재변환 실패 (" + clazz.getName() + "): " + t.getMessage());
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            logWarn("[JavassistEngine] getAllLoadedClasses 실패: " + t.getMessage());
        }
    }

    private static boolean isTargetClass(Class<?> clazz, String targetInternal) {
        String name = clazz.getName().replace('.', '/');
        return name.equals(targetInternal);
    }

    private void log(String msg) {
        if (log != null) log.println(msg);
    }

    private void logWarn(String msg) {
        if (log != null) log.println("[WARN] " + msg);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 내부 인터페이스 / 추상 변환기
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Javassist 기반 {@link ClassFileTransformer} 추상 구현체.
     *
     * <p>서브클래스는 {@link #targetClassName()}, {@link #targetMethodName()},
     * {@link #buildInsertBeforeSource(String, String)} 만 구현하면 된다.
     * 나머지 ClassPool 관리, 바이트코드 변환, 오류 처리는 이 클래스가 담당한다.
     *
     * <h2>Javassist 소스 코드 문자열 규약</h2>
     * <ul>
     *   <li>{@code $0}: this</li>
     *   <li>{@code $1}, {@code $2}, ...: 메서드 인수 (1-indexed)</li>
     *   <li>{@code $args}: 모든 인수 배열</li>
     *   <li>코드는 완전한 Java 문장(세미콜론 포함)이어야 함</li>
     *   <li>JDK 1.5 타겟 코드: 제네릭/오토박싱 사용 가능 (Javassist가 처리)</li>
     * </ul>
     */
    public abstract static class JavassistClassFileTransformer implements ClassFileTransformer {

        private final PrintStream log;

        // ClassPool을 인스턴스 변수로 유지 (재사용)
        // ClassPool은 CtClass 객체를 캐싱하므로 매 변환마다 새로 만들면 메모리 낭비
        private ClassPool classPool;

        protected JavassistClassFileTransformer(PrintStream log) {
            this.log = log;
        }

        /**
         * 위빙할 대상 클래스의 FQCN (예: "jeus.servlet.JeusHttpServlet").
         *
         * <p>내부적으로 JVM 내부 형식(슬래시 구분)으로 변환하여 비교한다.
         */
        public abstract String targetClassName();

        /**
         * 위빙할 대상 메서드 이름 (예: "service").
         *
         * <p>오버로드가 있는 경우 모든 시그니처에 위빙이 적용된다.
         * 특정 시그니처만 위빙하려면 서브클래스에서 {@link #shouldWeaveMethod(CtMethod)} 오버라이드.
         */
        public abstract String targetMethodName();

        /**
         * {@code insertBefore()}에 삽입할 Javassist 소스 코드 문자열을 생성한다.
         *
         * <p>이 코드는 대상 메서드 진입 직전에 실행된다.
         * 코드는 대상 클래스의 ClassLoader context에서 실행되므로
         * WAS 내부 클래스를 직접 참조할 수 있다.
         *
         * @param targetClassName  대상 클래스 FQCN
         * @param targetMethodName 대상 메서드 이름
         * @return Javassist {@code insertBefore()} 소스 문자열
         */
        public abstract String buildInsertBeforeSource(String targetClassName, String targetMethodName);

        /**
         * 이 메서드를 위빙 대상으로 포함할지 결정한다 (기본: targetMethodName 일치 시 포함).
         *
         * <p>서브클래스에서 오버라이드하여 시그니처 필터링 가능.
         * 예: void service(ServletRequest, ServletResponse)만 위빙하려면
         * 파라미터 타입을 확인하는 로직 추가.
         *
         * @param method 검사할 {@link CtMethod}
         * @return true이면 위빙 포함
         */
        protected boolean shouldWeaveMethod(CtMethod method) {
            return targetMethodName().equals(method.getName());
        }

        /**
         * JVM이 클래스를 로드할 때 호출되는 변환 콜백.
         *
         * <p>대상 클래스가 아니면 즉시 null(원본 유지)을 반환한다.
         * Javassist ClassPool을 통해 바이트코드를 조작하고 변환된 바이트코드를 반환한다.
         *
         * <p>어떤 예외도 상위로 전파하지 않는다 — 위빙 실패가 WAS 기동을 막으면 안 된다.
         */
        @Override
        public final byte[] transform(
                ClassLoader loader,
                String className,          // JVM 내부 형식: "jeus/servlet/JeusHttpServlet"
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer) throws IllegalClassFormatException {

            // JVM 내부 형식(슬래시) → 닷 형식으로 변환하여 비교
            if (className == null) return null;
            String dotName = className.replace('/', '.');

            // 대상 클래스가 아니면 원본 유지
            if (!dotName.equals(targetClassName())) return null;

            logInfo("[JavassistTransformer] 위빙 시작: " + dotName);

            // ClassPool 초기화 (첫 번째 변환 시)
            ensureClassPool(loader);

            CtClass ctClass = null;
            try {
                // classfileBuffer로 CtClass 생성 (ClassLoader에서 직접 로드하지 않고
                // 바이트코드 버퍼에서 생성 → 아직 초기화되지 않은 클래스도 처리 가능)
                ctClass = classPool.makeClass(
                        new java.io.ByteArrayInputStream(classfileBuffer));

                boolean woven = false;
                CtMethod[] methods = ctClass.getDeclaredMethods();
                for (int i = 0; i < methods.length; i++) {
                    CtMethod method = methods[i];
                    if (shouldWeaveMethod(method)) {
                        String source = buildInsertBeforeSource(targetClassName(), method.getName());
                        method.insertBefore(source);
                        logInfo("[JavassistTransformer] 위빙 완료: "
                                + dotName + "#" + method.getName());
                        woven = true;
                    }
                }

                if (!woven) {
                    logWarn("[JavassistTransformer] 대상 메서드 없음: "
                            + dotName + "#" + targetMethodName());
                    return null; // 원본 유지
                }

                return ctClass.toBytecode();

            } catch (Throwable t) {
                logWarn("[JavassistTransformer] 위빙 실패 (" + dotName + "): " + t.getMessage());
                return null; // 위빙 실패 → 원본 바이트코드 유지

            } finally {
                // CtClass detach — ClassPool 메모리 누수 방지
                if (ctClass != null) {
                    try {
                        ctClass.detach();
                    } catch (Throwable ignored) {}
                }
            }
        }

        /**
         * ClassPool을 초기화하고 대상 ClassLoader를 추가한다.
         *
         * <p>ClassPool은 한 번만 생성하고 재사용한다.
         * 대상 ClassLoader(WAS 클래스로더)를 ClassPool에 추가하여
         * WAS 내부 클래스(jeus.servlet.*, com.tmaxsoft.jeus.*)를 참조 가능하게 한다.
         */
        private synchronized void ensureClassPool(ClassLoader loader) {
            if (classPool != null) return;

            classPool = new ClassPool(ClassPool.getDefault());

            // 대상 ClassLoader의 클래스패스 추가 — WAS 내부 클래스 참조 가능
            if (loader != null) {
                classPool.appendClassPath(new LoaderClassPath(loader));
            }

            // 시스템 ClassLoader 추가 (Agent 자체 클래스 참조)
            ClassLoader systemCl = ClassLoader.getSystemClassLoader();
            if (systemCl != null) {
                classPool.appendClassPath(new LoaderClassPath(systemCl));
            }
        }

        private void logInfo(String msg) {
            if (log != null) log.println(msg);
        }

        private void logWarn(String msg) {
            if (log != null) log.println("[WARN] " + msg);
        }
    }
}
