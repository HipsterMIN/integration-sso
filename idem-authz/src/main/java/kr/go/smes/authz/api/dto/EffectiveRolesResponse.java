package kr.go.smes.authz.api.dto;

import java.util.List;

/**
 * 사용자×기관 유효 역할 목록 — 토큰 {@code roles[]} 클레임의 원천.
 *
 * <p>ido(Claim Issuer)가 Handoff/CAST 발급 시 본 응답을 호출해
 * 토큰에 임베드한다. {@code v}는 권한 버전(신선도 정합용, 추후 증분).
 */
public record EffectiveRolesResponse(
        String qimUserId,
        String agencyCode,
        List<String> roles
) {}
