package io.github.hipstermin.idem.hub.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.crypto.CryptoProviders;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.hub.admin.dto.AgencyCreateRequest;
import io.github.hipstermin.idem.hub.admin.dto.AgencyResponse;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.domain.IntegrationType;
import io.github.hipstermin.idem.hub.infrastructure.jpa.entity.AgencyMetaJpaEntity;
import io.github.hipstermin.idem.hub.infrastructure.jpa.repository.AgencyMetaJpaRepository;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * S1 범용화 — 기관 등록·수정 시 연동 유형은 {@link IntegrationType} 으로 검증되고,
 * APACHE_GATE 엔드포인트는 전용 컬럼에 저장된다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgencyAdminService — 연동 유형 검증·엔드포인트 분리")
class AgencyAdminServiceTest {

    @Mock AgencyMetaJpaRepository jpaRepository;
    @Mock AuditLogPublisher       auditLogPublisher;
    @Mock JdbcTemplate            jdbcTemplate;
    @Mock io.github.hipstermin.idem.hub.webhook.WebhookSigningSecrets signingSecrets;

    private AgencyAdminService service;

    @BeforeEach
    void setUp() {
        service = new AgencyAdminService(jpaRepository, auditLogPublisher, jdbcTemplate, new ObjectMapper(),
                new ServiceProfileMapper(new ObjectMapper()), signingSecrets);
        lenient().when(jpaRepository.save(any(AgencyMetaJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("미지의 integrationType 은 400(E-IDO-111) 로 거부하고 저장하지 않는다")
    void createAgency_unknownIntegrationType_rejected() {
        AgencyCreateRequest req = AgencyCreateRequest.builder()
                .agencyCode("AG_X").officialName("X").integrationType("WEBHOOK").build();

        assertThatThrownBy(() -> service.createAgency(req, "admin"))
                .isInstanceOf(PlatformException.class)
                .satisfies(ex -> assertThat(((PlatformException) ex).getErrorCode())
                        .isEqualTo(PlatformErrorCode.IDO_INVALID_INTEGRATION_TYPE))
                .hasMessageContaining("WEBHOOK");

        verify(jpaRepository, never()).save(any());
        verify(jpaRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("integrationType 미지정이면 DEFAULT(DIRECT)")
    void createAgency_noIntegrationType_defaultsToDirect() {
        AgencyCreateRequest req = AgencyCreateRequest.builder()
                .agencyCode("AG_D").officialName("D").build();

        AgencyResponse res = service.createAgency(req, "admin");

        assertThat(res.getIntegrationType()).isEqualTo("DIRECT");
    }

    @Test
    @DisplayName("APACHE_GATE 는 apacheGateEndpoint 전용 필드에 저장되고 bridgeEndpoint 와 섞이지 않는다")
    void createAgency_apacheGate_storesDedicatedEndpoint() {
        AgencyCreateRequest req = AgencyCreateRequest.builder()
                .agencyCode("AG_G").officialName("G")
                .integrationType(" apache_gate ")
                .apacheGateEndpoint("https://gw.example.org/internal/sso-session")
                .build();

        AgencyResponse res = service.createAgency(req, "admin");

        ArgumentCaptor<AgencyMetaJpaEntity> saved = ArgumentCaptor.forClass(AgencyMetaJpaEntity.class);
        verify(jpaRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getIntegrationType()).isEqualTo(IntegrationType.APACHE_GATE);
        assertThat(saved.getValue().getApacheGateEndpoint()).isEqualTo("https://gw.example.org/internal/sso-session");
        assertThat(saved.getValue().getBridgeEndpoint()).isNull();
        assertThat(res.getIntegrationType()).isEqualTo("APACHE_GATE");
        assertThat(res.getApacheGateEndpoint()).isEqualTo("https://gw.example.org/internal/sso-session");
    }

    @Test
    @DisplayName("수정 시에도 미지의 integrationType 은 거부한다")
    void updateAgency_unknownIntegrationType_rejected() {
        AgencyMetaJpaEntity existing = AgencyMetaJpaEntity.builder()
                .agencyCode("AG_U").officialName("U").integrationType(IntegrationType.DIRECT).active(true).build();
        given(jpaRepository.findById("AG_U")).willReturn(java.util.Optional.of(existing));
        AgencyCreateRequest req = AgencyCreateRequest.builder().integrationType("SAML").build();

        assertThatThrownBy(() -> service.updateAgency("AG_U", req, "admin"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("SAML");
        verify(jpaRepository, never()).save(any());
        verify(jpaRepository, never()).saveAndFlush(any());
    }

    // ── 1.1.1 G1-4 웹훅 서명 비밀 ─────────────────────────────────────────────

    private void agencyExists(String code) {
        lenient().when(jpaRepository.findById(code)).thenReturn(java.util.Optional.of(
                AgencyMetaJpaEntity.builder().agencyCode(code).officialName("x").minAuthLevel("L1").policyVersion("1.0").apiKeyHash("h").active(true).build()));
    }

    @Test
    @DisplayName("rotateWebhookSecret: 엔드포인트가 없는 기관은 404(E-IDO-126), 저장·감사 없음")
    void rotateWebhookSecret_notConfigured() {
        agencyExists("AG_W");
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("AG_W"))).thenReturn(0);
        assertThatThrownBy(() -> service.rotateWebhookSecret("AG_W", "admin"))
                .isInstanceOf(PlatformException.class)
                .satisfies(ex -> assertThat(((PlatformException) ex).getErrorCode()).isEqualTo(PlatformErrorCode.IDO_WEBHOOK_NOT_CONFIGURED));
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
        verify(auditLogPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("rotateWebhookSecret: 32바이트 난수 → KMS 봉인값·SHA-256 저장, 원문은 응답에 1회, 감사 WEBHOOK_SECRET_ROTATED")
    void rotateWebhookSecret_sealsAndReturnsOnce() {
        agencyExists("AG_W");
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("AG_W"))).thenReturn(1);
        when(signingSecrets.seal(anyString())).thenAnswer(inv -> "sealed:" + inv.getArgument(0));
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1);

        Map<String, Object> out = service.rotateWebhookSecret("AG_W", "admin");

        String secret = (String) out.get("signingSecret");
        assertThat(secret).hasSizeGreaterThanOrEqualTo(32);
        String hash = CryptoProviders.current().sha256Hex(secret);
        assertThat(out.get("fingerprint")).isEqualTo(hash.substring(0, 8));
        assertThat(out.get("agencyCode")).isEqualTo("AG_W");
        verify(jdbcTemplate).update(contains("signing_secret_sealed = ?"), eq("sealed:" + secret), eq(hash), eq("AG_W"));
        ArgumentCaptor<AuditLogPublisher.AuditEntry> entry = ArgumentCaptor.forClass(AuditLogPublisher.AuditEntry.class);
        verify(auditLogPublisher).publish(entry.capture());
        assertThat(entry.getValue().eventAction()).isEqualTo("WEBHOOK_SECRET_ROTATED");
        assertThat(entry.getValue().agencyCode()).isEqualTo("AG_W");
    }

    @Test
    @DisplayName("webhookStatus: 설정 없음 → configured=false; 봉인된 행은 지문(해시 앞 8자), 봉인 전(1.0.x 원문) 행은 지문 없음")
    void webhookStatus() {
        agencyExists("AG_W");
        when(jdbcTemplate.queryForList(anyString(), eq("AG_W"))).thenReturn(List.of());
        assertThat(service.webhookStatus("AG_W")).containsEntry("configured", false);

        Map<String, Object> sealedRow = new java.util.HashMap<>();
        sealedRow.put("endpoint_url", "https://a.example.org/hook"); sealedRow.put("active", true); sealedRow.put("sealed", true);
        sealedRow.put("signing_secret_hash", "0123456789abcdef"); sealedRow.put("secret_rotated_at", null); sealedRow.put("updated_at", null); sealedRow.put("webhook_enabled", true);
        when(jdbcTemplate.queryForList(anyString(), eq("AG_W"))).thenReturn(List.of(sealedRow));
        Map<String, Object> st = service.webhookStatus("AG_W");
        assertThat(st).containsEntry("configured", true).containsEntry("hasSecret", true).containsEntry("sealed", true)
                .containsEntry("fingerprint", "01234567").containsEntry("endpointUrl", "https://a.example.org/hook").containsEntry("webhookEnabled", true);

        Map<String, Object> legacyRow = new java.util.HashMap<>(sealedRow);
        legacyRow.put("sealed", false); legacyRow.put("signing_secret_hash", "raw-secret-from-1.0");
        when(jdbcTemplate.queryForList(anyString(), eq("AG_W"))).thenReturn(List.of(legacyRow));
        st = service.webhookStatus("AG_W");
        assertThat(st).containsEntry("hasSecret", true).containsEntry("sealed", false);
        assertThat(st.get("fingerprint")).isNull();
    }

    // ── 1.1.1 G1-3 기관 목록 페이징·검색 ────────────────────────────────────────

    @Test
    @DisplayName("listAgencies: q 는 소문자 LIKE 패턴, size 는 1~200, 테넌트가 있으면 searchInTenant, 응답은 items·page·size·total")
    void listAgencies_pagingAndSearch() {
        AgencyMetaJpaEntity e = AgencyMetaJpaEntity.builder().agencyCode("AG_1").officialName("기관").minAuthLevel("L1").policyVersion("1.0").apiKeyHash("h").active(true).build();
        when(jpaRepository.search(eq("%ag%"), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(e), org.springframework.data.domain.PageRequest.of(0, 200), 321));
        AgencyAdminService.AgencyPage page = service.listAgencies(0, 999, " Ag ", null);
        assertThat(page.items()).singleElement().satisfies(a -> assertThat(a.getAgencyCode()).isEqualTo("AG_1"));
        assertThat(page.size()).isEqualTo(200);
        assertThat(page.total()).isEqualTo(321);

        when(jpaRepository.searchInTenant(eq("T1"), eq("%%"), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 50), 0));
        AgencyAdminService.AgencyPage t = service.listAgencies(-3, 0, null, "T1");
        assertThat(t.items()).isEmpty();
        assertThat(t.page()).isZero();
        assertThat(t.size()).isEqualTo(1);
        verify(jpaRepository, never()).search(eq("%%"), any(org.springframework.data.domain.Pageable.class));
    }
}
