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

## FE-02: gateway 경로 분기 계약 (안 A · 단일 도메인 path-split)

날짜: 2026-07-08
선행: FE-01 §8 이월(gateway path-split·public.apiBase 확정) 해소
범위: 단일 도메인에서 frontend/backend 경로 분기 확정 + Nuxt SSR/브라우저 API base 이원화 확정. backend 무변경.
수용기준(로컬 달성): https://zslab-mall.duckdns.org/ → Nuxt 200 / /api/v1/* → backend 도달(401·라우팅 정상) / gateway healthy.

### §1-A 갈림길·채택/기각 근거

1) 경로 분기 방식
- α 단일 도메인 path-split (location /api/→backend, /→frontend) — 채택. 도메인 1·인증서 1·server block 1, CORS 불필요(동일 Origin), 운영 부담 최소(기조 1). 외부 검토 2회 통과.
- β 서브도메인 분리 (api.zslab-mall…) — 기각. DNS 레코드·별도 인증서·server block 추가로 단일 운영자 부담↑. 서비스 다분화 규모에서야 이득.
- γ 현행 유지(backend 단일) — 기각. frontend 도메인 접근 불가 방치.

2) /api prefix 보장 위치
- α gateway rewrite — 기각. 외부/내부 URL 이중 계약·rewrite 규칙 증식(Swagger·actuator·OAuth 콜백 확장 시).
- β backend @RequestMapping — 채택(=현상 유지). 실측: 전 컨트롤러가 이미 /api/v1/* + /api/webhooks/* 계약 준수. context-path 미설정. → gateway 무rewrite·backend 무변경으로 외부=내부 계약 일치.

3) Nuxt API base 이원화 (FE-01 §8 확정)
- 브라우저(public.apiBase): "/api" 상대경로 — 동일 Origin·도메인 하드코딩 제거(절대 URL에서 정정, 별도 머지 완료).
- SSR(apiInternalBase): http://zslab_mall_backend:8080 — 도커 내부 직결로 gateway hop 생략. FE-01 값 유지.

### §2 결정 라운드 재진입
- actuator 처리: /actuator는 /api 밖 → location / 규칙상 frontend로 흘러 외부 404. 관리 엔드포인트 외부 미노출과 일치, prometheus 스크레이핑은 내부망 직결이라 무관 → 별도 deny 블록 불요.
- nginx proxy_set_header replace 트랩: http 전역 6종(Host·X-Real-IP·X-Forwarded-For·X-Forwarded-Proto·Upgrade·Connection) 상속 중. 신규 location에 헤더 1개라도 선언 시 전역 세트 전부 상실 → 두 location 모두 무선언으로 상속 유지 확정.
- proxy_pass 정적 host:port라 reload 시 DNS 1회 해석. 재시작 루프(동일 컨테이너)는 IP 유지라 무영향, 컨테이너 recreate 시 재reload 필요(운영 유의).

### §8 이월(carry-over)
- gateway conf 형상은 zslab-mall 레포 밖(gateway 프로젝트, 로컬 C:\Users\pc\projects\gateway\nginx\nginx.conf)·git 미추적. 서버 이관 시 재현 필요(서버는 root 소유·sudo 편집).
- 서버 배포 트랙: frontend prod Dockerfile·mall.yml frontend 서비스·sparse-checkout에 frontend/ 포함·서버 .env ADMIN_BOOTSTRAP 2값·서버 gateway 2분기.
- /api/v1/products 401(공개 카탈로그 GET permitAll 예상과 불일치) — 라우팅 무관·backend 별건.
- [FE-03 실측 보강] §1-A 3 'SSR 직결 zslab_mall_backend' 전제 결함 확정: 임베디드 Tomcat이 언더스코어 호스트명을 Host 검증에서 400 거부(SSR undici 직결 시). 게이트웨이 경유는 nginx Host 재작성으로 무영향이라 FE-02 검증에서 미표면화, FE-03 홈 SSR 최초 실 소비에서 표면화. 조치: backend gateway_net 별칭 mall-backend 부여·SSR base 별칭 교체(코드·nuxt.config·gateway 무변경). 상세 LT-07.

---

## FE-03: 프론트엔드 기준 컴포넌트·패턴 확립 (공통 레이아웃 셸 + 홈)

날짜: 2026-07-08
선행: FE-02(gateway path-split·API base 이원화) 완료. 별건 /api/v1/products 401 = FE-65 정찰로 C′(무효 Bearer 동봉) 규명·비결함 종결(LT-06).
범위: default 레이아웃 셸 + 홈 페이지 + 재사용 기준 컴포넌트/패턴 확립. 홈 상품 섹션은 실 API 연동. 목록·상세·검색·카테고리는 후속 트랙.
목표 재정의: "홈 화면 구현"이 아니라 "이후 모든 목록성 화면이 올라갈 기준 컴포넌트·패턴(레이아웃·ProductCard·useFetch SSR 패턴·데이터 상태 처리·SEO·반응형)을 1회 확립"한다.
수용기준(확정·구현 후 달성 갱신): default 레이아웃 적용 / Header·Footer 렌더 / HomeHero 렌더 / HomeProductGrid 실 API(/api/v1/products?sort=LATEST) 연동 / SSR·CSR 정상 / Loading·Empty·Error 처리 / ProductCard 렌더 / useSeoMeta 적용 / 반응형 3폭(모바일 2·md 3·lg 4열) 확인.

### §1-A 갈림길·채택/기각 근거

1) δ 범위
- A 레이아웃 셸만 — 기각. 가시 결과물 부재로 SSR·fetch·grid 무엇도 실증 못 함("동작하는 벽" 미형성).
- B 셸 + 홈 페이지 — 채택. 한 화면으로 셸+그리드 완결·이후 화면이 붙을 골격 확정. 외부 검토 수용(목록·상세는 URL 구조·useRoute·SEO·pagination·옵션 렌더·상태 동반으로 별 사이클 규모).
- C 셸 + 홈 + 목록 + 상세 — 기각. 레이아웃 트랙 범위 초과.

2) 홈 상품 그리드 데이터
- 가 정적 목데이터 — 기각. API가 이미 존재·검증됨. Mock은 곧 폐기될 죽은 코드(기조 4). Mock의 목적(API 부재 시 seam 확정)이 성립 안 함.
- 나 실 /api/v1/products 연동 — 채택. 검증된 계약에 실사용처 부여 + SSR/브라우저 base 이원화·gateway·docker network를 홈 단계에서 1회 실검증. 시드 부재로 빈 목록이어도 fetch→렌더 경로 확정으로 후속 목록 트랙 리스크 감소.

3) UI킷·모션·상태관리 도입 시점
- ㄱ 지금(레이아웃 단계) 도입 — 기각. 셸·홈 골격은 div·nav·Tailwind 유틸로 완성. 예상 소비처(Button·Input·Dialog·Sheet·Dropdown·Toast·Pagination)가 레이아웃 단계에 없음. motion-v는 정적 셸에 소비처 없음. 미사용 의존성(기조 4).
- ㄴ 이연 유지 — 채택. shadcn-vue·motion-v는 폼·오버레이·모션 실수요 컴포넌트 트랙에서. Pinia도 미착수(관리 상태 없음·useFetch→computed로 충분, Store 삽입 시 API→Store→Component 불필요 계층 증가).

4) 컴포넌트 분리 입도 (외부 검토 반영·일관 원칙: 실 소비처 1개면 구현만·2번째에서 promote)
- 상태 처리(Loading/Empty/Error): HomeProductGrid 내부 처리 채택. 공용 EmptyState·ErrorState·LoadingSkeleton 추출은 기각(소비처 1개·단일 사례 추상화). 두 번째 목록 화면에서 승격.
- 이미지 전략: <img loading="lazy"> 채택·ProductCard를 이미지 변경 경계로. Nuxt Image 도입 기각(변경 비용이 Card 1개로 이미 국소화·현 단계 실측 성능 이슈 없음). ProductThumbnail 래퍼도 기각(이중 경계).
- 홈 섹션 분리: HomeHero + HomeProductGrid 2분리 채택. HomeBanner 별도 분리 기각(현재 풀블리드 배너 1개=Hero. 두 번째 프로모 영역 시 분리).

5) UX 참조안 가감 (국내/해외 커머스 검토 자료에서 FE-03 실소비처 있는 것만 채택)
- 채택: Sticky Header / 검색창 강조 헤더 레이아웃 / Skeleton Loading(=Loading 상태) / ProductCard hover(상승·shadow·이미지 zoom 1.03, Tailwind transition·hover 유틸만·의존성 0) / Empty State 완성(아이콘+안내문) / Micro-interaction 150~250ms(Tailwind duration) / a11y 기본(aria-label·focus ring) / 디자인 원칙("레이아웃은 11번가풍 익숙함 + 시각 완성도는 Shopify·Vercel풍 여백·타이포").
- 기각·이연(소비처 부재): 무한스크롤(홈 신상품은 size 8 고정·스크롤 로딩 없음→목록/검색/카테고리 트랙, vueuse useInfiniteScroll) / Cart Drawer·Quick View·찜·최근 본 상품(useStorage)·리뷰·배송·쿠폰 배지(장바구니·상품·인증 도메인 연동 필요) / Breadcrumb(홈 최상위·경로 없음→상세·카테고리) / 다크모드(MVP 후순위) / vueuse·Floating UI(위 기능 도입 시 동반).
- 목록 페이징 방침: Pagination 배제·무한스크롤 채택(단 구현은 목록 트랙·홈 범위 밖).

### §2 결정 라운드 재진입
- [실측 완료] API base 결합: 브라우저 base "/api"(NUXT_PUBLIC_API_BASE 미주입 시 composable "/api" 폴백)·SSR apiInternalBase(+"/api" 부가). 양쪽 .../api/v1/products 로 결합. nuxt.config 값 무변경.
- [실측 완료] 목록 응답 카드 필드: mainImageUrl(nullable·부재 시 ProductCard placeholder 박스)·displayPrice·soldOut·name·sellerName. 봉투 items·page·size·totalCount·hasNext.
- [실측·트랩] SSR 직결 대상이 언더스코어 컨테이너명(zslab_mall_backend)이면 Tomcat Host 검증 400 → 별칭 mall-backend 로 해소(FE-02 §1-A 3 전제 결함·LT-07). backend·nuxt.config·gateway 무변경.
- [DoD 달성] 셸·Hero·Header(sticky)·Footer·SEO·SSR·실 API 연동·Empty/Error 처리 실측 확인(게이트웨이 end-to-end 200). ProductCard 실물·반응형 3폭·hover 는 코드 확정·시드 부재로 시각 미확인(상품 시드 시 확인).
- 박제 시점: 본 항목 구현·검증 완료로 수용기준 "달성" 확정.

### §8 이월(carry-over)
- [FE 후속 트랙] 목록·상세·검색·카테고리 페이지(URL 구조·useRoute·pagination 대체 무한스크롤·옵션 렌더·Breadcrumb).
- [공용 컴포넌트 승격] Loading/Empty/Error를 두 번째 목록 화면 등장 시 EmptyState·ErrorState·LoadingSkeleton으로 promote.
- [UI킷·모션·상태] shadcn-vue(Button·Input·Dialog·Sheet·Dropdown·Toast·Pagination)·motion-v·Pinia — 실수요 컴포넌트 트랙에서.
- [커머스 UX 기능] 무한스크롤(vueuse useInfiniteScroll)·Cart Drawer·찜·최근 본 상품(useStorage)·상품 카드 배지(쿠폰·BEST·NEW·배송)·Quick View·리뷰 표시·다크모드 — 각 해당 도메인 트랙.
- [이미지] 실측 성능 이슈 표면화 시 Nuxt Image 도입(ProductCard 국소 교체).
- [서버 배포 트랙·미착수] frontend prod Dockerfile·mall.yml frontend 서비스·sparse-checkout에 frontend/ 포함·서버 gateway 2분기·서버 .env ADMIN_BOOTSTRAP 2값(FE-02 §8 이관 유지).

---
