import { Select } from 'antd';
import { useTranslation } from 'react-i18next';

function Translation(): JSX.Element {
	const { i18n } = useTranslation();
	const { t } = useTranslation(['languages']);

	const locales = [{ lang: 'en' }, { lang: 'ko' }, { lang: 'jp' }];

	const defaultValue = localStorage.getItem('languages') as string;

	// eslint-disable-next-line @typescript-eslint/explicit-function-return-type
	const handleSelectChange = (value: string) => {
		i18n.changeLanguage(value);
		localStorage.setItem('languages', value);
	};

	return (
		<div>
			<Select
				value={defaultValue == null ? t(`${'en'}`) : t(`${defaultValue}`)}
				onChange={handleSelectChange}
			>
				{locales.map((item) => (
					// eslint-disable-next-line react/jsx-key, react/jsx-no-undef
					<Select.Option value={item.lang}>{t(`${item.lang}`)}</Select.Option>
				))}
			</Select>
		</div>
	);
}

export default Translation;
