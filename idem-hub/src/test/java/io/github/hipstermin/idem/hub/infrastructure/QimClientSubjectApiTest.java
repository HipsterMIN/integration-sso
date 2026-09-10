package io.github.hipstermin.idem.hub.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.identity.SubjectScheme;
import io.github.hipstermin.idem.hub.auth.dto.im.QimMemberInfo;
import io.github.hipstermin.idem.hub.auth.dto.im.QimRegisterResponse;
import io.github.hipstermin.idem.hub.identity.SubjectRegistration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/** S4 — register-subject / subject-key / by-hash 클라이언트 계약. */
@DisplayName("QimClientImpl — S4 주체 등록·조회 (registerSubject / getSubjectKey / findByCi→by-hash)")
@ExtendWith(MockitoExtension.class)
class QimClientSubjectApiTest {

    @Mock private RestTemplate restTemplate;
    private QimClientImpl sut;

    private static final String BASE = "http://q-im-test:8082";

    @BeforeEach
    void setUp() {
        sut = new QimClientImpl(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(sut, "qimBaseUrl", BASE);
        ReflectionTestUtils.setField(sut, "qimInternalApiKey", "k");
    }

    @Test
    @DisplayName("registerSubject: register-subject 에 스킴·키·해시·PII 원문(registry 가 마스킹) 을 보내고 응답을 돌려준다")
    void registerSubject_postsContract() {
        given(restTemplate.exchange(eq(BASE + "/api/v1/internal/users/register-subject"), eq(HttpMethod.POST), any(), eq(QimRegisterResponse.class)))
                .willReturn(ResponseEntity.status(HttpStatus.CREATED).body(
                        QimRegisterResponse.builder().qimUserId("u1").status("ACTIVE").isNew(true).build()));

        QimRegisterResponse res = sut.registerSubject(SubjectRegistration.builder()
                .scheme(SubjectScheme.EMAIL).subjectKey("Alice@Example.org").providerCode("MOCK")
                .authLevel(AuthResult.AuthLevel.L1).name("Alice").phone("01012345678").birthDate("19900101")
                .correlationId("c1").build());

        assertThat(res.getQimUserId()).isEqualTo("u1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        then(restTemplate).should().exchange(any(String.class), eq(HttpMethod.POST), captor.capture(), eq(QimRegisterResponse.class));
        Map<String, Object> body = captor.getValue().getBody();
        assertThat(body).containsEntry("scheme", "EMAIL").containsEntry("subjectKey", "Alice@Example.org")
                .containsEntry("identifierHash", SubjectScheme.EMAIL.identifierHash("Alice@Example.org"))
                .containsEntry("rawName", "Alice").containsEntry("rawMobile", "01012345678")
                .containsEntry("birthYear", (short) 1990).containsEntry("authLevel", "L1");
        assertThat(captor.getValue().getHeaders().getFirst("X-Internal-Api-Key")).isEqualTo("k");
    }

    @Test
    @DisplayName("registerUser(레거시 CI) 는 registry 에 없는 /register 대신 register-subject(scheme=CI) 로 위임한다")
    void registerUser_delegatesToRegisterSubject() {
        given(restTemplate.exchange(eq(BASE + "/api/v1/internal/users/register-subject"), eq(HttpMethod.POST), any(), eq(QimRegisterResponse.class)))
                .willReturn(ResponseEntity.ok(QimRegisterResponse.builder().qimUserId("u2").isNew(false).build()));

        QimRegisterResponse res = sut.registerUser(io.github.hipstermin.idem.hub.auth.dto.AuthResult.builder()
                .ci("ci-raw-value").name("홍길동").mobile("01000000000").birthday("19850505").gender("1").build(), "c2");

        assertThat(res.getQimUserId()).isEqualTo("u2");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        then(restTemplate).should().exchange(any(String.class), eq(HttpMethod.POST), captor.capture(), eq(QimRegisterResponse.class));
        assertThat(captor.getValue().getBody()).containsEntry("scheme", "CI")
                .containsEntry("identifierHash", SubjectScheme.CI.identifierHash("ci-raw-value"));
    }

    @Test
    @DisplayName("registerSubject: 5xx·네트워크 오류·qimUserId 없는 응답은 IDO_QIM_UNREACHABLE")
    void registerSubject_failures() {
        given(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(QimRegisterResponse.class)))
                .willThrow(new ResourceAccessException("down"));
        assertThatThrownBy(() -> sut.registerSubject(reg())).isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.IDO_QIM_UNREACHABLE);

        given(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(QimRegisterResponse.class)))
                .willReturn(ResponseEntity.ok(QimRegisterResponse.builder().build()));
        assertThatThrownBy(() -> sut.registerSubject(reg())).isInstanceOf(PlatformException.class);
    }

    @Test
    @DisplayName("getSubjectKey: 200 이면 키, 404 면 empty(GUEST), 그 외 오류는 IDO_QIM_UNREACHABLE")
    void getSubjectKey_semantics() {
        String url = BASE + "/api/v1/internal/users/u1/subject?scheme=EMAIL";
        given(restTemplate.exchange(eq(url), eq(HttpMethod.GET), any(), eq(Map.class)))
                .willReturn(ResponseEntity.ok(Map.of("qimUserId", "u1", "scheme", "EMAIL", "subjectKey", "a@b.c")));
        assertThat(sut.getSubjectKey("u1", SubjectScheme.EMAIL, "c")).contains("a@b.c");

        given(restTemplate.exchange(eq(url), eq(HttpMethod.GET), any(), eq(Map.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "nf", null, null, StandardCharsets.UTF_8));
        assertThat(sut.getSubjectKey("u1", SubjectScheme.EMAIL, "c")).isEmpty();

        given(restTemplate.exchange(eq(url), eq(HttpMethod.GET), any(), eq(Map.class)))
                .willThrow(new ResourceAccessException("timeout"));
        assertThatThrownBy(() -> sut.getSubjectKey("u1", SubjectScheme.EMAIL, "c"))
                .isInstanceOf(PlatformException.class)
                .extracting(e -> ((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.IDO_QIM_UNREACHABLE);
    }

    @Test
    @DisplayName("findByCi 는 registry 에 없는 find-by-ci 대신 by-hash(CI 해시) 를 조회한다; 404 → empty")
    void findByCi_usesByHash() {
        String url = BASE + "/api/v1/internal/users/by-hash?identifierHash=" + SubjectScheme.CI.identifierHash("ci-x");
        given(restTemplate.exchange(eq(url), eq(HttpMethod.GET), any(), eq(Map.class)))
                .willReturn(ResponseEntity.ok(Map.of("qimUserId", "u7", "status", "ACTIVE")));
        Optional<QimMemberInfo> found = sut.findByCi("ci-x", "A101", "c");
        assertThat(found).isPresent();
        assertThat(found.get().getQimUserId()).isEqualTo("u7");
        assertThat(found.get().getMemberType()).isEqualTo("A101");

        given(restTemplate.exchange(eq(url), eq(HttpMethod.GET), any(), eq(Map.class)))
                .willThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "nf", null, null, StandardCharsets.UTF_8));
        assertThat(sut.findByCi("ci-x", "A101", "c")).isEmpty();
    }

    private static SubjectRegistration reg() {
        return SubjectRegistration.builder().scheme(SubjectScheme.EMAIL).subjectKey("a@b.c").providerCode("MOCK").correlationId("c").build();
    }
}
