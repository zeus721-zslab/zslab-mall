# build stage
FROM node:24 AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
# Node 기본 힙 상한으로 Nuxt 빌드 OOM(exit 134) 발생, 상향 조치 — build 스테이지 한정(runtime 스테이지에는 두지 않는다)
ENV NODE_OPTIONS=--max-old-space-size=4096
RUN pnpm build

# runtime stage
FROM node:24-slim AS runtime
WORKDIR /app
ENV NODE_ENV=production HOST=0.0.0.0 PORT=3000
COPY --from=build /app/.output ./.output
EXPOSE 3000
CMD ["node", ".output/server/index.mjs"]
