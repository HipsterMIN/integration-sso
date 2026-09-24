package io.github.hipstermin.idem.hub.kr.auth.validation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 회원 구분 코드 정책 — Q-IM 이 쓰는 회원 구분 값(예: 개인/기업)을 설정으로 외부화한다.
 *
 * <p>S1(범용화, {@code docs/generalization-plan.md})에서 코어 코드에 박혀 있던 {@code A101/A102} 비교를 걷어냈다.
 * 값의 의미(개인·기업 등)는 운영기관의 회원 체계에 따라 다르므로 코어는 "허용 목록" 과
 * "사업자등록번호가 필수인 목록" 만 안다. 회원 모델 자체의 일반화는 S8 에서 다룬다.
 *
 * <pre>
 * ido.qim.member-division-codes:    A101,A102   # 허용 목록
 * ido.qim.corporate-division-codes: A102        # bizno(사업자등록번호) 필수 목록 (허용 목록의 부분집합)
 * </pre>
 */
@Component
public class MemberDivisionPolicy {

    public static final List<String> DEFAULT_ALLOWED   = List.of("A101", "A102");
    public static final List<String> DEFAULT_CORPORATE = List.of("A102");

    private final Set<String> allowed;
    private final Set<String> corporate;

    public MemberDivisionPolicy(
            @Value("${ido.qim.member-division-codes:A101,A102}") List<String> allowed,
            @Value("${ido.qim.corporate-division-codes:A102}") List<String> corporate) {
        this.allowed   = normalize(allowed);
        this.corporate = normalize(corporate);
        if (this.allowed.isEmpty()) {
            throw new IllegalStateException("ido.qim.member-division-codes 가 비어 있습니다. 허용 회원 구분 코드를 1개 이상 지정하세요.");
        }
        if (!this.allowed.containsAll(this.corporate)) {
            throw new IllegalStateException("ido.qim.corporate-division-codes " + this.corporate
                    + " 는 member-division-codes " + this.allowed + " 의 부분집합이어야 합니다.");
        }
    }

    /** 설정이 없는 환경(표준 단위 테스트, 독립 Validator)용 기본 정책. */
    public static MemberDivisionPolicy defaults() {
        return new MemberDivisionPolicy(DEFAULT_ALLOWED, DEFAULT_CORPORATE);
    }

    public boolean isAllowed(String code) {
        return code != null && allowed.contains(code.trim().toUpperCase(Locale.ROOT));
    }

    /** 이 구분 코드는 사업자등록번호(bizno)가 함께 와야 하는가. */
    public boolean requiresBusinessNumber(String code) {
        return code != null && corporate.contains(code.trim().toUpperCase(Locale.ROOT));
    }

    public String describeAllowed() {
        return String.join(", ", allowed);
    }

    private static Set<String> normalize(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) return out;
        for (String v : raw) {
            if (v != null && !v.isBlank()) out.add(v.trim().toUpperCase(Locale.ROOT));
        }
        return out;
    }
}
