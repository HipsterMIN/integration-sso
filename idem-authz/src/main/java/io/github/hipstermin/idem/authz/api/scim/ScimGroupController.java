package io.github.hipstermin.idem.authz.api.scim;

import io.github.hipstermin.idem.authz.application.ScimGroupService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCIM 2.0 Group 프로비저닝 엔드포인트 ({@code /scim/v2/Groups}).
 *
 * <p>기관 프로비저닝 클라이언트가 역할 부여를 표준 SCIM으로 동기화한다.
 * 내부 API 키 인터셉터({@code /scim/v2/**})로 보호된다(MVP — 추후 Bearer 토큰).
 *
 * <p>Group ↔ 역할 매핑: {@code id = "{agencyCode}:{roleCode}"}, member.value = qimUserId.
 */
@Slf4j
@RestController
@RequestMapping("/scim/v2/Groups")
@RequiredArgsConstructor
public class ScimGroupController {

    private final ScimGroupService scimGroupService;

    /** 그룹(역할) 단건 조회 + 멤버. */
    @GetMapping("/{id}")
    public ResponseEntity<ScimGroup> get(@PathVariable String id) {
        return ResponseEntity.ok(scimGroupService.getGroup(id));
    }

    /** 기관 스코프 그룹 목록(ListResponse). */
    @GetMapping
    public ResponseEntity<ScimListResponse<ScimGroup>> list(@RequestParam("agencyCode") String agencyCode) {
        return ResponseEntity.ok(ScimListResponse.of(scimGroupService.listGroups(agencyCode)));
    }

    /** 그룹(역할) 생성 + 초기 멤버. displayName = "{agencyCode}:{roleCode}". */
    @PostMapping
    public ResponseEntity<ScimGroup> create(@RequestBody ScimGroup group, HttpServletRequest request) {
        ScimGroup created = scimGroupService.createGroup(group.displayName(), group.members(), clientIp(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 멤버 전체 교체(재조정) — 동기화 핵심. */
    @PutMapping("/{id}")
    public ResponseEntity<ScimGroup> replace(@PathVariable String id,
                                             @RequestBody ScimGroup group,
                                             HttpServletRequest request) {
        return ResponseEntity.ok(scimGroupService.replaceMembers(id, group.members(), clientIp(request)));
    }

    /** 멤버 add/remove. */
    @PatchMapping("/{id}")
    public ResponseEntity<ScimGroup> patch(@PathVariable String id,
                                           @RequestBody ScimPatchOp patchOp,
                                           HttpServletRequest request) {
        return ResponseEntity.ok(scimGroupService.patch(id, patchOp, clientIp(request)));
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
