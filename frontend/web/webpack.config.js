import { existsSync } from 'node:fs';
import path from 'path';
import process, { loadEnvFile } from 'node:process';
import { fileURLToPath } from 'url';
import HtmlWebpackPlugin from 'html-webpack-plugin';
import webpack from 'webpack';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const envPath = path.resolve(__dirname, '.env');

if (existsSync(envPath)) {
  loadEnvFile(envPath);
}

const backendBaseUrl = process.env.BACKEND_BASE_URL ?? 'http://localhost:8080';
const useMsw = process.env.USE_MSW ?? 'false';

export default {
  entry: './src/main.tsx',
  output: {
    filename: 'bundle.js',
    path: path.resolve(__dirname, 'dist'),
    publicPath: '/',
    clean: true,
  },
  module: {
    rules: [
      {
        test: /\.(ts|tsx)$/,
        exclude: /node_modules/,
        use: [
          {
            loader: 'babel-loader',
          },
        ],
      },
      {
        test: /\.(png|svg|webp)$/i,
        type: 'asset/resource',
      },
    ],
  },
  devServer: {
    port: 5173,
    open: true,
    hot: true,
    historyApiFallback: true,
    proxy: [
      {
        context: ['/api', '/oauth2', '/login/oauth2'],
        target: backendBaseUrl,
      },
    ],
    client: {
      overlay: true,
    },
  },
  resolve: {
    extensions: ['.tsx', '.ts', '.js'],
  },
  plugins: [
    new HtmlWebpackPlugin({
      template: './index.html',
      favicon: './src/assets/icons/favicon.png',
    }),
    new webpack.DefinePlugin({
      'process.env.USE_MSW': JSON.stringify(useMsw),
    }),
  ],
};
