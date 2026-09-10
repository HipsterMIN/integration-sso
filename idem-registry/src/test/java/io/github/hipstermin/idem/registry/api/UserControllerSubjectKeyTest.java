package io.github.hipstermin.idem.registry.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.registry.api.dto.UserResponse;
import io.github.hipstermin.idem.registry.crypto.CiCryptoService;
import io.github.hipstermin.idem.registry.identity.DiGenerationService;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.QimUserJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.UserProfileJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.QimUserJpaRepository;
import io.github.hipstermin.idem.registry.outbox.OutboxService;
import io.github.hipstermin.idem.registry.user.UserRegistrationService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** S4 — 주체 키 조회·스킴 중립 등록 엔드포인트 (standalone MockMvc). */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserController — /subject · /register-subject (S4)")
class UserControllerSubjectKeyTest {

    @Mock UserRegistrationService userRegistrationService;
    @Mock QimUserJpaRepository userRepository;
    @Mock DiGenerationService diGenerationService;
    @Mock OutboxService outboxService;
    @Mock CiCryptoService ciCryptoService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new UserController(
                userRegistrationService, userRepository, diGenerationService, outboxService, ciCryptoService)).build();
    }

    private QimUserJpaEntity user(String scheme, String encKey) {
        QimUserJpaEntity u = QimUserJpaEntity.builder().qimUserId("u1").status("ACTIVE").build();
        u.setProfile(UserProfileJpaEntity.builder().qimUserId("u1").subjectScheme(scheme).subjectKey(encKey).isMinor(false).build());
        return u;
    }

    @Test
    @DisplayName("EMAIL 스킴 사용자에게 scheme=EMAIL 을 물으면 복호화한 키를 돌려준다")
    void subjectKey_matchingScheme_decrypted() throws Exception {
        given(userRepository.findById("u1")).willReturn(Optional.of(user("EMAIL", "v1.iv.enc")));
        given(ciCryptoService.isEncrypted("v1.iv.enc")).willReturn(true);
        given(ciCryptoService.decrypt("v1.iv.enc")).willReturn("alice@example.org");

        mvc.perform(get("/api/v1/internal/users/u1/subject").param("scheme", "email"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheme").value("EMAIL"))
                .andExpect(jsonPath("$.subjectKey").value("alice@example.org"));
    }

    @Test
    @DisplayName("스킴이 다르거나 사용자가 없으면 404, CI·모르는 스킴은 400 — CI 평문은 나가지 않는다")
    void subjectKey_mismatch404_ci400() throws Exception {
        given(userRepository.findById("u1")).willReturn(Optional.of(user("CI", "v1.iv.ci")));
        mvc.perform(get("/api/v1/internal/users/u1/subject").param("scheme", "EMAIL")).andExpect(status().isNotFound());

        given(userRepository.findById("nobody")).willReturn(Optional.empty());
        mvc.perform(get("/api/v1/internal/users/nobody/subject").param("scheme", "EMAIL")).andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/internal/users/u1/subject").param("scheme", "CI"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SUBJECT_SCHEME_NOT_SELECTABLE"));
        mvc.perform(get("/api/v1/internal/users/u1/subject").param("scheme", "PAIRWISE_HMAC")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/internal/users/u1/subject").param("scheme", "nope")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("register-subject 는 신규 201 / 기존 200 으로 UserResponse 를 돌려준다")
    void registerSubject_statusByIsNew() throws Exception {
        given(userRegistrationService.registerOrGet(any()))
                .willReturn(UserResponse.builder().qimUserId("u9").status("ACTIVE").subjectScheme("EMAIL").isNew(true).build());
        mvc.perform(post("/api/v1/internal/users/register-subject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheme\":\"EMAIL\",\"subjectKey\":\"a@b.c\",\"providerCode\":\"MOCK\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.qimUserId").value("u9"))
                .andExpect(jsonPath("$.subjectScheme").value("EMAIL"));

        given(userRegistrationService.registerOrGet(any()))
                .willReturn(UserResponse.builder().qimUserId("u9").status("ACTIVE").isNew(false).build());
        mvc.perform(post("/api/v1/internal/users/register-subject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheme\":\"EMAIL\",\"subjectKey\":\"a@b.c\",\"providerCode\":\"MOCK\"}"))
                .andExpect(status().isOk());
    }
}
