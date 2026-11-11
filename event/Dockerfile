# Dev EC2(ARM64)에서 직접 빌드한다. sharp는 linuxmusl-arm64 사전 빌드 바이너리를 사용한다.
FROM node:24-alpine

WORKDIR /app
ENV NODE_ENV=production \
    NODE_OPTIONS=--max-old-space-size=160 \
    EVENT_HOST=0.0.0.0 \
    EVENT_ASSETS_DIR=/app/assets \
    EVENT_RESULT_DIR=/data/results

COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && npm cache clean --force

COPY server.mjs ./
COPY src ./src
COPY public ./public

# 백엔드와 같은 UID/GID: WIF 파일(root:10001, 640)을 그룹 권한으로 읽는다
RUN mkdir -p /data/results && chown -R 10001:10001 /data
USER 10001:10001

EXPOSE 3100
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:3100/health >/dev/null || exit 1
CMD ["node", "server.mjs"]
