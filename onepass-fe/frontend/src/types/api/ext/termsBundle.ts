export interface TermContent {
	format: 'HTML' | 'MARKDOWN' | 'PLAIN';
	body: string;
	toc: string[];
}

export interface Term {
	docCode: string;
	required: boolean;
	displayName: string;
	shortSummary: string;
	versionId: number;
	seq: number;
	effectiveFrom: string;
	content: TermContent;
	contentHash: string;
	pdfUrl: string | null;
	translationsAvailable: string[];
}

export interface TermsBundleResponse {
	bundleVersion: string;
	requiredOrder: string[];
	optionalOrder: string[];
	terms: Term[];
	submitEndpoint: string;
}
