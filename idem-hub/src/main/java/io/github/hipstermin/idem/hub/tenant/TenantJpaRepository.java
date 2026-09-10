package io.github.hipstermin.idem.hub.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantJpaRepository extends JpaRepository<TenantJpaEntity, String> {}
