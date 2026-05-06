// ══════════════════════════════════════════════════════════════════════════════
// LoginPage — 인증 수단 선택 진입점
// 설계서 §12.2 / §12.3 참조
//
// 카카오 OIDC 흐름:
//   1. 버튼 클릭 → window.location.href = '/api/v1/broker/kakao/authorize?returnUrl=...'
//   2. ido BrokerController → q-sign Authorization URL 발급
//   3. q-sign → 카카오로 302 리다이렉트
//   4. 카카오 로그인 완료 → q-sign /api/v1/oidc/kakao/callback
//   5. q-sign → idToken 검증 → AuthResult 발급 → ido /api/internal/v1/oidc/complete
//   6. ido → feSessionId 쿠키 발급 → returnUrl 302 리다이렉트
//
// 비OIDC(PASS/금융인증서/GPKI):
//   → /conversion/step1 으로 이동 (7단계 UI 흐름)
// ══════════════════════════════════════════════════════════════════════════════
import React, { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Card, Typography, Button, Space, Divider, Spin, message } from 'antd';
import {
  MobileOutlined,
  SafetyCertificateOutlined,
  BankOutlined,
  KeyOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { checkSession } from '@/api/session';

const { Title, Text } = Typography;

// ── 카카오 전용 색상 ────────────────────────────────────────────────────────
const KAKAO_YELLOW  = '#FEE500';
const KAKAO_BROWN   = '#191919';

const LoginPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const returnUrl = searchParams.get('returnUrl') ?? undefined;

  // ── 기존 세션 확인 ─────────────────────────────────────────────────────
  const { data: session, isLoading } = useQuery({
    queryKey: ['session-check', returnUrl],
    queryFn: () => checkSession(returnUrl),
    retry: 0,
  });

  useEffect(() => {
    if (session?.valid) {
      // 기존 세션 유효 → 전환 흐름 바이패스, 바로 Handoff 진행
      navigate('/conversion/step6', { state: { returnUrl, skipAuth: true } });
    }
  }, [session, navigate, returnUrl]);

  // ── 카카오 OIDC 시작 ────────────────────────────────────────────────────
  const handleKakaoLogin = () => {
    // ido BrokerController 로 전달 → q-sign Authorization URL → 카카오 리다이렉트
    // 전체 페이지 이동 (쿠키/302 리다이렉트 체인이 필요하므로 fetch 가 아닌 직접 이동)
    const params = new URLSearchParams();
    if (returnUrl) params.set('returnUrl', returnUrl);
    params.set('requestedLevel', 'L1');

    window.location.href = `/api/v1/broker/kakao/authorize?${params.toString()}`;
  };

  // ── 비OIDC 인증수단 (7단계 UI 흐름) ───────────────────────────────────
  const handleAuthSelect = (method: string) => {
    navigate('/conversion/step1', { state: { returnUrl, authMethod: method } });
  };

  if (isLoading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh' }}>
        <Spin size="large" tip="세션 확인 중..." />
      </div>
    );
  }

  return (
    <div className="onepass-page-container" style={{ paddingTop: 48 }}>

      {/* 로고 / 타이틀 */}
      <div style={{ textAlign: 'center', marginBottom: 32 }}>
        <SafetyCertificateOutlined style={{ fontSize: 48, color: '#1677ff', marginBottom: 8 }} />
        <Title level={2} style={{ margin: 0 }}>OnePass 통합인증</Title>
        <Text type="secondary">안전하고 편리한 하나의 인증</Text>
      </div>

      {/* 인증 수단 선택 카드 */}
      <Card
        title="간편인증 수단 선택"
        bordered={false}
        style={{ borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,0.08)' }}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={12}>

          {/* ── 카카오 OIDC — 실제 인증 연동 ── */}
          <Button
            block
            size="large"
            onClick={handleKakaoLogin}
            style={{
              textAlign: 'left',
              height: 52,
              backgroundColor: KAKAO_YELLOW,
              borderColor: KAKAO_YELLOW,
              color: KAKAO_BROWN,
              fontWeight: 600,
              display: 'flex',
              alignItems: 'center',
              gap: 8,
            }}
          >
            {/* 카카오 말풍선 아이콘 SVG (공식 브랜드 가이드라인) */}
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true">
              <path
                fillRule="evenodd"
                clipRule="evenodd"
                d="M10 2C5.589 2 2 4.807 2 8.246c0 2.176 1.373 4.09 3.455 5.211L4.66 16.5a.3.3 0 00.44.329L8.5 14.47c.492.07.993.106 1.5.106 4.411 0 8-2.807 8-6.33C18 4.807 14.411 2 10 2z"
                fill={KAKAO_BROWN}
              />
            </svg>
            카카오 간편인증 (L1)
          </Button>

          {/* ── 네이버 OIDC (UI 준비 — 브로커 미구현) ── */}
          <Button
            block
            size="large"
            icon={<MobileOutlined />}
            onClick={() => handleAuthSelect('NAVER_OIDC')}
            style={{ textAlign: 'left', height: 52 }}
            disabled
          >
            네이버 간편인증 (L1) — 준비 중
          </Button>

          <Divider style={{ margin: '8px 0' }}>
            <Text type="secondary" style={{ fontSize: 12 }}>보안 인증 (고수준)</Text>
          </Divider>

          <Button
            block
            size="large"
            icon={<MobileOutlined />}
            onClick={() => handleAuthSelect('PASS')}
            style={{ textAlign: 'left', height: 52 }}
          >
            PASS 인증 (L2)
          </Button>

          <Divider style={{ margin: '8px 0' }} />

          <Button
            block
            size="large"
            icon={<KeyOutlined />}
            onClick={() => handleAuthSelect('FINANCIAL_CERT')}
            style={{ textAlign: 'left', height: 52 }}
          >
            공동인증서 (L3)
          </Button>

          <Button
            block
            size="large"
            icon={<BankOutlined />}
            onClick={() => handleAuthSelect('GPKI')}
            style={{ textAlign: 'left', height: 52 }}
          >
            행정전자서명 GPKI (L3)
          </Button>

        </Space>
      </Card>

      {returnUrl && (
        <Text
          type="secondary"
          style={{ display: 'block', textAlign: 'center', marginTop: 16, fontSize: 12 }}
        >
          인증 완료 후 서비스로 돌아갑니다.
        </Text>
      )}

      {/* 카카오 OIDC 흐름 안내 */}
      <Card
        size="small"
        style={{ marginTop: 16, borderRadius: 8, backgroundColor: '#fafafa' }}
        bordered={false}
      >
        <Text type="secondary" style={{ fontSize: 11 }}>
          카카오 간편인증 클릭 시 카카오 로그인 페이지로 이동합니다.<br />
          카카오 로그인 완료 후 자동으로 인증이 처리됩니다.
        </Text>
      </Card>

    </div>
  );
};

export default LoginPage;
