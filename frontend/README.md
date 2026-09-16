# Nuxt Minimal Starter

Look at the [Nuxt documentation](https://nuxt.com/docs/getting-started/introduction) to learn more.

## Setup

Make sure to install dependencies:

```bash
# npm
npm install

# pnpm
pnpm install

# yarn
yarn install

# bun
bun install
```

## Development Server

Start the development server on `http://localhost:3000`:

```bash
# npm
npm run dev

# pnpm
pnpm dev

# yarn
yarn dev

# bun
bun run dev
```

## Production

Build the application for production:

```bash
# npm
npm run build

# pnpm
pnpm build

# yarn
yarn build

# bun
bun run build
```

Locally preview production build:

```bash
# npm
npm run preview

# pnpm
pnpm preview

# yarn
yarn preview

# bun
bun run preview
```

Check out the [deployment documentation](https://nuxt.com/docs/getting-started/deployment) for more information.

## 로컬 개발 컨테이너 (zslab-mall 전용·FE-24)

dev 스택은 리포 루트에서 `docker compose -f docker-compose.mall.yml -f docker-compose.dev.yml up -d`로 기동한다(운영은 `docker-compose.mall.yml` 단독).
frontend 컨테이너 `zslab_mall_frontend`는 dev 전용 이미지 `zslab-mall-frontend-dev`(`Dockerfile.dev`·CMD `pnpm dev`)로 뜨며,
소스는 bind-mount, `node_modules`는 익명 볼륨(재생성 시 승계)이다. 모든 pnpm 명령은 컨테이너 안에서 실행한다(`docker exec zslab_mall_frontend pnpm …`).

- `--build`는 수동: `Dockerfile.dev`·`package.json`(pnpm/Playwright 버전 등)·`pnpm-lock.yaml` 변경 시
  `docker compose -f docker-compose.mall.yml -f docker-compose.dev.yml up -d --build zslab_mall_frontend`. 평시 `up -d`는 기존 이미지 재사용.
- lockfile만 바뀐 경우 컨테이너 안 `pnpm install`로도 충분하다(익명 볼륨은 이미지의 node_modules를 최초 생성 시에만 복사).
- `layers/` 신설·auto-import 대상(store·plugin·컴포넌트 디렉토리) 추가 후에는 dev 서버가 재스캔하지 않으므로 `docker restart zslab_mall_frontend`.
- 실행 중 dev 서버에서 `pnpm typecheck`(nuxt prepare)를 돌리면 `.nuxt` 재생성으로 `#app-manifest` 오류가 날 수 있다 → typecheck 후 컨테이너 restart.
- Playwright: `pnpm test:e2e`(smoke + admin-shell). 관리자 케이스는 `ADMIN_E2E_EMAIL`/`ADMIN_E2E_PASSWORD` env를 `docker exec -e`로 넘길 때만 실행된다. 브라우저·OS 의존성은 이미지에 포함.
- 사용자 화면 픽셀 회귀(`e2e/tools/pixel.mjs`): `pnpm pixel capture <name>` → `playwright-report/pixel-baseline/<name>/`(gitignored) 12장 저장,
  `pnpm pixel compare <base> <name>` → 상이 픽셀 수 출력·차이가 있으면 `<name>-diff/`에 빨강 마킹 PNG, exit 1. 기준선은 변경 전(또는 main) 상태에서 1회 캡처해 둔다.
