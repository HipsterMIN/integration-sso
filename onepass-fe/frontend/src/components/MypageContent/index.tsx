import { ReactNode } from 'react';

interface MypageContentProps {
	children: ReactNode;
}

function MypageContent({ children }: MypageContentProps): JSX.Element {
	return <div className="sub-body">{children}</div>;
}

export default MypageContent;
