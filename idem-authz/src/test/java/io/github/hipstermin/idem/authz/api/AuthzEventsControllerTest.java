package io.github.hipstermin.idem.authz.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hipstermin.idem.authz.application.AuthzOutboxService;
import io.github.hipstermin.idem.authz.infrastructure.AuthzOutboxEntity;
import io.github.hipstermin.idem.authz.infrastructure.AuthzOutboxRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 1.1: 인가 이벤트 피드 — hub 폴러 계약 (payload 는 raw JSON, 키셋 파라미터, limit 상한). */
class AuthzEventsControllerTest {

    private MockMvc mockMvc;
    private AuthzOutboxRepository repo;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(AuthzOutboxRepository.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthzEventsController(repo)).build();
    }

    @Test
    @DisplayName("기본 topic·EPOCH 이후·limit 100 으로 조회하고 payload 를 JSON 그대로 내보낸다")
    void listsFeed() throws Exception {
        AuthzOutboxEntity e = AuthzOutboxEntity.builder().eventId("ev-1").eventType("AUTHZ_UNASSIGNED")
                .partitionKey("u1").aggregateId("AG1:*").payload("{\"eventId\":\"ev-1\",\"eventType\":\"AUTHZ_UNASSIGNED\"}")
                .topic(AuthzOutboxService.TOPIC).build();
        when(repo.findAfter(eq(AuthzOutboxService.TOPIC), eq(Instant.EPOCH), eq(""), any())).thenReturn(List.of(e));

        mockMvc.perform(get("/api/v1/internal/authz/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("ev-1"))
                .andExpect(jsonPath("$[0].payload.eventType").value("AUTHZ_UNASSIGNED"));
    }

    @Test
    @DisplayName("afterCreatedAt·afterEventId 키셋과 limit 상한(500)")
    void keysetAndLimit() throws Exception {
        when(repo.findAfter(any(), any(), any(), any())).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/internal/authz/events")
                        .param("afterCreatedAt", "2026-09-28T00:00:00Z").param("afterEventId", "ev-9").param("limit", "9999"))
                .andExpect(status().isOk());
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        Mockito.verify(repo).findAfter(eq(AuthzOutboxService.TOPIC), eq(Instant.parse("2026-09-28T00:00:00Z")), eq("ev-9"), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(500);
    }
}
