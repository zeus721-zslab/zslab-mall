# zslab-mall Frontend

Nuxt 4 SSR(구매자 `app/` · 관리자 `layers/admin` · 판매자 `layers/seller`). 처음 띄우는 절차는 루트 [README.md "로컬 실행"](../README.md#로컬-실행)을 따른다.

## 로컬 개발 컨테이너 (zslab-mall 전용·FE-24)

dev 스택은 리포 루트에서 `docker compose -f docker-compose.mall.yml -f docker-compose.dev.yml up -d`로 기동한다(운영은 `docker-compose.mall.yml` 단독).
frontend 컨테이너 `zslab_mall_frontend`는 dev 전용 이미지 `zslab-mall-frontend-dev`(`Dockerfile.dev`·CMD `pnpm dev`)로 뜨며,
소스는 bind-mount, `node_modules`는 익명 볼륨(재생성 시 승계)이다. 모든 pnpm 명령은 컨테이너 안에서 실행한다(`docker exec zslab_mall_frontend pnpm …`).

- `--build`는 수동: `Dockerfile.dev`·`package.json`(pnpm/Playwright 버전 등)·`pnpm-lock.yaml` 변경 시
  `docker compose -f docker-compose.mall.yml -f docker-compose.dev.yml up -d --build zslab_mall_frontend`. 평시 `up -d`는 기존 이미지 재사용.
- lockfile만 바뀐 경우 컨테이너 안 `pnpm i`(install)로도 충분하다(익명 볼륨은 이미지의 node_modules를 최초 생성 시에만 복사).
- `layers/` 신설·auto-import 대상(store·plugin·컴포넌트 디렉토리) 추가 후에는 dev 서버가 재스캔하지 않으므로 `docker restart zslab_mall_frontend`.
- 실행 중 dev 서버에서 `pnpm typecheck`(nuxt prepare)를 돌리면 `.nuxt` 재생성으로 `#app-manifest` 오류가 날 수 있다 → typecheck 후 컨테이너 restart.
- Playwright: `pnpm test:e2e`(e2e/ 전체 — smoke·관리자 admin-*·셀러 seller-shell·구매자 claims·password-change). 세션은 `e2e/helpers/login.ts`가 BE 로그인 API로 심으며,
  역할별 자격증명 `ADMIN_E2E_EMAIL`/`ADMIN_E2E_PASSWORD`·`SELLER_E2E_EMAIL`/`SELLER_E2E_PASSWORD`·`BUYER_E2E_EMAIL`/`BUYER_E2E_PASSWORD`를 `docker exec -e`로 넘길 때만 해당 케이스가 실행된다(미설정 skip).
  셀러 비밀번호 변경(seller-password)은 실제로 비밀번호를 바꿨다가 원복하므로 전용 계정 `SELLER_PASSWORD_E2E_EMAIL`/`SELLER_PASSWORD_E2E_PASSWORD`(SELLER_E2E_*·데모 계정과 다른 계정)로만 실행된다.
  데모 로그인 버튼 자체 검증(admin-shell ⑥·seller-shell ⑤·password-change ③)은 컨테이너의 `NUXT_*_DEMO_*`만 있으면 된다. 브라우저·OS 의존성은 이미지에 포함.
- 사용자 화면 픽셀 회귀(`e2e/tools/pixel.mjs`): `pnpm pixel capture <name>` → `playwright-report/pixel-baseline/<name>/`(gitignored) 12장 저장,
  `pnpm pixel compare <base> <name>` → 상이 픽셀 수 출력·차이가 있으면 `<name>-diff/`에 빨강 마킹 PNG, exit 1. 기준선은 변경 전(또는 main) 상태에서 1회 캡처해 둔다.

## 호스트 `frontend/node_modules` (IDE 타입 인식용)

- **기준이 아니다.** 실행·테스트·타입 검사는 컨테이너의 익명 볼륨 `node_modules`만 쓴다. 호스트의 `frontend/node_modules`는 컨테이너와 따로 놓여 있어 의존성이 바뀌어도 저절로 갱신되지 않는다.
- IDE가 옛 버전 타입을 보이면(예: 호스트 `node_modules/nuxt/package.json`의 version이 `pnpm-lock.yaml`과 다름) 컨테이너 것을 호스트로 복사해 갱신한다. 저장소 루트에서 **Git Bash**로 실행한다(PowerShell 5.1 파이프는 바이너리를 깨뜨린다).

  ```bash
  rm -rf frontend/node_modules
  MSYS_NO_PATHCONV=1 docker exec zslab_mall_frontend tar -chf - -C /app node_modules | tar -xf - -C frontend
  ```

  - `tar -h`로 심볼릭 링크를 실제 파일로 풀어 복사한다. 컨테이너의 pnpm 링크는 Windows로 그대로 옮길 수 없다(`docker cp`는 링크 생성 권한 오류로 실패하고, 컨테이너가 bind-mount에 만든 링크는 Windows 프로그램이 따라가지 못한다).
  - 수 분 걸린다. 복사가 끝나면 `frontend/node_modules/nuxt/package.json`의 version이 `pnpm-lock.yaml`의 nuxt 버전과 같은지 확인한다.
