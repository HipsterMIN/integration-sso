package io.github.hipstermin.idem.hub.admin.auth;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 컨트롤러 인자 {@link AdminPrincipal} — {@link AdminAuthFilter} 가 넣은 요청 속성. 없으면 401 (심층 방어). */
@Component
public class AdminPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AdminPrincipal.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Object p = request != null ? request.getAttribute(AdminAuthFilter.ATTR_PRINCIPAL) : null;
        if (p instanceof AdminPrincipal principal) return principal;
        throw new PlatformException(PlatformErrorCode.ADMIN_UNAUTHENTICATED, null, "관리자 세션 없음");
    }
}
