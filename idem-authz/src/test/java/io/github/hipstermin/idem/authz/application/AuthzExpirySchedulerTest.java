package io.github.hipstermin.idem.authz.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthzExpirySchedulerTest {

    @Mock AuthzService authzService;

    @InjectMocks AuthzExpiryScheduler scheduler;

    @Test
    void scanAndExpire_delegatesWithConfiguredBatchSize() {
        ReflectionTestUtils.setField(scheduler, "batchSize", 250);
        when(authzService.expireOverdue(any(Instant.class), eq(250))).thenReturn(3);

        scheduler.scanAndExpire();

        verify(authzService).expireOverdue(any(Instant.class), eq(250));
    }

    @Test
    void scanAndExpire_swallowsExceptionToProtectNextRun() {
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);
        doThrow(new RuntimeException("db down"))
                .when(authzService).expireOverdue(any(Instant.class), eq(100));

        // 예외가 전파되면 스케줄러가 죽음 → 흡수되어야 함
        scheduler.scanAndExpire();

        verify(authzService).expireOverdue(any(Instant.class), eq(100));
    }
}
