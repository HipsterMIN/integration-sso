// ══════════════════════════════════════════════════════════════════════════════
// 로그인 진입점 페이지
// 설계서 12.2 / 12.3절
//
// 진입 흐름:
//   1. 딥링크 / 기관 리다이렉트 → returnUrl 파라미터 추출
//   2. BFF /api/v1/session/check 호출 → 기존 세션 유효성 확인
//   3. 유효한 세션 있으면 → IdO Handoff 직행
//   4. 세션 없으면 → 인증 수단 선택 (ConversionPage Step 1)
// ══════════════════════════════════════════════════════════════════════════════
import React, { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Card, Typography, Button, Space, Divider, Spin } from 'antd';
import {
  MobileOutlined,
  SafetyCertificateOutlined,
  BankOutlined,
  KeyOutlined,
} from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { checkSession } from '@/api/session';

const { Title, Text } = Typography;

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
      <Card title="인증 수단 선택" bordered={false} style={{ borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,0.08)' }}>
        <Space direction="vertical" style={{ width: '100%' }} size={12}>

          <Button
            block
            size="large"
            icon={<MobileOutlined />}
            onClick={() => handleAuthSelect('KAKAO_OIDC')}
            style={{ textAlign: 'left', height: 52 }}
          >
            카카오 간편인증 (L1)
          </Button>

          <Button
            block
            size="large"
            icon={<MobileOutlined />}
            onClick={() => handleAuthSelect('NAVER_OIDC')}
            style={{ textAlign: 'left', height: 52 }}
          >
            네이버 간편인증 (L1)
          </Button>

          <Divider style={{ margin: '8px 0' }} />

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
        <Text type="secondary" style={{ display: 'block', textAlign: 'center', marginTop: 16, fontSize: 12 }}>
          인증 완료 후 서비스로 돌아갑니다.
        </Text>
      )}
    </div>
  );
};

export default LoginPage;
