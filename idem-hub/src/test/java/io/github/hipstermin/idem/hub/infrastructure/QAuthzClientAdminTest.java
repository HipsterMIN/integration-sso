package io.github.hipstermin.idem.hub.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

@DisplayName("QAuthzClient — 1.1.1 G1-3 관리 콘솔 할당 관리 호출 규약·오류 매핑")
class QAuthzClientAdminTest {

    private final RestTemplate rest = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
    private final QAuthzClient sut = new QAuthzClient(rest);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(sut, "qAuthzBaseUrl", "http://authz.test");
        ReflectionTestUtils.setField(sut, "qAuthzInternalApiKey", "k-authz");
        ReflectionTestUtils.setField(sut, "enabled", true);
    }

    @Test
    @DisplayName("할당 목록: 내부 API 키·상관관계 ID, X-Total-Count·X-Has-Next 헤더를 페이지로")
    void listAssignments() {
        HttpHeaders h = new HttpHeaders(); h.set("X-Total-Count", "7"); h.set("X-Has-Next", "true");
        server.expect(requestTo("http://authz.test/api/v1/internal/authz/agencies/AG1/assignments?page=0&size=50"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("X-Internal-Api-Key", "k-authz")).andExpect(header("X-Correlation-Id", "cid-1"))
                .andRespond(withSuccess("[{\"qimUserId\":\"u1\",\"agencyCode\":\"AG1\",\"status\":\"ACTIVE\",\"source\":\"CONSOLE\",\"grantedAt\":\"2026-10-09T00:00:00Z\",\"grantedBy\":\"sys\"}]", MediaType.APPLICATION_JSON).headers(h));

        QAuthzClient.AssignmentListPage page = sut.listAgencyAssignmentRecords("AG1", 0, 50, "cid-1");

        assertThat(page.total()).isEqualTo(7);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).qimUserId()).isEqualTo("u1");
        assertThat(page.items().get(0).grantedAt()).isEqualTo("2026-10-09T00:00:00Z");
        server.verify();
    }

    @Test
    @DisplayName("assign·unassign·roles·createRole·listUserRoles·grantRole·revokeRole 의 경로·본문(source=CONSOLE, X-Actor)")
    void writeCalls() {
        server.expect(requestTo("http://authz.test/api/v1/internal/authz/assignments")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.qimUserId").value("u1")).andExpect(jsonPath("$.agencyCode").value("AG1"))
                .andExpect(jsonPath("$.grantedBy").value("admin")).andExpect(jsonPath("$.source").value("CONSOLE"))
                .andExpect(jsonPath("$.expiresAt").value("2026-12-31T00:00:00Z")).andExpect(jsonPath("$.reason").value("onboard"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"qimUserId\":\"u1\",\"agencyCode\":\"AG1\",\"status\":\"ACTIVE\",\"source\":\"CONSOLE\"}"));
        server.expect(requestTo("http://authz.test/api/v1/internal/authz/assignments?qimUserId=u1&agencyCode=AG1&revokedBy=admin&reason=bye"))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withNoContent());
        server.expect(requestTo("http://authz.test/api/v1/internal/authz/roles?agencyCode=AG1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"agencyCode\":\"AG1\",\"roleCode\":\"MANAGER\",\"name\":\"관리자\",\"assignable\":true}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://authz.test/api/v1/internal/authz/roles")).andExpect(method(HttpMethod.POST)).andExpect(header("X-Actor", "admin"))
                .andExpect(jsonPath("$.roleCode").value("VIEWER")).andExpect(jsonPath("$.name").value("열람"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"agencyCode\":\"AG1\",\"roleCode\":\"VIEWER\",\"name\":\"열람\",\"assignable\":true}"));
        server.expect(requestTo("http://authz.test/api/v1/internal/authz/users/u1/roles?agencyCode=AG1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"r1\",\"qimUserId\":\"u1\",\"agencyCode\":\"AG1\",\"roleCode\":\"MANAGER\",\"status\":\"ACTIVE\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://authz.test/api/v1/internal/authz/grants")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.roleCode").value("MANAGER")).andExpect(jsonPath("$.source").value("CONSOLE"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"r2\",\"qimUserId\":\"u1\",\"agencyCode\":\"AG1\",\"roleCode\":\"MANAGER\",\"status\":\"ACTIVE\",\"source\":\"CONSOLE\"}"));
        server.expect(requestTo("http://authz.test/api/v1/internal/authz/grants?qimUserId=u1&agencyCode=AG1&roleCode=MANAGER&revokedBy=admin"))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withNoContent());

        assertThat(sut.assign("AG1", "u1", "admin", Instant.parse("2026-12-31T00:00:00Z"), "onboard", "c").status()).isEqualTo("ACTIVE");
        sut.unassign("AG1", "u1", "admin", "bye", "c");
        assertThat(sut.listRoles("AG1", "c")).singleElement().satisfies(r -> { assertThat(r.roleCode()).isEqualTo("MANAGER"); assertThat(r.assignable()).isTrue(); });
        assertThat(sut.createRole("AG1", "VIEWER", "열람", null, "admin", "c").roleCode()).isEqualTo("VIEWER");
        assertThat(sut.listUserRoles("u1", "AG1", "c")).singleElement().satisfies(r -> assertThat(r.roleCode()).isEqualTo("MANAGER"));
        assertThat(sut.grantRole("AG1", "u1", "MANAGER", "admin", null, null, "c").id()).isEqualTo("r2");
        sut.revokeRole("AG1", "u1", "MANAGER", "admin", null, "c");
        server.verify();
    }

    @Test
    @DisplayName("authz 404 → E-IDO-127, 409 → E-IDO-128, 400 → E-IDO-129(본문 포함), 5xx → E-IDO-117, 비활성 → E-IDO-117")
    void errorMapping() {
        server.expect(requestTo("http://authz.test/api/v1/internal/authz/roles?agencyCode=AG1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"E-AUTHZ-404-ROLE\",\"message\":\"없음\"}"));
        server.expect(requestTo("http://authz.test/api/v1/internal/authz/assignments"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"E-AUTHZ-409-DUP\",\"message\":\"이미\"}"));
        server.expect(requestTo("http://authz.test/api/v1/internal/authz/grants"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"E-AUTHZ-400\",\"message\":\"잘못된 요청\"}"));
        server.expect(requestTo("http://authz.test/api/v1/internal/authz/roles?agencyCode=AG1"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> sut.listRoles("AG1", "c")).satisfies(e -> assertThat(((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.IDO_AUTHZ_NOT_FOUND));
        assertThatThrownBy(() -> sut.assign("AG1", "u1", "admin", null, null, "c")).satisfies(e -> assertThat(((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.IDO_AUTHZ_CONFLICT));
        assertThatThrownBy(() -> sut.grantRole("AG1", "u1", "X", "admin", null, null, "c"))
                .satisfies(e -> assertThat(((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.IDO_AUTHZ_REJECTED)).hasMessageContaining("E-AUTHZ-400");
        assertThatThrownBy(() -> sut.listRoles("AG1", "c")).satisfies(e -> assertThat(((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE));
        server.verify();

        ReflectionTestUtils.setField(sut, "enabled", false);
        assertThatThrownBy(() -> sut.listRoles("AG1", "c")).satisfies(e -> assertThat(((PlatformException) e).getErrorCode()).isEqualTo(PlatformErrorCode.IDO_AUTHZ_UNAVAILABLE));
    }
}
