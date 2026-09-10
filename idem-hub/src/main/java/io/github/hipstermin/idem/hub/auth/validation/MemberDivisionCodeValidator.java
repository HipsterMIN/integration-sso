package io.github.hipstermin.idem.hub.auth.validation;

import io.github.hipstermin.idem.hub.qim.MemberDivisionPolicy;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link MemberDivisionCode} 검증기.
 *
 * <p>Spring 컨텍스트 안에서는 {@code SpringConstraintValidatorFactory} 가 {@link MemberDivisionPolicy} 를 주입한다.
 * 독립 Validator(표준 단위 테스트, MockMvc standalone)에서는 주입이 없으므로 기본 정책으로 동작한다.
 */
public class MemberDivisionCodeValidator implements ConstraintValidator<MemberDivisionCode, String> {

    @Autowired(required = false)
    private MemberDivisionPolicy policy;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) return true; // 필수 여부는 @NotBlank 가 담당
        MemberDivisionPolicy effective = policy != null ? policy : MemberDivisionPolicy.defaults();
        if (effective.isAllowed(value)) return true;

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                        "mbrDvsnCd 값이 허용 목록에 없습니다. 허용값: " + effective.describeAllowed())
                .addConstraintViolation();
        return false;
    }
}
