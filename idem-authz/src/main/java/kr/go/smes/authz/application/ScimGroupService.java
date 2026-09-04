package kr.go.smes.authz.application;

import kr.go.smes.authz.api.AuthzErrorCode;
import kr.go.smes.authz.api.AuthzException;
import kr.go.smes.authz.api.dto.GrantRoleRequest;
import kr.go.smes.authz.api.scim.ScimGroup;
import kr.go.smes.authz.api.scim.ScimGroupId;
import kr.go.smes.authz.api.scim.ScimMember;
import kr.go.smes.authz.api.scim.ScimPatchOp;
import kr.go.smes.authz.domain.AssignmentStatus;
import kr.go.smes.authz.domain.AuthzRoleEntity;
import kr.go.smes.authz.domain.AuthzRoleId;
import kr.go.smes.authz.domain.AuthzUserRoleEntity;
import kr.go.smes.authz.infrastructure.AuthzRoleRepository;
import kr.go.smes.authz.infrastructure.AuthzUserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SCIM 2.0 Group 프로비저닝 서비스 — 기관의 역할 부여 동기화(L2).
 *
 * <p>Group(=역할)의 멤버십을 표준 SCIM 연산으로 관리한다. 실제 부여/회수는
 * {@link AuthzService}에 위임(출처 SCIM)하여 감사·멱등·검증을 일관 적용한다.
 *
 * <ul>
 *   <li><b>PUT</b>(replace): 멤버 재조정 — 동기화의 핵심 의미(없는 건 grant, 빠진 건 revoke)</li>
 *   <li><b>PATCH</b>: add/remove 멤버</li>
 *   <li><b>GET</b>: 그룹/멤버 조회</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScimGroupService {

    private static final String SCIM_ACTOR = "SCIM";

    private final AuthzRoleRepository     roleRepository;
    private final AuthzUserRoleRepository userRoleRepository;
    private final AuthzService            authzService;

    // ── 조회 ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ScimGroup getGroup(String groupId) {
        ScimGroupId gid = ScimGroupId.parse(groupId);
        requireRole(gid);
        return toScimGroup(gid);
    }

    @Transactional(readOnly = true)
    public List<ScimGroup> listGroups(String agencyCode) {
        if (agencyCode == null || agencyCode.isBlank()) {
            throw new AuthzException(AuthzErrorCode.INVALID_REQUEST, "agencyCode 쿼리 파라미터가 필요합니다.");
        }
        return roleRepository.findByAgencyCodeOrderByRoleCode(agencyCode).stream()
                .map(r -> toScimGroup(new ScimGroupId(r.getAgencyCode(), r.getRoleCode())))
                .toList();
    }

    // ── 생성 ────────────────────────────────────────────────────────────────

    @Transactional
    public ScimGroup createGroup(String displayName, List<ScimMember> members, String actorIp) {
        ScimGroupId gid = ScimGroupId.parse(displayName);
        AuthzRoleId roleId = new AuthzRoleId(gid.agencyCode(), gid.roleCode());
        if (!roleRepository.existsById(roleId)) {
            authzService.createRole(
                    new kr.go.smes.authz.api.dto.CreateRoleRequest(
                            gid.agencyCode(), gid.roleCode(), gid.roleCode(), "SCIM provisioned"),
                    SCIM_ACTOR, actorIp);
        }
        if (members != null) {
            members.forEach(m -> grant(gid, m.value(), actorIp));
        }
        return toScimGroup(gid);
    }

    // ── PATCH (add/remove 멤버) ──────────────────────────────────────────────

    @Transactional
    public ScimGroup patch(String groupId, ScimPatchOp patchOp, String actorIp) {
        ScimGroupId gid = ScimGroupId.parse(groupId);
        requireRole(gid);
        if (patchOp == null || patchOp.operations() == null || patchOp.operations().isEmpty()) {
            throw new AuthzException(AuthzErrorCode.INVALID_REQUEST, "Operations가 비어 있습니다.");
        }
        for (ScimPatchOp.Operation op : patchOp.operations()) {
            String verb = op.op() == null ? "" : op.op().toLowerCase();
            switch (verb) {
                case "add"     -> membersOf(op).forEach(uid -> grant(gid, uid, actorIp));
                case "remove"  -> removeTargets(op).forEach(uid -> revoke(gid, uid, actorIp));
                case "replace" -> reconcile(gid, membersOf(op), actorIp);
                default -> throw new AuthzException(AuthzErrorCode.INVALID_REQUEST,
                        "지원하지 않는 PATCH op: " + op.op());
            }
        }
        return toScimGroup(gid);
    }

    // ── PUT (replace — 멤버 재조정) ──────────────────────────────────────────

    @Transactional
    public ScimGroup replaceMembers(String groupId, List<ScimMember> desiredMembers, String actorIp) {
        ScimGroupId gid = ScimGroupId.parse(groupId);
        requireRole(gid);
        Set<String> desired = new LinkedHashSet<>();
        if (desiredMembers != null) {
            desiredMembers.stream().map(ScimMember::value)
                    .filter(v -> v != null && !v.isBlank()).forEach(desired::add);
        }
        reconcile(gid, desired, actorIp);
        return toScimGroup(gid);
    }

    // ── private ───────────────────────────────────────────────────────────────

    /** 현재 ACTIVE 멤버를 desired 집합으로 재조정: 없는 건 grant, 초과분은 revoke. */
    private void reconcile(ScimGroupId gid, Set<String> desired, String actorIp) {
        Set<String> current = new LinkedHashSet<>(currentMemberIds(gid));
        desired.stream().filter(uid -> !current.contains(uid)).forEach(uid -> grant(gid, uid, actorIp));
        current.stream().filter(uid -> !desired.contains(uid)).forEach(uid -> revoke(gid, uid, actorIp));
        log.info("[SCIM] reconcile group={} desired={} current={}",
                gid.value(), desired.size(), current.size());
    }

    private Set<String> membersOf(ScimPatchOp.Operation op) {
        Set<String> ids = new LinkedHashSet<>();
        if (op.value() != null) {
            op.value().stream().map(ScimMember::value)
                    .filter(v -> v != null && !v.isBlank()).forEach(ids::add);
        }
        return ids;
    }

    /** remove 대상 — value 배열 또는 path 필터 {@code members[value eq "x"]} 모두 지원. */
    private Set<String> removeTargets(ScimPatchOp.Operation op) {
        Set<String> ids = membersOf(op);
        if (ids.isEmpty() && op.path() != null) {
            String fromPath = parseValueEqFilter(op.path());
            if (fromPath != null) ids.add(fromPath);
        }
        if (ids.isEmpty()) {
            throw new AuthzException(AuthzErrorCode.INVALID_REQUEST, "remove 대상 멤버가 지정되지 않았습니다.");
        }
        return ids;
    }

    /** {@code members[value eq "user-1"]} → "user-1" (간이 파서). */
    private String parseValueEqFilter(String path) {
        int eq = path.indexOf("eq");
        int q1 = path.indexOf('"', eq);
        int q2 = q1 >= 0 ? path.indexOf('"', q1 + 1) : -1;
        if (eq > 0 && q1 > 0 && q2 > q1) {
            return path.substring(q1 + 1, q2);
        }
        return null;
    }

    private void grant(ScimGroupId gid, String qimUserId, String actorIp) {
        if (qimUserId == null || qimUserId.isBlank()) return;
        authzService.grantRole(new GrantRoleRequest(
                qimUserId, gid.agencyCode(), gid.roleCode(), SCIM_ACTOR, null, "SCIM", "SCIM provisioning"),
                actorIp, null);
    }

    private void revoke(ScimGroupId gid, String qimUserId, String actorIp) {
        if (qimUserId == null || qimUserId.isBlank()) return;
        try {
            authzService.revokeRole(qimUserId, gid.agencyCode(), gid.roleCode(),
                    SCIM_ACTOR, actorIp, "SCIM provisioning", null);
        } catch (AuthzException e) {
            // 이미 부여 내역이 없으면(ASSIGNMENT_NOT_FOUND) 멱등 처리 — 동기화 목표 상태 달성
            if (e.getErrorCode() != AuthzErrorCode.ASSIGNMENT_NOT_FOUND) throw e;
        }
    }

    private List<String> currentMemberIds(ScimGroupId gid) {
        return userRoleRepository
                .findByAgencyCodeAndRoleCodeAndStatus(gid.agencyCode(), gid.roleCode(), AssignmentStatus.ACTIVE)
                .stream().map(AuthzUserRoleEntity::getQimUserId).toList();
    }

    private ScimGroup toScimGroup(ScimGroupId gid) {
        List<ScimMember> members = currentMemberIds(gid).stream().map(ScimMember::of).toList();
        return ScimGroup.of(gid.value(), gid.value(), members);
    }

    private void requireRole(ScimGroupId gid) {
        if (!roleRepository.existsById(new AuthzRoleId(gid.agencyCode(), gid.roleCode()))) {
            throw new AuthzException(AuthzErrorCode.ROLE_NOT_FOUND,
                    "Group(역할) 없음: " + gid.value());
        }
    }
}
