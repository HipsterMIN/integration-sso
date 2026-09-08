package io.github.hipstermin.idem.registry.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Q-IM WebMvc 설정 — 인터셉터 등록
 *
 * <h3>인터셉터 적용 경로</h3>
 * <ul>
 *   <li>{@code /api/v1/internal/**} — {@link InternalApiKeyInterceptor}
 *       ({@code X-Internal-Api-Key} 헤더 검증)</li>
 * </ul>
 *
 * <h3>제외 경로</h3>
 * <ul>
 *   <li>{@code /actuator/**}  — 헬스체크 (Kubernetes probe, 모니터링)</li>
 *   <li>{@code /api/v1/users/**} — Q-IM Status API (공개 상태 조회 — {@code QimStatusController})</li>
 * </ul>
 *
 * <p><b>P2 보안 수정</b>: 기존 {@code UserController}의 {@code X-Internal-Api-Key}
 * 헤더 검증이 누락되어 있던 문제를 이 설정으로 일괄 해소.
 * IdO → Q-IM 내부 호출 경로({@code /api/v1/internal/**}) 전체에 키 검증 적용.
 */
@Configuration
@RequiredArgsConstructor
public class QimWebMvcConfig implements WebMvcConfigurer {

    private final InternalApiKeyInterceptor internalApiKeyInterceptor;

    /**
     * {@link InternalApiKeyInterceptor}를 {@code /api/v1/internal/**} 경로에 등록.
     *
     * <p>excludePathPatterns로 Actuator와 공개 Status API는 제외.
     * Q-IM Status API({@code GET /api/v1/users/{qimUserId}})는 IdO가 호출하지만
     * 기관 서버가 직접 접근할 수도 있으므로 인터셉터에서 제외.
     * (대신 해당 API는 네트워크 레벨 접근 제어로 보호 — 설계서 §10.6)
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalApiKeyInterceptor)
                .addPathPatterns("/api/v1/internal/**");
    }
}
