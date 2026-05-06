// ══════════════════════════════════════════════════════════════════════════════
// Webpack 5 Config — Onepass FE (Pure React SPA)
//
// BFF 책임은 ido(port 8083)로 이관됨. onepass-fe 는 순수 React 모듈.
//
// 프로덕션 빌드: yarn build:prod
//   - output: dist/  → Nginx 혹은 ido 정적 리소스로 서빙
//   - publicPath: /
//
// 개발 서버: yarn dev
//   - port: 3000
//   - proxy: /api/** → http://localhost:8083 (ido Spring Boot)
//   - HMR enabled
// ══════════════════════════════════════════════════════════════════════════════
'use strict';

const path = require('path');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const MiniCssExtractPlugin = require('mini-css-extract-plugin');

module.exports = (env = {}) => {
  const isProduction = env.mode === 'production';

  return {
    mode: isProduction ? 'production' : 'development',
    devtool: isProduction ? 'source-map' : 'eval-cheap-module-source-map',

    entry: path.resolve(__dirname, 'src/index.tsx'),

    output: {
      path: path.resolve(__dirname, 'dist'),
      filename: isProduction ? 'static/js/[name].[contenthash:8].js' : 'static/js/[name].js',
      chunkFilename: isProduction ? 'static/js/[name].[contenthash:8].chunk.js' : 'static/js/[name].chunk.js',
      assetModuleFilename: 'static/media/[name].[hash][ext]',
      publicPath: '/',
      clean: true,
    },

    resolve: {
      extensions: ['.tsx', '.ts', '.jsx', '.js'],
      alias: {
        '@': path.resolve(__dirname, 'src'),
        '@api': path.resolve(__dirname, 'src/api'),
        '@components': path.resolve(__dirname, 'src/components'),
        '@pages': path.resolve(__dirname, 'src/pages'),
        '@hooks': path.resolve(__dirname, 'src/hooks'),
        '@store': path.resolve(__dirname, 'src/store'),
        '@types': path.resolve(__dirname, 'src/types'),
        '@utils': path.resolve(__dirname, 'src/utils'),
        '@styles': path.resolve(__dirname, 'src/styles'),
        '@constants': path.resolve(__dirname, 'src/constants'),
      },
    },

    module: {
      rules: [
        // TypeScript / JavaScript
        {
          test: /\.(ts|tsx|js|jsx)$/,
          exclude: /node_modules/,
          use: {
            loader: 'babel-loader',
            options: {
              presets: [
                ['@babel/preset-env', { targets: 'defaults' }],
                ['@babel/preset-react', { runtime: 'automatic' }],
                '@babel/preset-typescript',
              ],
              cacheDirectory: true,
            },
          },
        },
        // SCSS / CSS
        {
          test: /\.(scss|css)$/,
          use: [
            isProduction ? MiniCssExtractPlugin.loader : 'style-loader',
            { loader: 'css-loader', options: { sourceMap: !isProduction } },
            { loader: 'sass-loader', options: { sourceMap: !isProduction } },
          ],
        },
        // Assets
        {
          test: /\.(png|svg|jpg|jpeg|gif|ico|woff|woff2|eot|ttf|otf)$/,
          type: 'asset/resource',
        },
      ],
    },

    plugins: [
      new HtmlWebpackPlugin({
        template: path.resolve(__dirname, 'public/index.html'),
        filename: 'index.html',
        favicon: path.resolve(__dirname, 'public/favicon.ico'),
        inject: true,
        minify: isProduction ? {
          removeComments: true,
          collapseWhitespace: true,
          removeRedundantAttributes: true,
        } : false,
      }),
      ...(isProduction
        ? [
            new MiniCssExtractPlugin({
              filename: 'static/css/[name].[contenthash:8].css',
              chunkFilename: 'static/css/[name].[contenthash:8].chunk.css',
            }),
          ]
        : []),
    ],

    // ── Code Splitting ──────────────────────────────────────────────────────
    optimization: {
      splitChunks: {
        chunks: 'all',
        cacheGroups: {
          vendor: {
            test: /[\\/]node_modules[\\/](react|react-dom|react-router-dom)[\\/]/,
            name: 'vendors-react',
            priority: 20,
          },
          antd: {
            test: /[\\/]node_modules[\\/]antd[\\/]/,
            name: 'vendors-antd',
            priority: 10,
          },
        },
      },
    },

    // ── Option B: Dev Server ─────────────────────────────────────────────────
    devServer: {
      port: 3000,
      host: 'localhost',
      hot: true,
      historyApiFallback: true,        // SPA 라우팅 지원
      open: false,
      compress: true,
      client: {
        overlay: { errors: true, warnings: false },
      },
      // /api, /actuator 요청을 IdO(ido Spring Boot, port 8083)로 프록시
      // BFF 책임이 ido 모듈로 이관됨
      proxy: [
        {
          context: ['/api', '/actuator'],
          target: 'http://localhost:8083',
          changeOrigin: true,
          secure: false,
          logLevel: 'warn',
        },
      ],
    },

    performance: {
      hints: isProduction ? 'warning' : false,
      maxAssetSize: 512 * 1024,
      maxEntrypointSize: 1024 * 1024,
    },
  };
};
