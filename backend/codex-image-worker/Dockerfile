FROM node:24-bookworm-slim
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && mkdir -p /home/node/.codex && chown node:node /home/node/.codex
COPY --chown=node:node *.mjs ./
USER node
ENV NODE_ENV=production HOST=0.0.0.0 PORT=8090 CODEX_HOME=/home/node/.codex
EXPOSE 8090
CMD ["node", "server.mjs"]
