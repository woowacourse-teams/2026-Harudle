import path from 'path';
import { readFileSync } from 'node:fs';
import webpack from 'webpack';
import { fileURLToPath, URL } from 'url';
import HtmlWebpackPlugin from 'html-webpack-plugin';
import Dotenv from 'dotenv-webpack';
import CopyWebpackPlugin from 'copy-webpack-plugin';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const { version } = JSON.parse(
  readFileSync(new URL('./package.json', import.meta.url), 'utf8'),
);

export default function createWebpackConfig(_env, argv) {
  const isProduction = argv.mode === 'production';

  return {
    entry: './src/main.tsx',
    output: {
      publicPath: '/',
      filename: 'bundle.js',
      path: path.resolve(__dirname, 'dist'),
      clean: true,
    },
    devtool: isProduction ? false : 'eval-cheap-module-source-map',
    optimization: {
      minimize: isProduction,
    },
    module: {
      rules: [
        {
          test: /harudle-intro\.jpg$/i,
          type: 'asset/resource',
          generator: {
            filename: 'harudle-intro.jpg',
          },
        },
        {
          test: /\.(png|svg|jpg|jpeg|webp)$/i,
          exclude: /harudle-intro\.jpg$/i,
          type: 'asset/resource',
        },
        {
          test: /\.(ts|tsx)$/,
          exclude: /node_modules/,
          use: [
            {
              loader: 'babel-loader',
            },
          ],
        },
      ],
    },
    devServer: {
      port: 5173,
      open: true,
      hot: true,
      historyApiFallback: true,
      client: {
        overlay: true,
      },
    },
    resolve: {
      extensions: ['.tsx', '.ts', '.js'],
    },
    plugins: [
      new webpack.DefinePlugin({
        __APP_VERSION__: JSON.stringify(version),
      }),
      new HtmlWebpackPlugin({
        template: './index.html',
        favicon: './src/assets/images/favicon.png',
      }),
      new CopyWebpackPlugin({
        patterns: [
          {
            from: path.resolve(__dirname, 'manifest.json'),
            to: 'manifest.json',
          },
          {
            from: path.resolve(__dirname, 'icon-192.png'),
            to: 'icon-192.png',
          },
        ],
      }),
      new Dotenv({ systemvars: true }),
    ],
  };
}
