package io.github.hipstermin.idem.hub.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

/** 1.1 SCIM 아웃박스 — change → op 매핑, opt-in, 되돌이 방지, GUEST 생략. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScimOutboxServiceTest {

    @Mock JdbcTemplate jdbc;
    ScimOutboxService sut;

    @BeforeEach
    void setUp() {
        sut = new ScimOutboxService(jdbc, new ObjectMapper());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    static ServiceProfile profile(ServiceProfile.Scim scim) {
        return ServiceProfile.builder()
                .service(ServiceProfile.Service.builder().code("AG1").name("기관").build())
                .protocol(ServiceProfile.Protocol.builder().scim(scim).build())
                .build();
    }
    static ServiceProfile.Scim on(String onUnassign, Boolean groups) {
        return ServiceProfile.Scim.builder().enabled(true).baseUrl("https://a.example/scim/v2")
                .credentialRef("secrets/agency/AG1/scim-token").onUnassign(onUnassign).groups(groups).build();
    }

    private List<String> ops() {
        ArgumentCaptor<Object[]> c = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(anyString(), c.capture());
        return c.getAllValues().stream().map(a -> (String) a[2]).toList();
    }

    @Test
    @DisplayName("SCIM 꺼진 프로파일·프로파일 없음 → 0, DB 접근 없음")
    void disabled() {
        assertThat(sut.onAssignmentChanged(profile(null), "s1", "ASSIGNED", null, "ops", "e1", "AUTHZ_ASSIGNED", "c")).isZero();
        assertThat(sut.onAssignmentChanged(profile(ServiceProfile.Scim.builder().enabled(false).build()), "s1", "ASSIGNED", null, "ops", "e1", "t", "c")).isZero();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("ASSIGNED → ENSURE_USER, UNASSIGNED → onUnassign 정책(DEACTIVATE 기본 / DELETE / NONE)")
    void assignmentOps() {
        assertThat(sut.onAssignmentChanged(profile(on(null, null)), "s1", "ASSIGNED", null, "ops", "e1", "t", "c")).isEqualTo(1);
        assertThat(sut.onAssignmentChanged(profile(on(null, null)), "s1", "UNASSIGNED", null, "ops", "e2", "t", "c")).isEqualTo(1);
        assertThat(sut.onAssignmentChanged(profile(on("DELETE", null)), "s1", "ASSIGNMENT_EXPIRED", null, "ops", "e3", "t", "c")).isEqualTo(1);
        assertThat(sut.onAssignmentChanged(profile(on("NONE", null)), "s1", "UNASSIGNED", null, "ops", "e4", "t", "c")).isZero();
        assertThat(ops()).containsExactly("ENSURE_USER", "DEACTIVATE_USER", "DELETE_USER");
    }

    @Test
    @DisplayName("ROLE_GRANTED → ENSURE_USER + ADD_GROUP_MEMBER(groups 켜짐), ROLE_REVOKED → REMOVE; groups=false 면 사용자만")
    void roleOps() {
        assertThat(sut.onAssignmentChanged(profile(on(null, null)), "s1", "ROLE_GRANTED", "MANAGER", "ops", "e1", "t", "c")).isEqualTo(2);
        assertThat(sut.onAssignmentChanged(profile(on(null, null)), "s1", "ROLE_REVOKED", "MANAGER", "ops", "e2", "t", "c")).isEqualTo(1);
        assertThat(sut.onAssignmentChanged(profile(on(null, false)), "s1", "ROLE_GRANTED", "MANAGER", "ops", "e3", "t", "c")).isEqualTo(1);
        assertThat(ops()).containsExactly("ENSURE_USER", "ADD_GROUP_MEMBER", "REMOVE_GROUP_MEMBER", "ENSURE_USER");
    }

    @Test
    @DisplayName("기관 인바운드 SCIM(actor=SCIM)이 만든 변경은 되돌이 방지 — 적재 없음; GUEST(식별자 없음)도 없음")
    void loopAndGuest() {
        assertThat(sut.onAssignmentChanged(profile(on(null, null)), "s1", "ROLE_GRANTED", "R", "SCIM", "e1", "t", "c")).isZero();
        assertThat(sut.onAssignmentChanged(profile(on(null, null)), null, "ASSIGNED", null, "ops", "e2", "t", "c")).isZero();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("사용자 정지 → DEACTIVATE, 탈퇴 → onWithdraw(기본 DELETE); 전체 동기화는 ENSURE + 역할 그룹")
    void terminalAndFullSync() {
        assertThat(sut.onUserTerminal(profile(on(null, null)), "s1", false, "e1", "USER_SUSPENDED", "c")).isEqualTo(1);
        assertThat(sut.onUserTerminal(profile(on(null, null)), "s1", true, "e2", "USER_WITHDRAWN", "c")).isEqualTo(1);
        assertThat(sut.enqueueFullSyncUser(profile(on(null, null)), "s1", List.of("A", "B"), "sync-1", "c")).isEqualTo(3);
        assertThat(ops()).containsExactly("DEACTIVATE_USER", "DELETE_USER", "ENSURE_USER", "ADD_GROUP_MEMBER", "ADD_GROUP_MEMBER");
        verify(jdbc, times(5)).update(anyString(), any(Object[].class));
    }
}
