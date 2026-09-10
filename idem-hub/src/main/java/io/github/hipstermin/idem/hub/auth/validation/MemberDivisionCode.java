package io.github.hipstermin.idem.hub.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 회원 구분 코드(mbrDvsnCd) 가 {@code ido.qim.member-division-codes} 허용 목록에 있는지 검증한다.
 *
 * <p>null 은 통과시킨다 — 필수 여부는 {@code @NotBlank} 가 담당한다.
 * 허용 목록은 {@link io.github.hipstermin.idem.hub.qim.MemberDivisionPolicy} 에서 읽는다.
 */
@Documented
@Constraint(validatedBy = MemberDivisionCodeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface MemberDivisionCode {
    String message() default "mbrDvsnCd 값이 허용 목록에 없습니다";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
