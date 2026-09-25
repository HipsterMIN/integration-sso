package io.github.hipstermin.idem.registry.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.OutboxJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.OutboxJpaRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** D3: hub 폴링용 읽기 전용 이벤트 피드 — 아웃박스 상태를 바꾸지 않고 (created_at, event_id) 키셋으로 넘긴다. */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxEventsController — GET /api/v1/internal/events")
class OutboxEventsControllerTest {

    @Mock OutboxJpaRepository repo;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OutboxEventsController(repo)).build();
    }

    private static OutboxJpaEntity row(String id, Instant at) {
        return OutboxJpaEntity.builder().eventId(id).eventType("USER_SUSPENDED").partitionKey("u1").aggregateId("u1")
                .eventVersion(3L).payload("{\"eventId\":\"" + id + "\",\"userStatus\":\"SUSPENDED\"}")
                .topic("qim.user.events").status("PENDING").createdAt(at).build();
    }

    @Test
    @DisplayName("커서 뒤의 이벤트를 payload 원문 JSON 과 함께 돌려준다")
    void feed() throws Exception {
        Instant since = Instant.parse("2026-09-25T00:00:00Z");
        given(repo.findAfter(eq("qim.user.events"), eq(since), eq("e0"), any(Pageable.class)))
                .willReturn(List.of(row("e1", since.plusSeconds(1))));

        mvc.perform(get("/api/v1/internal/events").param("afterCreatedAt", since.toString()).param("afterEventId", "e0").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("e1"))
                .andExpect(jsonPath("$[0].eventType").value("USER_SUSPENDED"))
                .andExpect(jsonPath("$[0].eventVersion").value(3))
                .andExpect(jsonPath("$[0].payload.userStatus").value("SUSPENDED"));
    }

    @Test
    @DisplayName("파라미터가 없으면 EPOCH·빈 id 부터, limit 은 500 으로 클램프")
    void defaultsAndClamp() throws Exception {
        given(repo.findAfter(anyString(), any(), anyString(), any(Pageable.class))).willReturn(List.of());
        mvc.perform(get("/api/v1/internal/events").param("limit", "99999")).andExpect(status().isOk());
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(repo).findAfter(eq("qim.user.events"), eq(Instant.EPOCH), eq(""), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(OutboxEventsController.MAX_LIMIT);
    }
}
