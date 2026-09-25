package io.github.hipstermin.idem.hub.admin.auth;

import io.github.hipstermin.idem.common.util.ApiKeyHashUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 관리자 비밀번호 정책 — 길이·문자 종류·사용자명 포함 금지·최근 N개 재사용 금지. 해시는 PBKDF2-SHA256 310k ({@link ApiKeyHashUtil}). */
@Component
@RequiredArgsConstructor
public class PasswordPolicy {

    private final AdminProperties props;

    /** @return 위반 목록 (비면 통과) */
    public List<String> violations(String password, String username, List<String> recentHashes) {
        List<String> v = new ArrayList<>();
        if (password == null || password.length() < props.getPassword().getMinLength()) {
            v.add("길이 " + props.getPassword().getMinLength() + "자 이상");
        }
        if (password != null) {
            int classes = 0;
            if (password.chars().anyMatch(Character::isUpperCase)) classes++;
            if (password.chars().anyMatch(Character::isLowerCase)) classes++;
            if (password.chars().anyMatch(Character::isDigit)) classes++;
            if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c))) classes++;
            if (classes < props.getPassword().getMinClasses()) {
                v.add("대문자·소문자·숫자·특수문자 중 " + props.getPassword().getMinClasses() + "종류 이상");
            }
            if (username != null && username.length() >= 3 && password.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT))) {
                v.add("사용자명을 포함할 수 없음");
            }
            if (recentHashes != null) {
                for (String h : recentHashes) {
                    if (ApiKeyHashUtil.verify(password, h)) { v.add("최근 " + props.getPassword().getHistory() + "개 비밀번호 재사용 불가"); break; }
                }
            }
        }
        return v;
    }

    public String hash(String password) { return ApiKeyHashUtil.hash(password); }

    public boolean matches(String password, String hash) { return ApiKeyHashUtil.verify(password, hash); }
}
