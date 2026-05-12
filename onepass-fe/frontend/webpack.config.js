/* eslint-disable @typescript-eslint/no-var-requires */
// shared config (dev and prod)
const { resolve } = require('path');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const { sentryWebpackPlugin } = require('@sentry/webpack-plugin');
const portFinderSync = require('portfinder-sync');
const dotenv = require('dotenv');
const webpack = require('webpack');
const TsconfigPathsPlugin = require('tsconfig-paths-webpack-plugin');
const { BundleAnalyzerPlugin } = require('webpack-bundle-analyzer');

const appEnv = process.env.APP_ENV || 'local';
const envFile = appEnv === 'local' ? '.env' : `.env.${appEnv}`;
dotenv.config({ path: resolve(__dirname, envFile) });

console.log(resolve(__dirname, './src/'));

const cssLoader = 'css-loader';
const sassLoader = 'sass-loader';
const styleLoader = 'style-loader';

const plugins = [
	new HtmlWebpackPlugin({
		template: 'src/index.html.ejs',
		INTERCOM_APP_ID: process.env.INTERCOM_APP_ID,
		SEGMENT_ID: process.env.SEGMENT_ID,
		POSTHOG_KEY: process.env.POSTHOG_KEY,
		SENTRY_AUTH_TOKEN: process.env.SENTRY_AUTH_TOKEN,
		SENTRY_ORG: process.env.SENTRY_ORG,
		SENTRY_PROJECT_ID: process.env.SENTRY_PROJECT_ID,
		SENTRY_DSN: process.env.SENTRY_DSN,
		TUNNEL_URL: process.env.TUNNEL_URL,
		TUNNEL_DOMAIN: process.env.TUNNEL_DOMAIN,
	}),
	new webpack.ProvidePlugin({
		process: 'process/browser',
	}),
	new webpack.DefinePlugin({
		'process.env': JSON.stringify({
			NODE_ENV: process.env.NODE_ENV,
			FRONTEND_API_ENDPOINT: process.env.FRONTEND_API_ENDPOINT,
			WEBSOCKET_API_ENDPOINT: process.env.WEBSOCKET_API_ENDPOINT,
			INTERCOM_APP_ID: process.env.INTERCOM_APP_ID,
			SEGMENT_ID: process.env.SEGMENT_ID,
			POSTHOG_KEY: process.env.POSTHOG_KEY,
			SENTRY_AUTH_TOKEN: process.env.SENTRY_AUTH_TOKEN,
			SENTRY_ORG: process.env.SENTRY_ORG,
			SENTRY_PROJECT_ID: process.env.SENTRY_PROJECT_ID,
			SENTRY_DSN: process.env.SENTRY_DSN,
			TUNNEL_URL: process.env.TUNNEL_URL,
			TUNNEL_DOMAIN: process.env.TUNNEL_DOMAIN,
			FARO_COLLECTOR_URL: process.env.FARO_COLLECTOR_URL,
			FARO_TENANT_ID: process.env.FARO_TENANT_ID,
			QSIGN_BASE_URL: process.env.QSIGN_BASE_URL,
			QSIGN_REALM: process.env.QSIGN_REALM,
			QSIGN_CLIENT_ID: process.env.QSIGN_CLIENT_ID,
			BE_API_KEY: process.env.BE_API_KEY,
			BE_API_ENDPOINT: process.env.BE_API_ENDPOINT,
			EASYSIGN_URL: process.env.EASYSIGN_URL,
			EASYSIGN_ORIGIN: process.env.EASYSIGN_ORIGIN,
		}),
	}),
	sentryWebpackPlugin({
		authToken: process.env.SENTRY_AUTH_TOKEN,
		org: process.env.SENTRY_ORG,
		project: process.env.SENTRY_PROJECT_ID,
	}),
];

if (process.env.BUNDLE_ANALYSER === 'true') {
	plugins.push(new BundleAnalyzerPlugin({ analyzerMode: 'server' }));
}

/**
 * @type {import('webpack').Configuration}
 */
const config = {
	mode: 'development',
	devtool: 'source-map',
	entry: resolve(__dirname, './src/index.tsx'),
	devServer: {
		historyApiFallback: {
			index: '/',
			disableDotRule: true,
		},
		open: '/',
		hot: true,
		liveReload: true,
		port: portFinderSync.getPort(3301),
		static: {
			directory: resolve(__dirname, 'public'),
			publicPath: '/',
			watch: true,
		},
		allowedHosts: 'all',
		// API 엔드포인트가 설정되어 있을 때만 proxy 활성화
		proxy: {
			'/api/ext': {
				// B-5: EXT_API_KEY FE 번들 노출 제거 — ido(8083)가 서버사이드 X-Ext-Api-Key 주입
				target: process.env.IDO_BASE_URL || 'http://localhost:8083',
				changeOrigin: true,
				secure: false,
			},
			'/api': {
				target: process.env.BE_API_TARGET || 'http://localhost:9292',
				changeOrigin: true,
				secure: false,
				onProxyReq(proxyReq) {
					proxyReq.setHeader('X-BE-API-Key', process.env.BE_API_KEY || '');
				},
			},
			'/bizezauth-api-dev': {
				target: 'https://www.smes.go.kr',
				changeOrigin: true,
				secure: false,
				onProxyReq(proxyReq) {
					proxyReq.removeHeader('origin');
					proxyReq.removeHeader('referer');
				},
			},
			'/faro': {
				target: 'https://faro.smes-tipa.go.kr',
				changeOrigin: true,
				secure: false,
				pathRewrite: { '^/faro': '' },
			},
		},
	},
	target: 'web',
	output: {
		path: resolve(__dirname, './build'),
		publicPath: '/',
	},
	resolve: {
		extensions: ['.ts', '.tsx', '.js', '.jsx'],
		plugins: [new TsconfigPathsPlugin({})],
		fallback: { 'process/browser': require.resolve('process/browser') },
	},
	module: {
		rules: [
			{
				test: [/\.jsx?$/, /\.tsx?$/],
				use: ['babel-loader'],
				exclude: /node_modules/,
			},
			// Add a rule for Markdown files using raw-loader
			{
				test: /\.md$/,
				use: 'raw-loader',
			},
			{
				test: /\.css$/,
				use: [
					styleLoader,
					{
						loader: cssLoader,
						options: {
							modules: true,
						},
					},
				],
			},
			{
				test: /\.(jpe?g|png|gif|svg)$/i,
				use: [
					'file-loader?hash=sha512&digest=hex&name=img/[chunkhash].[ext]',
					'image-webpack-loader?bypassOnDebug&optipng.optimizationLevel=7&gifsicle.interlaced=false',
				],
			},
			{
				test: /\.(ttf|eot|woff|woff2)$/,
				use: ['file-loader'],
			},
			{
				test: /\.less$/i,
				use: [
					{
						loader: styleLoader,
					},
					{
						loader: cssLoader,
						options: {
							modules: true,
						},
					},
					{
						loader: 'less-loader',
						options: {
							lessOptions: {
								javascriptEnabled: true,
							},
						},
					},
				],
			},
			{
				test: /\.s[ac]ss$/i,
				use: [
					// Creates `style` nodes from JS strings
					styleLoader,
					// Translates CSS into CommonJS
					// cssLoader,
					{
						loader: cssLoader,
						options: {
							url: false,
						},
					},
					// Compiles Sass to CSS
					sassLoader,
				],
			},
		],
	},
	plugins,
	performance: {
		hints: false,
	},
	optimization: {
		minimize: false,
	},
};

module.exports = config;
