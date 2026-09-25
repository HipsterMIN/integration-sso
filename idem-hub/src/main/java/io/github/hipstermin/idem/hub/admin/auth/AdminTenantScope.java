package io.github.hipstermin.idem.hub.admin.auth;

import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 테넌트 범위 (S7 최소 구현) — 관리자가 Tenant 에 속하면 그 Tenant 의 Service 만 보고 고친다. 글로벌(tenant null) 관리자는 전부.
 */
@Component
@RequiredArgsConstructor
public class AdminTenantScope {

    private final ServiceProfileService serviceProfileService;

    /** 기존 Service 접근 — 다른 Tenant 면 403. 없는 Service 는 통과(호출자가 404 를 낸다) */
    public void checkService(AdminPrincipal p, String serviceCode) {
        if (p.isGlobal()) return;
        Optional<ServiceProfile> profile = serviceProfileService.find(serviceCode);
        if (profile.isPresent() && !p.tenantCode().equals(profile.get().service().tenantOrDefault())) {
            throw new PlatformException(PlatformErrorCode.ADMIN_FORBIDDEN, null, "다른 Tenant 의 Service: " + serviceCode);
        }
    }

    /** 새로 만들거나 고치는 프로파일의 {@code service.tenant} 가 범위 안인지 */
    public void checkTenant(AdminPrincipal p, String tenantCode) {
        if (p.isGlobal()) return;
        String t = tenantCode == null || tenantCode.isBlank() ? ServiceProfile.DEFAULT_TENANT : tenantCode;
        if (!p.tenantCode().equals(t)) {
            throw new PlatformException(PlatformErrorCode.ADMIN_FORBIDDEN, null, "다른 Tenant: " + t);
        }
    }

    public boolean inScope(AdminPrincipal p, String serviceCode) {
        if (p.isGlobal()) return true;
        return serviceProfileService.find(serviceCode).map(pr -> p.tenantCode().equals(pr.service().tenantOrDefault())).orElse(false);
    }
}
