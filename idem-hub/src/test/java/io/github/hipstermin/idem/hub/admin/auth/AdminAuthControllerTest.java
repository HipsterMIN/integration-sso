package io.github.hipstermin.idem.hub.admin.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.hub.api.GlobalExceptionHandler;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAuthController — 로그인 응답·세션 쿠키 속성·me")
class AdminAuthControllerTest {

    @Mock AdminAuthService authService;
    AdminProperties props = new AdminProperties();
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminAuthController(authService, props))
                .setCustomArgumentResolvers(new AdminPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void loginMfaEnrollThenSessionCookie() throws Exception {
        given(authService.login("admin", "pw", "127.0.0.1")).willReturn(new AdminAuthService.LoginResult(
                AdminAuthService.LoginStatus.MFA_ENROLL_REQUIRED, "tok", "SECRET", "otpauth://totp/x", null));
        mvc.perform(post("/api/v1/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MFA_ENROLL_REQUIRED"))
                .andExpect(jsonPath("$.mfaToken").value("tok"))
                .andExpect(jsonPath("$.secret").value("SECRET"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        AdminSessionStore.AdminSession s = new AdminSessionStore.AdminSession("sid-9", "a1", "admin", AdminRole.SYSTEM_ADMIN, null, true,
                Instant.now(), Instant.now(), "127.0.0.1");
        given(authService.verifyMfa(eq("tok"), eq("123456"), anyString())).willReturn(s);
        mvc.perform(post("/api/v1/admin/auth/mfa").contentType(MediaType.APPLICATION_JSON).content("{\"mfaToken\":\"tok\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin.username").value("admin"))
                .andExpect(jsonPath("$.admin.mustChangePassword").value(true))
                .andExpect(cookie().value("idemAdminSid", "sid-9"))
                .andExpect(cookie().httpOnly("idemAdminSid", true))
                .andExpect(cookie().secure("idemAdminSid", true))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Strict")));
    }

    @Test
    void meRequiresPrincipal() throws Exception {
        mvc.perform(get("/api/v1/admin/auth/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("E-IDO-130"));
        mvc.perform(get("/api/v1/admin/auth/me").requestAttr(AdminAuthFilter.ATTR_PRINCIPAL,
                        new AdminPrincipal("a1", "admin", AdminRole.AUDITOR, "T1", "sid", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("AUDITOR")).andExpect(jsonPath("$.tenantCode").value("T1"));
    }
}
