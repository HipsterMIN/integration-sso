package io.github.hipstermin.idem.plugin.niceoacx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hipstermin.idem.common.spi.identity.IdentityVerificationException;
import io.github.hipstermin.idem.plugin.niceoacx.dto.NiceResultApiResponse;
import io.github.hipstermin.idem.plugin.niceoacx.dto.NiceUrlApiResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/** S5a — 플러그인으로 옮긴 NICE 서비스: registry 등록·감사는 코어가 하므로 여기서는 API 흐름·복호화·오류 코드만 본다. */
@ExtendWith(MockitoExtension.class)
@DisplayName("NicePhoneService — 토큰·URL·결과 복호화 (S5a)")
class NicePhoneServiceTest {

    @Mock NiceApiClient api;
    @Mock NiceTokenStore tokenStore;
    @Mock NiceAuthSessionStore sessionStore;
    @Mock RedissonClient redisson;
    @Mock RLock lock;

    NicePhoneService service;

    private static final String TICKET = "ticket-1234567890";
    private static final String TX = "tx-abc";
    private static final int ITERS = 1000;

    @BeforeEach
    void setUp() {
        NiceProperties props = new NiceProperties();
        props.setReturnUrl("https://fe.example.org/auth-result");
        service = new NicePhoneService(api, tokenStore, sessionStore, new ObjectMapper(), props, redisson);
        org.mockito.Mockito.lenient().when(tokenStore.isValid()).thenReturn(true);
        org.mockito.Mockito.lenient().when(tokenStore.get()).thenReturn(new NiceTokenStore.NiceTokenSnapshot("AT", Long.MAX_VALUE, TICKET, ITERS));
    }

    @Test
    @DisplayName("start: URL 발급 성공 → requestNo·authUrl, 세션 저장 (NICE 가 준 request_no 우선)")
    void start_success() {
        NiceUrlApiResponse res = new NiceUrlApiResponse();
        res.setResultCode("0000"); res.setRequestNo("REQ-NICE"); res.setTransactionId(TX); res.setAuthUrl("https://nice/auth");
        given(api.requestAuthUrl(eq("AT"), anyString(), eq("https://fe.example.org/auth-result"))).willReturn(res);

        NicePhoneGateway.Started s = service.start(null);

        assertThat(s.requestNo()).isEqualTo("REQ-NICE");
        assertThat(s.authUrl()).isEqualTo("https://nice/auth");
        then(sessionStore).should().save("REQ-NICE", TX);
    }

    @Test
    @DisplayName("start: NICE 실패 응답 → reasonCode 5001")
    void start_failure() {
        NiceUrlApiResponse res = new NiceUrlApiResponse();
        res.setResultCode("9999"); res.setResultMessage("bad");
        given(api.requestAuthUrl(anyString(), anyString(), anyString())).willReturn(res);

        assertThatThrownBy(() -> service.start("https://x"))
                .isInstanceOf(IdentityVerificationException.class).extracting("reasonCode").isEqualTo("5001");
    }

    @Test
    @DisplayName("result: request_no 없음/세션 없음 → 4000, API 실패 → 5002")
    void result_inputAndApiFailures() {
        assertThatThrownBy(() -> service.result("W", " ")).extracting("reasonCode").isEqualTo("4000");
        given(sessionStore.find("R1")).willReturn(null);
        assertThatThrownBy(() -> service.result("W", "R1")).extracting("reasonCode").isEqualTo("4000");

        given(sessionStore.find("R2")).willReturn(new NiceAuthSessionStore.NiceAuthSession("R2", TX));
        NiceResultApiResponse bad = new NiceResultApiResponse();
        bad.setResultCode("5000"); bad.setResultMessage("err");
        given(api.requestAuthResult("AT", "W", TX, "R2")).willReturn(bad);
        assertThatThrownBy(() -> service.result("W", "R2")).extracting("reasonCode").isEqualTo("5002");
    }

    @Test
    @DisplayName("result: HMAC 불일치 → 5003, 정상 암호문 → CI 포함 결과 + 세션 삭제")
    void result_integrityAndSuccess() throws Exception {
        given(sessionStore.find("R3")).willReturn(new NiceAuthSessionStore.NiceAuthSession("R3", TX));
        String keyString = NiceCryptoUtil.deriveKey(TICKET, TX, ITERS);
        byte[] aesKey = NiceCryptoUtil.extractAesKey(keyString);
        String hmacKey = NiceCryptoUtil.extractHmacKey(keyString);
        String encData = encrypt(aesKey, "{\"ci\":\"CI-1\",\"di\":\"DI-1\",\"name\":\"홍길동\",\"birthdate\":\"19900101\",\"gender\":\"1\",\"national_info\":\"0\",\"mobile_no\":\"01012345678\",\"mobile_co\":\"1\"}");

        NiceResultApiResponse tampered = new NiceResultApiResponse();
        tampered.setResultCode("0000"); tampered.setEncData(encData); tampered.setIntegrityValue("wrong");
        given(api.requestAuthResult("AT", "W", TX, "R3")).willReturn(tampered);
        assertThatThrownBy(() -> service.result("W", "R3")).extracting("reasonCode").isEqualTo("5003");

        NiceResultApiResponse ok = new NiceResultApiResponse();
        ok.setResultCode("0000"); ok.setEncData(encData); ok.setIntegrityValue(NiceCryptoUtil.hmacSha256Base64Url(encData, hmacKey));
        given(api.requestAuthResult("AT", "W", TX, "R3")).willReturn(ok);
        NicePhoneGateway.Result r = service.result("W", "R3");

        assertThat(r.ci()).isEqualTo("CI-1");
        assertThat(r.di()).isEqualTo("DI-1");
        assertThat(r.name()).isEqualTo("홍길동");
        assertThat(r.mobileNo()).isEqualTo("01012345678");
        then(sessionStore).should().remove("R3");
    }

    @Test
    @DisplayName("토큰 캐시가 무효면 분산 락을 잡고 발급한다; 발급 실패 → 5000")
    void ensureToken_refreshUnderLock() throws Exception {
        given(tokenStore.isValid()).willReturn(false);
        given(redisson.getLock(anyString())).willReturn(lock);
        given(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(true);
        given(lock.isHeldByCurrentThread()).willReturn(true);
        given(api.fetchAccessToken(anyString())).willReturn(null);

        assertThatThrownBy(() -> service.start("https://x")).extracting("reasonCode").isEqualTo("5000");
        then(lock).should().unlock();
    }

    /** NiceCryptoUtil.aesGcmDecrypt 형식: Base64URL( IV(16) || ciphertext+tag ) */
    private static String encrypt(byte[] key, String plain) throws Exception {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }
}
