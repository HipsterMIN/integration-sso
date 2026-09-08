package io.github.hipstermin.idem.registry.crypto;

import org.springframework.stereotype.Component;

/**
 * PII(개인식별정보) 마스킹 서비스
 *
 * <p>마스킹 규칙 (설계서 §10.4):
 * <ul>
 *   <li>이름: 앞 1자 + 뒤 1자 보존, 중간 * 처리 (2자 이름: 뒤 1자만 보존)</li>
 *   <li>전화번호: 010-****-5678 형태</li>
 *   <li>이메일: us**@example.com 형태</li>
 * </ul>
 */
@Component
public class PiiMaskingService {

    /**
     * 이름 마스킹
     * <pre>
     *   "홍길동" → "홍*동"
     *   "홍길"   → "홍*"
     *   "홍"     → "*"
     *   "John Doe" → "J***D**"
     *   null/blank → null
     * </pre>
     */
    public String maskName(String name) {
        if (name == null || name.isBlank()) return null;
        String trimmed = name.trim();
        int len = trimmed.length();
        if (len == 1) return "*";
        if (len == 2) return trimmed.charAt(0) + "*";
        // 3자 이상: 첫 자 + 중간 * + 마지막 자
        int middleLen = len - 2;
        return trimmed.charAt(0)
                + "*".repeat(middleLen)
                + trimmed.charAt(len - 1);
    }

    /**
     * 전화번호 마스킹
     * <pre>
     *   "01012345678"   → "010-****-5678"
     *   "010-1234-5678" → "010-****-5678"
     *   기타 형식        → 중간 부분 **** 처리
     * </pre>
     */
    public String maskMobile(String mobile) {
        if (mobile == null || mobile.isBlank()) return null;
        // 숫자만 추출
        String digits = mobile.replaceAll("[^0-9]", "");
        if (digits.length() == 11) {
            // 010-XXXX-XXXX 형식
            return digits.substring(0, 3) + "-****-" + digits.substring(7);
        }
        if (digits.length() == 10) {
            // 02-XXXX-XXXX 또는 0XX-XXX-XXXX
            return digits.substring(0, 3) + "-****-" + digits.substring(6);
        }
        // 알 수 없는 형식 — 중간 **** 처리
        int len = digits.length();
        if (len <= 4) return "****";
        return digits.substring(0, 2) + "****" + digits.substring(len - 2);
    }

    /**
     * 이메일 마스킹
     * <pre>
     *   "user@example.com"   → "us**@example.com"
     *   "ab@example.com"     → "a*@example.com"
     *   "a@example.com"      → "*@example.com"
     * </pre>
     */
    public String maskEmail(String email) {
        if (email == null || email.isBlank()) return null;
        int atIdx = email.indexOf('@');
        if (atIdx < 0) return "****";
        String local  = email.substring(0, atIdx);
        String domain = email.substring(atIdx); // @domain.com 포함
        if (local.length() <= 1) return "*" + domain;
        if (local.length() == 2) return local.charAt(0) + "*" + domain;
        // 앞 2자 보존 + 나머지 *
        return local.substring(0, 2) + "*".repeat(local.length() - 2) + domain;
    }
}
