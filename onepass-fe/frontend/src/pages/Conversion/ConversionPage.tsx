// ══════════════════════════════════════════════════════════════════════════════
// ConversionPage — 인증 전환 7단계 흐름
// 설계서 §12.2 참조
//
// 카카오 OIDC 의 경우 이 페이지를 거치지 않는다.
//   → LoginPage 에서 window.location.href = '/api/v1/broker/kakao/authorize'
//   → 카카오 인증 완료 후 returnUrl 로 직접 이동 (q-sign → ido → 기관)
//
// 이 페이지는 PASS / 금융인증서 / GPKI 등 비OIDC 수단에 사용.
// Step3(인증 수행) 에서만 authMethod 에 따라 다른 UI 를 보여준다.
// ══════════════════════════════════════════════════════════════════════════════
import React, { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Steps, Card, Typography, Button, Space, Result, Spin, Alert, Tag } from 'antd';
import {
  UserOutlined,
  SafetyCertificateOutlined,
  CheckCircleOutlined,
  LoadingOutlined,
  MobileOutlined,
  KeyOutlined,
} from '@ant-design/icons';
import { checkSession } from '@/api/session';

const { Title, Text } = Typography;

// ── Step 1: 서비스 이용 동의 ────────────────────────────────────────────────
const Step1Intro: React.FC<{ authMethod?: string; onNext: () => void }> = ({
  authMethod,
  onNext,
}) => (
  <Card style={{ borderRadius: 12 }}>
    <Space direction="vertical" style={{ width: '100%' }} size={16}>
      <Title level={4} style={{ margin: 0 }}>서비스 이용 동의</Title>
      {authMethod && (
        <Tag color="blue">{authMethod}</Tag>
      )}
      <Text>OnePass 통합인증 서비스를 이용하여 본인 확인을 진행합니다.</Text>
      <Text type="secondary" style={{ fontSize: 12 }}>
        수집항목: 성명, 연락처, 인증수단 정보<br />
        목적: 본인확인 및 서비스 연계<br />
        보관기간: 서비스 이용 종료 시
      </Text>
      <Button type="primary" block size="large" onClick={onNext}>
        동의하고 진행
      </Button>
    </Space>
  </Card>
);

// ── Step 2: 본인 확인 ────────────────────────────────────────────────────────
const Step2IdVerify: React.FC<{ onNext: () => void }> = ({ onNext }) => (
  <Card style={{ borderRadius: 12 }}>
    <Space direction="vertical" style={{ width: '100%' }} size={16}>
      <Title level={4} style={{ margin: 0 }}>본인확인</Title>
      <Text>선택하신 인증 수단으로 본인 확인을 진행합니다.</Text>
      <Button
        type="primary"
        block
        size="large"
        onClick={onNext}
        icon={<SafetyCertificateOutlined />}
      >
        인증 진행
      </Button>
    </Space>
  </Card>
);

// ── Step 3: 인증 수행 (authMethod 별 분기) ───────────────────────────────────
const Step3AuthExecute: React.FC<{
  authMethod?: string;
  returnUrl?: string;
  onNext: () => void;
}> = ({ authMethod, returnUrl, onNext }) => {
  const [loading, setLoading] = React.useState(false);

  const handleAuth = async () => {
    setLoading(true);
    try {
      // 비OIDC 수단 PoC: 1.5초 딜레이 후 성공 처리
      // 실운영: 각 수단별 브로커 API 호출 (PASS: 앱 호출, 금융인증서: SDK 등)
      await new Promise(r => setTimeout(r, 1500));
      onNext();
    } finally {
      setLoading(false);
    }
  };

  return (
    <Card style={{ borderRadius: 12 }}>
      <Space direction="vertical" style={{ width: '100%', textAlign: 'center' }} size={16}>
        <Title level={4} style={{ margin: 0 }}>인증 수행 중</Title>
        <Text type="secondary">
          {authMethod === 'PASS' && '통신사 PASS 앱으로 인증 요청을 보내고 있습니다.'}
          {authMethod === 'FINANCIAL_CERT' && '금융인증서 확인 중입니다.'}
          {authMethod === 'GPKI' && '행정전자서명 인증서를 확인 중입니다.'}
          {!authMethod && '인증을 진행 중입니다.'}
        </Text>
        {loading ? (
          <Spin indicator={<LoadingOutlined style={{ fontSize: 40 }} spin />} />
        ) : (
          <Button type="primary" block size="large" onClick={handleAuth}
            icon={authMethod === 'PASS' ? <MobileOutlined /> : <KeyOutlined />}>
            인증 요청
          </Button>
        )}
      </Space>
    </Card>
  );
};

// ── Step 4: 인증 결과 확인 ──────────────────────────────────────────────────
const Step4Result: React.FC<{ onNext: () => void }> = ({ onNext }) => (
  <Card style={{ borderRadius: 12 }}>
    <Result
      status="success"
      title="인증 완료"
      subTitle="본인 확인이 완료되었습니다."
      extra={[
        <Button type="primary" key="next" size="large" onClick={onNext}
          icon={<CheckCircleOutlined />}>
          계속
        </Button>,
      ]}
    />
  </Card>
);

// ── Step 5: FE 세션 생성 ────────────────────────────────────────────────────
const Step5Session: React.FC<{ onNext: () => void }> = ({ onNext }) => {
  React.useEffect(() => {
    const timer = setTimeout(onNext, 800);
    return () => clearTimeout(timer);
  }, [onNext]);
  return (
    <Card style={{ borderRadius: 12, textAlign: 'center' }}>
      <Space direction="vertical" size={16}>
        <Spin size="large" />
        <Text>세션을 생성하고 있습니다...</Text>
      </Space>
    </Card>
  );
};

// ── Step 6: IdO Handoff 발행 ────────────────────────────────────────────────
const Step6Handoff: React.FC<{ onNext: () => void }> = ({ onNext }) => {
  React.useEffect(() => {
    const timer = setTimeout(onNext, 1000);
    return () => clearTimeout(timer);
  }, [onNext]);
  return (
    <Card style={{ borderRadius: 12, textAlign: 'center' }}>
      <Space direction="vertical" size={16}>
        <Spin size="large" />
        <Text>서비스로 연결하는 중...</Text>
      </Space>
    </Card>
  );
};

// ── Step 7: 완료 ────────────────────────────────────────────────────────────
const Step7Complete: React.FC<{ returnUrl?: string }> = ({ returnUrl }) => {
  React.useEffect(() => {
    if (returnUrl) {
      setTimeout(() => { window.location.href = returnUrl; }, 1500);
    }
  }, [returnUrl]);
  return (
    <Card style={{ borderRadius: 12 }}>
      <Result
        status="success"
        title="인증 완료"
        subTitle={returnUrl ? '잠시 후 서비스로 이동합니다.' : '인증이 완료되었습니다.'}
        icon={<CheckCircleOutlined style={{ color: '#52c41a' }} />}
      />
    </Card>
  );
};

// ── 스텝 메타 ────────────────────────────────────────────────────────────────
const STEPS = [
  { title: '동의',    icon: <UserOutlined /> },
  { title: '본인확인', icon: <UserOutlined /> },
  { title: '인증',    icon: <SafetyCertificateOutlined /> },
  { title: '결과',    icon: <CheckCircleOutlined /> },
  { title: '세션',    icon: <LoadingOutlined /> },
  { title: 'Handoff', icon: <LoadingOutlined /> },
  { title: '완료',    icon: <CheckCircleOutlined /> },
];

// ── ConversionPage 메인 ─────────────────────────────────────────────────────
const ConversionPage: React.FC = () => {
  const [currentStep, setCurrentStep] = useState(0);
  const location = useLocation();
  const navigate  = useNavigate();

  // LoginPage 에서 전달된 state
  const { returnUrl, authMethod, skipAuth } = (location.state as any) ?? {};

  // 기존 세션 유효 → step6 바로 이동
  React.useEffect(() => {
    if (skipAuth) {
      setCurrentStep(5); // Step6 (Handoff)
    }
  }, [skipAuth]);

  const next = () => {
    setCurrentStep(prev => Math.min(prev + 1, STEPS.length - 1));
  };

  const stepComponents = [
    <Step1Intro authMethod={authMethod} onNext={next} />,
    <Step2IdVerify onNext={next} />,
    <Step3AuthExecute authMethod={authMethod} returnUrl={returnUrl} onNext={next} />,
    <Step4Result onNext={next} />,
    <Step5Session onNext={next} />,
    <Step6Handoff onNext={next} />,
    <Step7Complete returnUrl={returnUrl} />,
  ];

  return (
    <div className="onepass-page-container" style={{ paddingTop: 24 }}>

      {/* 인증 수단 표시 */}
      {authMethod && (
        <div style={{ textAlign: 'center', marginBottom: 12 }}>
          <Tag color="blue" style={{ fontSize: 13 }}>{authMethod}</Tag>
        </div>
      )}

      <Steps
        current={currentStep}
        items={STEPS}
        size="small"
        style={{ marginBottom: 24 }}
        responsive={false}
      />

      {stepComponents[currentStep]}

      {/* 뒤로가기 */}
      {currentStep === 0 && (
        <Button
          block
          style={{ marginTop: 12 }}
          onClick={() => navigate('/login', { state: { returnUrl } })}
        >
          인증 수단 다시 선택
        </Button>
      )}
    </div>
  );
};

export default ConversionPage;
