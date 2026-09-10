package io.github.hipstermin.idem.registry.infrastructure;

import io.github.hipstermin.idem.common.domain.UserStatus;
import io.github.hipstermin.idem.registry.domain.AuthMeanMapping;
import io.github.hipstermin.idem.registry.domain.QimUser;
import io.github.hipstermin.idem.registry.domain.UserProfile;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.AuthMeanMappingJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.QimUserJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.entity.UserProfileJpaEntity;
import io.github.hipstermin.idem.registry.infrastructure.jpa.repository.QimUserJpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

/**
 * Q-IM UserRepository JPA 구현체
 * 설계서 §10.2 — 도메인 모델 ↔ JPA 엔터티 변환 담당
 *
 * [DB] NHN Cloud RDS for MariaDB (PoC: Docker MariaDB 11.x)
 *      스키마 없음 — qim DB 자체가 단일 스키마
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository {

    private final QimUserJpaRepository jpaRepository;

    // ── 저장 ──────────────────────────────────────────────────────────────────

    @Override
    public void save(QimUser user) {
        QimUserJpaEntity entity = toEntity(user);
        if (entity.isNew()) {
            // 신규: Persistable.isNew()=true → persist → @MapsId 프로필까지 cascade PERSIST (INSERT)
            jpaRepository.save(entity);
        }
        // 기존(managed) 엔터티는 트랜잭션 커밋 시 더티 체킹과 flush-time cascade PERSIST 로 반영된다.
        // 여기서 save()(= merge) 를 다시 호출하면 새로 붙인 @MapsId 프로필의 merge 가
        // Hibernate 6.6 에서 StaleObjectStateException 을 낸다 (MapsIdPersistRegressionTest).
        log.debug("[UserRepository] 저장 qimUserId={} status={}", user.getQimUserId(), user.getStatus());
    }

    // ── 조회 ──────────────────────────────────────────────────────────────────

    @Override
    public Optional<QimUser> findById(String qimUserId) {
        return jpaRepository.findById(qimUserId)
                .map(this::toDomain);
    }

    @Override
    public Optional<QimUser> findByIdentifierHash(String identifierHash) {
        return jpaRepository.findByIdentifierHash(identifierHash)
                .map(this::toDomain);
    }

    // ── 매핑 (Entity → Domain) ────────────────────────────────────────────────

    private QimUser toDomain(QimUserJpaEntity e) {
        List<AuthMeanMapping> mappings = e.getAuthMeanMappings() == null
                ? List.of()
                : e.getAuthMeanMappings().stream()
                        .map(this::toMappingDomain)
                        .collect(Collectors.toList());

        UserProfile profile = e.getProfile() != null ? toProfileDomain(e.getProfile()) : null;

        return QimUser.builder()
                .qimUserId(e.getQimUserId())
                .status(UserStatus.valueOf(e.getStatus()))
                .authMeanMappings(mappings)
                .profile(profile)
                .eventVersion(e.getEventVersion())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private AuthMeanMapping toMappingDomain(AuthMeanMappingJpaEntity e) {
        return AuthMeanMapping.builder()
                .mappingId(e.getMappingId())
                .qimUserId(e.getUser().getQimUserId())
                .providerCode(e.getProviderCode())
                .identifierHash(e.getIdentifierHash())
                .status(AuthMeanMapping.MappingStatus.valueOf(e.getStatus()))
                .linkedAt(e.getLinkedAt())
                .revokedAt(e.getRevokedAt())
                .build();
    }

    private UserProfile toProfileDomain(UserProfileJpaEntity e) {
        return UserProfile.builder()
                .nameMasked(e.getNameMasked())
                .mobileMasked(e.getMobileMasked())
                .nationalityType(e.getNationalityType())
                .ci(e.getCi())
                .di(e.getDiMap())
                .build();
    }

    // ── 매핑 (Domain → Entity) ────────────────────────────────────────────────

    private QimUserJpaEntity toEntity(QimUser domain) {
        // 기존 엔터티 조회 후 갱신 (없으면 신규 생성)
        QimUserJpaEntity entity = jpaRepository.findById(domain.getQimUserId())
                .orElseGet(QimUserJpaEntity::new);

        entity.setQimUserId(domain.getQimUserId());
        entity.setStatus(domain.getStatus().name());
        entity.setEventVersion(domain.getEventVersion());

        // 인증수단 매핑 동기화
        if (domain.getAuthMeanMappings() != null) {
            entity.getAuthMeanMappings().clear();
            for (AuthMeanMapping m : domain.getAuthMeanMappings()) {
                AuthMeanMappingJpaEntity me = new AuthMeanMappingJpaEntity();
                me.setMappingId(m.getMappingId());
                me.setUser(entity);
                me.setProviderCode(m.getProviderCode());
                me.setIdentifierHash(m.getIdentifierHash());
                me.setStatus(m.getStatus().name());
                me.setLinkedAt(m.getLinkedAt());
                me.setRevokedAt(m.getRevokedAt());
                entity.getAuthMeanMappings().add(me);
            }
        }

        // 프로필 동기화
        if (domain.getProfile() != null) {
            UserProfileJpaEntity pe = entity.getProfile() != null
                    ? entity.getProfile()
                    : new UserProfileJpaEntity();
            pe.setQimUserId(entity.getQimUserId());
            pe.setUser(entity);
            pe.setNameMasked(domain.getProfile().getNameMasked());
            pe.setMobileMasked(domain.getProfile().getMobileMasked());
            pe.setNationalityType(domain.getProfile().getNationalityType());
            pe.setCi(domain.getProfile().getCi());
            pe.setDiMap(domain.getProfile().getDi());
            // P3-05 미성년자·보호자 필드 동기화 — 누락 시 신규 프로필 INSERT 가 is_minor NOT NULL 에 걸린다
            // (MapsIdPersistRegressionTest.addProfileToManagedUserViaDomainRepository)
            pe.setBirthYear(domain.getProfile().getBirthYear());
            pe.setIsMinor(Boolean.TRUE.equals(domain.getProfile().getIsMinor()));
            pe.setGuardianQimUserId(domain.getProfile().getGuardianQimUserId());
            pe.setGuardianConsentAt(domain.getProfile().getGuardianConsentAt());
            entity.setProfile(pe);
        }

        return entity;
    }
}
