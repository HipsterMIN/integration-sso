package io.github.hipstermin.idem.relay.job.authz;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.ResultSet;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AuthzKafkaRelayJob 단위 테스트 — authz.authz_outbox → authz.assignment.events.
 */
@DisplayName("AuthzKafkaRelayJob — 단위 테스트")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthzKafkaRelayJobTest {

    private static final String TOPIC    = "authz.assignment.events";
    private static final String EVENT_ID = "01999999-aaaa-7000-8000-000000000001";
    private static final String USER_KEY = "user-1";
    private static final String PAYLOAD  =
            "{\"eventType\":\"AUTHZ_REVOKED\",\"qimUserId\":\"user-1\",\"roleCode\":\"MANAGER\"}";

    @Mock private JdbcTemplate                  authzJdbcTemplate;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private ResultSet                     mockResultSet;

    private AuthzKafkaRelayJob sut;

    @BeforeEach
    void setUp() throws Exception {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        sut = new AuthzKafkaRelayJob(authzJdbcTemplate, kafkaTemplate, new ObjectMapper(), meterRegistry);
        ReflectionTestUtils.setField(sut, "batchSize", 100);
        ReflectionTestUtils.setField(sut, "maxRetry", 5);
        ReflectionTestUtils.setField(sut, "enabled", true);
        ReflectionTestUtils.setField(sut, "assignmentEventsTopic", TOPIC);

        given(mockResultSet.getString("event_id")).willReturn(EVENT_ID);
        given(mockResultSet.getString("partition_key")).willReturn(USER_KEY);
        given(mockResultSet.getString("payload")).willReturn(PAYLOAD);
        given(mockResultSet.getInt("retry_count")).willReturn(0);
    }

    private void stubOnePending() {
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            handler.processRow(mockResultSet);
            return null;
        }).when(authzJdbcTemplate).query(contains("authz_outbox"), any(RowCallbackHandler.class), eq(100));
    }

    @Test
    @DisplayName("enabled=false → 즉시 반환, DB/Kafka 미접근")
    void disabled_noInteractions() {
        ReflectionTestUtils.setField(sut, "enabled", false);

        sut.relay();

        verifyNoInteractions(authzJdbcTemplate, kafkaTemplate);
    }

    @Test
    @DisplayName("PENDING 발행 성공 → 토픽/파티션키로 전송 + PUBLISHED 갱신")
    void publishesPending_marksPublished() {
        stubOnePending();
        given(kafkaTemplate.send(eq(TOPIC), eq(USER_KEY), any()))
                .willReturn(CompletableFuture.completedFuture((SendResult<String, Object>) null));

        sut.relay();

        verify(kafkaTemplate).send(eq(TOPIC), eq(USER_KEY), any());
        verify(authzJdbcTemplate).update(contains("PUBLISHED"), eq(EVENT_ID));
    }

    @Test
    @DisplayName("Kafka 발행 실패 → retry_count 증가(영구 FAILED 아님)")
    void kafkaFailure_incrementsRetry() {
        stubOnePending();
        given(kafkaTemplate.send(eq(TOPIC), eq(USER_KEY), any()))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        sut.relay();

        verify(authzJdbcTemplate).update(contains("retry_count = retry_count + 1"), anyString(), eq(EVENT_ID));
    }
}
