package io.github.hipstermin.idem.hub.admin.auth;

import io.github.hipstermin.idem.common.error.ErrorResponse;
import io.github.hipstermin.idem.common.error.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 관리자 인증·관리자 관리·감사 조회 API 의 오류 응답 (S7 PR-2).
 *
 * <p>전역 핸들러는 코드의 기본 메시지만 내보내 비밀번호 정책 위반 사유(E-IDO-135)·범위 밖 기관 같은 상세가 콘솔에 닿지 않았다.
 * 관리 API 는 인증된 관리자만 부르므로 {@link PlatformException#getMessage()} (상세) 를 그대로 낸다 — {@code ServiceProfileAdminController} 와 같은 원칙.
 */
@Slf4j
@RestControllerAdvice(basePackages = {"io.github.hipstermin.idem.hub.admin.auth", "io.github.hipstermin.idem.hub.admin.audit"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminApiExceptionHandler {

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ErrorResponse> handle(PlatformException ex) {
        log.warn("[AdminApi] {} cid={} : {}", ex.getErrorCode().getCode(), ex.getCorrelationId(), ex.getMessage());
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(ErrorResponse.of(ex));
    }
}
