package io.github.hipstermin.idem.hub.kr.fe;

import io.github.hipstermin.idem.hub.fe.config.InternalCallerAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** KR 에디션 MVC 추가 설정 — 회원전환 세션 발급 경로에 코어의 내부 호출자 인증 인터셉터를 건다. */
@Configuration
@RequiredArgsConstructor
public class KrHubWebMvcConfig implements WebMvcConfigurer {

    private final InternalCallerAuthInterceptor internalCallerAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalCallerAuthInterceptor)
                .addPathPatterns("/api/v1/fe-session/conversion");
    }
}
