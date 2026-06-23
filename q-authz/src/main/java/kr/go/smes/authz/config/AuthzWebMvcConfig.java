package kr.go.smes.authz.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 내부 API 키 인터셉터를 {@code /api/v1/internal/**} 에 적용.
 * Actuator·Swagger 경로는 제외하여 헬스체크/문서 접근을 허용한다.
 */
@Configuration
@RequiredArgsConstructor
public class AuthzWebMvcConfig implements WebMvcConfigurer {

    private final InternalApiKeyInterceptor internalApiKeyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalApiKeyInterceptor)
                // 내부 인가 API + SCIM 2.0 프로비저닝(기관 동기화) 모두 키 보호
                .addPathPatterns("/api/v1/internal/**", "/scim/v2/**")
                .excludePathPatterns("/actuator/**", "/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");
    }
}
