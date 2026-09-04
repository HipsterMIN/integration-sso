export interface TelemetryDashboardConfig {
	[key: string]: TelemetryDashboardVariable;
}

export interface TelemetryPipelines {
	pipelines: TelemetryDashboardConfig;
	filters?: string[];
}

export interface TelemetryDashboardVariable {
	pipeline?: string[];
	receivers: string[];
	processors: string[];
	exporters: string[];
}

export interface TelemetryDashboard {
	name: string;
	info: string;
}
