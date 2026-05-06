import React from 'react';
import { Result, Button } from 'antd';
import { useNavigate, useSearchParams } from 'react-router-dom';

const ERROR_MESSAGES: Record<string, string> = {
  SESSION_EXPIRED: '세션이 만료되었습니다. 다시 로그인해 주세요.',
  AUTH_FAILED: '인증에 실패하였습니다.',
  INVALID_RETURN_URL: '허용되지 않은 returnUrl 입니다.',
  DEFAULT: '오류가 발생하였습니다.',
};

const ErrorPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const code = searchParams.get('code') ?? 'DEFAULT';
  const message = ERROR_MESSAGES[code] ?? ERROR_MESSAGES['DEFAULT'];

  return (
    <div className="onepass-page-container" style={{ paddingTop: 80 }}>
      <Result
        status="error"
        title="오류 발생"
        subTitle={message}
        extra={[
          <Button type="primary" key="home" onClick={() => navigate('/login')}>
            처음으로
          </Button>,
        ]}
      />
    </div>
  );
};

export default ErrorPage;
