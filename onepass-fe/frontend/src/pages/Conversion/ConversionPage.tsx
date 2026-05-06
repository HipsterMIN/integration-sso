// ══════════════════════════════════════════════════════════════════════════════
// 인증 전환 흐름 — 7단계 (설계서 12.2절)
//
// Step1: 인증 수단 확인 / 동의
// Step2: 본인확인 (QIM 조회)
// Step3: 인증 수행 (Q-Sign → IdP)
// Step4: 인증 결과 확인
// Step5: 세션 생성 (BFF feSessionId 발급)
// Step6: IdO Handoff 발행
// Step7: 기관 returnUrl 이동
// ══════════════════════════════════════════════════════════════════════════════
import React, { useState } from 'react';
import { Routes, Route, useNavigate, useLocation } from 'react-router-dom';
import { Steps, Card, Typography, Button, Space, Result, Spin } from 'antd';
import {
  UserOutlined,
  SafetyCertificateOutlined,
  CheckCircleOutlined,
  LoadingOutlined,
} from '@ant-design/icons';

const { Title, Text } = Typography;

// ── Step 컴포넌트들 ─────────────────────────────────────────────────────────

const Step1Intro: React.FC<{ onNext: () => void }> = ({ onNext }) => (
  <Card style={{ borderRadius: 12 }}>
    <Space direction="vertical" style={{ width: '100%' }} size={16}>
      <Title level={4} style={{ margin: 0 }}>서비스 이용 동의</Title>
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

const Step2IdVerify: React.FC<{ onNext: () => void }> = ({ onNext }) => (
  <Card style={{ borderRadius: 12 }}>
    <Space direction="vertical" style={{ width: '100%' }} size={16}>
      <Title level={4} style={{ margin: 0 }}>본인확인</Title>
      <Text>선택하신 인증 수단으로 본인 확인을 진행합니다.</Text>
      <Button type="primary" block size="large" onClick={onNext}
        icon={<SafetyCertificateOutlined />}>
        인증 진행
      </Button>
    </Space>
  </Card>
);

const Step3AuthExecute: React.FC<{ onNext: () => void }> = ({ onNext }) => {
  const [loading, setLoading] = React.useState(false);
  const handleAuth = async () => {
    setLoading(true);
    // 실제 Q-Sign API 호출 위치 — PoC에서는 타임아웃 후 성공 처리
    await new Promise(r => setTimeout(r, 1500));
    setLoading(false);
    onNext();
  };
  return (
    <Card style={{ borderRadius: 12 }}>
      <Space direction="vertical" style={{ width: '100%', textAlign: 'center' }} size={16}>
        <Title level={4} style={{ margin: 0 }}>인증 수행 중</Title>
        {loading ? (
          <Spin indicator={<LoadingOutlined style={{ fontSize: 40 }} spin />} />
        ) : (
          <Button type="primary" block size="large" onClick={handleAuth}>
            인증 요청
          </Button>
        )}
      </Space>
    </Card>
  );
};

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

const Step5Session: React.FC<{ onNext: () => void }> = ({ onNext }) => {
  React.useEffect(() => {
    // PoC: 세션 생성 처리 후 자동 이동
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

const Step6Handoff: React.FC<{ onNext: () => void }> = ({ onNext }) => {
  React.useEffect(() => {
    // PoC: IdO Handoff 발행 시뮬레이션
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

const Step7Complete: React.FC = () => {
  const location = useLocation();
  const returnUrl = (location.state as any)?.returnUrl;
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

// ── 메인 ConversionPage ────────────────────────────────────────────────────
const STEPS = [
  { title: '동의', icon: <UserOutlined /> },
  { title: '본인확인', icon: <UserOutlined /> },
  { title: '인증', icon: <SafetyCertificateOutlined /> },
  { title: '결과', icon: <CheckCircleOutlined /> },
  { title: '세션', icon: <LoadingOutlined /> },
  { title: 'Handoff', icon: <LoadingOutlined /> },
  { title: '완료', icon: <CheckCircleOutlined /> },
];

const ConversionPage: React.FC = () => {
  const [currentStep, setCurrentStep] = useState(0);
  const navigate = useNavigate();

  const next = () => {
    if (currentStep < STEPS.length - 1) {
      setCurrentStep(prev => prev + 1);
    }
  };

  const stepComponents = [
    <Step1Intro onNext={next} />,
    <Step2IdVerify onNext={next} />,
    <Step3AuthExecute onNext={next} />,
    <Step4Result onNext={next} />,
    <Step5Session onNext={next} />,
    <Step6Handoff onNext={next} />,
    <Step7Complete />,
  ];

  return (
    <div className="onepass-page-container" style={{ paddingTop: 24 }}>
      <Steps
        current={currentStep}
        items={STEPS}
        size="small"
        style={{ marginBottom: 24 }}
        responsive={false}
      />
      {stepComponents[currentStep]}
    </div>
  );
};

export default ConversionPage;
