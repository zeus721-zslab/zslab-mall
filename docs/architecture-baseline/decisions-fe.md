# Frontend Decisions (SoT)

프론트엔드 전용 append-only 결정 기록. 넘버링: FE-XX.

## 경계 규약
- 이 파일: 순수 FE 전용 (컴포넌트 구조·상태관리·라우팅·스타일링·빌드툴)
- API 계약·인증·공용 에러 규약 등 백엔드 거동을 건드리는 결정은 decisions.md(D-XX)에 유지, 이 파일은 ID로 참조만
- FE 결정이 BE 변경을 유발하면: BE는 D-XX, FE는 FE-XX로 각각 기록하고 상호 참조

## FE-XX 항목 형식
(D-XX와 동일: §1-A α/β/γ 옵션+채택/기각 근거, §2 결정 라운드 재진입, §8 이월 항목 or "없음")

---

## FE-01: 프론트엔드 스캐폴딩 (Nuxt 4 SSR · 컨테이너 전용)

날짜: 2026-07-08
범위: frontend/ 초기화 + dev 컨테이너 기동. gateway 도메인 라우팅·prod 배포·UI킷(shadcn-vue)·모션(motion-v) 제외(후속).
수용기준(달성): docker compose dev 기동 → http://localhost:3000 HTTP 200 / zslab_mall_frontend Up / node_modules 익명볼륨 populate.

### §1-A 갈림길·채택/기각 근거

1) 런타임 이미지
- α node:20 (스택 초안) — 기각. 실측: Node 20 EOL 2026-04-30(패치 종료). pnpm v11(Node 22.13+ 요구) 설치 불가.
- β node:22 (Maintenance LTS, EOL 2027-04) — 기각. 그린필드에 단기 라인 불필요.
- γ node:24 (Active LTS, EOL 2028-04) — 채택. 로컬 v24.16.0과 메이저 일치·pnpm v11 호환.

2) 프레임워크 버전
- α Nuxt 3 (스택 초안) — 기각. nuxi latest가 Nuxt 4 기본 생성. 그린필드 다운그레이드는 근거 없는 역행(기조 2).
- β Nuxt 4 SSR (srcDir=app/) — 채택. 현행 안정판·Vue3 기반이라 후속 UI킷/모션 호환 유지.
  ※ 04-mall-stack.md "Nuxt 3"·"node:20" 표기는 본 결정으로 Nuxt 4·node:24 동기화(로컬 SoT).

3) compose 배치
- α mall.yml prod base + dev override (backend 대칭) — 기각(현 트랙). prod Dockerfile·prod 서비스는 로컬 기동에 소비처 없음(기조 4 YAGNI).
- β dev.yml 단독 정의 — 채택. compose merge union으로 로컬 기동 성립. mall.yml·prod는 FE 배포 트랙 이연.

4) pnpm v11 빌드 스크립트 차단
- 실측: v11은 postinstall 빌드 기본 차단·package.json "pnpm" 필드 무시.
- 채택: pnpm-workspace.yaml allowBuilds(@parcel/watcher, esbuild) 명시 허용.

5) Windows 빌드 컨텍스트
- 실측: node_modules 내 Linux 심볼릭링크가 Windows→Docker 컨텍스트 전송 실패 유발.
- 채택: frontend/.dockerignore로 컨텍스트 제외, 컨테이너 install이 재생성.

6) bind-mount node_modules 은폐 — 채택: dev.yml /app/node_modules 익명볼륨(Track 62 JAR 은폐 재판 방지).

7) Windows HMR — 채택: nuxt.config vite.server.watch.usePolling=true (inotify 미전파 대응).

### §2 결정 라운드 재진입
- gateway 경유 검증은 FE-01에서 제외 확정. 실측: 로컬 gateway_nginx 가동하나 zslab-mall 서버블록이 backend(8080) 단일 라우팅·frontend(3000) location 없음. 편집 대상이 repo 밖 공유 conf·path-split은 API 경로 계약 요구 → 독립 태스크 분리.

### §8 이월(carry-over)
- gateway path-split 라우팅(frontend 3000 / backend 8080) + 현재 도메인 502 원인 규명(동일 태스크 처리).
- prod: frontend/Dockerfile(prod)·mall.yml frontend 서비스·ghcr.io 빌드/배포.
- UI킷 shadcn-vue(reka-ui)·모션 motion-v — 컴포넌트/레이아웃 단계.
- runtimeConfig public.apiBase 확정값 — gateway path-split 후.

---

