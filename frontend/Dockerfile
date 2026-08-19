FROM node:24-bookworm-slim AS builder

WORKDIR /workspace

RUN corepack enable \
    && corepack prepare pnpm@11.20.0 --activate

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY web/package.json ./web/package.json

RUN pnpm install --frozen-lockfile

COPY web ./web

WORKDIR /workspace/web

RUN pnpm check

FROM nginx:1.29-alpine3.22 AS runtime

COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /workspace/web/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/health || exit 1
