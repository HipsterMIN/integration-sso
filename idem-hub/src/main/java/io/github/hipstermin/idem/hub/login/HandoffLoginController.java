package io.github.hipstermin.idem.hub.login;

import io.github.hipstermin.idem.common.domain.AuthResult;
import io.github.hipstermin.idem.common.domain.HandoffTicket;
import io.github.hipstermin.idem.common.error.PlatformErrorCode;
import io.github.hipstermin.idem.common.error.PlatformException;
import io.github.hipstermin.idem.common.event.AuditLogEvent;
import io.github.hipstermin.idem.common.spi.identity.IdentityProviderRegistry;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationProvider;
import io.github.hipstermin.idem.common.spi.identity.VerificationStart;
import io.github.hipstermin.idem.common.util.CorrelationIdHolder;
import io.github.hipstermin.idem.hub.admin.auth.AdminAuthFilter;
import io.github.hipstermin.idem.hub.audit.AuditLogPublisher;
import io.github.hipstermin.idem.hub.consent.ConsentItem;
import io.github.hipstermin.idem.hub.consent.ConsentRegistryClient;
import io.github.hipstermin.idem.hub.domain.AgencyMeta;
import io.github.hipstermin.idem.hub.fe.session.FeSession;
import io.github.hipstermin.idem.hub.fe.session.FeSessionCookie;
import io.github.hipstermin.idem.hub.fe.session.FeSessionPolicyEnforcer;
import io.github.hipstermin.idem.hub.fe.session.FeSessionService;
import io.github.hipstermin.idem.hub.handoff.HandoffIssueCommand;
import io.github.hipstermin.idem.hub.handoff.HandoffService;
import io.github.hipstermin.idem.hub.handoff.validate.CallbackUrlValidator;
import io.github.hipstermin.idem.hub.identity.spi.IdentityLoginService;
import io.github.hipstermin.idem.hub.infrastructure.AgencyMetaRepository;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfile;
import io.github.hipstermin.idem.hub.serviceprofile.ServiceProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 1.1 <b>코어 로그인 프런트</b> — Handoff 유형 서비스의 브라우저 진입 (로그인 → 발급 → 콜백).
 *
 * <pre>
 *   기관 화면 "Idem 으로 로그인"
 *     → GET {hub}/api/v1/handoff/login?service=AG1&callback=https://agency/cb[&provider=MOCK][&level=L2][&state=xyz]   ①
 *         · 기관 존재·활성·Handoff 유형, callback 은 프로파일 callbackWhitelist 안 — 아니면 오류 화면(콜백으로 보내지 않는다)
 *         · 이미 FE 세션 쿠키가 있으면 바로 ④
 *         · 제공자: provider 파라미터 / 등록 제공자가 하나면 자동 / 여럿이면 선택 화면
 *     → GET …/login/start?req=&provider=                                                                             ②
 *         · SPI 제공자: initiate(returnUrl = …/login/continue?req=) → 제공자 화면으로 302
 *         · broker:&lt;name&gt;: {hub}/api/v1/broker/&lt;name&gt;/authorize?returnUrl=…/login/continue?req= 로 302 (Keycloak·NonOidc)
 *     → GET …/login/continue?req=&amp;…제공자 콜백 파라미터                                                             ③
 *         · FE 세션 쿠키가 있으면(브로커 경로) 그대로, 없으면 SPI complete → registry 확정 → FE 세션 + 쿠키
 *     → (1.1 동의 카탈로그) 프로파일 consent.enabled 이고 registry 카탈로그에 <b>필수</b> 미동의 항목이 있으면 동의 화면 200    ③′
 *         · POST …/login/consent (req, agree=&lt;versionId&gt;…, decline) — 같은 경로로 form POST (CSP form-action 'self')
 *         · 동의 → registry 에 기록(감사 MEMBER/CONSENT_AGREED) 후 ④, 거부 → 302 callback?error=E-IDO-125 (감사 CONSENT_DECLINED)
 *     → 발급(HandoffService.issue — 정책·레이트리밋·화이트리스트·감사는 API 발급과 같다)                                     ④
 *     → 302 callback?ticketId=…[&amp;state=…]  /  정책 거부는 302 callback?error=E-IDO-120&amp;error_description=…[&amp;state=…]
 * </pre>
 *
 * <p>기관 API 키는 어디에도 없다 — 발급은 hub 안에서 FE 세션(로그인 사실)과 화이트리스트로 정당화된다. 기관은 종전대로
 * 서버 간 {@code POST /api/v1/handoff/verify} 만 한다. 진입 상태는 Redis 에만 있어 쿼리로 콜백·기관을 바꿀 수 없다.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/handoff/login")
public class HandoffLoginController {

    static final String PARAM_REQ = "req";
    static final String AUDIT_CONSENT_AGREED = "CONSENT_AGREED";
    static final String AUDIT_CONSENT_DECLINED = "CONSENT_DECLINED";
    private static final List<String> LEVELS = List.of("L1", "L2", "L3");

    private final HandoffLoginRequestStore store;
    private final AgencyMetaRepository agencyMetaRepository;
    private final CallbackUrlValidator callbackUrlValidator;
    private final IdentityProviderRegistry providerRegistry;
    private final IdentityLoginService identityLoginService;
    private final FeSessionService feSessionService;
    private final FeSessionPolicyEnforcer feSessionPolicyEnforcer;
    private final HandoffService handoffService;
    private final ServiceProfileService serviceProfileService;
    private final ConsentRegistryClient consentClient;
    private final AuditLogPublisher auditLogPublisher;
    private final String publicUrl;
    private final List<String> brokerProviders;

    public HandoffLoginController(HandoffLoginRequestStore store,
                                  AgencyMetaRepository agencyMetaRepository,
                                  CallbackUrlValidator callbackUrlValidator,
                                  IdentityProviderRegistry providerRegistry,
                                  IdentityLoginService identityLoginService,
                                  FeSessionService feSessionService,
                                  FeSessionPolicyEnforcer feSessionPolicyEnforcer,
                                  HandoffService handoffService,
                                  ServiceProfileService serviceProfileService,
                                  ConsentRegistryClient consentClient,
                                  AuditLogPublisher auditLogPublisher,
                                  @Value("${idem.hub.public-url:http://localhost:8083}") String publicUrl,
                                  @Value("${idem.hub.handoff.login.broker-providers:}") String brokerProviders) {
        this.store = store;
        this.agencyMetaRepository = agencyMetaRepository;
        this.callbackUrlValidator = callbackUrlValidator;
        this.providerRegistry = providerRegistry;
        this.identityLoginService = identityLoginService;
        this.feSessionService = feSessionService;
        this.feSessionPolicyEnforcer = feSessionPolicyEnforcer;
        this.handoffService = handoffService;
        this.serviceProfileService = serviceProfileService;
        this.consentClient = consentClient;
        this.auditLogPublisher = auditLogPublisher;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.brokerProviders = Arrays.stream(brokerProviders.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    // ── ① 진입 ──────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<String> entry(@RequestParam("service") String service,
                                        @RequestParam("callback") String callback,
                                        @RequestParam(value = "provider", required = false) String provider,
                                        @RequestParam(value = "level", required = false) String level,
                                        @RequestParam(value = "state", required = false) String state,
                                        HttpServletRequest request) {
        String cid = CorrelationIdHolder.generate();
        CorrelationIdHolder.set(cid);

        Optional<AgencyMeta> agency = agencyMetaRepository.findByCode(service);
        if (agency.isEmpty() || !agency.get().isActive()) {
            return errorPage(HttpStatus.NOT_FOUND, PlatformErrorCode.AGENCY_NOT_FOUND.getCode(),
                    "등록되지 않았거나 비활성인 서비스입니다.", cid);
        }
        if (agency.get().getIntegrationType() != null && !agency.get().getIntegrationType().usesHandoff()) {
            return errorPage(HttpStatus.BAD_REQUEST, PlatformErrorCode.IDO_PROTOCOL_MISMATCH.getCode(),
                    "이 서비스는 표준 OIDC 로 로그인합니다 — 서비스의 OIDC 로그인 주소를 쓰세요.", cid);
        }
        try {
            callbackUrlValidator.validate(callback, agency.get().getCallbackWhitelist(), cid);
        } catch (PlatformException e) {
            // 콜백이 화이트리스트 밖이면 그 콜백으로 되돌리지 않는다 (open redirect 방지)
            return errorPage(HttpStatus.FORBIDDEN, e.getErrorCode().getCode(),
                    "허용되지 않은 콜백 주소입니다. 서비스 프로파일의 callbackWhitelist 를 확인하세요.", cid);
        }
        String requestedLevel = level == null || level.isBlank() ? "L1" : level.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(requestedLevel)) {
            return errorPage(HttpStatus.BAD_REQUEST, "E-IDO-400", "level 은 L1·L2·L3 중 하나여야 합니다.", cid);
        }
        if (state != null && state.length() > 512) {
            return errorPage(HttpStatus.BAD_REQUEST, "E-IDO-400", "state 가 너무 깁니다(최대 512자).", cid);
        }

        HandoffLoginRequest req = HandoffLoginRequest.builder()
                .requestId(HandoffLoginRequestStore.newRequestId())
                .agencyCode(service)
                .callbackUrl(callback)
                .requestedLevel(requestedLevel)
                .state(state)
                .correlationId(cid)
                .createdAt(Instant.now())
                .build();
        store.save(req);
        log.info("[HandoffLogin] 진입: req={} service={} level={} cid={}", req.requestId(), service, requestedLevel, cid);

        // 이미 로그인돼 있으면 바로 발급
        Optional<FeSession> existing = currentSession(request);
        if (existing.isPresent()) {
            return complete(req.toBuilder().providerCode("SESSION").build(), existing.get(), cid);
        }

        List<LoginPages.ProviderLink> links = providerLinks(req.requestId());
        if (provider != null && !provider.isBlank()) {
            return start(req, provider.trim(), cid);
        }
        if (links.size() == 1) {
            return start(req, links.get(0).code(), cid);
        }
        if (links.isEmpty()) {
            return errorPage(HttpStatus.SERVICE_UNAVAILABLE, "E-IDO-109",
                    "이 설치본에 사용할 수 있는 인증 방법이 없습니다 (본인인증 제공자 플러그인 또는 브로커 설정).", cid);
        }
        return html(HttpStatus.OK, LoginPages.chooser(agency.get().getOfficialName(), links));
    }

    // ── ② 제공자 시작 ─────────────────────────────────────────────────────────

    @GetMapping("/start")
    public ResponseEntity<String> start(@RequestParam(PARAM_REQ) String requestId,
                                        @RequestParam("provider") String provider) {
        Optional<HandoffLoginRequest> req = store.find(requestId);
        if (req.isEmpty()) {
            return expiredPage(null);
        }
        CorrelationIdHolder.set(req.get().correlationId());
        return start(req.get(), provider.trim(), req.get().correlationId());
    }

    private ResponseEntity<String> start(HandoffLoginRequest req, String provider, String cid) {
        String continueUrl = publicUrl + "/api/v1/handoff/login/continue?" + PARAM_REQ + "=" + req.requestId();

        if (provider.startsWith(HandoffLoginRequest.BROKER_PREFIX)) {
            String name = provider.substring(HandoffLoginRequest.BROKER_PREFIX.length());
            if (!brokerProviders.contains(name)) {
                return errorPage(HttpStatus.BAD_REQUEST, "E-IDO-109", "허용되지 않은 브로커 제공자입니다: " + name, cid);
            }
            store.save(req.toBuilder().providerCode(provider).build());
            String url = publicUrl + "/api/v1/broker/" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8) + "/authorize"
                    + "?returnUrl=" + java.net.URLEncoder.encode(continueUrl, StandardCharsets.UTF_8)
                    + "&requestedLevel=" + req.requestedLevel();
            log.info("[HandoffLogin] 브로커 시작: req={} broker={} cid={}", req.requestId(), name, cid);
            return redirect(url);
        }

        Optional<IdentityVerificationProvider> spi = providerRegistry.find(provider);
        if (spi.isEmpty()) {
            return errorPage(HttpStatus.BAD_REQUEST, "E-IDO-109", "등록되지 않은 인증 제공자입니다: " + provider, cid);
        }
        VerificationStart startResult;
        try {
            startResult = identityLoginService.initiate(spi.get().code(), continueUrl, Map.of(), cid);
        } catch (PlatformException e) {
            return errorPage(e.getErrorCode().getHttpStatus(), e.getErrorCode().getCode(), "인증을 시작할 수 없습니다.", cid);
        }
        store.save(req.toBuilder().providerCode(spi.get().code()).txId(startResult.txId()).build());
        if (startResult.redirectUrl() == null || startResult.redirectUrl().isBlank()) {
            // 위젯형 제공자(브라우저 안에서 벤더 모듈을 띄우는 유형)는 코어 최소 프런트가 그리지 않는다 — 운영기관 프런트의 몫
            return errorPage(HttpStatus.NOT_IMPLEMENTED, "E-IDO-109",
                    "이 인증 제공자(" + spi.get().code() + ")는 리다이렉트형이 아니라 코어 로그인 프런트가 지원하지 않습니다.", cid);
        }
        log.info("[HandoffLogin] 제공자 시작: req={} provider={} tx={} cid={}", req.requestId(), spi.get().code(), startResult.txId(), cid);
        return redirect(startResult.redirectUrl());
    }

    // ── ③ 제공자에서 복귀 ─────────────────────────────────────────────────────

    @GetMapping("/continue")
    public ResponseEntity<String> resume(@RequestParam(PARAM_REQ) String requestId,
                                         @RequestParam Map<String, String> allParams,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        Optional<HandoffLoginRequest> found = store.find(requestId);
        if (found.isEmpty()) {
            return expiredPage(null);
        }
        HandoffLoginRequest req = found.get();
        String cid = req.correlationId();
        CorrelationIdHolder.set(cid);

        Optional<FeSession> session = currentSession(request);
        if (session.isPresent()) {
            return complete(req, session.get(), cid);
        }
        if (req.isBrokerProvider()) {
            // 브로커 콜백이 쿠키를 심었어야 한다 — 없으면 로그인이 완료되지 않은 것
            return errorPage(HttpStatus.UNAUTHORIZED, PlatformErrorCode.IDO_SESSION_NOT_FOUND.getCode(),
                    "로그인이 완료되지 않았습니다. 처음부터 다시 시도하세요.", cid);
        }
        if (req.providerCode() == null || req.txId() == null) {
            return errorPage(HttpStatus.BAD_REQUEST, "E-IDO-400", "인증이 시작되지 않은 요청입니다.", cid);
        }
        Map<String, String> params = new LinkedHashMap<>(allParams);
        params.remove(PARAM_REQ);
        IdentityLoginService.Completed done;
        try {
            done = identityLoginService.complete(req.providerCode(), req.txId(), params, cid);
        } catch (PlatformException e) {
            log.warn("[HandoffLogin] 인증 실패: req={} provider={} code={} cid={}", req.requestId(), req.providerCode(), e.getErrorCode(), cid);
            return errorPage(e.getErrorCode().getHttpStatus(), e.getErrorCode().getCode(), "본인인증에 실패했습니다.", cid);
        }
        FeSession fe = feSessionService.create(done.qimUserId(), done.identity().txId(),
                done.identity().level() != null ? done.identity().level().name() : req.requestedLevel(), null);
        response.addHeader(HttpHeaders.SET_COOKIE, FeSessionCookie.build(fe.getFeSessionId()).toString());
        log.info("[HandoffLogin] 로그인 완료 → FE 세션: req={} provider={} newUser={} cid={}",
                req.requestId(), req.providerCode(), done.newUser(), cid);
        return complete(req, fe, cid);
    }

    // ── ③′ 동의 (1.1 동의 카탈로그, 플랜 §5 #8) ──────────────────────────────────

    /**
     * 동의 화면의 form POST — {@code req}, {@code agree=<versionId>}(여러 개), {@code decline=1}.
     * 기록 대상은 서버가 다시 계산한 미동의 목록 안의 항목만이다(브라우저가 임의 versionId 를 보내도 기록되지 않는다).
     */
    @PostMapping(value = "/consent", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> consent(@RequestParam(PARAM_REQ) String requestId,
                                          @RequestParam(value = "agree", required = false) List<String> agree,
                                          @RequestParam(value = "decline", required = false) String decline,
                                          HttpServletRequest request) {
        Optional<HandoffLoginRequest> found = store.find(requestId);
        if (found.isEmpty()) {
            return expiredPage(null);
        }
        HandoffLoginRequest req = found.get();
        String cid = req.correlationId();
        CorrelationIdHolder.set(cid);

        Optional<FeSession> session = currentSession(request);
        if (session.isEmpty()) {
            return errorPage(HttpStatus.UNAUTHORIZED, PlatformErrorCode.IDO_SESSION_NOT_FOUND.getCode(),
                    "로그인 세션이 없습니다. 처음부터 다시 시도하세요.", cid);
        }
        FeSession fe = session.get();
        List<ConsentItem> missing;
        try {
            missing = missingConsents(req, fe, cid);
        } catch (PlatformException e) {
            return consentUnavailable(req, e, cid);
        }

        if (decline != null && !decline.isBlank()) {
            store.delete(req.requestId());
            auditConsent(AUDIT_CONSENT_DECLINED, req, fe, missing, AdminAuthFilter.clientIp(request), cid);
            log.info("[HandoffLogin] 동의 거부 → 콜백: req={} service={} cid={}", req.requestId(), req.agencyCode(), cid);
            Map<String, String> q = new LinkedHashMap<>();
            q.put("error", PlatformErrorCode.IDO_CONSENT_DECLINED.getCode());
            q.put("error_description", PlatformErrorCode.IDO_CONSENT_DECLINED.getDefaultMessage());
            return redirect(appendQuery(req.callbackUrl(), q, req.state()));
        }

        Set<String> agreed = agree == null ? Set.of() : new HashSet<>(agree);
        boolean requiredLeft = missing.stream().anyMatch(i -> i.required() && !agreed.contains(i.versionId()));
        if (requiredLeft) {
            return consentPage(req, missing, "필수 항목에 모두 동의해야 계속할 수 있습니다.");
        }
        List<ConsentItem> toRecord = missing.stream().filter(i -> agreed.contains(i.versionId())).toList();
        String ip = AdminAuthFilter.clientIp(request);
        try {
            for (ConsentItem it : toRecord) {
                consentClient.agree(fe.getQimUserId(), it.versionId(), it.consentType(), "LOGIN_FRONT:" + req.agencyCode(), ip, cid);
            }
        } catch (PlatformException e) {
            log.warn("[HandoffLogin] 동의 기록 실패: req={} service={} code={} cid={}", req.requestId(), req.agencyCode(), e.getErrorCode().getCode(), cid);
            return errorPage(e.getErrorCode().getHttpStatus(), e.getErrorCode().getCode(),
                    "동의를 기록하지 못해 로그인을 진행할 수 없습니다. 잠시 후 다시 시도하세요.", cid);
        }
        if (!toRecord.isEmpty()) {
            auditConsent(AUDIT_CONSENT_AGREED, req, fe, toRecord, ip, cid);
        }
        log.info("[HandoffLogin] 동의 기록 → 발급: req={} service={} agreed={} cid={}", req.requestId(), req.agencyCode(), toRecord.size(), cid);
        return issue(req, fe, cid);
    }

    /**
     * 프로파일 {@code consent.enabled} 인 서비스의 미동의 카탈로그 항목(플랫폼 공통 포함 여부는 {@code includePlatform}).
     * 꺼져 있으면 빈 목록. registry 장애는 {@link PlatformException}(E-IDO-106) 으로 올라온다.
     */
    private List<ConsentItem> missingConsents(HandoffLoginRequest req, FeSession fe, String cid) {
        ServiceProfile.Consent consent = serviceProfileService.find(req.agencyCode()).map(ServiceProfile::consent).orElse(null);
        if (consent == null || !consent.enabledOrFalse()) {
            return List.of();
        }
        List<ConsentItem> missing = consentClient.missing(fe.getQimUserId(), req.agencyCode(), cid);
        if (!consent.includePlatformOrTrue()) {
            missing = missing.stream().filter(i -> !i.platformItem()).toList();
        }
        return missing;
    }

    private ResponseEntity<String> consentPage(HandoffLoginRequest req, List<ConsentItem> missing, String error) {
        String serviceName = agencyMetaRepository.findByCode(req.agencyCode()).map(AgencyMeta::getOfficialName).orElse(req.agencyCode());
        String action = publicUrl + "/api/v1/handoff/login/consent";
        return html(HttpStatus.OK, LoginPages.consent(serviceName, action, req.requestId(), missing, error));
    }

    /** 동의 상태를 확인할 수 없으면 발급하지 않는다(fail-secure) — 콜백으로 보내지도 않는다 */
    private ResponseEntity<String> consentUnavailable(HandoffLoginRequest req, PlatformException e, String cid) {
        log.warn("[HandoffLogin] 동의 확인 실패: req={} service={} code={} cid={}", req.requestId(), req.agencyCode(), e.getErrorCode().getCode(), cid);
        return errorPage(e.getErrorCode().getHttpStatus(), e.getErrorCode().getCode(),
                "동의 상태를 확인할 수 없어 로그인을 진행할 수 없습니다. 잠시 후 다시 시도하세요.", cid);
    }

    private void auditConsent(String action, HandoffLoginRequest req, FeSession fe, List<ConsentItem> items, String ip, String cid) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("via", "LOGIN_FRONT");
        meta.put("versionIds", items.stream().map(ConsentItem::versionId).toList());
        meta.put("consentTypes", items.stream().map(ConsentItem::consentType).toList());
        auditLogPublisher.publish(AuditLogPublisher.AuditEntry.builder()
                .eventCategory(AuditLogEvent.CATEGORY_MEMBER)
                .eventAction(action)
                .actorType(AuditLogEvent.ACTOR_USER)
                .actorId(fe.getQimUserId())
                .resourceType("CONSENT")
                .resourceId(req.agencyCode())
                .agencyCode(req.agencyCode())
                .correlationId(cid)
                .sourceIp(ip)
                .outcome(AuditLogEvent.OUTCOME_SUCCESS)
                .metadata(meta)
                .build());
    }

    // ── ④ 발급 → 콜백 ─────────────────────────────────────────────────────────

    /** 발급 전 동의 관문 — 필수 미동의 항목이 있으면 동의 화면(요청 상태는 유지), 아니면 발급 */
    private ResponseEntity<String> complete(HandoffLoginRequest req, FeSession fe, String cid) {
        List<ConsentItem> missing;
        try {
            missing = missingConsents(req, fe, cid);
        } catch (PlatformException e) {
            return consentUnavailable(req, e, cid);
        }
        if (missing.stream().anyMatch(ConsentItem::required)) {
            log.info("[HandoffLogin] 동의 필요: req={} service={} missing={} cid={}", req.requestId(), req.agencyCode(), missing.size(), cid);
            return consentPage(req, missing, null);
        }
        return issue(req, fe, cid);
    }

    private ResponseEntity<String> issue(HandoffLoginRequest req, FeSession fe, String cid) {
        store.delete(req.requestId());   // 1회 — 새로고침으로 두 번 발급하지 않는다
        AuthResult.AuthLevel level = AuthResult.AuthLevel.parseOrDefault(fe.getAuthLevel(), AuthResult.AuthLevel.L1);
        String providerCode = req.providerCode() == null ? "SESSION"
                : req.isBrokerProvider() ? req.brokerName().toUpperCase(Locale.ROOT) : req.providerCode();
        HandoffIssueCommand cmd = HandoffIssueCommand.builder()
                .correlationId(cid)
                .agencyCode(req.agencyCode())
                .qimUserId(fe.getQimUserId())
                .authResultId(fe.getAuthResultId() != null ? fe.getAuthResultId() : "fe:" + fe.getFeSessionId())
                .authLevel(level)
                .providerCode(providerCode)
                .callbackUrl(req.callbackUrl())
                .redirectUri(req.callbackUrl())
                .build();
        try {
            HandoffTicket ticket = handoffService.issue(cmd);
            feSessionPolicyEnforcer.applyForService(fe.getFeSessionId(), req.agencyCode(), cid);
            log.info("[HandoffLogin] 발급 → 콜백: req={} service={} ticket={} cid={}", req.requestId(), req.agencyCode(), ticket.getTicketId(), cid);
            return redirect(appendQuery(req.callbackUrl(), Map.of("ticketId", ticket.getTicketId()), req.state()));
        } catch (PlatformException e) {
            // 정책 거부(E-IDO-120 등)는 기관이 처리할 수 있게 콜백으로 돌려준다 — 콜백은 화이트리스트 검증을 이미 지났다
            log.info("[HandoffLogin] 발급 거부 → 콜백: req={} service={} code={} cid={}", req.requestId(), req.agencyCode(), e.getErrorCode().getCode(), cid);
            Map<String, String> q = new LinkedHashMap<>();
            q.put("error", e.getErrorCode().getCode());
            q.put("error_description", e.getErrorCode().getDefaultMessage());
            return redirect(appendQuery(req.callbackUrl(), q, req.state()));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Optional<FeSession> currentSession(HttpServletRequest request) {
        String id = FeSessionCookie.read(request);
        return id == null ? Optional.empty() : feSessionService.findById(id);
    }

    private List<LoginPages.ProviderLink> providerLinks(String requestId) {
        List<LoginPages.ProviderLink> links = new ArrayList<>();
        String base = publicUrl + "/api/v1/handoff/login/start?" + PARAM_REQ + "=" + requestId + "&provider=";
        for (IdentityVerificationProvider p : providerRegistry.all()) {
            links.add(new LoginPages.ProviderLink(p.code(), p.code() + " (" + p.level() + ")", base + p.code()));
        }
        for (String b : brokerProviders) {
            String code = HandoffLoginRequest.BROKER_PREFIX + b;
            links.add(new LoginPages.ProviderLink(code, b, base + code));
        }
        return links;
    }

    static String appendQuery(String url, Map<String, String> params, String state) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(url);
        params.forEach(b::queryParam);
        if (state != null && !state.isBlank()) b.queryParam("state", state);
        return b.encode(StandardCharsets.UTF_8).build().toUriString();
    }

    private static ResponseEntity<String> redirect(String url) {
        HttpHeaders h = new HttpHeaders();
        h.setLocation(URI.create(url));
        h.setCacheControl("no-store");
        return ResponseEntity.status(HttpStatus.FOUND).headers(h).build();
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    private static ResponseEntity<String> errorPage(HttpStatus status, String code, String message, String cid) {
        return html(status, LoginPages.error(code, message, cid));
    }

    private static ResponseEntity<String> expiredPage(String cid) {
        return errorPage(HttpStatus.GONE, "E-IDO-400", "로그인 요청이 만료됐거나 이미 처리됐습니다. 처음부터 다시 시도하세요.", cid);
    }
}
