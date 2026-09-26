# Frontend Decisions (SoT)

프론트엔드 전용 append-only 결정 기록. 넘버링: FE-XX.

## 경계 규약
- 이 파일: 순수 FE 전용 (컴포넌트 구조·상태관리·라우팅·스타일링·빌드툴)
- API 계약·인증·공용 에러 규약 등 백엔드 거동을 건드리는 결정은 decisions.md(D-XX)에 유지, 이 파일은 ID로 참조만
- FE 결정이 BE 변경을 유발하면: BE는 D-XX, FE는 FE-XX로 각각 기록하고 상호 참조

## FE-XX 항목 형식
(D-XX와 동일: §1-A α/β/γ 옵션+채택/기각 근거, §2 결정 라운드 재진입, §8 이월 항목 or "없음")

---

## 현행 트랙 로드맵

최신 넘버링 기준 인덱스. 과거 트랙 본문에 남은 넘버링 표기는 당시 기록으로 보존되며, 현행 순서를 한눈에 파악하도록 본 로드맵을 최신 기준으로 유지한다(새 트랙 박제 시 이 목록도 함께 갱신). append-only 원칙의 가독성 예외로 신설.

완료:
- FE-01 프론트엔드 스캐폴딩 (Nuxt 4 SSR · 컨테이너 전용)
- FE-02 gateway 경로 분기 계약 (안 A · 단일 도메인 path-split)
- FE-03 프론트엔드 기준 컴포넌트·패턴 확립 (공통 레이아웃 셸 + 홈)
- FE-04 데모 카탈로그 시드 + 나눔고딕 self-host + 실물 렌더 확정
- FE-05 상품 목록 페이지 + 상태 컴포넌트 promote + DevTools iframe 허용
- FE-06 구매자 사이트맵 전면 설계
- FE-07 디자인 파운데이션 (디자인 토큰 + 컴포넌트 전환)

신규·예정:
- FE-08 Tailwind v4 마이그레이션 (완료)
- FE-09 Global Layout (완료) — Header 확장(sticky·검색 UI·카테고리 placeholder·장바구니 뱃지·auth 분기)·auth/cart Pinia store·cart SSR 로드 plugin·shadcn-vue(reka-ui) 도입 + v4 토큰 배선. Footer=FE-03 유지(무변경). BUYER 미들웨어·로그인 페이지·검색 동작·카테고리 데이터는 소비처 부재로 이연
- FE-10a 상품상세 (완료)
- FE-10b 장바구니(완료)
- FE-11 체크아웃 (완료)
- FE-12 주문 (완료) (order-lifecycle: 조회·자동취소·종료 모델·삭제 배치)
- FE-13 계정 (완료)
- FE-14 클레임 (완료)
- FE-15 FE 테스트 (완료)
- FE-16 체크아웃 배송지 연동 (완료)
- FE-17 체크아웃 주문 요약 (완료)
- FE-18 판매중지·품절 표시와 구매 차단 (완료)
- Tier2 페이지(BE-추가작업 대응)는 각 머지 후 개별 FE 트랙(FE-15+)

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

## FE-04: 데모 카탈로그 시드 + 나눔고딕 self-host + 실물 렌더 확정

날짜: 2026-07-08
선행: FE-03(레이아웃 셸·홈·ProductCard·SSR 직결) 완료. 홈이 실 API에 연동됐으나 카탈로그가 empty(items 0)라 ProductCard 실물·반응형·hover를 시각 미확인 상태였음(FE-03 §2 DoD 잔여).
범위: (1) 노출 성립 최소 체인 데모 상품 2건을 dev 부팅 시 멱등 공급하는 백엔드 부트 시드, (2) 나눔고딕 woff2 self-host 3점 주입, (3) 그 결과 홈 카드·반응형·hover·폰트의 실물 렌더 시각 확인.
수용기준(달성): dev https://zslab-mall.duckdns.org/ → 홈 상품 카드 2건 SSR 렌더 / API /api/v1/products 200·items 2 / 반응형 2·3·4열 / 카드 hover(상승·shadow·이미지 zoom) / NanumGothic computed 적용 / 전체 백엔드 814 테스트 GREEN / 3환경(test·dev·prod) 시드 격리.

### §1-A 갈림길·채택/기각 근거

1) 시드 구현 방식
- α 부트 Runner(CommandLineRunner + count()==0 멱등 + 도메인 팩토리 경유) — 채택. SuperAdminBootstrapRunner 동형 전례 재사용. 도메인 팩토리 경유로 public_id @PrePersist·불변식·status 고정을 자동 준수. "비었을 때만" 삽입은 부트 훅이 자연(R__ repeatable 전례 없음).
- β Flyway V17 시드 SQL — 기각. ULID public_id를 SQL로 수동 생성해야 하고, CHAR(30) 고정폭 패딩 리스크·status PENDING 강제와 option 체인을 raw INSERT로 재현하며 불변식을 우회한다. 멱등성도 V 마이그레이션은 1회성이라 "비었을 때만"과 부정합.

2) 폰트 주입 지점 (self-host 확정·배치만 결정)
- α 글로벌 CSS(main.css @font-face) + tailwind.config.ts(fontFamily.sans) + nuxt.config css[] 등록 — 채택. 표준 Nuxt 관례이고 CSS와 config의 관심사가 분리된다. tailwind.config는 이후 디자인 트랙의 theme 확장에 재사용된다.
- β nuxt.config 전량 인라인 — 기각. 폰트·테마 커스터마이즈가 config 1파일에 응집돼 이후 확장 시 비대해진다.
- 폰트 소스: 공식 OFL TTF→woff2 변환. Regular 0.35MB·Bold 0.42MB(합 0.77MB)·글리프 13,297 무손실. 용량이 경미해 서브셋 미적용(원본 배치).

3) 시드 활성 범위 (구현 중 트랩 대응·STEP 1 무플래그 스펙에서 변경)
- 발견: CatalogDemoSeedRunner가 CommandLineRunner라 @SpringBootTest 기동 시에도 실행돼 싱글톤 공유 테스트 컨테이너에 데모 행을 커밋 → 전역 상태를 오염시킨다. InventoryHistoryRepositoryTest(inventory.variant_id=1 UNIQUE 충돌)·ProductRegistrationControllerIntegrationTest T9(product_option_group·product_variant 전역 count isZero 위반) 2건을 파괴.
- A 조건부 활성화(@ConditionalOnProperty catalog.demo-seed.enabled) — 채택. 시드가 모든 @SpringBootTest를 오염시키는 구조적 원인을 차단하고, 향후 전역-count/저-ID 테스트 취약성을 제거한다.
- B 무플래그 유지·깨진 테스트 2개를 시드 관용으로 수정 — 기각. 오염을 방치한 채 증상만 땜질해 향후 재발 취약성이 잔존(기조 4 근본 처치 아님).
- 무플래그 스펙 이탈 사유: SuperAdminBootstrapRunner의 무플래그 근거(admin 부재 시 로그인 데드락)는 데모 시드에 무해. 데모데이터의 dev 한정이 운영보호·테스트 무오염에 오히려 정합.

### §2 결정 라운드 재진입
- [실측·MCP 4점 교차검증] Runner: @ConditionalOnProperty(havingValue="true", matchIfMissing 미지정=기본 OFF)·productRepository.count()==0 멱등·@Transactional·도메인 팩토리 경유(Product.create→approve()로 PENDING→SALE·DEFAULT sentinel 옵션·Variant.create SALE·Inventory 재고 100). ProductVariant.create 시그니처 정합 확인.
- [실측] 3환경 격리 메커니즘: 테스트는 profile=local이라 application-local.yml enabled:true를 로드하므로, build.gradle.kts test 태스크가 systemProperty("catalog.demo-seed.enabled","false")로 상위 우선순위에서 명시 차단(테스트 OFF). dev는 local yml true로 ON. 운영은 prod 프로파일이 local.yml을 미로드하고 프로퍼티가 부재해 기본 OFF. → 데모데이터가 실 DB·공유 테스트 컨테이너에 미유입.
- [실측] 회귀: ./backend/gradlew.bat test --rerun-tasks → 814/814 GREEN(164 클래스). 파괴됐던 2건(InventoryHistoryRepositoryTest 3/3·ProductRegistrationControllerIntegrationTest 17/17) 통과.
- [실측] 폰트 적용: Preflight html{font-family:NanumGothic,…} computed 근거 + @font-face 서빙 확인.
- [FE-03 DoD 잔여 해소] ProductCard 실물 2건·반응형 2/3/4열·hover(상승·shadow·zoom)를 dev에서 시각 확인 완료 — FE-03 §2 "시드 부재로 시각 미확인" 종결.

### §8 이월(carry-over)
- [서버 배포 트랙·미착수 유지] frontend prod Dockerfile·mall.yml frontend 서비스·sparse-checkout에 frontend/ 포함·서버 gateway 2분기·서버 .env ADMIN_BOOTSTRAP 2값. FE-04는 dev 한정이라 서버 반영 시 시드 활성 정책(운영은 기본 OFF 유지·데모데이터 미유입)을 확인해야 한다.
- [폰트 서브셋] 현재 원본 woff2(0.77MB)를 배치. 실측 로딩 성능 이슈가 표면화되면 한글 서브셋 도입을 검토(현 단계 불요).
- [1차 디자인 조정] FE-03 렌더 실물 확보(FE-04)를 기준으로 한 경미한 조정 대기. 본격 디테일은 상세·목록 트랙.
- [FE 후속 트랙] 목록·상세·검색·카테고리(FE-03 §8 유지)·공용 상태 컴포넌트 promote·shadcn-vue·motion-v·Pinia(실수요 트랙).

---

## FE-05: 상품 목록 페이지 + 상태 컴포넌트 promote + DevTools iframe 허용

날짜: 2026-07-08
선행: FE-04(데모 시드·폰트·홈 실물 렌더) 완료. 홈이 상태 4종을 HomeProductGrid 내부에서 처리하며, 공용 상태 컴포넌트는 "두 번째 목록 화면에서 승격"으로 이월된 상태였음(FE-03 §8).
범위: (1) /products 목록 페이지(offset 무한스크롤·URL 쿼리 동기화·sort 4종), (2) Loading·Error·Empty 상태 컴포넌트를 common/으로 승격(홈 교체 포함), (3) DevTools iframe 허용(gateway nginx X-Frame-Options 완화·인프라 곁처리). 검색·상세·카테고리 페이지는 후속 트랙.
수용기준(달성): /products 200·상품 렌더 / sort 4종 전환·백엔드 정렬 반영 / URL 쿼리(sort·categoryId) 복원·동기화 / 무한스크롤 offset append 로직(hasNext 종료) / 공용 3종 홈·목록 양쪽 소비 / 홈 렌더 FE-04 대비 무변화 / DevTools 패널 iframe 정상.

### §1-A 갈림길·채택/기각 근거

1) useProducts 처리 (목록은 파라미터화·append 필요)
- α 목록 전용 useProductList 신규·홈 useProducts 무변경 존치 — 채택. 홈은 단발 useFetch(SSR 캐싱·고정 key), 목록은 append 누적·동적 key·무한스크롤로 성격이 상이하다. 통합 시 분기가 과다해지고, 홈 무변경으로 회귀를 0으로 유지한다.
- β useProducts 일반화·홈/목록 공용 — 기각. 성격이 다른 두 조회를 한 함수에 담으면 조건 분기가 증식한다.

2) 상태 컴포넌트 promote 입도
- α LoadingSkeleton·ErrorState·EmptyState 3종 common/ 추출·홈+목록 소비 — 채택. FE-03이 명시 이월한 "2번째 화면 승격" 지점이고, 소비처가 2개로 늘어 승격 조건을 충족한다.
- β 목록에 상태 마크업 복붙·promote 이연 — 기각. 중복 마크업을 방치한다(기조 4).
- 설계: LoadingSkeleton은 스켈레톤 카드를 count개 반복만 렌더한다(그리드 wrapper는 호출 측 소유). ErrorState는 emit('retry')로 재조회를 상위에 위임한다(재사용성). 빈/에러 판정식은 부모가 유지한다.

3) URL 쿼리 동기화 범위
- α sort·categoryId를 useRoute().query와 동기화(공유 URL·뒤로가기 보존)·page는 미반영 — 채택. 정렬·필터는 북마크/공유 대상이다. useRoute는 net-new지만 검색·카테고리 트랙의 재사용 기반이 된다.
- β 컴포넌트 로컬 상태만 — 기각. 공유 불가·표준 UX 미달.

4) 무한스크롤 방식 (정찰 확정 재확인)
- offset(page 증가·append·hasNext 종료) 채택 — 백엔드가 offset 페이징만 제공하고(커서 API 없음) PagedResponse.hasNext로 종료를 판정한다. IntersectionObserver는 브라우저 네이티브를 사용한다(외부 라이브러리 미도입·기조 4).

5) DevTools iframe 허용 위치 (별도 정찰 recon-67 D섹션)
- 완화 위치 = gateway nginx 확정 — 헤더 출처가 nginx mall vhost의 X-Frame-Options DENY(L262)이고, DevTools 자산은 frontend가 서빙해 backend를 미경유한다. 따라서 backend Spring Security 완화는 무효라 후보에서 탈락한다.
- 처리 = 로컬 gateway conf DENY→SAMEORIGIN(dev 한정) — 채택. 로컬·서버 conf가 별개 물리 파일이라 서버 prod DENY에 무영향이다(파일 분리로 dev/운영이 자연 분리). 형제 도메인 zslab/lms의 SAMEORIGIN 전례를 따른다.
- nuxt.config devtools:false로 끄기 — 기각. 사용자가 DevTools 사용을 요구했고, 끄기는 기능 제거라 요구를 충족하지 못한다.

### §2 결정 라운드 재진입
- [실측·MCP 4점 교차검증] useProductList: 초기 page 0은 useAsyncData(SSR 페이로드), items·hasNext는 data 파생 computed(별도 ref를 watch로 채우면 SSR watcher가 재실행되지 않아 빈 목록으로 렌더되는 트랩을 회피), loadMore는 $fetch append, 필터 변경 시 watch(data)로 누적분 리셋. loadMore는 3중 가드(hasNext·loadingMore·pending)를 두고 실패 시 hasNext를 닫아 observer 재요청 루프를 차단한다.
- [실측] products/index.vue: URL→상태 복원(비허용 sort는 LATEST 폴백·categoryId는 정규식 검증)·watch(sort)로 router.replace 동기화(기존 쿼리 보존·history 미증가)·sentinel은 v-if hasNext(종료 시 제거로 요청 중단)·observer 라이프사이클(watch 재관측·onBeforeUnmount disconnect).
- [실측] 홈 회귀: HomeProductGrid의 판정식·grid·max-w·SKELETON_COUNT·refresh를 유지하고 공용 3종만 교체 → SSR 카드 2건·반응형·hover·zoom 무변화(순수 리팩터).
- [실측] 목록 동작: /products 200·sort 4종 렌더 순서 정확(LATEST 후디 먼저·PRICE_ASC 티셔츠 먼저)·?sort=PRICE_ASC 복원·?categoryId=999 EmptyState 실렌더(승격 컴포넌트 동작 확증).
- [실측·트랩 2건] ① SSR 빈 목록: items를 watch(data)로 채운 초기안이 SSR watcher 미재실행으로 빈 렌더 → data 파생 computed로 교정. ② Nuxt dev 신규파일 미스캔: 실행 중 dev 서버가 신규 컴포넌트/라우트를 미스캔 → docker restart zslab_mall_frontend로 재스캔 해소(파일·config 무변경).
- [실측·인프라] nginx L262 DENY→SAMEORIGIN(WriteAllText inode 보존·D-148 준수)·nginx -t ok·reload·curl X-Frame-Options SAMEORIGIN·zslab-shop DENY 보존(무회귀)·DevTools 패널 정상.
- [명명] common/ 컴포넌트는 Nuxt pathPrefix로 CommonLoadingSkeleton·CommonErrorState·CommonEmptyState로 auto-import된다(nuxt.config 무변경).

### §8 이월(carry-over)
- [무한스크롤 다건 미검증] 시드가 2건(hasNext=false)이라 append 로직 확정까지만 검증했고, 다건 스크롤 실측은 후속이다(시드 증량 또는 상세/검색 트랙에서).
- [브라우저 전용 미검증] select→router.replace URL 갱신·IntersectionObserver 발화·클라이언트 Loading 스켈레톤은 코드 배선은 확정이나 런타임 브라우저 확인은 미실시.
- [인프라·서버 이관] DevTools nginx 완화는 로컬 conf 한정. 서버 배포 트랙에서 서버 gateway conf는 prod DENY를 유지한다(dev 완화 미이관 확인).
- [FE 후속 트랙] 상세·검색·카테고리 페이지(FE-03 §8 유지). categoryId는 목록이 URL로 받으나 카테고리 선택 UI·페이지는 미구현.
- [공용 컴포넌트 추가 승격] shadcn-vue·motion-v·Pinia는 폼·오버레이·모션 실수요 트랙에서(FE-03 §8 유지).
- [타입 불일치 경미] FE ProductSummary.categoryId=number(non-null) vs 백엔드 Long(nullable)·현 렌더 무영향(recon A-4).

---

## FE-06: 구매자 사이트맵 전면 설계

날짜: 2026-07-08
선행: FE-05 완료. 근거 = recon-report-68(PART 1~4·gitignore 로컬 전용) + 외부 검토 1회(Q1~Q4) 흡수.
범위: 구매자(쇼핑몰 프론트) 전 페이지 사이트맵 + 페이지별 BE 구성 상태 + BE-추가작업 목록 + FE-07~12 트랙 분할 + 공용 컴포넌트 카탈로그. 구현 없음(설계 트랙). seller/admin 화면은 범위 밖(별도 트랙 재정의).
박제 근거 성격: 코드 구현이 없는 설계 트랙이므로 "구현·검증 후 박제" 대신 정찰 실측(recon-68 PART1~4)·외부검토 완료를 근거로 한다.

상태 라벨: [완비]=원함+BE완성(FE만 개발) · [부분]=원함+BE일부(FE+BE보강) · [미구성]=원함+BE없음(BE선행 후 FE). 근거=recon-68 PART1 엔드포인트·PART4 DTO 실측.

### 사이트맵

Tier 0 — 완료(구현됨)
- / 홈 [완비]
- /products 상품목록 [완비] (무한스크롤·sort 4종·categoryId 필터)

Tier 1 — 구매 퍼널 (전부 [완비]·즉시 구현 가능)

| URL | 기능 | 뒷받침 API | 상태 |
|---|---|---|---|
| /products/:id | 상품상세·옵션·수량·담기/구매 | GET /products/{id} | [완비] |
| /cart | 장바구니(담기·수량·선택·삭제) | /cart* (7) | [완비] · 판매자 묶음=FE 클라이언트 그룹핑(PART4 flat) |
| /checkout | 체크아웃·주문생성 | POST /cart/checkout (Idempotency-Key) | [완비] |
| /orders/:id | 주문완료·상세·판매자별 섹션·구매확정·재결제·클레임요청 | GET /orders/{id}·POST …/confirm·…/payments | [완비] · sellers[] 계층 보유(PART4) |
| /orders | 주문내역 | GET /orders | [완비] |
| /login | 로그인 | POST /auth/login | [완비] · logout·refresh 없음→BE-H |
| /signup | 회원가입 | POST /users | [완비] |
| /mypage | 마이페이지 허브 | GET /users/me | [완비] · 등급/알림 섹션=[미구성] |
| /mypage/profile | 프로필 수정 | PATCH /users/me | [완비] |
| /mypage/password | 비번 변경 | PATCH /users/me/password | [완비] |
| /mypage/addresses | 배송지 관리 | /users/me/addresses* (5) | [완비] |
| /mypage/withdraw | 탈퇴 | POST /users/me/withdraw | [완비] |
| /mypage/claims | 클레임 목록 | GET /claims | [완비] |
| /mypage/claims/:id | 클레임 상세 | GET /claims/{id} | [완비] · 요청은 CANCEL 한정 |

Tier 1 상태 UX (페이지 아닌 상태·전역/컴포넌트로 처리):
- 결제 실패: /checkout 실패 상태(모달 또는 /checkout/fail)·CheckoutResponse 상태 분기
- 상품 404: 상세 삭제·판매중지·오URL → 404 상태 렌더
- 품절: 상세 구매/담기 비활성(soldOut)·목록 품절 뱃지
- 로그인 만료: 401 → 로그인 리다이렉트 → 원위치 복귀(BUYER 미들웨어·전역)
- 빈 상태: 장바구니·주문내역·클레임 Empty (common/EmptyState)

Tier 2 — 확장 (BE 선행 필요)

| URL | 기능 | 상태·필요 BE |
|---|---|---|
| 카테고리 네비·/categories | 카테고리 탐색/필터 | [미구성] BE-A |
| /search | 키워드 검색 | [미구성] BE-B |
| /sellers/:id | 판매자 스토어(상세 판매자명→이동) | [미구성] BE-E |
| 리뷰(상세 섹션·/mypage/reviews) | 조회·작성·평점 | [미구성] BE-C |
| /wishlist | 찜 | [미구성] BE-D |
| /orders/:id 배송추적 | 배송상태·송장 | [미구성] BE-F · PART4: OrderResponse 배송필드 전무 확정 |
| /mypage/notifications | 알림함 | [미구성] BE-G |
| /mypage 등급 섹션 | 등급 표시 | [미구성] BE-I · PART4: ProfileResponse 등급필드 전무 확정 |

### BE-추가작업 목록 (우선순위 A > B > E > F > H > C > D > G > I)

- BE-A 카테고리 목록/트리 조회 API — 카테고리 탐색·필터 (필수·검색보다 선행)
- BE-B 상품 keyword 검색 API — 검색 페이지 (필수)
- BE-E 공개 Seller 조회 + 상품 by seller API — 판매자 스토어 (멀티벤더 차별성)
- BE-F 배송추적 조회 API 또는 OrderResponse 확장 — 배송상태·송장 (PART4 필드 부재 확정)
- BE-H logout(+refresh) — 인증 수명주기 (경량·조기 채택 가능)
- BE-C Review Aggregate + API(작성·목록·평점) — 리뷰 (포폴 가치·작업량 큼·후순위 존치)
- BE-D Wishlist Aggregate + API — 위시리스트 (MVP 후순위)
- BE-G NotificationLog 조회 API — 알림센터 (MVP 후순위)
- BE-I ProfileResponse 등급 확장 또는 BuyerGrade 조회 API — 마이페이지 등급

판매자별 배송비/무료배송: CartResponse·OrderResponse 모두 배송비 필드 부재(PART4). 멀티벤더 배송비 정책(무료배송·조건·제주 추가)은 BE 설계 선행 필요·BE-A~I와 별개 논점(§8 이월).

### FE 트랙 분할 (외부검토 R2 반영·재편)

- FE-07 Global Layout — Header(sticky·검색바·카테고리 메뉴·장바구니 뱃지·auth)·Footer·Navigation·auth store·cart store·BUYER 미들웨어. 전 페이지 의존이라 선행.
- FE-08 상품상세 + 장바구니 — /products/:id·/cart(클라이언트 판매자 그룹핑)·품절·404 상태.
- FE-09 체크아웃 — /checkout(주문 생성·Idempotency·결제 성공/실패 분기).
- FE-10 주문 — /orders 목록·/orders/:id 상세(판매자별 섹션)·구매확정·재결제.
- FE-11 계정 — 로그인·가입·마이페이지·프로필·비번·배송지·탈퇴·401 복귀.
- FE-12 클레임 — 요청(CANCEL)·목록·상세.
- Tier2 페이지는 각 대응 BE-추가작업 머지 후 개별 FE 트랙(FE-13+).

### 공용 컴포넌트 카탈로그 (common/)

- 기존(FE-05 승격): LoadingSkeleton·ErrorState·EmptyState
- 신규 등재: Button·Badge·Price
  - Price: 가격 표현(10,000원·무료·품절)이 전 화면 반복 → <Price :value/> 단일화로 toLocaleString 산개 방지(외부검토 채택·YAGNI 아님).
- 부가기능(BE 무관·저비용·외부검토 R4 채택): 최근 본 상품(localStorage)·브레드크럼·공유(clipboard). Cart Drawer는 MVP 이후 백로그.
- Sticky Header: AppHeader 기존 sticky 유지·확정(외부검토 채택).

### §1-A 갈림길·채택/기각 근거

1) 사이트맵 대상 범위
- α 구매자 전용 우선 — 채택. 구매자 프론트가 홈+목록 2p뿐이라 퍼널 완성이 순서. seller/admin은 별도 트랙 재정의.
- β 구매자+판매자+관리자 3면 전체 — 기각. 페이지 3배·YAGNI.

2) 정찰 방식
- 사이트맵 스파인 고정 후 페이지→기능→API 실재 확인 — 채택. API 없는 페이지부터 그리면 역순 사태.
- inventory-first(백엔드 나열 후 페이지 상상) — 기각.

3) R1 멀티벤더 그룹핑 (DTO 실측 선행)
- 장바구니: CartResponse flat(PART4) → FE 클라이언트 그룹핑(sellerName). 주문상세: OrderResponse sellers[] 계층 보유(PART4) → 즉시 판매자별 섹션. 추측 대신 DTO 실측 확정(기조 5).

4) 트랙 재편 (외부검토 R2)
- Global Layout 선행 트랙 분리 + Checkout/Order 분리 — 채택. Header 전 페이지 의존·Checkout/Order 예외처리(Idempotency·결제실패·재결제·구매확정) 과중.
- 단일 대형 트랙(FE-08 통합) — 기각.

5) Review 존치 (외부검토 R3)
- 사이트맵 존치·우선순위 최하위 — 채택. 드롭 아닌 후순위·포폴 가치.
- 제거 — 기각.

6) 부가기능·Price (외부검토 R4)
- 최근본상품·브레드크럼·공유·Sticky Header·Price 채택 / Cart Drawer 이후 — 채택. 전 화면 반복·저비용이라 Price는 산개 후 통일 비용을 회피(YAGNI 아님).

### §2 결정 라운드 재진입
- [실측·recon-68 PART1] 구매자 엔드포인트 28개·도메인 커버리지(상품·장바구니·주문·유저·클레임 존재 / 카테고리·검색·리뷰·위시리스트 부재).
- [실측·recon-68 PART4] CartResponse flat(sellerGroups 없음·배송비 필드 없음)·OrderResponse sellers[] 계층 보유(배송추적 필드 전무)·ProfileResponse 4필드(등급 없음). → 배송추적·등급이 "미확인"에서 "필드 없음(미구성)"으로 확정.
- [외부검토 흡수] Q1 상태 UX 5종(결제실패·404·품절·401·Empty) 반영 / Q2 판매자별 배송비 이월(BE 설계) / Q3 우선순위 A>B>E>F 반영 / Q4 트랙 재편·Global Layout 선행 반영.
- [넘버링] 외부검토가 Global Layout을 FE-06으로 제안했으나 FE-06=사이트맵 설계(현 트랙) 점유 → Global Layout=FE-07 배치.

### §8 이월(carry-over)
- [판매자별 배송비 정책] CartResponse·OrderResponse 배송비 필드 부재(PART4). 멀티벤더 배송비(무료배송·조건·제주 추가) BE 설계 선행·별도 논점.
- [seller/admin 프론트] 판매자·관리자 화면 사이트맵은 범위 밖·별도 트랙 재정의(구매자 퍼널 완성 후).
- [Tier2 BE 트랙] BE-A~I는 BE 트랙으로 별도 착수(FE와 교차). 각 머지 후 대응 FE-13+ 트랙.
- [무한스크롤 다건] FE-05 §8 유지(시드 2건·다건 미검증).
- [디자인 상세] shadcn-vue·motion-v·Pinia 실수요는 각 구현 트랙(FE-03 §8 유지).

---

## FE-07: 디자인 파운데이션 (디자인 토큰 + 컴포넌트 전환)

날짜: 2026-07-08
선행: FE-06(사이트맵) 완료. 근거 = recon-report-69(Tailwind v3.4.19·하드코딩 유채색 0·중립색 토큰 일치) + 시안 4라운드 외부검토 흡수.
범위: 디자인 언어 확정 및 tailwind theme.extend 토큰화 + 기존 컴포넌트(홈·목록) 토큰 전환. 신규 페이지 없음. 뱃지 데이터 구동·shadcn-vue는 이연.
넘버링 조정: FE-06 트랙분할이 FE-07=Global Layout였으나 디자인 파운데이션을 선행 삽입 → FE-07=디자인 파운데이션, Global Layout=FE-08, 이하 상품상세+장바구니 FE-09·체크아웃 FE-10·주문 FE-11·계정 FE-12·클레임 FE-13으로 한 칸씩 이동.

### 확정 디자인 토큰 (시안 최종·유실 방지 완전 기록)

컬러
- surface 3단: page #FAFAFA · card #FFFFFF · section #F5F5F5
- primary #2563EB · primary-hover #1D4ED8 (CTA·링크·브랜드)
- price #E11D48 (가격 전용·행동색과 분리)
- text: ink #111827 · sub #6B7280 · seller #9CA3AF (상품명>판매자 위계)
- border #E5E7EB
- 역할 분리: primary=blue · price=red · soldout=gray · success=green · warning=amber
- 뱃지: new bg #DBEAFE / ink #1D4ED8 · sale bg #FEE2E2 / ink #E11D48 · soldout bg #F3F4F6 / ink #6B7280

타이포
- 스케일 12·13·14·16·18·20·24 (Tailwind 기본 xs~2xl 매핑)
- weight: regular 400 · medium 500 · semibold 600 · bold 700
- 가격 18 / 700 (커머스 최우선 가독) · 섹션 제목 24 / 500 · 서브타이틀 14 · 상품명 14 · 판매자명 12/seller색

radius: card 16 · button·input·cta 14 · badge 6
shadow: 평상 none(border만) · hover 0 4px 12px rgba(0,0,0,0.08)
transition 토큰: fast 150 · normal 180 · slow 250 · hover translateY(-2px)·image scale 1.02
spacing: 8px 그리드

폰트: 나눔고딕 self-host(FE-04) 유지 — 토큰과 독립.

### §1-A 갈림길·채택/기각 근거

1) 디자인 방향 (시안 3안 → 홈 전체 시안 3안)
- 포인트 컬러 단독 비교(레드/블루/그린) 중 블루 채택 후, 외부검토 "색보다 타이포·여백이 인상 좌우" 수용 → 홈 전체 레이아웃 3안(11번가형·11번가+Shopify·Apple/Vercel형)으로 재비교.
- 11번가+Shopify형(4열·중간여백) 채택 — FE-03 박제 원칙("11번가 익숙함+Shopify/Vercel 완성도")과 정확 정합. 11번가형(정보밀도 최대) 기각(차별성 희석·클론 인상). Apple/Vercel형(3열·최대여백) 기각(커머스 밀도 부족·목록 허전).

2) 컬러 시스템 (단일 포인트 → 역할 분리)
- 외부검토 수용: Primary=Blue(행동)·Price=Red(가격)·soldout=Gray·success=Green·warning=Amber로 역할 분리 — 채택. 가격에 Primary를 재사용하면 SaaS처럼 차가워지고, 국내 커머스는 가격이 최우선 가독이라 Red 분리가 자연스럽다. 디자인시스템으로도 semantic 분리가 견고.

3) 토큰 정의 위치 (recon-69 재료 기반)
- α theme.extend 단독 — 채택. 현 CSS 변수 소비처 0·폰트 토큰 선례가 이미 theme.extend·YAGNI. shadcn-vue 도입은 FE-08 미확정 실수요라 (c) CSS변수 병행 선투자는 추측(기조 4·5).
- β 지금부터 :root CSS변수 병행 — 기각. 근거(shadcn-vue 확정) 부재.
- 이월: FE-08 shadcn-vue 채택 시 (c) 병행 재배선 비용 발생 — 그 시점 판단.

4) 뱃지 부착 범위 (데이터 부재)
- α Badge 스타일 정의만·카드 부착 이연 — 채택. recon-69: ProductSummary에 isNew/onSale/할인 필드 부재(product.ts). 데이터 없는 NEW/세일 뱃지를 전 카드에 하드코딩하면 죽은 마크업(기조 4). 무료배송·품절 등 기존 필드 표현 가능분도 이번 범위(토큰+기존렌더 전환)에서 제외.
- β 시안대로 정적 뱃지 부착 — 기각. 가짜 뱃지 박제.

5) 호버 강도 (외부검토 4차)
- 평상 shadow 없음 + hover 0 4px 12px/.08·translateY(-2px)·image 1.02 채택. shadow-md(0 6px 16px/.10)는 카드 다수 정렬 시 튐 → "뜬다"가 아닌 "살아난다" 강도로 절제.

### §2 결정 라운드 재진입
- [실측·recon-69] Tailwind v3.4.19(@nuxtjs/tailwindcss 6.14.0 transitive)·theme.extend 방식 확정(v4 @theme 아님). tailwind.config theme.extend에 fontFamily.sans(나눔고딕)만 존재·색/radius/shadow 커스텀 전무. main.css는 @font-face 2건뿐(:root·하드코딩색 0). 하드코딩 유채색 0건·중립색(gray-900/500/400)이 토큰 hex와 이미 일치 → 회귀 국소(가격·radius·페이지bg 3점).
- [구현·검증 실측] 홈·목록 SSR 200(게이트웨이 end-to-end·브라우저 /api 200). Tailwind CSS 실측 전 토큰 정확 컴파일(price→rgb225 29 72=#E11D48·surface-page #FAFAFA·badge-new-bg #DBEAFE·primary #2563EB/hover #1D4ED8·ink/sub/seller/line/soldout·radius card16/control14/badge6·shadow-card-hover 0 4px 12px rgba(0,0,0,0.08)·duration-normal 180ms·hover -translate-y-0.5/scale1.02). 페이지 bg #FAFAFA·가격 빨강 18/700·카드 radius16·border line·히어로 라이트(#DBEAFE·h1 #1D4ED8·CTA primary solid·암색 잔여 클래스 0)·나눔고딕 woff2 200·섹션제목 24/500·FE-04/05 무회귀(카드 2건 SSR·prices 39,900/19,900·양 라우트 200·sort pill 유지)·빌드/콘솔 에러 0.
- [외부검토 4라운드 흡수 요약] R1 포인트컬러 블루 / R2 역할분리 컬러+홈 전체시안 비교 / R3 간격·타이포위계·호버·Hero CTA·판매자명 연하게·뱃지 제안 / R4 호버 절제·가격 18/700·CTA radius 14 정렬·transition/weight 토큰화.
- [구현 중 결정] HomeHero 암색→라이트(#DBEAFE) 재설계 시 흰 CTA가 연파랑에 묻힘(대비 1.1) → 역할토큰 일관 위해 primary CTA(A안) 채택. ProductCard border를 line 토큰으로 정합. 섹션 제목을 홈·목록 공통 24/500으로 통일(기존 30/700 폐기). 검색바 pill(rounded-full)은 FE-03 헤더 디자인 요소라 control 14 교체 대상서 제외·유지. success #16A34A·warning #F59E0B 관용값 선점(소비처 0).

### §8 이월(carry-over)
- [뱃지 데이터] new/sale 뱃지 실구동은 BE DTO 확장(ProductSummary에 isNew/onSale/할인 필드) 선행 → 상품 도메인 BE 트랙. 무료배송·품절 뱃지도 데이터 정합 후 부착.
- [토큰 위치 재배선] FE-08 shadcn-vue(reka-ui) 채택 시 :root CSS변수 병행((c)) 재배선 — 그 시점 결정. 현재 미설치 확정.
- [Hero CTA·프로모 동작] 시안의 "지금 받기" CTA는 쿠폰 도메인·동작 부재라 이번 미부착. 프로모/쿠폰 트랙에서.
- [Global Layout] FE-08로 이동(Header 검색바·카테고리 메뉴·장바구니 뱃지·auth·Footer·store·미들웨어).
- [shadcn-vue·motion-v·Pinia] 실수요 트랙 유지(FE-03 §8).

---

## FE-08: Tailwind v3.4.19 → v4 마이그레이션 (@theme 전면 이전)

날짜: 2026-07-09
선행: FE-07(디자인 파운데이션) 완료. shadcn-vue(reka-ui) 도입 확정이 v4 전제(정식 v4 지원 라인만 정렬)라, UI킷 도입(FE-09) 전에 엔진 전환을 선행 트랙으로 분리.
범위: Tailwind 엔진 v3→v4 전환 + FE-07 토큰(theme.extend) → @theme 전면 이전 + 빌드 배선(@nuxtjs/tailwindcss 제거·@tailwindcss/vite) 교체. 신규 기능 0·무회귀 유지가 성공 기준.
수용기준(달성): 홈/목록 SSR 200·나눔고딕 woff2 200·커스텀 클래스 21종 dev/prod 동일 산출·pnpm build 성공·FE-04/05 무회귀·빌드/콘솔 에러 0.

### §1-A 갈림길·채택/기각 근거

1) v3 유지 vs v4 전환
- α v3.4.19 유지(+shadcn-vue@2.2 v3 경로) — 기각. v3는 직전 메이저·rolling support로 유지보수만. shadcn-vue 정식 라인이 v4 전제라 v3 도입 시 레거시 CLI + 향후 v4 재전환 이중작업.
- β v4 전환 — 채택. 데모·포트폴리오라 구형 브라우저 지원 불필요(v4 타겟 Safari16.4+/Chrome111+ 무관). 프론트 화면 2p뿐이라 회귀면이 지금 최소. shadcn-vue(FE-09)와 정렬. 실측: @nuxtjs/tailwindcss 6.14.0이 tailwindcss ~3.4.17 고정·정식 v4 지원 라인 없음 → 전환은 모듈 교체 동반.

2) 토큰 이전 방식
- α @config로 tailwind.config.ts 유지 — 기각. FE-09 shadcn-vue(CSS변수/:root 기반) 도입 시 @theme/:root 재작업 불가피 → 이중작업.
- β @theme 전면 이전 — 채택. CSS-first 완성·CSS변수 생성이 shadcn-vue 정렬. codemod가 변환 보조·화면 2p라 회귀면 작음.

3) 빌드 배선
- @nuxtjs/tailwindcss 제거 → @tailwindcss/vite(Nuxt vite.plugins) 채택. 모듈 정식 라인은 v4 미지원(alpha/7.0.0-beta만)이라 beta 의존 회피. main.css 진입점 @import "tailwindcss"로 전환(v3 모듈 자동주입 대체).

### §2 결정 라운드 재진입 (구현 중 결정·실측)
- 실측(recon-72): @nuxtjs/tailwindcss 6.14.0 = tailwindcss ~3.4.17 고정. 커스텀 클래스 실사용 21종·소비 컴포넌트 7개. breaking 노출점 outline-none 6·button cursor 3·bare rounded 3·gray-* 7파일·placeholder 1.
- 구현 중 결정: tailwind.config.ts 완전 제거(v4 auto content-scan·@config 미사용·theme 전량 @theme 이전으로 잔여 역할 0).
- v4 트랩 대응: (1) @theme --duration-* 는 named duration 유틸 미생성 → @utility duration-fast/normal/slow로 --tw-duration 미러링 복원. (2) outline-none → outline-hidden(의미변경·공식 codemod). (3) Preflight button cursor:pointer 제거 → @layer base 복원. placeholder·bare rounded·gray-*는 회귀 없어 무변경.
- 검증 실측: 21클래스 dev 생성 CSS + prod 빌드 CSS 패리티(text-price #E11D48·surface-page #FAFAFA·rounded card16/control14/badge6·shadow-card-hover 0 4px 12px rgba(0,0,0,0.08)·duration-normal 180ms 등 정확 일치·불일치 0). 홈/목록 200·pnpm build 성공·FE-04/05 무회귀.

### §8 이월(carry-over)
- shadcn-vue 도입: FE-09(Global Layout)에서 v4 @theme/:root 위에 reka-ui 기반 도입. FE-07 §8 "토큰 위치 재배선(:root 병행)"은 v4 @theme 확정으로 방향 정리 — shadcn 색 토큰을 @theme 변수와 정합시키는 배선은 FE-09에서.
- duration 유틸 구조: @utility 미러링이 v4 마이너 업데이트 시 내장 duration 구조 변화에 취약할 수 있음 — 회귀 시 재점검(관찰).

---

## FE-09: Global Layout (Header·Footer·Nav + auth/cart store + shadcn-vue 도입)

날짜: 2026-07-09
선행: FE-08(Tailwind v4) 완료. BE 로그인/JWT 계약 근거 = recon-report-70(FE 실측).
범위: Header 확장(sticky·검색 UI·카테고리 placeholder·장바구니 뱃지·auth 분기)·auth/cart Pinia store·cart SSR 로드 plugin·shadcn-vue(reka-ui) 기반 도입 + v4 토큰 배선. Footer=FE-03 유지(무변경). BUYER 미들웨어·로그인 페이지·검색 동작·카테고리 데이터는 소비처 부재로 이연.
수용기준(달성): 홈/목록 SSR 200·FE-08 무회귀(text-primary #2563EB·21클래스 패리티)·미인증 렌더(로그인 링크·뱃지 미표시)·hydration mismatch 0·컴파일/타입 에러 0·store auto-import 런타임 정상.

### §1-A 갈림길·채택/기각 근거

1) shadcn 토큰 배선
- γ 채택: shadcn 표준 셋(:root + @theme inline) + FE-07 브랜드 @theme 리터럴 유지, --primary만 브랜드(#2563EB) 정합, destructive/price 분리 유지.
- α(브랜드 토큰에 shadcn 매핑·병합) 기각: 의미 다른 토큰 억지 병합 꼼수.
- β(완전 격리) 기각: --color-primary 이름 충돌로 순수 격리 불가.
- (브랜드까지 :root 통일) 기각: 런타임 오버라이드 소비처 0·YAGNI.

2) 상태 계층
- α 채택: Pinia setup store 단일 계층(상태·게터·액션·base 이원화 보유).
- β(store + useAuth/useCart 컴포저블 래퍼) 기각: 래퍼가 store 재노출만 하는 무로직 계층(padding). 추가 로직 필요 시 얹기 가벼우므로 그때 도입.

3) cart 로드 트리거
- α 채택: callOnce SSR 1회(뱃지 첫 페인트 정확·no-flash·중복 fetch 없음).
- β(client-only) 기각: 하이드레이션 후 0→N 반짝.

4) 토큰 저장
- useCookie(non-httpOnly): SSR·클라 양쪽 JS 접근 필요(뱃지·auth 분기 SSR 렌더). role/exp = JWT payload 무라이브러리 base64url 수동 디코드(UI 표시·만료 UX 전용·UTF-8 안전·실패 null). 실인가는 서버 응답이 SoT(디코드로 접근제어 판단 금지).
- BE 계약 상호 참조: POST /api/v1/auth/login {email,password,role}→{token}·JWT HS256·claim role·exp 1h·Authorization Bearer = recon-report-70(FE 실측) 기록. decisions.md는 D-39(X-Buyer-Id 임시 인증)까지이고 정식 JWT 발급은 D-39 "범위 외(Track 4.5/후속)" 명시 → FE는 recon-70 실측 계약을 SoT로 배선(정식 인증 D-XX 신설 시 상호 참조 갱신).

5) Button 블로커 해결
- typescript devDependency 설치가 shadcn-vue + Nuxt4 "Failed to resolve extends base type"의 문서상 정식 해결(SFC 타입 리졸버 요구). 버전 도박·설정 우회·스캐폴드 수정·이연 전부 기각 → 공식 Button.vue 무수정 유지.

6) pnpm store 위치
- storeDir = node_modules 익명 볼륨 내부(/app/node_modules/.pnpm-store): 바인드-마운트(호스트 device)와 익명 볼륨(컨테이너 device) 교차-디바이스 purge 트랩 근본 제거. verifyDepsBeforeRun:false 마스크 기각(증상 은폐).

### §2 결정 라운드 재진입·구현 중 결정
- /login 링크: NuxtLink 유지(올바른 코드). /login 페이지 부재로 dev SSR "No match found for /login" 경고 1종 발생 → 검증 기준의 명시 예외(로그인 트랙에서 페이지 생성 시 자동 소멸·dev 전용·prod 미출력). <a href> 다운그레이드(우회)는 기각.
- 검색바 UI-only·카테고리 정적 placeholder: 소비처(검색 라우트·카테고리 데이터) 부재로 동작 미배선(dead handler 금지).
- logout 조합: store 간 결합은 store 밖에서 — Header 핸들러가 auth.logout() + cart.clear() 순차 호출(UI 계층 조합).
- [실측·트랩] STEP 2 store를 tsc만으로 검증(소비처 0) → 런타임 auto-import 미스캔 미탐지. STEP 3 Header가 store 소비 시 "useAuthStore is not defined" 500 → dev 재기동으로 unimport 재스캔 해소(LT-11). auto-import 대상(store/plugin) 추가 후 재기동 + 페이지 실측 필수.

### §8 이월(carry-over)
- [DEFERRED] BUYER 라우트 미들웨어(첫 보호 페이지 트랙) · 로그인 페이지 + authStore.login() 소비(로그인 트랙·login은 준비된 seam) · 검색 동작·검색결과 라우트(검색 트랙) · 카테고리 nav 데이터·라우팅(FE-10) · cart items 요소 전체 타이핑(FE-10).
- [RESOLVED] FE-07 §8·FE-08 §8 shadcn :root 배선 이월 → 본 트랙 §1-A(1)에서 해소.

---

## FE-10a: 상품 상세 페이지 (GET /products/{productPublicId} 소비)

날짜: 2026-07-09
선행: FE-09(Global Layout) 완료 + D-149(cart 외부 대상키 variantPublicId 정상화·Track 65) 머지. 정찰 = recon-report-75(§1·§3-1). FE-10을 상세(10a)·장바구니(10b) 2-split한 앞부분.
범위: 상품 상세 페이지·상세 조회 composable·ProductDetail 타입(+중첩 5)·목록→상세 링크 배선. 옵션/variant 선택→variantPublicId 확보 seam·담기 버튼 배치까지. 담기 API 호출·인증 게이트·/login·BUYER 미들웨어·장바구니 페이지는 FE-10b 이연.
수용기준(달성): 홈/목록 SSR 200 무회귀·카드→상세 링크 이동·상세 200(이미지 갤러리·옵션/variant 선택·수량·담기 seam)·미존재 id 404 not-found 렌더(500 아님)·hydration mismatch 0·컴파일 에러 0.

### §1-A 갈림길·채택/기각 근거
1) 분할·순서 (FE-10 착수 결정 라운드)
- α 채택: 상세(10a) → 장바구니(10b) 2-split. 상세는 permitAll·인증 무의존·variantPublicId 생산자라 선행 리스크 최저·독립 검증 용이. 장바구니가 끌어오는 인증 인프라(미들웨어·게이트·login)를 뒤로 분리해 각 STEP 검증 표면 축소.
- β 병행 단일 트랙 기각: 인증 인프라와 상세 렌더가 한 STEP에 섞여 회귀면·검증 표면 확대.
2) 담기 액션 범위 (미사용 선작성 금지)
- α 채택: 담기 버튼 배치 + variant 선택 결과(variantPublicId)를 seam으로 보유(data-variant-public-id·computed)하되 handleAddToCart는 no-op. 실제 POST /cart/items·인증 게이트는 소비처(BUYER 보호·cart store 갱신)가 생기는 FE-10b에서 배선.
- β 담기까지 이번 트랙 배선 기각: /login·미들웨어·cart store action 등 인증 인프라 선행 필요 → 소비처 없이 선작성(기조4). auth store login()은 seam으로 준비돼 있어 되돌림 가벼움.
3) 옵션→variant 매칭 방식
- 단순상품(optionGroups 빈)=variants[0] 바로 사용(선택 UI 생략). 다중 옵션=그룹별 값 선택·전 그룹 완료 시 variants에서 options(groupName+value) 집합 정확 일치 1건 확정(미완료·불일치 null·안내 문구). 확정 시 salePrice·soldOut 반영. 실측: 데모 시드 2건 모두 단순상품이라 단순 경로만 컨테이너 실측·다중은 DTO 구조 기준 구현(E2E 미실측·§8).

### §2 결정 라운드 재진입·구현 중 결정
- [실측·recon-75] cart 계약 D-149 반영(대상키 variantPublicId String)·상세 Variant.variantPublicId(var_ String)와 타입 일치 → 상세→담기 브리지 변환 불요(recon-74 §5 블로커 해소). ProductDetailResponse 11필드+중첩 무변경·products GET permitAll 무변동.
- [구현·검증 실측] useProductDetail=useProducts 패턴 동형(useFetch·API base 이원화·key=product-detail:{id}·404 error). ProductDetail+중첩5 타입 BE DTO 정확 일치(description string|null). 상세 페이지 이미지 main-우선 정렬·부재 placeholder·soldOut 오버레이·가격 variant salePrice fallback·수량 ±·404 분기. ProductCard <a href="#">→NuxtLink. 컨테이너(gateway HTTPS): 홈/목록 200 무회귀·상세 200(단순상품 variant 자동확정·data-variant-public-id=var_)·미존재 id not-found·hydration 0·컴파일 0.
- [구현 중 결정] useSeoMeta로 상세 동적 title/description 부착(상품별 title). og:image·JSON-LD·canonical·sitemap은 SEO 성숙 백로그로 이연.
- [트랩·참고] 이미지 실 저장 form: 데모 시드 상세 images 빈 배열이라 placeholder만 실측·실 이미지 렌더 미검증(목록 mainImageUrl은 picsum 절대URL 검증됨·§8). LT-11(auto-import 재기동) 신규 composable/page 적용·dev 재기동 후 실측.

### §8 이월(carry-over)
- [DEFERRED·FE-10b] 담기 API 배선(POST /cart/items)·인증 게이트(미인증→/login)·/login 페이지(auth store login() 소비)·BUYER 미들웨어(첫 보호 라우트)·장바구니 페이지(GET /cart·수량/선택/삭제·dangling 표기)·cart store items unknown[]→CartItemView[] 승격 + 조작 action.
- [미확인] 이미지 저장 form(절대/상대): 이미지 있는 시드로 상세 렌더 실측 필요. 다중 옵션 variant 매칭 E2E: 다중 variant 시드 부재로 미실측(로직은 DTO 기준).
- [백로그] FE SEO 성숙(og:image·JSON-LD Product·canonical·sitemap)·FE 테스트 도입(FE-12/CI). 동적 title/description은 본 트랙 해소.
- [재이연] FE-09 §8 "카테고리 nav·cart items 타이핑(FE-10)"은 상세서 미소비 → cart 타이핑=FE-10b·카테고리 nav=카탈로그 트랙으로 재이연.

---

## FE-10b: 장바구니 + 인증 인프라 (로그인·BUYER 미들웨어·담기 배선·장바구니 페이지)

날짜: 2026-07-09
선행: FE-10a(상품상세) 머지 + D-149(cart 대상키 variantPublicId). 정찰 = recon-report-76. FE-10 2-split 뒷부분. 2 구현 프롬프트(P1 로그인+미들웨어+담기 배선 / P2 장바구니 페이지+조작+타입 승격)로 진행·통합 박제.
범위: /login 페이지·BUYER 미들웨어·상세 담기 액션 실배선·cart store 조작 5종(add P1·quantity/selected/selected-all/remove P2)·CartItemView 타입 승격·장바구니 페이지(/cart)·AppHeader /cart 이동. checkout은 seam(FE-11 이연).
수용기준(달성): 미인증 /cart→/login?redirect 리다이렉트(미들웨어 SSR)·로그인→담기 201→GET /cart 9필드 렌더·조작 4종 200+재load·합계(selected∧purchasable)·빈 상태·홈/목록/상세/로그인 200 무회귀·hydration 0.

### §1-A 갈림길·채택/기각 근거
1) STEP 분할·순서
- α 채택: ① 로그인+미들웨어 → ② 담기 배선 → ③ 장바구니 페이지. ①이 ②③의 인증 게이트·리다이렉트 공통 선결·회귀 표면 0. ③이 cart store 타입 승격(unknown[]→CartItemView[])으로 회귀 표면 최대라 마지막. (실행 P1=①②·P2=③ 2 프롬프트.)
- β 병행 단일 트랙 기각: 인증 인프라+렌더가 한 STEP에 섞여 회귀면 확대.
2) role 입력 방식
- α 채택: /login에 role="BUYER" 고정(hidden·lib/constants/auth BUYER_ROLE 상수)·email·password만. buyer 스토어프론트라 구매자 로그인만 필요·셀러/어드민 별도 콘솔 전제(화면 부재).
- β role 선택 UI 기각: 소비처 없는 UI(기조4). BE는 role enum @NotNull 대문자 정확 일치(소문자→400·recon-76 §1-2).
3) 복귀 리다이렉트 관습
- α 채택: ?redirect=<fullPath> query(미들웨어·담기 게이트·로그인 3곳 공유). navigateTo('/login?redirect='+encodeURIComponent(fullPath))→성공 시 resolveRedirect. 오픈리다이렉트 방어(내부 절대경로만·'//' 차단).
- β 복귀 없음 기각: 담기하려다 로그인→원래 상세 복귀 흐름 끊김.
4) 담기 게이트 위치·조작 후 상태
- 게이트 = 클릭 시점(상세 permitAll이라 진입 안 막음·미인증/비-BUYER 클릭 시 /login 유도). 조작 후 = 전체 재load(GET /cart) — 조작 4종 Void 확정(recon-76 §1-1)이라 유일 정합(낙관 갱신은 displayPrice/purchasable/quantityAvailable enrich 재계산 유실).

### §2 결정 라운드 재진입·구현 중 결정
- [실측·recon-76] cart 조작 6종·auth login(role enum BUYER/SELLER/ADMIN·200+token·실패 401 통합)·401 UNAUTHENTICATED/403 FORBIDDEN 실측. buyer 셀프가입 POST /api/v1/users(role 자동 BUYER·V11 seed)로 데모 계정 생성(로컬·운영 재현 경로).
- [구현·검증 실측] login.vue(BUYER 고정·resolveRedirect 오픈리다이렉트 방어·단일 에러·기인증 복귀). buyer.ts(미인증/비-BUYER→/login?redirect·실인가는 서버 SoT). cart store add(P1)+조작 4종(P2·각 재load). 상세 handleAddToCart 게이트(클릭 시점·adding 가드). CartItemView 9필드 타입 승격(count length 파생·뱃지 무회귀). cart.vue(합계 selected∧purchasable·dangling 비활성·삭제만·runMutation 401→로그인·checkout seam). 컨테이너 E2E: 미인증 /cart→302 /login?redirect=/cart·로그인→담기 201→조작 4종 200·합계·빈상태·무회귀·hydration 0.
- [구현 중 결정] 미들웨어는 P1 소비처 부재로 P2에 신설·부착(담기 게이트는 인라인 판정 독립 동작). 로컬 백엔드 stale(D-149 미반영) → dev 컨테이너 재시작 해소(bootRun이 git pull 미재컴파일). LT-12(Pinia setup store .value 트랩) 발견·수정.

### §8 이월(carry-over)
- [DEFERRED·FE-11] checkout 버튼 seam→실배선(선택 품목 POST /cart/checkout·주문 생성·Idempotency-Key).
- [미재현] dangling(purchasable=false) 표기·컨트롤 비활성: 데모 시드 전량 purchasable=true라 코드 경로만·soft-delete 시드 필요 E2E.
- [백로그] 데모 로그인 버튼(로그인 페이지 데모 buyer 자동 로그인·포트폴리오용·demo@zslab-mall.com·운영 시드 재현). FE SEO 성숙(og:image·JSON-LD·canonical·sitemap). FE 테스트(FE-12/CI).
- [RESOLVED] FE-09 §8 DEFERRED 소비: BUYER 미들웨어·로그인 페이지·cart items 타이핑 → FE-10b 해소. (검색 동작·카테고리 nav는 각 트랙 유지.)
- [트랩] LT-12 live-traps.md 신규.

---

## FE-11: 체크아웃·결제 (모의 PG·주문 생성·webhook 완결·완료 화면)

날짜: 2026-07-09
선행: FE-10b(장바구니+인증 인프라) 머지 + Track 67(카트 소진 OrderPlaced→PaymentCompleted 이동·D-151) 동일 브랜치 선적재. 정찰 = recon-report-fe-11(FE seam)·recon-report-fe-11-be-event(소진 타이밍)·recon-report-fe-11-cart-timing(BE 결함). BE 계약 = 인수인계 실측(POST /cart/checkout·POST /api/webhooks/payments).
범위: 3 STEP — (a)체크아웃 폼(배송지 4필수+3선택·결제수단·POST /cart/checkout·Idempotency-Key·Location 캡처) → (b)모의 PG 페이지(/payment/mock·webhook SUCCESS/FAILURE/CANCEL 완결) → (c)완료 화면(/checkout/complete·orderPublicId 표시·cart.load()). cart.vue handleCheckout seam→/checkout 배선. 주문 상세 실조회·우편번호 검색 API·재결제 실배선은 이연.
수용기준(달성): cart 결제하기→/checkout→201·attemptKey·Location→/payment/mock→webhook 200→/checkout/complete 200(orderPublicId 렌더·cart 0). 무인증 3페이지 302 /login. 결제 전 카트 유지·SUCCESS 후 소진·FAILURE/CANCEL 후 보존(Track 67 E2E). hydration 0·컴파일 0.

### §1-A 갈림길·채택/기각 근거
1) 결제 완료 처리 방식
- α 채택: FE 모의 PG 페이지. checkout redirectUrl(https://mock-pg.zslab.local·죽은 도메인)은 미방문·attemptKey만 파싱, FE 내부 /payment/mock에서 webhook POST로 결제 완결. 외부 PG 없이 결제 상태전이 전 구간(SUCCESS/FAILURE/CANCEL) E2E 시연 가능.
- β 실 PG 연동 기각: 외부 계약·키·콜백 인프라 필요·포트폴리오 범위 초과(YAGNI).
- γ redirectUrl 실방문 기각: 죽은 도메인이라 불가·mock 도메인 실서빙은 과잉.
2) 카트 소진 처리
- α 채택: BE 위임(Track 67 CartPaymentCompletedHandler가 PaymentCompleted AFTER_COMMIT 소진)·FE는 완료화면 cart.load() 재조회만. BE 정찰(recon-fe-11-be-event) 실측 — SUCCESS webhook 소진이 완료화면 진입보다 선행·경합 없음.
- β FE 로컬 cart.clear() 기각: 서버가 이미 HARD DELETE·로컬 clear는 서버 실상태 미반영. 실패/취소 시 오소진 위험.
- 이 트랙 착수 중 "결제 전 카트 소진" 결함 발견 → Track 67로 분리 수정(BE)·FE는 그 결과 위에서 cart.load() 정합.
3) 배송지 입력
- α 채택: 수기 입력(recipientName·recipientPhone·zonecode·addressRoad 4필수 + addressJibun·addressDetail·deliveryMemo 3선택). 우편번호 검색 API 이연(SEO급 백로그).
- β 우편번호 검색 연동 기각: 외부 API·소비처 대비 과함(MVP 시연은 수기로 충족).
4) 완료 페이지 범위
- α 채택: 최소 "주문 완료"(orderPublicId 표시 + 홈/주문 링크 seam). 주문 상세 실조회 없음.
- β 주문 상세 렌더 기각: 주문 상세 조회는 FE-12 트랙·소비처 선행 필요(YAGNI).
5) webhook occurredAt 포맷
- LocalDateTime(무 timezone)·Z 제거 필수. new Date().toISOString().slice(0,23). checkout expiresAt(Z 포함)과 비대칭 — Z 붙이면 400. LT 등재 후보(§8).

### §2 결정 라운드 재진입·구현 중 결정
- [실측·recon-fe-11] api base 공통 util 부재 확정(인라인 이원화 복제가 실 스타일)·handleCheckout 단일 seam·buyer 미들웨어 문자열 부착·조작 $fetch throw·Button size lg·CommonErrorState message+@retry. 신규 fetch도 인라인 복제(util 추출은 범위 밖).
- [실측·recon-fe-11-cart-timing] "결제 전 카트 소진" 결함의 수술 지점: PaymentCompleted(orderId 보유)로 구독 이동이 해법1(형제 InventoryPaymentCompletedHandler 2단계 모델 선례·핸들러 본문 무변경). 보상 복원(해법2)은 PAID후취소 이벤트 미발행(Track 5 이연)으로 트리거 확보 불가·기각. → Track 67·D-151로 구현.
- [구현·검증 실측] STEP (a) checkout/index.vue(배송지 폼·결제수단·submit·INITIATE_FAILED/422/401 분기·redirectUrl 파싱→/payment/mock·orderPublicId 관통)·useCheckout($fetch.raw Location 캡처·Idempotency-Key crypto.randomUUID). STEP (b) payment/mock.vue(결제 요약·3버튼·sendPaymentCallback webhook)·occurredAt Z제거. STEP (c) checkout/complete.vue(orderPublicId 표시·onMounted cart.load() try/catch 흡수·홈/주문 링크). cart.vue handleCheckout→navigateTo('/checkout')·준비중 문구 제거.
- [E2E 실측·buyer fe11-t67-test] 3경로 카트 전이: 성공=결제전 1 유지→SUCCESS 200→결제후 0 소진 / 실패=FAILURE 200→1 보존 / 취소=CANCEL 200→1 보존. Track 67 결함 수정 E2E 증명(결제 확정 전 미소진·확정 시에만 소진). complete 200·orderPublicId checkout Location→mock→complete SSR 관통·cart.load() 후 count 0 정합. 무인증 3페이지 302 /login. occurredAt Z제거 3경로 전건 200.
- [구현 중 결정·트랩] backend dev 컨테이너가 Track 67 구 클래스 기동본이라 초기 E2E서 구 소진 동작(checkout 직후 즉시 0) → docker restart로 신 클래스(CartPaymentCompletedHandler) 로드 후 정상 전이 확인. bootRun 바인드마운트가 신 커밋 자동 재컴파일 안 함(BE 계약/핸들러 변경 머지 후 dev 재기동 필수·LT 기존).

### §8 이월(carry-over)
- [DEFERRED·FE-12] complete.vue [주문 내역 보기] 링크가 주문 상세 부재로 임시 /products 연결(소스 주석 "FE-12에서 /orders/{orderPublicId}로 교체"). FE-12 주문 상세 트랙에서 실경로 교체 필수 — 미완 seam(잊으면 임시 링크 잔존).
- [DEFERRED·FE-12] 기존 주문 재결제(next.retryPaymentUrl·INITIATE_FAILED)는 방어 안내만·실배선 이연. 주문 상세/내역 페이지도 FE-12.
- [LT 후보] webhook occurredAt(LocalDateTime·무 Z) vs checkout expiresAt(Z 포함) 포맷 비대칭 → 3경로 200 실증 완료. live-traps.md 등재 검토(FE-11 실증분).
- [백로그·유지] 우편번호 검색 API(배송지)·데모 로그인 버튼·FE SEO 성숙·FE 테스트 도입(FE-12/CI).
- [RESOLVED] FE-10b §8 DEFERRED checkout seam 실배선 → 본 트랙 해소.

---

## FE-12. 주문 조회 화면 (목록·상세) — FE-12a

### 결정
구매자 주문 조회 화면을 신설한다. BE 주문 조회 API(GET /api/v1/orders·/api/v1/orders/{orderPublicId})는 기완비라
순수 FE 배선(+표시용 productName 추가분 D-152). 재결제 배선은 의도적 제외(아래 근거).

### 산출
- types/order.ts: BE record 미러(OrderSummary·OrderDetail·SellerGroup·OrderItem·StatusView·PagedResponse<T>).
  배송지는 요청용과 필드 동일해 checkout.ts의 ShippingAddress 재사용.
- lib/constants/order.ts: OrderStatus 8값 code→한글 라벨 단일 소스(ORDER_STATUS_LABELS·orderStatusLabel 폴백).
  BE StatusView.label=code(한글 미제공·Code 도메인 미도입 fallback) 보완. CLAUDE.md 4층위 enum 잠금 (4)프론트.
- composables/useOrders.ts: useOrderList(page,size)·useOrderDetail(id). BUYER 전용 API라 Authorization: Bearer 주입
  (permitAll useProductDetail 미복제)·API base 이원화(SSR internal/브라우저 상대경로)·useFetch SSR 관통.
- pages/orders/index.vue: buyer 미들웨어·page 왕복 페이징(hasNext)·상태 배지·orderedAt hydration-safe 파싱.
- pages/orders/[orderPublicId].vue: buyer 미들웨어·seller 그룹·품목 productName(null→"삭제된 상품")·소계·총액·
  배송지(null 미표시)·404 존재은닉 안내·401→/login. Pinia setup store 언랩 접근(.value 금지·LT-12).
- pages/checkout/complete.vue seam: [주문 내역 보기] /products → /orders/{orderPublicId}(부재 시 /orders 폴백).

### 재결제 제외 근거
재결제(POST /orders/{id}/payments)는 주문 생성/PG 시작 트랜잭션 분리에서 파생된 기술적 복구 경로(INITIATE_FAILED·
결제 이탈·만료). 무통장/가상계좌 등 실 결제수단 관점에선 "재결제"가 부자연스럽고, 대형몰 다수(결제 선행형)엔 미완 주문
자체가 없어 재결제 버튼도 없다. 붕 뜬 PENDING_PAYMENT 주문은 재결제로 살리기보다 자동취소로 정리가 정합(FE-12b 이관).
따라서 상세에 재결제 버튼 미배선.

### 검증 (authed·mutation 0)
buyer 17건 조회 실측: /orders 200·SSR HTML 한글 라벨(결제완료 7·결제대기 10·API 정합)·상세 productName SSR 관통
("데모 코튼 티셔츠")·seam href 정확·code→한글 라벨 live. useFetch Bearer SSR 주입 서버측 실동작 확인(curl 원문 바디 포함).

---

---

## FE-13: 계정 (회원가입·마이페이지·프로필·비밀번호·배송지·탈퇴·데모 로그인)

날짜: 2026-07-10
선행: FE-12(주문 lifecycle) 머지. 정찰 = recon-report-fe13. BE 계정 API 전량 기완비(회원가입 POST /users·로그인 POST /auth/login·프로필 GET·PATCH /users/me·비번 PATCH /users/me/password·탈퇴 POST /users/me/withdraw·배송지 CRUD 5종 /users/me/addresses*) → 순수 FE 배선 트랙(신규 BE 0). 구현 3분할(P1 types·상수·가입 / P2 허브·프로필·비번 / P3 배송지·탈퇴) + 조건부 STEP 8(데모 로그인) + chore(@types/node).
범위: /signup·/mypage(허브)·/mypage/{profile,password,addresses,withdraw}·데모 로그인 버튼. 등급/알림 섹션 제외.
수용기준(달성): 가입→자동로그인→/mypage·프로필 조회/수정·비번 변경·배송지 CRUD/기본지정·탈퇴→세션정리→홈·데모버튼 1클릭 로그인. 전 화면 buyer 미들웨어(가입/로그인 제외)·typecheck 0 errors·dev 브라우저 GREEN.

### §1-A 갈림길·채택/기각 근거
1) 인계 전제 폐기(정찰 실측)
- "데모 계정 토큰 우회"·"FE-09 이연분(BUYER 미들웨어·로그인 페이지) 미해소" 두 전제가 실측과 상이. 실토큰 로그인은 FE-10b에서 이미 구현(auth.ts login→쿠키)·미들웨어·로그인 페이지 실재(FE-10b RESOLVED). → FE-13은 net-new 계정 화면만.
2) 가입 후 UX
- α 채택: 가입 성공 → 자동 로그인(가입 폼 비번으로 login 재호출) → /mypage. SignupResponse가 userPublicId만 반환·토큰 미발급이라 재로그인이 유일 경로. 포트폴리오 happy-path 매끄러움. 가입 성공·자동로그인만 실패 시 signupSucceeded 플래그로 구분해 /login 유도.
- β 로그인 페이지 유도 기각: 시연 흐름 1단계 추가·이점 없음.
3) 마이페이지 라우팅
- α 채택: /mypage 허브 + 하위 중첩(/mypage/profile 등). Tier1 계획표 매핑과 정합.
- β 플랫 기각: 허브 없는 개별 페이지는 진입 응집 저하.
4) 계정 데이터 접근 계층
- β 채택: auth store엔 인증만(login·logout·signup), 프로필/비번/탈퇴=useProfile·배송지=useAddresses composable 분리. 기존 useOrders/useCheckout 관습·단일책임(CLAUDE.md). /users/me 계열(프로필·비번·탈퇴)은 useProfile 단일 통합(별도 composable 남발 회피).
- α auth store 확장 기각: 인증 store에 데이터 fetch 혼입은 책임 비대.
5) 범위 — 등급/알림 제외
- 채택: 마이페이지 허브에서 등급/알림 섹션 제외. BE-I(등급)·BE-G(알림) 미구성 → 소비처 없는 UI는 과잉(기조4·YAGNI).
6) 데모 로그인 자격증명 소스
- α 채택: runtimeConfig.public 경유(NUXT_PUBLIC_DEMO_EMAIL/PASSWORD·apiBase의 NUXT_PUBLIC_* 관습 복제). git 미노출(.env 소유)·데모 계정은 공개 저권한이라 클라 번들 노출 허용. 값 미주입 시 버튼 차단.
- β 하드코딩 상수 기각: 평문 git 커밋 불필요. γ BE 데모 로그인 엔드포인트 기각: 신규 BE·범위 초과(YAGNI).
7) 배송지 우편번호
- 수기 입력 유지(검색 API 백로그). 체크아웃(FE-11)과 동일 정책.

### §2 구현 중 결정·트랩
- [실측 재확인] BE DTO 전건 명세 일치(AddressResponse.id=Long→FE number·SignupResponse{userPublicId}·ProfileResponse 4필드). 가입 이메일 중복=409 EMAIL_ALREADY_EXISTS·검증=400. 비번 불일치=400 MALFORMED_REQUEST(정책위반 VALIDATION_FAILED와 code로만 구분 가능하나 사유 은닉 위해 단일 문구).
- [구현 중 결정] 배송지 생성·수정 단일 폼 겸용(editingId null=생성·값=수정, isDefault는 생성 시만 노출·BE UpdateAddressRequest isDefault 제외와 정합). 옵션 필드 빈값→undefined 전송(서버 빈문자열 저장 방지). 탈퇴 후처리=auth.logout()+cart.clear()(AppHeader.handleLogout 동일 조합)+홈 이동. 신규 composable/page auto-import는 nuxt prepare 선행 필요(LT-11 계열).
- [chore] @types/node@24 devDep + nuxt.config typescript.nodeTsConfig types:['node']로 nuxt.config process TS2591 해소(lockfile 변경 @types/node 국한). vue-tsc devDep·typecheck script 정식화는 백로그 유지(임시 dlx로 검증 지속).

### §8 이월(carry-over)
- [RESOLVED] FE-10b §8 백로그 "데모 로그인 버튼(demo@zslab-mall.com)" → 본 트랙 STEP 8 해소.
- [버그·백로그] 주문조회 등 BUYER 페이지에서 로그아웃 시 페이지 잔류(헤더만 갱신·라우트 유지). 원인 가설=handleLogout이 라우팅 미수행·미들웨어는 진입 가드라 잔류 페이지 재평가 안 됨. 근본 수정(핸들러 후처리 vs 전역 가드)은 별도 fix 트랙·정찰 후 처리(FE-13 미개입).
- [백로그·유지] 우편번호 검색 API(배송지)·FE SEO 성숙·FE 테스트 도입(CI). typecheck 정식화(vue-tsc devDep·pnpm typecheck script).

---

## FE-14: 구매자 클레임 (취소·반품·교환 요청·조회)

날짜: 2026-07-10
선행: FE-13(계정) 머지 + Track 68(OrderItemResponse item_status StatusView 노출) 머지(선행 BE). 정찰 = recon-report-fe-14. BE 클레임 도메인 기구축(BuyerClaimController 구매자 3엔드포인트: POST /api/v1/claims·GET 목록·GET 단건). 승인·거부·수거·교환출고는 Seller/Admin 전용. 구매자 액션은 "요청(REQUESTED)"까지·이후 승인+환불 이벤트 체인은 비동기.
범위: 유스케이스 A(요청)=주문 상세 품목별 조건부 버튼→/claims/new 폼→POST / 유스케이스 B(조회)=/claims 목록·/claims/[claimPublicId] 상세(진행 타임라인)·마이페이지 진입점. Seller/Admin 액션·구매자 refund 조회(API 부재) 제외.
수용기준(달성): 품목 item_status별 조건부 버튼(PAID·PREPARING→취소 / SHIPPING→반품 / DELIVERED→반품·교환)·요청 폼 제출→POST 201→OrderItem CANCEL_REQUESTED 전이·화면 반영·목록/상세 타임라인·마이페이지 진입. typecheck GREEN·dev 브라우저 GREEN.

### §1-A 갈림길·채택/기각 근거
1) 착수 전 BE seam gap (선행 트랙 분기)
- OrderItemResponse가 item_status 미노출 → 품목별 조건부 클레임 버튼 데이터 부재. (a)BE 노출 / (b)무조건 노출+422 UX / (c)조회로 축소 중 (a) 채택 → 별도 Track 68로 선행(item_status를 order.status와 동일 StatusView로 노출). (b) 시연 UX 저하·(c) 요청 없이 조회 대상 없음(닭-달걀) 기각.
2) reasonCode 노출 정책
- α 채택: claimType 무관 reasonCode 10개 전량 노출. BE가 reasonCode↔claimType 연결 미강제(실측)와 정합·단순. 실제 클레임 가능 판정은 claimType×item_status 조건부 버튼이 담당.
- β type별 필터 기각: BE 미강제 규칙을 FE가 임의 신설(기조2)·소비처(BE 검증) 없음(기조4)·향후 BE 규칙 도입 시 불일치 위험.
3) 페이지 구조 (억지 함축 금지·UX 기준)
- 전용 페이지 3분리 채택(/claims/new·/claims·/claims/[id]). 기존 기능별 전용 페이지 컨벤션(mypage 하위) 정합. 요청 폼은 type 안내·사유·상세·승인대기 고지 입력량이 많아 모달 압축 부적합. 목록(스캔)·상세(타임라인 추적)는 관심사 상이. 단 위저드형 과분할은 안 함(요청 폼 단일 페이지).
4) 유스케이스 A 완결 방식
- A 성공 후처리를 "주문 내역 복귀 + 인라인 성공 피드백"으로 닫음(상세 페이지 B 의존 없이 A 완결). toast 인프라 부재로 인라인 성공 상태 사용.
5) 조건부 버튼 매트릭스
- claimableTypes(item_status)를 constants에 정의·BE OrderItemStatus.canTransitionTo와 1:1 정합 실측(라이브 트랩 방지). 불가 품목은 버튼 미노출.
6) 타임라인 범위
- ClaimStatus 4값(REQUESTED·APPROVED·REJECTED·COMPLETED)만 스텝 인디케이터. 수거·재배송 세부는 구매자 노출 ClaimStatus에 없어(OrderItem status·pickedUpAt 영역) 미표시(추측 확장 금지·YAGNI). 상품명도 클레임 응답 미포함이라 미표시.
7) 활성 클레임 배지 제외
- "이미 클레임 진행 중" 배지는 주문 상세 응답(OrderItemResponse)에 활성 클레임 여부 데이터가 없어 제외. 중복 요청은 서버 422(CLM-5)를 UX로 처리. 경계 판정상 배지는 유스케이스 필수 아님·근거 데이터 부재.

### §2 구현 중 결정·트랩
- [실측 계약] 요청 body 4필드(orderItemPublicId·claimType·reasonCode·reasonDetail?). 응답 status·claimType·reasonCode는 raw enum name 문자열(StatusView 아님) → FE 라벨 매핑(4층위 프론트). 에러 매핑 실측(GlobalExceptionHandler): ClaimNotFound→404·ClaimInvalidState(중복 CLM-5·상태불가)→422·Bean Validation/enum 역직렬화→400.
- [구현 중 결정] 요청 폼 진입 query에 type 전달(주문 상세 버튼별)·name은 표시용(서버 미전송). 부정 query 시 CommonErrorState 대신 커스텀 안내 패널(재시도 무의미한 곳에 죽은 재시도 버튼 회피). 날짜 포맷 formatDateTime을 lib/utils/datetime.ts로 추출(소비처 3: 주문 목록·클레임 목록·상세).
- [캐시 트랩] 클레임 요청 후 주문 상세가 stale 캐시(PAID) 렌더. 원인=useOrderDetail 정적 key useFetch·캐시 무효화 부재. 해소=getCachedData: () => undefined(재방문 항상 재조회). BE는 정상 전이(OrderItem CANCEL_REQUESTED)·order.status=PAID는 OrderStatusResolver 설계(취소 확정 전 PAID 유지·주문 헤더 PAID·품목 배지 취소요청).
- [dev 트랩·LT 후보] backend stale-class(compileJava UP-TO-DATE 오판+gradle named volume→부분 클래스 부팅·NoSuchMethodError·build/classes 삭제로 해소)·frontend 신규 라우트 watcher 미인식(claims/ 신규 디렉토리→프론트 컨테이너 restart로 해소). 둘 다 소스 정합·dev 런타임 미반영·CI통과·라이브만 터짐.

### §8 이월(carry-over)
- [백로그] 구매자 refund 조회 화면 — BE에 구매자용 refund 조회 API 부재(Admin·Webhook만)·ClaimResponse도 refund 상태 미노출. API 신설 시 재검토.
- [백로그] reasonCode↔claimType 그룹핑 — BE 규칙 강제 도입 시 FE 필터 재검토.
- [백로그·LT 박제 대기] dev 트랩 2건(stale-class·라우트 미인식) live-traps.md LT 등록.
- [연계] Track 69 타임존(β KST 통일) — 클레임 requestedAt 등 시각 표시가 이 트랙 영향받음(현재 UTC 표시 → KST). 별도 트랙.

---

## FE-15: FE 테스트 플랫폼 도입 (STEP 1 — Vitest 배선) [진행 중 트랙]

날짜: 2026-07-10
성격: 인프라 트랙(페이지 아님). STEP 1~4 다단계 — STEP1(플랫폼 배선·본 박제)·STEP2(컴포넌트 확대)·STEP3(E2E)·STEP4(CI). 본 항목은 STEP 1 한정 박제이며 후속 STEP 결정은 동 FE-15에 이어 append.
선행: FE-13/FE-14 머지. FE-14 §8 백로그 "FE 테스트 도입(CI)" 착수분. 정찰 = recon-report-test-platform.md.
STEP 1 목표: "Vitest 플랫폼이 프로젝트에 정상적으로 붙는다"를 최소 변경으로 증명. 최적 테스트 아키텍처 구성이 아님(그것은 후속 STEP).
범위: vitest 배선 + 저의존 대상 2개(node 순수함수 + Vue SFC) 통과. project 분리·Button·auto-import mock·coverage 수집·E2E·CI 전부 제외(후속 STEP).
수용기준(달성): pnpm test → 2 파일 6 케이스 green(datetime 4·EmptyState 2). environment:'nuxt'+happy-dom 정상 동작. ~ alias 자동 상속(수동 배선 0).

### §1-A 갈림길·채택/기각 근거
1) 러너 구성 (STEP1 한정)
- α 채택: 단일 defineVitestConfig, environment:'nuxt'. STEP1 목표=플랫폼 배선 증명이므로 검증 대상을 하나로 좁힘.
- β 기각(STEP2 이연): node/nuxt project 분리. 최종 형태로는 우수(순수 unit=node·컴포넌트=nuxt·속도/DOM 분리)하나, STEP1에 도입 시 include·alias·environment·runner가 두 벌 → "Vitest가 안 붙는 건지 / 분리가 잘못된 건지" 원인 분리 곤란. project 분리는 언제든 추가 가능한 리팩토링이라 STEP2로 이연.
2) config 위치
- α 채택: vitest.config.ts 신규 1개. @nuxt/test-utils 표준 경로·관심사 분리(테스트 설정을 nuxt.config에 혼입하지 않음). nuxt.config의 vite 설정(alias·tailwindcss) 자동 상속.
- β 기각: nuxt.config 공존. test-utils 표준 아님·관심사 혼입.
3) DOM 환경
- happy-dom 채택. Nuxt 공식 testing 문서 기본값. jsdom 대비 경량.
4) 테스트 위치·디렉토리 기준
- 채택: test/unit/ (종류 기준). .nuxt/tsconfig.app.json include에 test/nuxt/·tests/nuxt/ 사전 배선 실측 → co-location 불채택(include 재배선 불필요). 단 디렉토리는 환경 기준(node/nuxt)이 아니라 종류 기준(unit/component/integration)으로 — 환경은 config가 알고 디렉토리는 목적을 설명(외부검토: 종류가 환경보다 오래 살아남음). STEP1은 unit 성격 2개뿐이라 test/unit/만 생성.
- 기각: component/·integration/ 빈 디렉토리 선생성. 실제 첫 대상 생길 때 생성(빈 디렉토리 선생성 실익 없음·YAGNI).
5) STEP1 테스트 대상 범위
- 채택: datetime.ts(node 순수함수) + EmptyState.vue(순수 Vue SFC). 이 둘 통과로 "node 영역 + Vue 컴파일 영역" 최소 비용 증명.
- 기각(STEP2 이연): Button.vue. reka-ui Primitive·cn·alias·slot 등 플랫폼 외 요소 다수 → 실패 시 원인이 플랫폼인지 컴포넌트인지 구분 곤란. 플랫폼 증명 단계엔 부적합.
6) 버전 정책
- @nuxt/test-utils^4 · vitest^4 · @vue/test-utils^2.4 · happy-dom, 전부 caret. 현행 nuxt4.4/vite7/vue3.5/node24와 peer 정합(동세대). vitest 3.x 회피(test-utils v4 peer=vitest^4 불충족). caret 유지(테스트 라이브러리는 patch/minor 버그수정 잦음·고정보다 운영 편이).
7) coverage 정책
- 수집 미도입(STEP1 목표=배선 증명). 단 .gitignore에 coverage 선반영(테스트 동작 무영향·이후 도입 시 산출물 오커밋 예방). 현재 어느 .gitignore에도 coverage 규칙 부재였음(정찰 실측).

### §2 구현 중 결정·트랩
- [실측 검증] ~ alias 자동 상속 실증: vitest.config.ts에 alias 수동 배선 0으로 ~/lib/utils/datetime·~/components/common/EmptyState import 정상 해석. defineVitestConfig+environment:'nuxt'가 nuxt.config vite 설정을 상속함을 6/6 green으로 확인.
- [실측 버전] @nuxt/test-utils 4.0.3 · vitest 4.1.10 · @vue/test-utils 2.4.11 · happy-dom 20.10.6.
- [환경 우회] 세션 PATH에 pnpm 미노출 → corepack pnpm으로 설치·실행(corepack=Node24 동봉). 실행 수단 우회일 뿐 사양 무위반.
- [정정] pnpm add가 @vue/test-utils를 tilde(~2.4.11) 저장 → 확정 사양 "전부 caret" 따라 ^2.4.11로 수정.

### §8 이월(carry-over)
- [STEP2] 컴포넌트 테스트 확대. 회귀 위험 있는 것 우선(단순 존재 이유로 늘리지 않음·YAGNI). Button.vue 포함. node/nuxt project 분리 재검토. auto-import 의존 컴포넌트/컴포저블(useRuntimeConfig·navigateTo·useRoute 등) 등장 시 mockNuxtImport 전략 확정. component/ 디렉토리는 이때 생성.
- [STEP3] Playwright happy-path E2E 1개. 가장 안정적 사용자 흐름 1개로 브라우저 실행 증명. integration/ 또는 e2e 디렉토리 이때 생성.
- [STEP4] GitHub Actions에 typecheck + test 연결. ESLint 미도입(lint script·설정 부재 실측)이라 CI에서 lint 제외 — ESLint 도입 시 별도 추가.
- [FE-14 §8 연계] "FE 테스트 도입(CI)" 백로그 → 본 FE-15 트랙 착수로 진행 중 전환.

### §STEP2 — 컴포넌트 테스트 확대 (2026-07-10)

STEP1(플랫폼 배선) 위에 컴포넌트 6종 테스트 추가. 게이트 2건으로 mount 전략·mock 경계를 실측 확정 후 확대.

#### 갈림길·채택/기각
1) mount 전략 (게이트 STEP2-0·ProductCard 실측)
- 채택: mountSuspended(@nuxt/test-utils/runtime). @vue/test-utils mount는 NuxtLink를 <routerlink> stub으로만 렌더(실 <a href> 미해석·router 컨텍스트 부재)해 링크 시맨틱 검증 불가 실증. mountSuspended는 Nuxt 앱 컨텍스트(router 포함)를 세워 NuxtLink를 실 앵커로 해석(mock 0). → 순수 SFC 포함 전 컴포넌트 mountSuspended 단일화(예외 없음·SoT-구현 정합). STEP1 EmptyState도 정렬(행동 불변 리팩터링).
- 기각: mount 혼용. "이 컴포넌트가 순수한가" 판단 비용 + 순수 SFC가 후에 auto-import 얻으면 전략 변경 필요.

2) composable 의존 컴포넌트 mock (게이트 STEP2-2·HomeProductGrid)
- 채택: mockNuxtImport로 composable(useProducts) 경계 처리. vi.hoisted 홀더 + it별 mockReturnValue 패턴 확립. data/pending/error를 ref로 주입해 4상태(pending/error/empty/success) 상태머신 검증. registerEndpoint·실 useFetch 미도입(네트워크 검증이 아니라 상태머신 검증이 목적).
- 재사용성: useFetch 계열({data,pending,error,refresh})은 반환 형태만 맞추면 동일 패턴. $fetch 직접 호출 composable은 반환 형태 상이·별도 실측.

3) store 의존 컴포넌트 전략 (AppHeader·외부검토)
- γ 채택: store(useAuthStore·useCartStore)·useRoute·navigateTo를 전부 mockNuxtImport로 경계 처리. AppHeader 책임은 store 내부가 아니라 store와의 상호작용(렌더 분기 + handleLogout 오케스트레이션)이므로 경계만 mock. HomeProductGrid composable mock과 동일 사고 모델("Nuxt boundary는 mock한다") 유지.
- α createTestingPinia 기각: auth.isAuthenticated·cart.count가 setup store의 파생 computed(useCookie 토큰·items 파생)라 getter 직접 주입 불가·state override 마찰. AppHeader 테스트가 store 구현에 종속되어 부분 통합 테스트화.
- β 실제 store 기각: 쿠키·네트워크까지 끌려옴·컴포넌트 테스트 범위 초과.
- mock drift 방지: AppHeader 실제 소비분만 최소 mock(auth={isAuthenticated,logout}·cart={count,clear}). store 전체 흉내 금지 → API 변경 시 수정 지점 명확·거짓 성공 방지.

4) 검증 범위 (외부검토·전 대상 공통)
- 상태머신·계약까지만. HomeProductGrid=4상태별 올바른 자식 렌더. ErrorState=기본/커스텀 message + retry emit 계약. ProductCard=name·anchor href·가격(원+숫자). AppHeader=렌더 분기 + 오케스트레이션. 자식 내부 구현·CSS 클래스·snapshot 미검증(MVP 과잉).
- 가격 검증: toLocaleString 전체 문자열 비교 금지(Node/ICU 버전차). '원' 포함 + 숫자 포맷 존재 수준(견고성).
- NuxtLink: 이동(Router 책임) 아니라 href/to 속성까지만.

5) 대상 선정 (회귀 위험순·YAGNI)
- 채택: (상)AppHeader·HomeProductGrid·ProductCard + (중)ErrorState. 회귀 위험 있는 것.
- 제외: AppFooter·HomeHero(정적·회귀 표면 0·"존재 이유 테스트" 금지). LoadingSkeleton(후순위). Button(reka-ui 결합·이연 유지).

6) 디렉토리
- test/component/ 생성(종류 기준). 순수 단위는 test/unit/ 유지. component/·integration/ 빈 디렉토리 선생성 금지 원칙대로 실대상 생길 때 생성.

#### 구현 실측·트랩
- vi·ref 명시 import 필요: vitest globals 미설정(vi)·test 파일 top-level auto-import 미적용(ref from vue). vitest.config 무변경으로 확인.
- store 반환 객체 속성은 .value 없이 직접 소비(isAuthenticated·count) → mock은 평범한 값으로 충분.
- EmptyState.spec.ts는 test/unit/ 잔류(컴포넌트지만 파일 이동은 범위 밖). mountSuspended 정렬만.

#### §STEP2 이월
- [STEP3] Playwright happy-path E2E 1개. integration/ 또는 e2e 디렉토리 이때 생성.
- [STEP4] CI(GitHub Actions): typecheck + test. ESLint 미도입이라 lint 제외.
- [백로그] store 자체 단위 테스트(isAuthenticated 파생·expired·count·decodeJwtPayload·logout). Component Test보다 Node Unit에 가까움·별도 트랙. $fetch 직접 호출 composable(useCheckout·useAddresses 등) 테스트 시 반환 형태 별도 실측. Button(reka-ui) 테스트. LoadingSkeleton. happy-dom→jsdom 전환 조건(ResizeObserver·IntersectionObserver·Clipboard·Layout 등 DOM API 등장 시).

### §STEP3 — Playwright Browser/SSR Smoke (2026-07-10)

STEP1·2(Vitest 컴포넌트/단위) 위에 브라우저 기동 계층 신설. STEP3는 "Playwright가 브라우저를 띄워 SSR 렌더까지 도달함"의 최소 증명이며, 사용자 여정 E2E가 아니라 Browser/SSR Smoke로 범위를 고정한다(외부검토 반영). 착수 전 정찰 2회(recon-report-fe15-step3 = FE/인증 계약, recon-report-fe15-step3-infra = 실행 토폴로지) — 두 번째 정찰에서 "실행 토폴로지가 시나리오·러너보다 선행 병목"이 드러나 결정 0으로 승격.

#### 갈림길·채택/기각
0) 실행 토폴로지 (결정 0 — 선행 병목)
- 채택: SSR·Playwright 모두 frontend 컨테이너 내부 실행. 로컬은 reuseExistingServer로 구동 중 dev(:3000) 재사용. 근거: backend host 포트 미노출(compose zslab_mall_backend에 ports 없음·8080 내부만) → 컨테이너 밖 프로세스는 SSR로 backend 도달 불가(인프라 정찰 실측). frontend·backend 동일 gateway_net → 내부 alias mall-backend:8080 해석 가능. node_modules 익명 볼륨이라 host/container 분리 → 컨테이너로 통일.
- α 기각(컨테이너 밖 로컬 preview): backend 포트 미노출로 localhost:8080 도달 경로 없음 → SSR fetch 실패.
- γ 기각(실 dev 스택 https): 게이트웨이/nginx conf 로컬 미보유(external network·서버 전용) → 로컬 재현 불가.
1) 성격 (결정 1 — Browser/SSR Smoke·외부검토 반영)
- 채택: goto → SSR 렌더 assert까지만. 인증·click→API·쓰기 흐름 제외. 명명을 "E2E"가 아니라 "Browser/SSR Smoke"로 고정 — click→API→render를 거치지 않아 User Journey가 아니며, 이름에서 범위를 드러내 "이게 무슨 E2E냐" 오해를 차단(외부검토 수용). 근거: MVP 스모크 목적(라우팅·SSR·시드 노출 성립의 최소 체인 확인).
- β 기각(로그인→마이페이지): 인증 흐름은 Cookie(secure:true·http 미저장 소지)·Gateway·HTTPS·CORS가 전부 걸려 STEP3("Playwright가 돈다") 목표 대비 과중 → 인증 트랙 이월.
2) /api 프록시 (결정 2)
- 채택: nuxt.config.ts routeRules '/api/**': { proxy: API_INTERNAL_BASE||'http://mall-backend:8080' + '/api/**' }. 근거: :3000 직접 접근(게이트웨이 미경유) 시 client-side /api 도달 확보. 타깃 env화(하드코딩 금지).
- 게이트웨이 비충돌 실측: SSR은 apiInternalBase 직결이라 /api 미사용, 실 dev는 gateway_nginx가 /api를 선점해 Nuxt에 미도달 → routeRule은 :3000 직접 경로에서만 활성(가로챈 요청엔 무작용).
3) 디렉토리·러너 분리 (결정 3)
- 채택: 최상위 frontend/e2e/ + vitest.config.ts exclude:[...configDefaults.exclude,'e2e/**']. 근거: Vitest 기본 글롭이 e2e/*.spec.ts를 잡으면 @playwright/test import로 실패 → exclude 필수(STEP2까지 부재분 신규 추가). 검증: vitest 6 files 유지(e2e 미수집)·smoke는 playwright test로만 실행.
4) Playwright 구성 (결정 4)
- 채택: chromium 단일 + webServer 'pnpm build && pnpm preview'(clean/CI 정본) + reuseExistingServer:!CI(로컬 dev 재사용) + baseURL http://localhost:3000. 근거: 단일 브라우저 MVP·CI는 프로덕션 preview 정본·로컬은 dev로 SSR 동등 검증(preview 동시기동은 :3000 충돌이라 CI로 이월).
- Firefox·WebKit 기각(백로그): CI 안정화 이후 충분(YAGNI).

#### 구현 실측·트랩
- 프록시는 dev 서버 재시작 후 활성(nuxt.config 변경은 HMR 아님) → 컨테이너 재시작으로 재로딩 후 curl :3000/api/v1/products 200·totalCount 2 확인.
- chromium은 컨테이너 익명 볼륨(/root/.cache/ms-playwright)에 설치 → 컨테이너 recreate 시 재설치 필요. Dockerfile.dev 반영은 이번 범위 밖(CI는 별도 install 스텝 전제).
- webServer는 SSR이 backend 도달 가능한 환경(컨테이너/CI)에서만 실행 가능 — host 단독 preview는 mall-backend 미해석으로 실패(결정 0 제약의 재확인).
- Dockerfile.dev 불변(dev CMD 유지·무회귀). smoke는 상품 publicId 랜덤이라 하드코딩 불가 → 목록에서 첫 카드 href 추출 후 상세 goto·h1/가격 SSR assert. 데모 시드 미기동 시 skip 아닌 명확 실패(카드 부재 메시지).

#### §진입점
1) 목적: Playwright 브라우저 기동 + SSR 렌더 도달을 스모크 1개로 증명(인증·쓰기 제외).
2) 실행: docker exec zslab_mall_frontend pnpm test:e2e(컨테이너 내부·dev 재사용).
3) 설정: frontend/playwright.config.ts(testDir e2e·chromium·baseURL :3000·webServer build&&preview·reuseExistingServer !CI).
4) 스펙: frontend/e2e/smoke.spec.ts(목록→첫 카드 href→상세 goto→h1·가격 SSR assert).
5) 프록시·러너 분리: frontend/nuxt.config.ts routeRules(/api→API_INTERNAL_BASE)·frontend/vitest.config.ts exclude e2e/**·package.json test:e2e script.
6) 전제·트랩: catalog.demo-seed.enabled=true(상품 2건)·backend 포트 미노출(컨테이너 내부 실행 강제)·chromium 컨테이너 볼륨(recreate 시 재설치).

#### §STEP3 이월(carry-over)
- [STEP4] CI(GitHub Actions): typecheck + vitest + (chromium install → smoke). backend·데모 시드 기동 전제 job 설계. ESLint 미도입이라 lint 제외.
- [백로그·fixture] E2E fixture 별도 관리 — 시드 상품은 운영 정책 변화에 취약(첫 카드/데모 상품명 의존) → 장기적으로 E2E 전용 fixture로 데이터 소유·격리. 현 스모크엔 과함(외부검토 반영).
- [백로그·인증 트랙] 인증 세션 주입(프로그래매틱 로그인·쿠키)·시드 상태 제어 후 인증/쓰기 User Journey E2E 확대. secure 쿠키(http 미저장)·CORS(duckdns 한정)·게이트웨이 경유가 전제라 실스택(γ) 토폴로지 필요. 데모 자격증명(login.vue handleDemoLogin·NUXT_PUBLIC_DEMO_* 로컬 구성)의 DB 계정 실재 확인 병행.
- [백로그·인프라] chromium을 Dockerfile.dev 또는 CI 캐시로 고정(volume 재설치 제거)·preview 정본 경로 CI 실검증.

### §STEP4 — CI (typecheck + vitest) (2026-07-10)

STEP1~3(Vitest 컴포넌트/단위 + Playwright Browser/SSR Smoke) 위에 GitHub Actions 검증 계층 신설. STEP4는 "PR·main push마다 FE 타입·단위 테스트가 자동 검증됨"의 최소 자동화이며, FE-15 마지막 STEP. smoke(브라우저)·backend·DB는 런타임 풀스택 기동 난도가 높아 본 STEP에서 제외하고 별도 이월(외부검토 반영). 기존 CI는 deploy.yml(운영 SSH 배포) 1개뿐이라 테스트 CI는 본 워크플로가 최초. 정찰 = recon-report-fe15-step4.

#### 갈림길·채택/기각
1) CI 범위 (결정 1)
- α 채택: frontend typecheck + vitest만. 근거: 두 명령은 backend·DB·브라우저 무의존(정찰 §2 실측)이라 러너에서 즉시 green 재현·저비용 회귀 신호 확보. smoke 파이프라인이 도는 것은 STEP3에서 로컬로 이미 증명 → CI에서의 추가 가치(렌더 회귀 감시) 대비 풀스택 기동 비용이 비대칭(YAGNI·기조4).
- β 기각(smoke 동시 포함): 정찰 실측상 compose 2파일이 external network(gateway_net·zslab_zslab_net)·외부 소유 MariaDB(zslab_mariadb 서비스 미정의) 전제라 CI 무수정 재사용 불가 + SSR이 API_INTERNAL_BASE 기본값(zslab_mall_backend)이면 Tomcat Host 검증 400. 한 STEP에 묶으면 typecheck/vitest green까지 지연 → 이월.
- backend 범위 명확화: backend 테스트/빌드 CI는 별도 트랙 예정이며 본 워크플로는 frontend 전용(현재 backend CI 부재 = 미테스트 아님·범위 분리).

2) 트리거 (결정 2)
- 채택: pull_request→main·push→main, paths [frontend/**, .github/workflows/**]. 근거: FE 변경·CI 정의 변경에만 반응해 무관 커밋(backend 등)에 러너 낭비 회피. PR(머지 전 게이트)+push(main 직접 반영 회귀) 양쪽 포착. deploy.yml 변경 시 1회 초과 구동은 무해로 수용.

3) pnpm 버전 고정·캐시 (결정 3)
- 채택: frontend/package.json "packageManager":"pnpm@11.10.0"(로컬 corepack 실측·lockfileVersion 9.0 정합). 사유: Corepack 기본 버전 비의존·로컬↔CI pnpm 버전 재현성 확보(외부검토 반영).
- store 캐시: setup-node cache:'pnpm' + cache-dependency-path:frontend/pnpm-lock.yaml. pnpm store 재사용으로 install 반복 비용 절감(외부검토 반영·구현분·백로그 아님).

#### 구현 실측·트랩
- 루트 package.json 부재(frontend/만 존재) → pnpm/action-setup@v4에 package_json_file:frontend/package.json 명시해야 핀에서 버전 자동 인식(기본값은 루트 package.json 조회라 미지정 시 "no version" 실패 트랩).
- 스텝 순서: action-setup(pnpm 설치)을 setup-node(cache:'pnpm')보다 먼저 — cache:pnpm은 pnpm 실행파일 선존재 전제.
- cache-dependency-path는 defaults.working-directory(frontend)와 무관하게 repo 루트 상대(frontend/pnpm-lock.yaml).
- typecheck 스크립트가 'nuxt prepare && vue-tsc -b'라 .nuxt 타입 생성 선행 포함 — CI는 pnpm typecheck 한 줄로 충분(별도 prepare 스텝 불요).
- install은 --frozen-lockfile(핀 pnpm11·lock 9.0 정합)로 lock 표류 차단.
- 로컬 검증(정본 컨테이너): pnpm typecheck EXIT0(vue-tsc 에러 0)·pnpm test 6 files/21 tests passed(e2e/smoke.spec.ts 미수집 = vitest exclude 작동). paths 필터·store 캐시·frozen-lockfile 실동작은 PR에서만 검증 가능(로컬 한계).
- [LT 후보·CI store-dir 우선순위] pnpm-workspace.yaml storeDir가 컨테이너 전용 절대경로(/app/node_modules/.pnpm-store)면 CI 러너(ubuntu-latest)에서 mkdir '/app' EACCES(exit 243). 3라운드 실측 확정: job env npm_config_store_dir(14eb6002)·.npmrc는 storeDir에 우선순위 밀려 실패, install CLI 플래그 --config.store-dir(2a104705)만 오버라이드 성공. setup-node cache:pnpm은 내부 pnpm store path가 /app 참조하므로 제거·actions/cache로 store 직접 캐싱 대체. pnpm-workspace.yaml 무변경(컨테이너 트랩 회피 보존). 컨테이너 절대경로 설정이 CI에서 터지는 일반 패턴(향후 backend CI·풀스택 Browser Smoke CI 재발 주의) → live-traps 등재 검토.

#### §진입점
1) 목적: PR·main push마다 FE typecheck + vitest 자동 green 게이트(브라우저·backend 무관).
2) 파일: .github/workflows/frontend-ci.yml(job frontend-check·ubuntu-latest·working-directory frontend).
3) 스텝: checkout@v4 → pnpm/action-setup@v4(package_json_file:frontend/package.json) → setup-node@v4(node 24·cache pnpm) → install --frozen-lockfile → pnpm typecheck → pnpm test.
4) 버전 SoT: frontend/package.json packageManager(pnpm@11.10.0) 단일 소스 — action-setup·로컬 corepack 공통 참조.
5) 트리거: pull_request/push→main + paths(frontend/**·.github/workflows/**).

#### §STEP4 이월(carry-over)
- [이월·풀스택 Browser Smoke CI] "풀스택 Browser Smoke CI(MariaDB→Backend→시드→Preview→Playwright)" 별도 STEP/트랙. 정찰 근거: (a) compose 2파일 external network·외부 MariaDB 전제라 CI 무수정 재사용 불가 → CI 전용 compose 또는 GHA services(mariadb:11.4) 신설. (b) SSR backend 도달 위해 API_INTERNAL_BASE 언더스코어 없는 주소 주입 필수(기본값 zslab_mall_backend → Tomcat Host 400 트랩). (c) fresh DB fail-fast(ADMIN_BOOTSTRAP_* 필수)·데모 시드 활성(local 프로파일 or catalog.demo-seed.enabled=true)·기동 순서(DB→backend /actuator/health 200→playwright build/preview) 배선. (d) chromium CI install(Linux --with-deps)·@playwright/test 1.61.1 캐시 키.
- [이월·backend CI] backend 테스트/빌드 CI 별도 트랙(Testcontainers mariadb:11.4는 gradle test 전용·상시 DB 아님).
- [백로그] Firefox·WebKit 확대·E2E fixture 소유·인증 User Journey E2E(STEP3 이월 승계).

---

## FE-15 종결 표기 정정 (2026-09-15)

본문 헤더([진행 중 트랙]·:743)와 로드맵(완료)이 불일치했음. STEP2~4 커밋(5c7828dc·a0049fdd·4676d0f9)과 CI 가동으로 FE-15 종결 확정. 본문 이월 항목(풀스택 Browser Smoke CI·backend CI·Firefox/WebKit 등)은 백로그로 승계.

---

## FE-16: 체크아웃 배송지 연동 (저장 배송지 불러오기·주문 후 저장)

날짜: 2026-09-15
선행: FE-13(계정·배송지 CRUD)·FE-11(체크아웃) 머지. 신규 BE 없음(기존 /users/me/addresses·/cart/checkout 재사용). 정찰 = recon-report-fe16-address(본문 + 부록·기진행 여부 점검).
범위: checkout/index.vue에 저장 배송지 불러오기(기본 자동입력·드롭다운·새 주소)·주문 생성 직후 신규 저장·배송지 필드 maxlength. 순수 로직은 lib/utils/address-form.ts로 분리·단위 테스트. mypage/addresses.vue·useAddresses 시그니처·결제/주문 로직 무변경.
수용기준(달성): 기본 배송지 SSR 자동입력 / 드롭다운·새 주소 전환 / 무변경 시 저장 생략 / 주문 후 신규 저장(isDefault:false) / 저장 실패 격리로 결제 무차단 / maxlength / 로드 실패 안내. typecheck 0·vitest 29·SSR 1a/1b·E2E 2~7 실측 GREEN.

### §1-A 갈림길·채택/기각 근거
1) 수정한(불러온) 주소의 저장 방식
- α 채택: POST 신규 추가(createAddress). 불러온 주소를 편집해 저장하면 별도 신규 주소로 남긴다.
- β 기각: PATCH 원 주소 수정. 체크아웃에서의 수정이 사용자 인지 없이 마이페이지 주소·기본 배송지를 바꿀 위험. 확정 흐름("기존 POST 배송지 API로 저장")과도 불일치.
2) 기본 배송지 부재 시
- α 채택: 빈 폼(find(isDefault) 실패 시 무입력·checkout/index.vue:107).
- β 기각: 목록 첫 항목 자동입력. 목록은 서버 반환 순서 의존·정렬 미보장(addresses.vue 클라 정렬 없음)이라 "첫 항목"이 불안정.
3) 저장 주소 선택 후 편집
- α 채택: 자유 편집. 불러온 값을 그대로 고쳐 쓸 수 있어 운영 편의.
- β 기각: 읽기 전용 + "새 주소" 전환 강제. 단계 증가·이점 없음.
4) 결제 준비 실패(publicId=null) 시 저장 여부
- α 채택: 저장. maybeSaveAddress를 publicId 판정보다 먼저 호출(checkout/index.vue:181). 결제 준비 실패일 뿐 배송지 오입력이 아니고 주문은 이미 생성됨.
- β 기각: 저장 생략.
5) 목록 로드 실패(401 외)
- α 채택: 배송지 영역 안내 문구(addressLoadFailed·checkout/index.vue:58,233). 수기 입력·제출은 그대로 허용.
- β 기각: 무안내(수기 입력만).
6) 체크아웃 필드 maxlength
- α 채택: 이번 범위 포함(공유 6필드·checkout/index.vue:259~326). DB VARCHAR 초과 시 서버 실패 예방·account.ts 상수 재사용·저비용.
- β 기각: 제외(범위 밖).
7) 목록 로드 방식
- α 채택: useAsyncData SSR(checkout/index.vue:33). addresses.vue·profile·orders 관습·기본 자동입력이 SSR HTML에 반영.
- β 기각: lazy·onMounted. 자동입력 지연·관습 이탈.

### §2 확정 구현 규칙 (file:line)
- 저장 조건: "배송지 저장" 체크박스 기본 체크 + 선택 주소와 무변경이면 저장 생략·체크박스 숨김. showSaveCheckbox = !isUnchanged(...)(checkout/index.vue:65)·maybeSaveAddress 가드(:151).
- 저장 페이로드: isDefault:false 고정(타입상 필수 필드 → "기본 지정 미요청"의 구현·첫 주소는 서버가 기본 강제)·addressLabel·deliveryMemo 제외·선택 필드 빈값→undefined. buildCreateAddressRequest(lib/utils/address-form.ts).
- 저장 위치: checkout.submit 성공 직후·publicId 판정 전·goToMockPayment 이전(checkout/index.vue:181). 실패는 try/catch 격리·console.warn만(:149~).
- 저장 성공 시: 응답 Address를 로컬 목록에 추가 + 해당 주소 "선택+무변경"으로 전환(재제출 중복 저장 방지).
- deliveryMemo는 주소 선택·교체 시 유지(주문별 값·applyAddress/clearAddressFields 대상 아님).

### §진입점
1. 목적: 체크아웃에서 저장 배송지 자동입력·드롭다운 선택·주문 후 신규 저장으로 재입력 부담 제거(신규 BE 0).
2. 페이지: frontend/app/pages/checkout/index.vue(SSR 로드·자동입력·드롭다운·체크박스·저장 배선·maxlength·로드 실패 안내).
3. 순수 로직: frontend/app/lib/utils/address-form.ts(isUnchanged·buildCreateAddressRequest·CheckoutAddressForm). datetime.ts 선례로 utils 배치.
4. 테스트: frontend/test/unit/address-form.spec.ts(isUnchanged 5·buildCreateAddressRequest 3).
5. 재사용 자산: composables/useAddresses.ts(listAddresses·createAddress·시그니처 무변경)·lib/constants/account.ts(공유 6필드 maxlength 상수).
6. 전제·트랩: LT-12(useAsyncData 콜백 내 store 접근 회피·useAddresses는 setup 최상위 호출)·frontend/.nuxt bind-mount 공유(아래 트랩)·로컬 hosts 127.0.0.1 가드.

### §실측·트랩
- 검증: typecheck 0·vitest 7 files 29 tests·SSR HTML 1a(기본 자동입력·6필드 대조 일치)·1b(0건 빈 폼·드롭다운 숨김)·dev E2E 2~7(Playwright·호스트 Chromium→게이트웨이). checkout.submit은 page.route 목킹으로 실주문 0건·실측 데이터(테스트 주소·카트) 사후 정리 완료.
- [트랩 후보·1회차] typecheck의 nuxt prepare가 bind-mount된 frontend/.nuxt를 재생성 → 실행 중 dev 서버의 #app-manifest 대상 소실 → vite import-analysis 오류. 조치: frontend 컨테이너 재시작. 규칙: dev 실측 중 typecheck(nuxt prepare) 재실행 금지·필요 시 실측 후 실행 + 컨테이너 재시작. 재발 시 live-traps 승격(LT-15 계열). (본 트랙 실발생: 구현 단계 typecheck 후 오류 발생 → 컨테이너 재시작으로 복구. 이후 E2E 중 typecheck 미재실행으로 재발 없음.)
- [가드] 로컬 hosts가 zslab-mall.duckdns.org를 운영 IP로 두는 경우가 있음(자동 모드 운영 도메인 쓰기 차단으로 운영 영향 0). 인증 dev 실측 전 DNS/hosts 127.0.0.1 확인 필수.

### §8 이월(carry-over)
- [미확인·BE] 기본 배송지 삭제 시 자동 승격 거동·주소 개수 상한. 현 구현은 저장 실패 격리라 결제 무영향.
- [백로그] 우편번호 검색 API(FE-11·FE-13 이연 승계)·주문 요약·CI 체크아웃 경로 미커버(인증 User Journey E2E 백로그 승계).


## FE-13 §8 로그아웃 잔류 버그 해소 정정 (2026-09-15)

FE-13 §8(:701) 이월 "[버그·백로그] BUYER 페이지에서 로그아웃 시 페이지 잔류"는 커밋 8f6d4c87(2026-07-10 `fix(fe): 로그아웃 시 보호 페이지에서만 홈 이동`·AppHeader.vue·origin/main 반영)로 해소됨. 방식 = handleLogout이 현재 라우트가 buyer 보호 페이지일 때만 로그아웃 후 '/' 이동, 공개 페이지는 잔류(recon-report-logout 후보 (b)·isBuyerProtectedRoute). zslab 확인으로 의도된 동작 확정. 당시 decisions-fe.md 기록 누락으로 백로그 정찰(recon-report-backlog)에서 미해소로 분류됐던 것을 정정한다. 본문 :701은 append-only 원칙에 따라 수정하지 않는다.

## FE-17: 체크아웃 주문 요약 (상품 목록·결제 금액)

날짜: 2026-09-15
선행: FE-16(체크아웃 배송지 연동) 머지. 신규 BE 없음(기존 GET /cart·POST /cart/checkout 재사용). 정찰 = recon-report-fe17-summary.
범위: checkout/index.vue에 주문 상품 목록(왼쪽·배송지 위)과 결제 금액 요약(오른쪽 결제 영역)·결제 버튼 금액 문구·선택 0개/구매 불가 안내. 순수 로직은 lib/utils/checkout-summary.ts로 분리·단위 테스트. 주문 완료 페이지·cart.vue·결제/주문/배송지 로직 무변경.
수용기준(달성): 목록(썸네일·상품명·판매자·단가·수량·소계) / 요약(상품 금액·배송비 0원·최종 금액·종류·수량) / 버튼 "N원 결제하기" / 선택 0개 안내+링크+비활성 / 구매 불가 포함 시 결제 차단 / 화면 최종 금액 == /payment/mock amount. typecheck 0·vitest 35·SSR·E2E 1·2·3·5 실측 GREEN.

### §1-A 갈림길·채택/기각 근거
1) 구매 불가(selected ∧ !purchasable) 선택 품목 처리
- α 채택: 결제 차단. 목록에 "구매 불가 (품절 또는 판매 중지)" 구분 표시(OrderItemList.vue:20,42)·요약에 삭제 유도 안내+장바구니 링크(PaymentSummary.vue:24)·버튼 비활성(canSubmit·checkout/index.vue:141).
- β 기각: 요약에서만 제외하고 결제 허용(cart.vue selectedTotal 필터 준용). 서버는 selected 전체를 주문에 포함(CartCheckoutService.java:49)하고 신규 주문 경로는 상품 status 재검증이 없어(D-63) 판매중지+재고 有 상품이 주문될 수 있음 → 화면 금액 ≠ 실제 결제 금액.
2) 옵션명 표시
- α 채택: 이번 범위 제외. 다중 옵션 시드 부재로 검증 불가·장바구니/주문 상세에도 없어 체크아웃만 넣으면 화면 간 불일치.
- β 기각: 체크아웃만 BE 응답 필드 추가 → 별도 트랙(§8).
3) 배송비 표시
- α 채택: 0원 상수 표시(SHIPPING_FEE·lib/constants/checkout.ts:5). 서버 계산도 배송비 0(D-61·CheckoutService.java:177). 배송비 정책 트랙에서 교체.
- β 기각: 미표시. 최종 금액 구성이 불투명.
4) 구매 조건 동의 체크박스
- α 채택: 제외(데모·실제 약관 없음).
- β 기각: 포함.
5) 선택 0개
- α 채택: 요약 안내 "선택된 상품이 없습니다" + 장바구니 링크(PaymentSummary.vue:17) + 버튼 비활성(checkout/index.vue:140). 기존 CART_CHECKOUT_EMPTY 422 처리 유지.
- β 기각: 제출 후 422 안내만. 불필요 요청·안내 지연.
6) 요약 금액 출처
- α 채택: FE 계산(cart items·buildCheckoutSummary). 체크아웃 응답에 금액 필드 없음·redirectUrl 쿼리(amount)로만 전달(checkout/index.vue goToMockPayment).
- β 기각: 응답 기반. 제출 전에는 금액을 알 수 없음.
7) 금액 포맷
- α 채택: 로컬 formatPrice 관습 유지(cart.vue·orders·ProductCard 동일 패턴). 최소 변경.
- β 기각: 공용 util 신설. 무관 파일 6곳 리팩토링 유발.

### §2 확정 구현 규칙 (file:line)
- 장바구니 재조회: 진입 시 useAsyncData('checkout-cart', cart.load)(checkout/index.vue:23~28·cart.vue 패턴·LT-12 언랩 주석). 렌더는 store cart.items 반응. 로드 실패는 CommonErrorState+재시도(:246).
- buildCheckoutSummary(lib/utils/checkout-summary.ts:31): 목록 = selected 전체(구매 불가 포함·표시용) / 금액·종류 수·수량 = selected ∧ purchasable / hasUnpurchasableSelected = 둘의 개수 불일치(:37) / finalAmount = productTotal + SHIPPING_FEE(:42).
- canSubmit(checkout/index.vue:134~142) = 배송지 필수 4 입력 ∧ summary.items.length > 0 ∧ !hasUnpurchasableSelected. 버튼 활성일 때 selected 전체 = purchasable이므로 화면 금액 == 서버 금액.
- 버튼 문구: `${formatPrice(summary.finalAmount)} 결제하기`(:403)·제출 중 "주문 처리 중…" 유지.
- 컴포넌트 분리: components/checkout/(common·ui 서브디렉토리 관습·자동 import CheckoutOrderItemList·CheckoutPaymentSummary). 결제 버튼·제출은 페이지 소유.
- 기존 안내문 "선택하신 장바구니 상품으로 주문을 생성합니다."는 요약 컴포넌트로 대체.

### §진입점
1. 목적: 결제 전 주문 상품·금액을 화면에서 확인(신규 BE 0).
2. 페이지: frontend/app/pages/checkout/index.vue(cart 로드·summary·canSubmit·컴포넌트 배치·버튼 문구).
3. 순수 로직: frontend/app/lib/utils/checkout-summary.ts(buildCheckoutSummary·CheckoutSummary).
4. 컴포넌트: frontend/app/components/checkout/OrderItemList.vue(목록)·PaymentSummary.vue(금액 요약·안내).
5. 상수: frontend/app/lib/constants/checkout.ts(SHIPPING_FEE=0).
6. 테스트: frontend/test/unit/checkout-summary.spec.ts(빈 목록·미선택 제외·합계/수량/종류·배송비 0·구매 불가 포함·구매 불가 미선택 6케이스).

### §실측·트랩
- 검증: typecheck 0·vitest 8 files 35 tests·SSR HTML(상품명 2종·최종 금액·버튼 문구·FE-16 자동입력 value 포함·에러 오렌더 0)·dev E2E(Playwright·호스트 Chromium→게이트웨이·데모 buyer) 1 목록/종류/수량/합계/배송비/최종/버튼 · 2 실제 체크아웃 → /payment/mock amount == 화면 최종 금액 · 3 선택 0개 안내/링크/비활성 · 5 FE-16 자동입력·무변경 시 저장 없음 PASS. E2E 4(구매 불가) 재현 불가(카탈로그 품절 variant 0건·buyer 권한으로 생성 불가) → 단위 테스트 2케이스로 대체.
- FE-16 트랩(nuxt prepare가 bind-mount .nuxt 재생성) 규칙 준수: typecheck 후 frontend 컨테이너 재시작 뒤 런타임 검증 → 재발 없음.
- 로컬 dev 실측 부산물: 미결제 주문 1건 잔존(PENDING_PAYMENT·삭제 API 없음·자동취소 배치 대상). 카트 스냅샷 복원·테스트 주소 삭제 완료.

### §8 이월(carry-over)
- [백로그·FE] 장바구니 페이지 단계에서 구매 불가 선택 품목 안내·결제하기 차단(현재는 체크아웃에서 차단).
- [백로그·BE] 신규 주문 경로의 상품 판매 상태 검증 부재(D-63) — 판매중지 상품이 재고 有면 주문 생성 가능. 서버 검증 필요 여부 판단 필요.
- [백로그·FE+BE] 옵션명 표시 트랙: BE 응답 필드 + 장바구니·체크아웃·주문 상세 표시 + 다중 옵션 시드(FE-10a 다중 옵션 매칭 미검증 항목 함께 해소).
- [백로그] 배송비 정책 트랙 시 SHIPPING_FEE 상수 교체.
- 주문 완료 페이지 요약: 범위 제외(FE-11 최소 구성 유지).

## FE-18: 판매중지·품절 표시와 구매 차단

날짜: 2026-09-15
선행: decisions.md D-160(Track 71·BE 판매 상태 전환·상세 허용·담기/주문 422·같은 브랜치·같은 PR). 정찰 = docs/track-71/recon-report.md §4.
범위: 상품 상세(판매중지·품절 표기·담기 비활성·담기 실패 문구)·장바구니(구매 불가 선택 품목 안내·결제하기 차단). 목록 카드(품절 배지 기존 유지)·체크아웃(FE-17)·결제/주문 화면 무변경.
수용기준(달성): 판매중지 상세 200 + "판매가 중지된 상품입니다" + 담기 비활성(SSR 포함) / 품절 "품절" + 담기 비활성 / 담기 422 전용 문구 / 장바구니 안내 + 결제하기 비활성 / 체크아웃 FE-17 차단 유지. typecheck 0·vitest 35·E2E 29/29 GREEN.

### §1-A 갈림길·채택/기각 근거
1) 상세 표기 위치
- α 채택: 기존 품절 오버레이 배지(soldout 토큰) 재사용·라벨만 교체(판매중지 > 품절 우선·둘 다 해당 시 판매중지만). 중복 표기 없음.
- β 기각: 버튼 위 별도 표기 영역 추가. 품절 표기와 이중 노출.
2) 판매 불가 판정
- α 채택: 페이지 computed(`unavailableLabel`). 분기 2개.
- β 기각: lib/utils 순수 함수 + 단위 테스트. 분기 대비 과함.
3) 장바구니 차단 방식
- α 채택: 안내 문구 + 결제하기 비활성. 구매 불가 품목은 선택 해제 불가·삭제만 가능하므로 삭제를 유도.
- β 기각: 자동 선택 해제. 서버 selected 상태 변경 부작용·사용자 인지 없이 주문 대상 변경.
4) 장바구니 판정식
- α 채택: cart.vue 인라인 `some(selected ∧ !purchasable)`.
- β 기각: FE-17 `buildCheckoutSummary.hasUnpurchasableSelected` 재사용. 판정식은 같으나 체크아웃 전용 util(합계·수량 계산 포함)에 대한 불필요한 의존.
5) 목록 품절 배지
- 기존 유지(변경 없음). 판매중지는 목록에서 숨겨지므로 카드 변경 불요.

### §2 확정 구현 규칙 (file:line)
- 타입: `frontend/app/types/product.ts:87` `ProductDetail.saleStopped`(BE Track 71 필드).
- 상세 판정: `pages/products/[productPublicId].vue:76` `unavailableLabel`(saleStopped → '판매가 중지된 상품입니다' / soldOut → '품절' / null) · `:83` `canAddToCart` = variant 확정 ∧ !variant.soldOut ∧ unavailableLabel===null · `:186` 오버레이 `v-if="unavailableLabel"` · `:278` 버튼 `:disabled="!canAddToCart || adding"`.
- 담기 실패 문구: `:133-134` statusCode 422 ∧ data.code==='CART_ITEM_NOT_PURCHASABLE' → '지금 구매할 수 없는 상품입니다.'(checkout/index.vue의 code 추출 방식 동일). 401·기타 문구 기존 유지.
- 장바구니: `pages/cart.vue:32` `hasUnpurchasableSelected` · `:35` `checkoutEnabled = some(selected ∧ purchasable) ∧ !hasUnpurchasableSelected` · `:218` 안내 '구매할 수 없는 상품이 포함되어 있습니다. 삭제 후 결제해 주세요.'(role=alert·text-soldout) · `:221` 결제하기 `:disabled="!checkoutEnabled"`. `selectedTotal`(selected ∧ purchasable) 기존 유지.

### §진입점
1. 목적: 판매중지·품절 상품을 상세에서 명확히 표기하고 담기·결제 진입을 화면 단계에서 차단(서버 422와 이중 방어).
2. 상세: frontend/app/pages/products/[productPublicId].vue(unavailableLabel·canAddToCart·오버레이·담기 문구).
3. 장바구니: frontend/app/pages/cart.vue(hasUnpurchasableSelected·checkoutEnabled·안내).
4. 타입: frontend/app/types/product.ts(saleStopped).
5. 재사용 자산: 품절 오버레이 배지(soldout 토큰·rounded-badge·bg-badge-soldout-bg)·checkout/index.vue 422 code 추출 패턴.
6. 전제·트랩: BE D-160 상세 허용·saleStopped 필드 선행 / FE-16 트랩(typecheck 후 frontend 재시작) / 로컬 hosts 127.0.0.1 가드 / 관리자 전환·재고 조정은 PowerShell 헬퍼(.env 읽기·출력 없음).

### §실측·트랩
- 검증: typecheck 0·vitest 8 files 35 tests(신규 없음)·dev E2E(Playwright·데모 buyer) 29/29 — 판매중지 14(목록 미노출·상세 API/SSR HTML/브라우저 문구·품절 문구 없음·담기 비활성·우회 API 담기 422·disabled 제거 강제 클릭 시 문구 노출·/cart 안내+비활성·/checkout FE-17 차단 유지) / 품절 7(목록 배지·상세 품절·담기 비활성) / 정상 회귀 8(담기 201·/cart·/checkout 정상). pageerror 0.
- 원복: 후디 SALE·티셔츠 재고 +98·카트(티셔츠1·후디1)·목록 2건 soldOut false.
- FE-16 트랩 규칙 준수(typecheck → frontend 재시작 → 런타임) → 재발 없음.

### §8 이월(carry-over)
- [RESOLVED] FE-17 §8 "장바구니 페이지 단계 구매 불가 선택 품목 안내·결제하기 차단" → 본 트랙 해소.
- [RESOLVED] FE-17 §8 "신규 주문 경로의 상품 판매 상태 검증 부재(BE)" → D-160 해소(판매자 상태는 D-160 §8 이월).
- [백로그] 관리자 FE 트랙(D-160 §8 참조).

## FE-19: 헤더 계정 드롭다운

날짜: 2026-09-15
선행: FE-13(마이페이지 5페이지)·FE-12(주문)·FE-14(클레임) 라우트 실존 + 로그아웃 잔류 버그 정정(8f6d4c87). 정찰 = docs/frontend/recon-report-fe19-account-menu.md.
범위: AppHeader 인증 분기를 "내 계정" 드롭다운으로 교체(링크 6 + 구분선 + 로그아웃). 비인증 헤더·auth store·withdraw.vue·/mypage 허브 무변경.
수용기준(달성): 비인증 트리거 없음 / 인증 "내 계정" / 열기→마이페이지·주문내역·회원정보 수정·비밀번호 변경·배송지 관리·취소·반품·교환 내역·로그아웃 순 / 회원 탈퇴 부재 / 바깥 클릭·Esc·항목 선택 시 닫힘 / 보호 페이지 로그아웃→'/' 이동·장바구니 초기화·공개 페이지 잔류(기존 로직 보존). typecheck 0·vitest 36·smoke 1·dev 실측 18 GREEN.

### §1-A 갈림길·채택/기각 근거
1) 트리거 라벨
- α 채택: "내 계정" 고정. 추가 조회 없음(JWT에 이름 claim 없음·store에 이름 필드 없음).
- β 기각: /users/me를 store에 연동해 이름 표시. SSR Bearer 주입·401 처리·로그아웃 초기화 비용 대비 라벨 1개 이득.
2) 드롭다운 구현
- α 채택: shadcn dropdown-menu(reka-ui 래핑). 바깥 클릭·Esc·포커스·role=menu 접근성 내장. 컨테이너 `pnpm dlx shadcn-vue@2 add dropdown-menu`(CLI 2.8.2).
- β 기각: 직접 구현. 바깥 클릭·키보드 처리 수기 작성(@vueuse/core 미설치 상태였음).
3) 아이콘 라이브러리(CLI 산출물이 @radix-icons/vue import → typecheck 실패)
- α 채택: components.json `iconLibrary: "lucide"` 지정 후 `--overwrite` 재생성. @radix-icons 0건.
- β 기각: @radix-icons/vue 설치. 아이콘 라이브러리 이중화.
- γ 기각: 미사용 3파일(CheckboxItem·RadioItem·SubTrigger) 삭제. CLI 산출물 수기 편집.
4) 회원 탈퇴 항목 — 제외. 파괴적 동작을 헤더 상시 노출 메뉴에 두지 않고 /mypage 허브에서만 진입.
5) 로그아웃 위치 — 헤더 상시 버튼 → 드롭다운 마지막 항목. `handleLogout`(auth.logout→cart.clear→보호 라우트면 '/') 함수 무수정·호출 지점만 이동.

### §2 확정 구현 규칙 (file:line)
- `frontend/app/components/AppHeader.vue:8` `accountMenuItems` 6링크 · `:79` `<DropdownMenu v-if="auth.isAuthenticated">` · `:81` 트리거 `data-testid="account-menu-trigger"`(기존 로그아웃 Button 스타일 ghost/sm 유지) · `:86` 항목 `as-child` NuxtLink · `:89` Separator · `:90` 로그아웃 `data-testid="account-menu-logout" @select="handleLogout"` · `:26` handleLogout 무변경.
- `frontend/components.json` `iconLibrary: "lucide"` 추가 — FE-09 PROGRESS 기록엔 lucide 지정으로 돼 있으나 커밋본(8fc237ae)엔 없었던 불일치 정정.
- `frontend/package.json` `@vueuse/core ^14.4.0`·`@lucide/vue ^1.46.0` dependency 추가(shadcn dropdown-menu 산출물 요구: reactiveOmit·아이콘). 기존 `lucide-vue-next`(deprecated·app 사용처 0)는 미제거.
- `frontend/app/components/ui/dropdown-menu/*` 15파일 CLI 산출물 무수정.

### §진입점
1. 목적: 로그인 사용자의 계정 관련 페이지 진입점을 헤더 한 곳으로 모으고 로그아웃을 그 안으로 이동.
2. 헤더: frontend/app/components/AppHeader.vue(accountMenuItems·DropdownMenu 배선).
3. UI 컴포넌트: frontend/app/components/ui/dropdown-menu/(shadcn 산출물, auto-import·prefix '').
4. 테스트: frontend/test/component/AppHeader.spec.ts 6 it(data-testid 기반·열기=trigger click·조회=document Portal).
5. 전제·트랩: FE-16 트랩(typecheck 후 frontend 재시작) / 로컬 hosts 127.0.0.1 가드 / CLI 실행 시 components.json iconLibrary 필수.

### §실측·트랩
- 검증: typecheck 0 · vitest 8 files 36 tests(AppHeader 5→6) · Playwright smoke 1 · dev 실측 18/18(비로그인·로그인·열기·링크 6·탈퇴 부재·바깥 클릭·Esc·6링크 이동+닫힘·/mypage 로그아웃→/·뱃지 2→0·/products 로그아웃 잔류).
- 트랩: reka-ui@2.10 DropdownMenuTrigger는 `onClick` 토글(pointerdown 아님) → vitest에서 `trigger('click')`로 열림. Portal 콘텐츠는 wrapper 밖 document.body → `document.querySelector` 조회. Portal stub 불필요.
- 트랩: dev SSR 페이지는 hydration 전 클릭 무시 → 브라우저 실측 시 networkidle 대기 후 클릭.
- 부수: 데모 계정 장바구니에 실측용 상품 1건 추가됨(원복 안 함).

### §8 이월(carry-over)
- [백로그] `lucide-vue-next` deprecated dep 제거(@lucide/vue로 통일) — 사용처 0.
- [백로그] /mypage 허브에 주문내역 링크 부재(드롭다운에만 존재).

## FE-20: 검색 결과·카테고리 페이지 + 헤더 카테고리 드롭다운

날짜: 2026-09-16
선행: BE Track 72(D-161·GET /api/v1/products?keyword= / GET /api/v1/categories) 머지(0db33190) · FE-19 dropdown-menu · FE-05 useProductList. 정찰 = docs/frontend/recon-report-fe20-search-category.md.
범위: /search?keyword= 신설 · /categories/[id] 신설 · /products 본문을 공용 ProductListView로 승격(정렬 select·1차 카테고리 탭·그리드·4상태·무한스크롤) · 헤더 검색 submit 배선 · 헤더 카테고리 nav placeholder를 드롭다운으로 교체. ProductCard·HomeProductGrid·auth store·BE 무변경.
수용기준(달성): 헤더 검색 "베이직" → /search?keyword=베이직 결과 1건·입력 유지 / 빈 검색 미이동·/search 직접 진입 안내 / 0건 빈 상태 / 카테고리 드롭다운 전체 상품·데모 이동+닫힘 / /products "전체"·/categories/1 "데모" 활성 / /categories/abc API 미호출 빈 상태·999999 빈 상태 / ?categoryId= 호환 / 그리드 375·640·768·1024·1280 = 2·3·4·5·6열 / 카테고리 12 mock 탭 가로 스크롤 1행 / 계정 메뉴 회귀 없음 / hydration 경고 0. typecheck 0·vitest 65·smoke 1·dev 실측 11/11 GREEN.

**레이아웃은 시안 반복 대상** — 목록 레이아웃(그리드 열·탭·정렬 위치)을 교체할 때는 `components/product/ProductListView.vue`(+SortSelect·CategoryTabs) 한 곳만 바꾸고, 그 결정은 본 FE-20 항목에 append한다(페이지 3개는 껍데기라 무변경).

### §1-A 갈림길·채택/기각 근거
1) 카테고리 라우트 — D-161 §1-A 6) 참조
- α 채택: `/categories/[id]` 신설 + 뷰는 ProductListView 공용. `/products?categoryId=` 호환 유지(URL 전용 필터·탭은 /categories로 이동).
- β 기각: `/products?categoryId=` 단일 라우트. 향후 카테고리 전용 디자인 분리 시 URL 변경 비용.
2) 검색 라우트 — `/search?keyword=` 신설(D-161). 탭 숨김(검색은 전 카테고리 대상).
3) 데이터/레이아웃 분리
- α 채택: 데이터·상태 = composable(useProductList keyword Ref 확장·useCategories 신설), 조립 = ProductListView, 페이지 3개 = props 계산 껍데기. 조각(SortSelect·CategoryTabs) 테스트는 렌더·링크 수준만.
- β 기각: 페이지별 본문 복제. 레이아웃 교체 시 3곳 동시 수정.
4) 헤더 카테고리 UI
- α 채택: FE-19 shadcn dropdown-menu 재사용(트리거 "카테고리"·항목 전체 상품/구분선/루트 목록). FE-19 §1-A 2) 채택 근거(접근성 내장) 동일.
- β 기각: 메가메뉴(카테고리 패널 상시 노출). 1단 구조·루트 소량에 비용 과다.
5) 카테고리 데이터 공유 — useAsyncData 고정 key `'categories'`로 헤더·탭이 한 요청을 공유(대안 검토 없음·Nuxt 동일 key dedupe).
6) 2차 칩 생략 — 자식 카테고리 API·데이터 부재(D-161 §8)라 1단 탭만. 시드 미보강 — 다수 카테고리 레이아웃은 page.route mock 실측으로 대체.
7) 빈 입력·잘못된 id 처리 — 페이지가 ProductListView를 마운트하지 않아 API 호출 자체를 막는다(/search 빈 keyword → "검색어를 입력하세요", /categories/[id] 비-양의정수 → EmptyState). 존재하지 않는 id는 API 빈 목록 그대로.

### §2 확정 구현 규칙 (file:line)
- `frontend/app/composables/useProductList.ts:18` 3번째 인자 `keyword: Ref<string|null> = ref(null)` · `:34` normalizedKeyword(trim·빈값 null) · buildQuery·useAsyncData key·watch에 반영. size는 4번째 인자로 이동(호출처 0).
- `frontend/app/composables/useCategories.ts` useAsyncData key `'categories'`·baseURL 이원화 · `frontend/app/types/category.ts` CategorySummary.
- `frontend/app/components/product/ProductListView.vue:10` props title·categoryId·keyword·showCategoryTabs(기본 true) · `:33-36` toRef → useProductList · `:80` GRID_CLASS `grid-cols-2 sm:3 md:4 lg:5 xl:6` · 스켈레톤 12 · sort URL replace·IntersectionObserver는 FE-05 그대로 이동.
- `frontend/app/components/product/SortSelect.vue` defineModel · `frontend/app/lib/constants/product.ts` PRODUCT_SORT_OPTIONS·DEFAULT_PRODUCT_SORT(products/index 인라인 승격).
- `frontend/app/components/product/CategoryTabs.vue` `data-testid="category-tabs"`·overflow-x-auto·whitespace-nowrap·활성 `aria-current="page"`·조회 실패/빈이면 "전체"만.
- 페이지: `pages/products/index.vue`(?categoryId 숫자 computed) · `pages/categories/[id].vue`(`/^[1-9]\d*$/`) · `pages/search.vue`(keyword trim·제목 `'{keyword}' 검색 결과`). 미들웨어 없음.
- `frontend/app/components/AppHeader.vue:9-24` searchKeyword ref + route.query.keyword watch + handleSearchSubmit(`navigateTo({path:'/search', query:{keyword}})`·빈값 return) · `:65` `<form role="search" data-testid="search-form">`·`:79` `data-testid="search-input"` · `:26-27` useCategories→categoryMenuItems · `:134` `category-menu-trigger` · `:138` `category-menu-content` · `:110` `account-menu-content`(계정 메뉴 항목·handleLogout 무수정).

### §진입점
1. 목적: 상품 탐색 진입점(검색·카테고리)을 열고, 목록 레이아웃을 한 컴포넌트로 모아 시안 반복 교체를 가능하게 함.
2. 공용 뷰: frontend/app/components/product/ProductListView.vue(+SortSelect·CategoryTabs).
3. 데이터: frontend/app/composables/useProductList.ts(keyword)·useCategories.ts.
4. 페이지: frontend/app/pages/{products/index,categories/[id],search}.vue.
5. 헤더: frontend/app/components/AppHeader.vue(검색 form·카테고리 드롭다운).
6. 테스트: test/unit/useProductList·useCategories · test/component/ProductListView·CategoryTabs·SearchPage·CategoryPage·AppHeader(12 it).

### §실측·트랩
- 검증: typecheck 0 · vitest 14 files 65 tests(기존 36 + 신규 23 + AppHeader 6→12) · Playwright smoke 1 · dev 실측 11/11.
- 트랩(테스트): useAsyncData의 data·error 기본값은 `undefined`(null 아님) → `toBeFalsy`로 단언. 같은 key(sort·categoryId·keyword)는 캐시 재사용으로 $fetch 생략 → it 간 `clearNuxtData()`.
- 트랩(테스트): 계정·카테고리 드롭다운이 공존하므로 `[data-slot="dropdown-menu-content"]` 조회는 첫 content만 잡는다 → 각 content `data-testid`로 조회.
- 트랩(실측): 클라이언트 네비 직후 `networkidle`은 fetch 시작 전 즉시 해소될 수 있음 → 카드 locator waitFor로 대기. SSR 페이로드에 categories가 실려 클라이언트 재조회가 없으므로 page.route mock은 `nuxtApp._asyncData.categories.execute()`로 재조회를 강제해야 반영된다.
- 부수: 데모 계정 로그인·로그아웃만 수행(장바구니·주문 호출 0).

### §8 이월(carry-over)
- [백로그] 2차 카테고리 칩 — BE 자식 카테고리 API·데이터·V13 재설계 선행(D-161 §8).
- [백로그] 헤더 검색 자동완성·최근 검색어 — 범위 밖.
- [백로그] ProductCard 이미지 onerror 대체 없음(D-161 §8).

## FE-21: 옵션명 표시 — 장바구니·체크아웃 요약·주문 상세

날짜: 2026-09-16
선행: BE Track 75(D-164·CartItemView.optionLabel·OrderItemResponse.optionLabel) 동일 브랜치(feat/track-75-option-label). 정찰 = docs/track-75/recon-report.md §6·§7.
범위: types/cart.ts CartItemView·types/order.ts OrderItem에 `optionLabel?: string | null`(선택 필드·NON_NULL 생략 대응·checkout-summary.spec 픽스처 무수정) · pages/cart.vue · components/checkout/OrderItemList.vue · pages/orders/[orderPublicId].vue 각 1줄. store·composable·BE 호출 무변경.

### 결정
1. 표시 위치: 상품명 `<p>` 바로 아래 회색 작은 글씨(`text-xs text-sub`·truncate)·`data-testid="item-option-label"` 3곳 동일.
2. 조건부 렌더: `v-if="item.optionLabel"` — 단순상품·기존 주문(NULL·생략)·빈 문자열 모두 미렌더(빈 요소 없음·레이아웃 불변).
3. 공통 품목 컴포넌트 미신설 — cart.vue·OrderItemList.vue가 같은 마크업을 중복하지만 1줄 추가로 충분·대안 검토 없음(D-164 §8과 별개·YAGNI).
4. 클레임 신청 링크 query(name)에 옵션명 미동반(범위 밖·D-164 7).

### 테스트
- test/component/OrderItemList.spec.ts 2 · CartPage.spec.ts 2(useCartStore·useAsyncData mock) · OrderDetailPage.spec.ts 2(useOrderDetail·useRoute mock) — 라벨 있음/없음(null·생략).
- typecheck 0 · vitest 17 files 71 tests(기존 65 + 신규 6) · Playwright smoke 1 GREEN.

### §실측·트랩
- dev 실측(컨테이너 내 playwright 임시 스크립트·종료 후 삭제·데모 buyer): 장바구니 옵션 상품 "색상: 블랙 / 사이즈: M"·단순상품 미표시 / 체크아웃 요약 동일 / 장바구니 결제 후 주문 상세 라벨 표시(단순 품목 미표시) / 기존 주문 상세 라벨 0·상품명 다음 형제가 가격 줄(레이아웃 정상) — 4/4 PASS. hydration: cart·checkout·orders 목록 0, 주문 상세 2건은 변경 전(HEAD)에도 동일(기존 이슈·별건).
- 트랩(실측): 백엔드 dev 컨테이너는 bootRun 상주라 코드 변경이 자동 반영되지 않음 → restart(healthy 110s·Flyway V20 적용) 후 API 응답에 optionLabel 등장. `https://zslab-mall.duckdns.org` 호출 스크립트는 auto-mode 분류기가 운영 배포로 차단 → `http://localhost:3000/api` 프록시 사용. PowerShell 5.1 스크립트의 한글 리터럴은 UTF-8 BOM 필수.
- 부수: 로컬 dev DB에 판매자 owner 회원(t75-seller@zslab.local)·판매자 T75 옵션샵·상품 prd_01M2K01B3GVZXNMTP8EHA07D85·데모 buyer 주문 1건(ord_01M2K088TGEJ85SDHXCAKT7E67·PENDING_PAYMENT) 생성.

### §8 이월
- [백로그] 주문 상세 hydration mismatch(기존) 원인 조사.
- [백로그] 주문 목록 previewTitle·클레임 화면 옵션명 표기.

## FE-22: 관리자 공통 셸 — Nuxt Layer `frontend/layers/admin/`

날짜: 2026-09-16
선행: 정찰 = docs/frontend/recon-report-fe-22.md(§4 D-1~D-8 추천안 전부 채택·D-9 추가). BE 관리자 API 19개(전부 POST/DELETE·조회 GET 0)라 셸은 BE 무의존.
범위: 관리자 로그인·가드·레이아웃(사이드바+상단바)·메뉴 상수·플레이스홀더 페이지 22. 사용자 영역(app/)은 D-2 가드 2파일 외 무변경. 메뉴별 화면·BE 조회 API는 후속 트랙.
수용기준(달성): /admin/** 미인증→/admin/login?redirect= / BUYER 토큰→'/' 차단 / ADMIN 로그인→/admin·사이드바 22항목 이동 / 상단바 /users/me 표시·로그아웃→/admin/login / x-robots-tag noindex. typecheck 0·vitest 20 files 82 tests·smoke 1 GREEN.

### §1-A 갈림길·채택/기각 근거
1) 관리자 FE 구조
- α 기각: 별도 Nuxt 앱(frontend-admin/). 컨테이너·CI·Dockerfile·nginx location 추가·shadcn/main.css/auth store 중복. 단일 운영자 편의 기조 위배.
- β 기각: app/pages/admin/ 폴더. 사용자 영역과 layouts·middleware·components가 한 트리에 섞여 경계 불명·pages 폴더 비대.
- γ 채택: Nuxt Layer `frontend/layers/admin/`. Nuxt 4.4.8 `layers/*` 자동 등록(@nuxt/kit loadNuxtConfig glob)·`#layers/admin` alias 자동·`.nuxt/tsconfig` include 기존 포함·Dockerfile `COPY . .`·CI `frontend/**` 무수정. 루트 shadcn ui·main.css·auth store 공유. 레이어 등록엔 `layers/admin/nuxt.config.ts` 필수(config 없는 디렉토리는 kit이 skip).
2) D-1 관리자 로그인 진입 — α 채택: `/admin/login` 레이어 전용 페이지(role=ADMIN 고정·layout:false). 기존 login.vue(BUYER 하드코딩) 무수정. β(기존 /login role 선택)·γ(401 시 ADMIN 재시도) 기각.
3) D-2 ADMIN 토큰으로 사용자 페이지 진입 — β 채택: AppHeader 계정 드롭다운 `v-if="auth.isAuthenticated && auth.role === BUYER_ROLE"`·cart-load 플러그인 role≠BUYER면 skip(GET /cart 403 로그 제거). 단일 쿠키 세션이라 ADMIN 상태의 사용자 헤더는 "로그인" 링크 노출(클릭 시 login.vue가 인증 상태 판정해 홈 복귀)·허용.
4) D-3 테스트 위치 — α 채택: `frontend/test/admin/`(기존 test/ 관습·CI 무수정). Playwright는 testDir 'e2e' 고정.
5) D-4 레이아웃 적용 — α 채택: 페이지마다 `definePageMeta({ layout:'admin', middleware:'admin' })`. 자동 부여 훅 기각(단일 사용 추상화 금지).
6) D-5 상단바 관리자 표시 — α 채택: `GET /users/me`(name || email). 세분 역할(SUPER_ADMIN/ADMIN_OPERATOR)은 JWT·API 모두 부재라 미표시. `GET /admin/me` 신설은 SUPER_ADMIN 전용 메뉴 트랙에서.
7) D-6 `lucide-vue-next` 제거 — α 채택: `pnpm remove`(사용처 0·FE-19 §8 백로그 해소). 아이콘은 `@lucide/vue`로 통일(셸은 아이콘 미사용).
8) D-7 운영 admin 401 — 이월. 원인 후보 1순위 = 최초 기동 시 서버 .env 값 ≠ 현재 값(SuperAdminBootstrapRunner 생성 전용·SUPER_ADMIN 존재 시 skip·값 미갱신). 조사(운영 DB SUPER_ADMIN 회원 email·상태 읽기 1회)와 조치(비밀번호 재설정 경로 부재 → DB BCrypt 갱신 또는 user_role 삭제 후 재기동 재공급)는 zslab 승인 후 별도.
9) D-8 API 래퍼 — α 채택: 레이어 전용 `useAdminApi`($fetch.create·CSR baseURL·onRequest Bearer·onResponseError 401→logout+/admin/login). 루트 buyer 16곳 복제 코드 리팩토링은 백로그.
10) D-9 `/admin/**` routeRules — `ssr:false` + `X-Robots-Tag: noindex, nofollow`(레이어 nuxt.config). 관리자 화면은 SEO 불필요·토큰 조작 UI라 CSR 전용. 부수: SSR 셸 HTML만 응답(페이지 콘텐츠는 hydration 후 렌더).
11) admin 미들웨어 분기 — 미인증은 `/admin/login?redirect=`, 인증+role≠ADMIN은 `/`(비관리자에게 관리자 로그인 폼 미노출). 정찰 §5 초안(둘 다 /admin/login)에서 확정 사양대로 변경.
12) 레이어 내부 참조 — `#layers/admin/app/...` alias만 사용(`~/`는 루트 app 고정이라 레이어 파일 참조 금지). 루트 공유 자원(`~/lib/constants/auth`·`~/types/user`·auto-import store/Button)은 `~/`.

### §2 확정 구현 규칙 (file:line)
- `frontend/layers/admin/nuxt.config.ts:4-6` routeRules `/admin/**` ssr:false·X-Robots-Tag.
- `frontend/layers/admin/app/lib/constants/auth.ts` `ADMIN_ROLE='ADMIN'`·`ADMIN_HOME_PATH='/admin'`·`ADMIN_LOGIN_PATH='/admin/login'`.
- `frontend/layers/admin/app/middleware/admin.ts:9-17` 미인증→login?redirect / role≠ADMIN→'/'.
- `frontend/layers/admin/app/composables/useAdminApi.ts:8-24` `$fetch.create({ baseURL: public.apiBase||'/api', onRequest Bearer, onResponseError 401 })`.
- `frontend/layers/admin/app/pages/admin/login.vue:5` layout:false · `:16-22` resolveRedirect(`/admin/` 하위만 허용·기본 /admin) · `:25-27` ADMIN 인증 시 즉시 복귀 · `:34` `auth.login(email,password,ADMIN_ROLE)`.
- `frontend/layers/admin/app/lib/constants/admin-menu.ts` ADMIN_MENU 6그룹 22경로(순서 고정) — 사이드바·페이지 파일 1:1.
- `frontend/layers/admin/app/components/admin/AdminSidebar.vue:6-8` 활성 판정 정확 일치(prefix 매칭 시 상위·하위 동시 강조 방지).
- `frontend/layers/admin/app/components/admin/AdminTopbar.vue:10-14` useAsyncData('admin-profile', adminApi('/v1/users/me'))·name||email · `:17-20` 로그아웃 = auth.logout()+navigateTo(ADMIN_LOGIN_PATH).
- `frontend/layers/admin/app/components/admin/AdminPlaceholder.vue` "준비 중입니다"(data-testid admin-placeholder).
- `frontend/layers/admin/app/layouts/admin.vue` Sidebar + (Topbar + main slot).
- `frontend/layers/admin/app/pages/admin/**` 22파일 동형(definePageMeta layout/middleware + useSeoMeta + AdminPlaceholder).
- `frontend/app/components/AppHeader.vue:2` BUYER_ROLE import · `:107` `v-if="auth.isAuthenticated && auth.role === BUYER_ROLE"`.
- `frontend/app/plugins/cart-load.ts:1·:12-15` role≠BUYER 인증 시 return.
- `frontend/package.json` `lucide-vue-next` 제거·pnpm-lock.yaml 동기.

### §진입점
1. 목적: 관리자 화면 공통 셸(로그인·가드·레이아웃·메뉴)을 사용자 앱과 분리된 레이어로 확립. 메뉴별 화면은 BE 조회 API 트랙과 함께 후속.
2. 레이어: frontend/layers/admin/(nuxt.config.ts 필수·app/ 하위 pages/layouts/middleware/components/composables/lib).
3. 사용자 영역 접점: AppHeader.vue·plugins/cart-load.ts(D-2 가드만).
4. 테스트: frontend/test/admin/(admin-middleware 3·useAdminApi 4·cart-load 3) + AppHeader.spec ADMIN 케이스 1.
5. 전제·트랩: 컨테이너 dev 서버는 layers/ 신설을 감지 못함 → `docker restart zslab_mall_frontend` 필수(404·"No match found for location" 경고). typecheck는 `nuxt prepare`가 `#layers/admin` paths·MiddlewareKey 'admin'·layouts 'admin'을 .nuxt에 생성.

### §실측·트랩
- 검증: typecheck exit 0 · vitest 20 files 82 tests(기존 71 + admin 10 + AppHeader 1) · Playwright smoke 1 · 컨테이너 headless(playwright 임시 스크립트·삭제): /admin/login 폼 렌더·/admin/orders/refunds 미인증→/admin/login?redirect=/admin/orders/refunds·pageerror 0 · curl: /admin/login 200 + `x-robots-tag: noindex, nofollow`.
- 트랩: 레이어 디렉토리 추가 후 실행 중 dev 서버(bind mount·usePolling)가 hard restart를 트리거하지 않아 /admin/** 404 → 컨테이너 재시작으로 해소(6s). 정찰의 "addDir → restart" 예측(nuxt/dist/index.mjs:6980-6987)은 컨테이너 환경에서 미발동.
- 트랩: AppHeader.spec authMock에 role 부재 → D-2 가드로 트리거 미렌더·4건 실패 → authMock에 `role:'BUYER'` 기본값 추가.
- 로컬 브라우저 수동 확인 항목(미실행·관리자 계정 = 로컬 .env ADMIN_BOOTSTRAP_*): ① /admin/login 로그인 → /admin 대시보드·상단바에 관리자 email(name null) 표시 ② 사이드바 6그룹 22항목 순서·클릭 시 각 "준비 중" 페이지·활성 강조 1개 ③ /admin 직접 진입(미인증) → /admin/login?redirect=/admin → 로그인 후 /admin 복귀 ④ 데모 buyer 로그인 상태에서 /admin → '/' 차단·사용자 헤더 정상 ⑤ 관리자 로그인 상태에서 / 진입 → 헤더 "내 계정" 미노출·콘솔 cart 403 없음 ⑥ 로그아웃 → /admin/login·뒤로가기 시 다시 /admin/login.

### §8 이월(carry-over)
- [BE] `role=ADMIN` 로그인 통합 테스트 부재(AuthControllerIntegrationTest BUYER 4·SELLER 2) — CI 미호출 경로.
- [BE] 관리자 조회 API 0건 — 메뉴별 트랙(회원·주문·상품·정산·통계)마다 GET 신설 선행. `GET /admin/me`(세분 role) 포함.
- [운영] admin 401(D-7) 조사·조치 — zslab 승인 후.
- [백로그] 루트 buyer composable/store 16곳 baseURL·Bearer 복제 → 공통 래퍼 리팩토링.
- [백로그] AppHeader ADMIN 상태의 "로그인" 링크 노출(클릭 시 홈 복귀) — 관리자 콘솔 링크로 교체 여부.

### §라이브러리 선정 (FE-22b/22c · 2026-09-16)
정찰 = docs/frontend/recon-report-fe-22b.md(Nuxt UI 스파이크)·recon-report-fe-22c.md(Vuetify 스파이크). 판정 기준 4개 = 사용자 화면 픽셀 diff 0 / 사용자 영역 수정 0 / 동반 모듈·전역 영향·SPA 누수 / 사용자 entry 번들 증감.
- α Nuxt UI v4(4.11.1) **기각** — 4기준 중 3 미달(실측): `@import "@nuxt/ui"`를 main.css(Tailwind 루트)에 둬야만 유틸 생성(레이아웃 `<style>` 격리 시 UCard ring/radius 0·버튼 텍스트색 미적용) → ui.css `body{@apply text-default bg-default antialiased}`로 사용자 12장 중 11장 diff(209~871px·상속 텍스트색) · 레이어 `modules`도 kit merger concat으로 앱 전체 등록 · `#__nuxt class="isolate"`·`<style id="nuxt-ui-colors">` 전역 주입 · @nuxt/icon `/api/_nuxt_icon/*`가 routeRules `/api/**` 프록시·운영 nginx `/api/`와 충돌해 401 · 사용자 entry JS +13.1KB gz·CSS +20.5KB gz · reka-ui 2.10.1+2.10.4·@nuxt/kit 4.4.8+4.5.2·tailwind 4.3.2+4.3.3 이중 설치.
- β shadcn-vue 확장 — 추가 dep 0·일관 스타일이나 data-table·dialog·sheet를 CLI 추가 + @tanstack 별도 dep. 기각(관리자 화면 컴포넌트 폭·개발 속도).
- γ **Vuetify 3.13.4 채택(D-12 α)** — 사용자 fresh load 12장 0px · 사용자 영역 수정 0 · 사용자 entry CSS 불변(entry.Dp22OyE1.css 동일 해시)·entry JS 82.1→83.4KB gz(+1.3·미들웨어 등록분) · 관리자 청크에만 Vuetify(vuetify-styles 248.9KB raw/30.3KB gz + 컴포넌트 CSS·JS). 누수(SPA 이동 시 vuetify/styles 잔류로 사용자 홈 1465→1173px 붕괴)는 D-15 가드로 차단.
- 연동 방식: vuetify-nuxt-module(latest 1.0.0-rc.6·kit 4.5.2 병설·modules 전역) 기각 → **수동**: 레이어 `vite.plugins:[vuetify({autoImport:true})]`(빌드타임 변환·layers/admin/nuxt.config.ts:9) + `ensureVuetify`(동적 import·vueApp 1회 설치·lib/vuetify.ts) + `vuetify` 미들웨어(middleware/vuetify.ts). Vuetify latest는 4.2.1이나 3 계열(v3-stable 3.13.4) 고정(D-16).
- D-13 관리자 컴포넌트 Tailwind 유틸 금지: Vuetify 무레이어 `!important` 유틸(`.rounded-lg`·`.border`·`.text-center`·`.overflow-hidden`·`.text-white`·`.h-screen`)이 Tailwind @layer 유틸을 덮음 → Admin* 컴포넌트·layouts/admin*.vue·login.vue는 Vuetify 컴포넌트·Vuetify 유틸(pa-6·text-h5 등)만 사용.
- D-14 폰트: vuetify/styles가 `html{font-family:Roboto}`·`.v-application` 강제 → `assets/css/admin-vuetify.css` `html,.v-application{font-family:var(--font-sans)}`(main.css @theme 변수 재사용)를 vuetify/styles 뒤에 로드(lib/vuetify-styles.ts import 순서).
- 로딩 시점: 보호 페이지 `middleware:['admin','vuetify']`(admin이 먼저 BUYER→'/' 차단·Vuetify 미로드) · `/admin/login`은 `['vuetify']`만(미인증 상태에서 로드·로그인 폼도 Vuetify·관리자 셸 일부). 미들웨어 자체도 `로그인 경로 || ADMIN 세션`일 때만 로드해 순서 의존을 제거(vuetify.ts:12-16).
- D-15 누수 가드: `lib/admin-leave-guard.ts` `shouldReloadOnLeave(path)`(= /admin 자신·/admin/ 하위 아님) + `useAdminLeaveGuard()`가 layouts/admin.vue·admin-auth.vue의 `onBeforeUnmount`에서 `router.currentRoute.fullPath`가 관리자 밖이면 `window.location.assign(target)`. 관리자 내부 이동(로그아웃→/admin/login·사이드바)은 새로고침 없음(e2e 마커로 실측). 현 셸에 관리자→사용자 링크 0 — 추가 시 `<a href>`/`navigateTo(to,{external:true})` 규칙.
- 화면: layouts/admin.vue = v-app > AdminSidebar(v-navigation-drawer·md 이상 permanent·모바일 temporary·v-list-group 펼침 초기값=현재 경로 그룹·exact 활성) + AdminTopbar(v-app-bar·nav-icon 토글·/users/me name||email·로그아웃 v-btn) + v-main. AdminPlaceholder = v-card outlined. login.vue = layouts/admin-auth.vue(v-app+v-main) + v-card/v-form/v-text-field/v-alert/v-btn loading — ADMIN 고정·redirect `/admin/` 하위 제한 로직 무수정.
- 테스트: test/admin 6파일 24건(신규 vuetify-middleware 4·admin-leave-guard 9·ensure-vuetify 1) · e2e/admin-shell.spec.ts 3건(ADMIN_E2E_EMAIL/PASSWORD env 미설정 시 skip) ① 로그인→/admin 셸 렌더 ② 내부 뒤로가기 새로고침 없음(마커 유지) + `history.go(-2)`로 `/` 복귀 시 Vuetify 시트 0·html font·높이 기준선 일치 ③ BUYER→/admin→'/'·시트 0. typecheck 0 · vitest 23 files 96 tests · Playwright 4(smoke 1 + admin 3) GREEN · 사용자 12장 0px.
- 트랩 4건(실측): (1) `pnpm add`→`pnpm remove`는 lockfile 비가역(transitive 재해석) → 스파이크는 package.json·pnpm-lock.yaml 파일 복사 백업/복원. (2) Git Bash에서 `docker exec … /app/...` 인자는 MSYS 경로 변환(C:/Program Files/Git/app)에 걸림 → `sh -c '…'` 경유. (3) 세션 중 `frontend/nul` 파일 생성(Windows 리다이렉트 부산물) → git status 확인·삭제. (4) Playwright `fullPage:true` 캡처가 뷰포트 리사이즈 직후 폰트 재래스터 레이스(폴백 글리프) → 페이지별 새 컨텍스트 + 문서 높이 뷰포트 고정 + `fullPage:false`.
- 부수 트랩: `nuxt.hooks.hook('vite:extendConfig', c=>c.plugins.push())`는 vue-tsc TS2540(read-only)·`@nuxt/kit` import는 TS2307(직접 dep 아님)·`import('vuetify/styles')`는 TS2306 → 부수효과 import 전용 모듈(lib/vuetify-styles.ts)을 동적 import. recon-report-fe-22의 변경 파일 "43개"는 42개(7 M + 35 ??)의 오기.
- 수동 확인 항목(갱신·미실행·관리자 계정 = 로컬 .env ADMIN_BOOTSTRAP_*): ① /admin/login Vuetify 폼 로그인(오류 시 v-alert·로딩 버튼) → /admin ② 데스크톱 사이드바 6그룹 펼침·22항목 이동·활성 1개·현재 그룹 펼침 ③ 모바일 폭(≤959px) drawer 닫힘 시작·상단 메뉴 아이콘 토글·항목 선택 후 오버레이 닫힘 ④ 상단바 관리자 email 표시·로그아웃 → /admin/login(새로고침 없음) ⑤ 주소창 `/` 입력·뒤로가기로 사용자 페이지 복귀 시 사용자 폰트·레이아웃 정상(Vuetify 잔류 0) ⑥ 데모 buyer 상태 /admin → '/'.

### §세션 분리 (FE-22d · 2026-09-16)
요구: 사용자(auth_token)/관리자(admin_token)/판매자(seller_token·추후) 세션 독립. 로그인 페이지는 자기 role만. 상호 로그인 공존·로그아웃/401은 해당 세션만 제거.
- STEP 24 BE 판정(read): `AuthService.login`은 요청 role을 `roleAuthorization.isAuthorized(userId, role)`로 검증해 불일치 시 401 ROLE_MISMATCH(AuthService.java:55-58)·토큰은 **요청 role로 발급**(`tokenProvider.issue(userId, role)` :59·JwtTokenProvider.java:96 claim role=요청 role). 판정은 `DbRoleAuthorization`(BUYER=user_role BUYER·ADMIN=user_role∈{SUPER_ADMIN,ADMIN_OPERATOR}·SELLER=seller_user 존재 :40-47). 이메일은 `uk_user_email`(V1__init.sql:42)로 계정 1개·다중 role은 user_role 행으로 허용(같은 계정이 BUYER·ADMIN 토큰을 각각 발급받을 수 있음). → **BE는 불일치를 거절하며 응답 role=요청 role이 보장**되므로 BE 보강 불필요. FE 방어 검증(응답 role≠ADMIN 저장 거절)은 관리자 측만 두고 사용자 login.vue는 무수정(BUYER 요청→BUYER 토큰 외 경로 없음).
- §1-A 갈림길
  - α 채택: **역할별 쿠키**(`auth_token` path=/ · `admin_token` path=/admin · 추후 `seller_token` path=/seller). 세션이 물리적으로 독립·로그아웃/401이 자기 쿠키만 제거·path 스코프로 사용자 페이지 document.cookie에 admin_token 미노출(e2e ④ 실측). 셀러 확장 = 같은 패턴(레이어 전용 스토어 + 쿠키명·path·role 상수 3개).
  - β 기각: 단일 쿠키에 role별 토큰 맵(JSON). 사용자 auth 스토어·쿠키 포맷 변경(사용자 영역 수정·기존 세션 호환 깨짐)·4KB 쿠키 한도에 JWT 3개.
  - γ 기각: 단일 세션 유지(FE-22 상태). 관리자 로그인이 사용자 세션을 대체·AppHeader/cart 가드 의존·요구사항(공존) 미충족.
- 구현: `layers/admin/app/stores/adminAuth.ts` `useAdminAuthStore`(setup store·쿠키 `admin_token` `{path:'/admin', sameSite:'lax', secure:true, maxAge:3600}`·JWT role/exp 디코드·`login(email,password)`=role ADMIN 고정 요청 + 응답 role≠ADMIN throw·`logout()`=admin_token만 null). @pinia/nuxt는 루트 stores/만 auto-import(@pinia/nuxt module.mjs:77-78)라 소비처가 `#layers/admin/app/stores/adminAuth` 명시 import. JWT 디코드는 `lib/jwt.ts`에 복제(app/stores/auth.ts 함수가 모듈 비공개·사용자 영역 무수정).
- 전환(auth_token 참조 0): `middleware/admin.ts`(admin_token 없음·만료 → `/admin/login?redirect=` / role≠ADMIN → `adminAuth.logout()` 후 `/admin/login` / 기존 "인증+role≠ADMIN → '/'" 분기 제거) · `middleware/vuetify.ts`(ADMIN 세션 판정 = adminAuth·로드 조건 `로그인 경로 || 관리자 세션` 유지) · `useAdminApi`(Bearer=admin_token·401 → admin_token만 제거 후 /admin/login) · `login.vue`(adminAuth.login·관리자 세션 있으면 즉시 복귀) · `AdminTopbar` 로그아웃(adminAuth.logout). 사용자 AppHeader·cart-load의 FE-22 D-2 role 가드는 방어용 유지.
- 테스트: test/admin 7파일 28건(adminAuth-store 4: 쿠키명·path·속성 / ADMIN 저장 / role≠ADMIN 거절 / 로그아웃 격리(admin↔auth_token) · admin-middleware 3 갱신(auth 스토어 미참조 검증 포함) · vuetify-middleware 4 갱신 · useAdminApi 401 격리 검증 추가) · e2e/admin-shell.spec.ts 4건(③ BUYER 세션 /admin → /admin/login·auth_token 유지·admin_token 없음·뒤로가기 시 Vuetify 시트 0 / ④ 관리자 로그인→사용자 데모 로그인→양 쿠키 공존(admin_token path=/admin)·사용자 페이지 document.cookie에 admin_token 미노출·/admin 셸 유지→로그아웃 후 admin_token만 제거·/mypage 진입 가능 / ①② 회귀 0). typecheck 0 · vitest 24 files 100 tests · Playwright 5 GREEN · 사용자 12장 0px(login 페이지 caret 노이즈 ≤8px는 기준선 자체 재현치).
- 결정 항목: (D-17) BE 보강 불필요 판정 유지 — 향후 SELLER 세션 도입 시에도 동일 엔드포인트·role 요청 방식. (D-18) 사용자 login.vue role 검증은 미추가(불필요·YAGNI) — BE 발급 규칙 변경 시 재검토.

### §디자인 톤 (FE-22e · 2026-09-16)
대안: (a) Vuetify 기본 Material(Roboto·elevation·대문자 버튼·채움색 활성) — 사용자 몰과 이질·정보 밀도 낮음 / (b) Materio형(어드민 템플릿·컬러 카드·그림자 다층) — 장식 과다·템플릿 의존 / (c) 정보밀도형(Compact 데이터 그리드 우선) — 지금은 데이터 화면 0·과도 / (d) **모던 SaaS(Linear·Vercel류) 채택 — zslab 선택**.
- 팔레트(lib/vuetify.ts ADMIN_LIGHT_THEME): zinc neutral — background #F4F4F5 / surface #FFFFFF / border #E4E4E7(불투명 `border-opacity:1`) / on-surface #18181B / muted `medium-emphasis-opacity` 0.62 / primary #2563EB(주요 버튼·포커스·링크·아바타만) / error·success·warning·info 지정. 활성 오버레이 `activated-opacity` 0.06·hover 0.04.
- 깊이·형태(ADMIN_DEFAULTS): VCard flat+border+rounded lg · VBtn flat+rounded lg(대문자·자간 해제는 CSS) · VTextField/VSelect outlined·comfortable·rounded lg · VList compact · VNavigationDrawer elevation 0+border e · VAppBar flat·border b · VAlert tonal·compact. 그림자는 VMenu·VDialog·VSnackbar 기본값만.
- 타이포(admin-vuetify.css): `--font-sans` 유지·`.v-application` 14px·list/field 14px·버튼 `text-transform:none` `letter-spacing:0`·페이지 제목 text-h6 bold + 설명 muted.
- 셸: AdminSidebar = 배경색 `background`(페이지와 동일 톤)+우측 border·상단 로고(mdiStorefrontOutline)+서비스명/관리자·그룹은 접지 않고 작은 uppercase muted 라벨(아이콘 14px)+항목 나열·활성 = 기본 오버레이(옅은 회색)+굵기·채움색 없음·항목 32px. 아이콘 매핑은 표시 전용이라 admin-menu.ts가 아닌 사이드바 내부 상수. AdminTopbar = surface·52px·flat+하단 border·좌측 v-breadcrumbs(admin-menu 기준 그룹 › 메뉴·마지막만 medium)·우측 아바타(이니셜) v-menu(이름/이메일·로그아웃 `admin-logout`·트리거 `admin-account-menu`). AdminPageHeader 신설(title·description·actions 슬롯) → AdminPlaceholder(빈 상태 카드: 아이콘 아바타·"준비 중입니다"·보조 문구)에 적용. 레이아웃 콘텐츠 max-width 1200·px-6 py-8. 로그인 = 배경 옅은 회색 위 서비스명/콘솔 라벨 + 단일 카드(안내·폼·primary 버튼).
- 기능·로직 무변경: 가드·세션·누수 가드·메뉴 구조·라우트 동일. e2e ④는 로그아웃이 아바타 메뉴 안으로 들어가 `admin-account-menu` 클릭 1단계 추가.
- 검증: typecheck 0 · vitest 24 files 100 tests · Playwright 5 · 사용자 12장 0px. 스크린샷(gitignored·zslab 확인용): `frontend/playwright-report/fe-22e/` — login-{desktop,mobile}·dashboard-{desktop,mobile}·products-{desktop,mobile}·products-desktop-menu(아바타 메뉴)·products-mobile-drawer(모바일 drawer). 데스크톱 1440×900·모바일 390×844.

#### 변경(FE-22f · 2026-09-16): 모던 SaaS → Argon형 톤 재구성 — zslab 선택
- 근거: 참고 사이트(Creative Tim Argon Dashboard 2·MIT)의 상단 컬러 밴드·카드형 사이드바·그림자 카드 구성을 **스타일만 재현**. CSS·이미지·로고·명칭 미사용(레이어 CSS 자체 작성·보조 색은 Tailwind 팔레트 값). primary #2563EB(브랜드)·폰트 `--font-sans`(D-14) 유지 — 사용자 몰과 정체성 일관.
- 테마(lib/vuetify.ts): 배경 #F4F4F5 유지 · 보조 색 채도 정리 success #22C55E / info #0EA5E9 / warning #F97316 / error #EF4444 · VCard flat(테두리 제거·radius 16·그림자 `0 20px 27px rgba(0,0,0,.05)`는 admin-vuetify.css `.v-card`) · VBtn rounded lg(8px) · VNavigationDrawer elevation 0.
- CSS 유틸(admin-vuetify.css·레이어 전용 접두사 adm-): `.adm-grad-{primary,success,info,warning,error}`(310° 그라데이션) · `.adm-band`(v-app 최상단 absolute 300px·z 0) · `.adm-main`(relative z 1) · `.adm-content`(좌측 정렬·max-width 1600·padding 8/24/32) · `.adm-page-header`(흰색·설명 82%).
- 셸: layouts/admin.vue = 밴드(adm-grad-primary) + AdminSidebar + AdminTopbar + v-main.adm-main > .adm-content(v-container 제거·가운데 몰림 해소). AdminTopbar = `color="transparent" flat theme="dark"` 56px(흰 텍스트·아이콘)·아바타는 surface 배경+primary 이니셜·계정 메뉴 `theme="light"`·`.v-toolbar__append` 우측 12px. AdminSidebar(md↑) = `admin-sidebar--card`(top/left 16px·width 250·height calc(100%-32px)·radius 16·그림자)·레이아웃 예약폭 266(`:width="mdAndUp ? 266 : 250"`)로 콘텐츠와 겹침 없음·모바일은 temporary drawer 250 유지. 그룹 헤더 = 색 다른 28px 배지 아이콘(primary/info/warning/success/error/secondary)+라벨(`.adm-group-header`), 대시보드 항목은 prepend 배지(spacer 4px)로 하위 항목(`.adm-leaf` padding-start 50px)과 텍스트 x 정렬. 활성 = 흰 배경+`0 3px 8px` 그림자+bold·Vuetify 오버레이 제거. AdminPageHeader = 밴드 위 흰색. AdminPlaceholder = 새 카드(그림자·radius 16). 로그인 = admin-auth 레이아웃에도 밴드·상단 서비스명(흰색)·중앙 흰 카드(pt-16).
- 신규 공통 컴포넌트 1: AdminStatCard(label·value·caption?·icon·color? → `.adm-grad-*` 원형 아바타 아이콘)·사용처 없음(대시보드 트랙용)·vitest 렌더 1건(createVuetify 플러그인 주입).
- 기능·로직 무변경(가드·세션·누수 가드·메뉴 구조·라우트·e2e 동일). 검증: typecheck 0 · vitest 25 files 101 tests · Playwright 5 · 사용자 12장 0px · 1280/1920 사이드바·아바타 잘림 없음(스크린샷). 스크린샷(gitignored): `frontend/playwright-report/fe-22f/` — login/dashboard/members × desktop(1280)·wide(1920)·mobile(390) + members-{desktop,wide}-menu(아바타 메뉴)·members-mobile-drawer.
- 변경(FE-22g · 2026-09-16·zslab 요청): 밴드를 연한 톤으로(`.adm-band-soft` #C9DCFB→#E9F0FE·primary를 흰색에 옅게 섞은 그라데이션·`.adm-grad-primary`는 StatCard용으로 유지) + 밴드 위 텍스트 어두운 색 전환(Topbar `theme="dark"` 제거·아바타 primary 배경, 제목 on-surface 92%·설명/브레드크럼/구분자 70%). 대비(밴드 진한 쪽 #C9DCFB 기준): 제목 10.6:1·muted 5.6:1(62%는 4.4:1 미달이라 70%로 상향)·AA 충족. 검증 typecheck 0·vitest 25/101·Playwright 5·사용자 12장 0px. 스크린샷 `frontend/playwright-report/fe-22g/`. 트랩: 실행 중 dev 서버에서 `pnpm typecheck`(nuxt prepare)가 .nuxt/manifest를 재생성해 `#app-manifest` 미해석·vite 오류 오버레이 → 컨테이너 restart로 해소.

### §첫 렌더 (FE-22h · 2026-09-16)
증상(zslab 보고): /admin/* 진입 시 상단 밴드는 즉시, 콘텐츠 카드는 수 초 뒤 아래에서 올라옴.
- 실측(컨테이너 headless·dev·/admin/members·rAF 프레임 단위 카드 bbox·v-main padding·drawer transform·app-bar y 기록): 첫 방문 — 페인트된 첫 프레임(352ms)부터 card=(290,144)·padding 56/266·drawer transform 0 고정, 이후 3초간 변화 0 / 재방문(캐시) — 261ms부터 동일·변화 0 / 네트워크 스로틀(1.5Mbps·150ms) — 10,831ms에 첫 페인트·즉시 최종 위치·변화 0. 100ms 폴링(30샘플)도 동일. vuetify-styles(main.css 336KB)·admin-vuetify.css는 `ensureVuetify` await 뒤(206ms)·레이아웃 컴포넌트 CSS(VMain/VNavigationDrawer/VAppBar)는 모듈 평가 시 동기 주입(Vite dev)·Vuetify 자체가 첫 rAF까지 v-main/drawer 트랜지션을 비활성(composables/ssrBoot.js)이라 ② 트랜지션 원인도 배제.
- 판정: **③ 기타** — headless에서는 위치 이동 재현 0(① CSS 비동기 지연·② 트랜지션 모두 불일치). 후보: (a) Vite dev 최초 로드의 의존성 재최적화("Re-optimizing dependencies"·restart 로그 실측) 시 페이지 재로드로 중간 상태 노출(1회성), (b) 실브라우저에서 모듈 스트리밍(첫 방문 ~230 요청) 중 레이아웃 등록 전 프레임이 그려질 가능성(headless는 마운트~첫 페인트 사이 rAF 미발화로 관측 불가). 어느 쪽이든 "셸을 부분 상태로 그리지 않기"가 공통 대책.
- 수정(① 방식 적용): `lib/first-paint-gate.ts` `useFirstPaintGate()` — onMounted 후 첫 rAF에 ready → layouts/admin.vue·admin-auth.vue가 `<template v-if="ready">`로 밴드·사이드바·상단바·v-main(콘텐츠)을 **같은 프레임에 렌더**(게이트 전엔 v-app 배경색만). Vuetify JS·styles는 기존대로 vuetify 미들웨어가 렌더 전에 로드(관리자 한정 로딩·누수 가드·세션 분리 무변경). styles 사전 로드(link preload)는 미적용 — 로드 완료 전 화면은 배경색뿐이라 체감 지연을 키우지 않고, 관리자 한정 로딩 원칙상 사용자 entry에 preload를 넣을 수 없음.
- 검증: 재측정 첫 방문 603ms·재방문 494ms·스로틀 10,831ms 모두 첫 프레임부터 최종 위치·이동 0 / 사이드바 토글 트랜지션 정상(padding-left 266→0 보간 13샘플) / 관리자 내부 이동(/admin→/admin/members) 중 v-app 자식 수 4 고정(빈 프레임 0) / e2e ⑤ "카드가 보이는 60프레임 bbox 동일" 추가 / typecheck 0·vitest 25/101·Playwright 6·사용자 12장 0px.
- 트랩 후보: 관리자 한정 비동기 Vuetify CSS 로드 구조에서는 레이아웃 등록(drawer/app-bar)·모듈 스트리밍 순서에 따라 첫 렌더 레이아웃 이동이 생길 수 있다 → 셸은 항상 첫 프레임 게이트 뒤에 통째로 렌더한다. 또한 실행 중 dev 서버에서 `pnpm typecheck`(nuxt prepare)는 .nuxt/manifest를 지워 `#app-manifest` 오류를 내므로 typecheck 후 컨테이너 restart 필요(FE-22g 트랩 재확인).

## FE-23: 관리자 데모 로그인 — /admin/login "관리자 데모 로그인" 버튼 (2026-09-16)
목적: 포트폴리오 방문자가 자격증명 입력 없이 관리자 콘솔을 둘러보게 한다. **권한 제한 없음(실제 SUPER_ADMIN 계정 그대로) = zslab 결정(포트폴리오 목적)**. 계정 값은 채팅·커밋·로그·번들·응답 어디에도 싣지 않는다.

### §1-A 갈림길·채택/기각 근거
- α **Nuxt 서버 라우트 대행 — 채택**. 브라우저는 자격증명을 모른 채 `POST /_admin-demo/login`만 호출하고, Nitro가 비공개 runtimeConfig(`adminDemoEmail/adminDemoPassword` ← `NUXT_ADMIN_DEMO_*`)로 BE `/api/v1/auth/login`(role ADMIN)을 대행해 `{ token }`만 돌려준다. 이후 저장·role≠ADMIN 거절·redirect는 기존 스토어 경로(`storeAdminToken`) 재사용. BE 무수정·값 노출 0.
- β 자격증명 화면 표기(사용자 BUYER 데모처럼 public runtimeConfig) — **기각**: ADMIN 계정 값이 클라이언트 번들·HTML payload에 실린다(사용자 데모는 저권한이라 허용했던 전제가 성립하지 않음).
- γ BE 데모 전용 엔드포인트(임시 토큰·권한 축소) — **기각**: 권한 제한 없음이 결정이라 BE 신규 엔드포인트·role 분기가 소비처 없는 과잉개발(기조 4). 필요해지면 α의 서버 라우트가 호출 대상만 바꾸면 된다.
- 표시 boolean: `GET /_admin-demo/status` → `{ enabled }` **채택** / public 플래그 env(`NUXT_PUBLIC_ADMIN_DEMO_ENABLED`) 기각 — env 1개 추가·서버 실제 설정과 표류(이메일만 있고 비번 blank → 버튼 노출·404) 가능.
- 배치: `layers/admin/server/`(레이어) 채택 — `@nuxt/nitro-server` `scanDirs: layerDirs.map(dirs => dirs.server)` 실측(레이어 server/ 자동 스캔). 관리자 코드는 레이어에 모은다는 FE-22 원칙 유지·루트 `server/` 신설 없음.
- 경로 `/_admin-demo/*`: 루트 routeRules `/api/**`(backend 프록시)·운영 nginx `location /api/`·레이어 routeRules `/admin/**`(CSR 페이지) 모두 회피, gateway `location /`로 Nitro 도달(게이트웨이 경유 실측 200).
- env: `.env` 신규 키 없음 — `docker-compose.mall.yml` frontend environment가 backend와 동일한 `${ADMIN_BOOTSTRAP_EMAIL:-}`/`${ADMIN_BOOTSTRAP_PASSWORD:-}`를 `NUXT_ADMIN_DEMO_EMAIL/PASSWORD`로 매핑(값 복제 없음·dev·운영 공통). 미설정 시 `status {enabled:false}`·버튼 미표시·`login` 404.

### §2 확정 구현 규칙 (file:line)
- `layers/admin/server/lib/admin-demo-login.ts` — Nitro 무의존 코어(`isAdminDemoConfigured`·`loginAsAdminDemo` → `{ok:true, body:{token}} | {ok:false, statusCode:404|401}`). BE 실패 사유는 `console.warn('[admin-demo] …')` 메시지만(자격증명 미포함).
- `layers/admin/server/routes/_admin-demo/status.get.ts`·`login.post.ts` — 코어 결과를 `createError`로 매핑. BE 호출은 Node 내장 `fetch`(전역 `$fetch`는 임의 문자열 URL에서 nitro 타입드 라우트 추론 TS2321 Excessive stack depth 실측).
- `layers/admin/nuxt.config.ts` runtimeConfig(비공개) `adminDemoEmail:''`·`adminDemoPassword:''` / `lib/constants/auth.ts` `ADMIN_DEMO_STATUS_PATH`·`ADMIN_DEMO_LOGIN_PATH` / `stores/adminAuth.ts` `loginDemo()` + `storeAdminToken()`(login과 공유) / `pages/admin/login.vue` `attemptLogin()` 공통 흐름·onMounted status 조회·`v-divider` + outlined 보조 버튼(`data-testid="admin-demo-login"`)·실패 문구는 폼과 동일 단일 문구.

### §데모 계정 훼손 경로 (정찰·BE 무변경)
- `PATCH /api/v1/users/me/password`(anyRequest authenticated → ADMIN 토큰으로 호출 가능) — 변경 시 env 값과 불일치 → 데모 401.
- `POST /api/v1/users/me/withdraw` — 탈퇴 마킹·재로그인 차단. SuperAdminBootstrapRunner는 "SUPER_ADMIN 보유 회원 존재"면 skip이라 자동 복구 없음(복구 = DB 직접 조치).
- `DELETE /api/v1/admin/users/{id}/roles/{roleCode}` — self SUPER_ADMIN 회수 403·마지막 SUPER_ADMIN 409로 차단됨.
- 보호 장치 미추가는 결정(권한 제한 없음). 훼손 시 운영자 수동 복구 전제.

### §운영 401 선행 조건
- 운영 frontend가 받는 값 = 서버 `.env`의 `ADMIN_BOOTSTRAP_*`(backend와 동일 출처). decisions.md:10114 "운영 관리자 로그인 401(최초 기동 값과 현 .env 불일치 추정·미확정)"이 남아 있으면 운영 데모 버튼은 노출되나 클릭 시 401. 배포 전 운영 계정 비밀번호·서버 .env 정합 확인 필수.

### §검증
- vitest 27 files 110 tests(+admin-demo-login-server 3: 미설정 404·성공 {token}만·BE 실패 401/로그에 자격증명 없음 · admin-login-page 4: enabled true 버튼·클릭→loginDemo→/admin / false 미표시 / 조회 실패 미표시 / 401 문구 · adminAuth-store +2: 데모 성공 저장·role≠ADMIN 거절) · typecheck 0 · Playwright 7(smoke 1 + admin-shell 6·⑥ 데모 버튼→/admin 셸·admin_token path=/admin·auth_token 없음) · 사용자 12장 0px(동일 컨테이너에서 변경 stash 상태 기준선 재캡처 대비) · 빌드 산출물(.output 전체) 계정 값 문자열 0건·/admin/login HTML에 `adminDemo` 0건 · 런타임: env 미설정 `{enabled:false}`·login 404 / BE 불달 401(본문·stderr에 자격증명 없음).
- 트랩(실측): (1) 로컬 "dev" 컨테이너는 prod 이미지(CMD `node .output/server/index.mjs`·2026-07-10 빌드)에 bind-mount 상태라 코드 변경 반영 = 컨테이너 내 `pnpm build` + `docker restart`(HMR 없음). (2) `up -d` 재생성 시 컨테이너 로컬 상태 소실 — `corepack enable`·`playwright install chromium`·`install-deps chromium`(node:24-slim은 libglib 부재)·`/tmp` 픽셀 기준선. (3) install-deps 이후 글리프 래스터가 달라져 이전 컨테이너 기준선(final22h) 대비 텍스트 픽셀 diff(6~12K px) — 레이아웃 무관·동일 환경 재기준선으로 0px 확인. 기준선은 컨테이너 밖(스크래치패드)에도 보관할 것.
- 트랩 후보: 로컬 zslab_mall_frontend 재생성 시 corepack·chromium·install-deps·픽셀 기준선 소실 → 별도 chore에서 정상화(dev 이미지 재빌드).

## FE-24: 로컬 frontend 개발 컨테이너 정상화 (2026-09-16)
배경(정찰 실측·recon-report-fe-dev-container.md): `zslab_mall_frontend`가 prod runtime 이미지(2026-07-10 빌드·node:24-slim·CMD `node .output/server/index.mjs`·NODE_ENV=production)에 dev.yml 오버라이드(bind-mount·3000)만 얹혀 호스트 `frontend/.output`을 서빙 → HMR 없음·변경 반영 = 컨테이너 내 `pnpm build` + restart. 재생성 시 수동 설치한 corepack·chromium·apt 의존성·`/tmp` 픽셀 기준선 소실(FE-23 실발생).

### §1-A 갈림길·채택/기각 근거
- α **dev 이미지 정상화 — 채택**: `docker-compose.dev.yml` frontend에 `image: zslab-mall-frontend-dev`(prod 태그 `zslab-mall-zslab_mall_frontend`와 분리) + `Dockerfile.dev`에 `pnpm exec playwright install --with-deps chromium`(node:24 non-slim). 운영 파일(mall.yml·Dockerfile·deploy.yml) 무수정 → 운영 영향 0.
- β prod 이미지에 개발 도구 layer 추가 — **기각**: 운영 런타임 이미지 비대·NODE_ENV=production에 dev 도구 혼입(기조 1·4 위배).
- γ 공식 Playwright 이미지 베이스 / 검증 사이드카 — **기각**: 공식 이미지 Node 버전이 lockfile·CI(node 24)와 정합하는지 미검증·pnpm corepack 별도. 사이드카는 SSR·reuseExistingServer·baseURL 재설계가 필요해 단일 운영자 구성에 과잉.
- 결정 5건(정찰 추천안 전부 채택): (1) 이미지 태그 분리 (2) Playwright는 Dockerfile.dev `--with-deps`(버전은 pnpm exec가 lockfile 1.61.1을 그대로 사용·중복 기입 없음, pnpm 버전도 packageManager를 corepack이 읽음) (3) 픽셀 스크립트 커밋 `frontend/e2e/tools/pixel.mjs` + 기준선 gitignored `frontend/playwright-report/pixel-baseline/`(frontend/.gitignore:34 커버·bind-mount라 재생성과 무관) (4) dev-up.ps1은 `--build` 상시 미포함, Dockerfile.dev·package.json·lock 변경 시 수동 `up -d --build`(README·dev-up.ps1 주석) (5) node_modules 익명 볼륨 유지(재생성 시 승계 실측·동일 볼륨 ID).

### §2 확정 구현 규칙
- `frontend/Dockerfile.dev`: node:24 · corepack enable · pnpm install · `playwright install --with-deps chromium` · CMD `pnpm dev --host 0.0.0.0`. NODE_ENV 미설정(컨테이너 printenv 실측 unset → nuxt dev가 development 적용).
- `docker-compose.dev.yml:12-17` image 태그 + 이유 주석. mall.yml 불변.
- `frontend/e2e/tools/pixel.mjs` + `package.json` script `pixel`: `pnpm pixel capture <name>` / `pnpm pixel compare <a> <b>`(상이 픽셀 → `<b>-diff/` 빨강 PNG·exit 1). 캡처 규칙은 FE-22b §3-1 그대로(페이지별 새 컨텍스트·문서 높이 뷰포트 고정·fullPage:false·데모 로그인·DevTools 숨김). pngjs는 `playwright-core/lib/utilsBundle`(exports 키·`.js` 확장자 붙이면 ERR_PACKAGE_PATH_NOT_EXPORTED)에서 재사용.
- 문서: `frontend/README.md` "로컬 개발 컨테이너" 절(--build 시점·layers 재시작·typecheck 후 재시작·E2E env·픽셀 도구) · `scripts/dev-up.ps1`(git 제외·로컬) 헤더 주석 동기.

### §검증(실측)
- 빌드 `zslab-mall-frontend-dev` 953MB · prod 태그 `2316e027f7fc`(7/10) 무손상 유지.
- 재생성 후 Image=dev·CMD `pnpm dev`·NODE_ENV unset·node_modules 볼륨 동일 ID 승계. `/`·`/admin/login`·`/_admin-demo/status` 200. HMR: AppFooter 문구 수정→첫 폴링(2s 내) 반영·원복 즉시 반영.
- 신규 기준선 `main-dev` 12장 → 연속 3회 캡처 상호 0px(12/12).
- `--force-recreate` 1회 더 → 재설치 없이 pnpm 11.10.0·브라우저 3종·기준선 유지 · typecheck 0 · vitest 27/110 · Playwright 7/7 · 픽셀 12장 0px.
- 트랩 해소: (1) dev/prod 태그 공유로 prod 검증 빌드가 dev 이미지를 덮음(7/10~9/16) → 태그 분리로 해결. (2) 재생성 시 도구 소실 → 이미지 layer로 해결. 잔존 규칙: layers/·auto-import 신설 후 `docker restart` · 실행 중 dev에서 typecheck 후 restart(LT-15 계열) · lock 변경 시 컨테이너 내 `pnpm install`(익명 볼륨은 최초 생성 시만 이미지에서 복사).

## FE-25: 관리자 상품 목록 (2026-09-16)
배경: Track 76 BE(관리자 상품 CRUD·일괄·판정 단일화)·Track 77(업로드) 완료 후 관리자 첫 실화면. `/admin/products`는 플레이스홀더였고 관리자 조회·변경 UI 관습(표·필터·스낵바·다이얼로그)이 없었다. 정찰: BE 파라미터·응답 필드는 Track 76 그대로(GET keyword/status/soldOut/sellerPublicId/categoryId/sort/page/size·PagedResponse·bulk results)·사용자 영역 포맷 유틸은 datetime만 공용(`~/lib/utils/datetime`·레이어 import 가능)·가격은 컴포넌트 인라인뿐.

### §1-A 갈림길·채택/기각 근거
- 필터 상태 소유: α **URL query 단일 소스(route.query → 조회·조작은 router.replace) — 채택** / β 컴포넌트 로컬 state + URL 반영 — 기각(새로고침·뒤로가기 시 이중 소스 동기화 버그 여지). 기본값 항목은 URL에서 생략(`admin-product-query.ts` 순수 함수·vitest 9).
- 검색 확정 시점: α **검색 버튼·Enter 명시 확정 — 채택** / β 디바운스 — 기각(타이핑마다 URL history·API 호출이 흔들리고 운영자는 정확 검색 의도가 큼). 드롭다운은 즉시 확정.
- 행 변경 갱신: α **응답 후 해당 행 patch + 실패 원복(품절 토글은 판정 값을 재고로 근사·재조회 없음) — 채택** / β 매번 재조회 — 기각(선택·스크롤 소실·왕복 비용).
- 일괄 결과: α **스낵바 집계 + 실패 있을 때만 상세 다이얼로그 자동 열림 — 채택** / β 항상 다이얼로그 — 기각(전부 성공 시 클릭 1회 낭비). 재조회·선택 해제는 공통.
- 삭제 409: **안내 다이얼로그 + "판매중지로 전환" 버튼(같은 changeStatus 경로·이미 STOPPED면 안내만)** — 대안 검토 없음(BE detail이 판매중지를 안내하는 계약과 1:1).
- 상태 전환 메뉴: 3항목 고정 노출·허용 전이(`ADMIN_PRODUCT_ALLOWED_TRANSITIONS`·BE D-165) 외 비활성 — 대안 검토 없음. 호출 경로 분기(PENDING→SALE=approve·REJECTED=reject·그 외 sale-status)는 composable이 담당.
- 스낵바: **useState 큐 + 레이아웃 v-snackbar 1개** — 대안 검토 없음(페이지마다 인스턴스를 두면 라우트 이동 중 유실).
- 컴포넌트 위치: `components/admin/` 평탄(AdminProductFilterCard·AdminProductTable·AdminProductBulkBar·AdminConfirmDialog·AdminBulkResultDialog) — `admin/products/` 하위는 Nuxt pathPrefix 이름 합성(AdminProducts+AdminProduct…) 불확실해 기존 평탄 관습 유지.
- 사이드바/브레드크럼 활성: `resolveActiveMenuPath`(정확 일치 우선·하위 경로는 가장 긴 메뉴·대시보드 prefix 제외) — /admin/products/{id}에서 "상품 목록" 유지·기존 exact 동작 보존(vitest).
- D-165 §8 4층위 4단: `layers/admin/app/lib/constants/product.ts`에 ProductStatus·AdminProductSort·ProductImageType 유니온 + 라벨/색/옵션 단일 소스.

### §2 확정 구현 규칙
- 데이터: `types/admin-product.ts` · `composables/useAdminProducts.ts`(list·setSoldOut·changeStatus·bulkStatus·bulkSoldOut·remove·sellers·categories — 전부 useAdminApi) · `useAdminSnackbar.ts` · `lib/admin-product-query.ts` · `lib/admin-error-message.ts`(코드→문구·409 포함·detail 폴백) · `lib/admin-product-view.ts`(품절 표시 분기·일괄 집계) · `lib/format.ts`(원화·판매기간: 상시/즉시/무기한).
- 화면: `pages/admin/products/index.vue`(AdminPageHeader + 등록 버튼 / 필터 카드 / 선택 시 일괄 바 / v-data-table-server 선택·썸네일(오류 시 대체 아이콘)·상품명+ID·셀러·재고합·상태 chip·품절 chip(수동/재고 구분)·판매가·공급가(참고)·판매기간·수동 품절 스위치·수정/메뉴(전이·삭제) / 로딩·빈 상태 2종·에러+재시도) · `pages/admin/products/[id].vue` 플레이스홀더(FE-26).
- 표 문구 한글(페이지당·{0}-{1} / {2}). 트랩: nitro 타입드 fetch에 템플릿 리터럴 경로를 주면 TS2321(excessive stack depth) → 경로는 string 함수로 고정·제네릭 명시.
- 정리: `scripts/admin-product-status.ps1` 삭제(D-165 §8 이월 해소·decisions.md D-160 참조 문구 갱신) · D-166 보충(운영 실측·traversal 400 정정).

### §검증(실측)
- vitest 29 files 128 tests(+admin-product-query 9·admin-product-helpers 9) · typecheck 0 · Playwright 11(smoke 1 + admin-shell 6 + admin-products 4: 렌더·필터→URL→새로고침 유지·API 파라미터 / 품절 토글 PATCH·스낵바 / 전체 선택→일괄→성공 1/실패 1 스낵바+상세 / 삭제 409 안내·전이 메뉴 비활성·API는 page.route mock) · 사용자 12장 픽셀 0px(main-dev 대비).
- 로컬 dev 실데이터(backend 재시작으로 Flyway V21·V22 적용·데모 3건): 목록·필터·정렬·상세 라우트·사이드바 활성 확인. 스크린샷(gitignored): `frontend/playwright-report/fe-25/` — products-desktop(1440)·products-mobile(390)·products-filtered-desktop·product-edit-placeholder.
- 트랩: 로컬 backend 컨테이너가 Track 76 이전 코드로 떠 있으면 관리자 API가 500(NoResourceFound) → `docker restart zslab_mall_backend`. Vuetify v-dialog는 닫힌 다이얼로그 DOM도 유지 → 한 페이지 여러 다이얼로그는 testId prop으로 구분.

### §8 이월
- FE-26 상품 등록·수정 폼(이미지 업로드 연동·옵션/variant 편집) · 썸네일 컬럼은 외부 URL 로드 실패 시 대체 아이콘만(프록시 없음).

### FE-25 §토스트 — 관리자 알림을 vue-sonner로 교체 (2026-09-16)
배경: FE-25 1차는 useState 큐 + 레이아웃 v-snackbar 1개(하단·단건)였다. 연속 조작(품절 토글·일괄·삭제)에서 메시지가 덮이고 실패 상세로 이어지는 action이 없었다.
- §1-A: α **vue-sonner 2.0.9 — 채택**(MIT·ESM·Nuxt peer optional·CSS `vue-sonner/style.css` 별도 import·전역 셀렉터 0(html/body/* 없음·전부 `[data-sonner-*]` 스코프·모바일 @media 600px 내장)·스택·richColors·closeButton·action 내장) / β v-snackbar 커스텀(큐·스택·action 자작) — **기각**: Vuetify snackbar는 단일 인스턴스 전제라 스택·개별 타이머·action을 직접 구현해야 하며 그 코드가 라이브러리보다 큼 / γ vue-toastification — **기각**: Vue 3 정식 릴리스가 next 태그 정체·CSS가 전역 클래스(.Vue-Toastification__*)로 사용자 영역 격리 검증 부담.
- 관리자 한정 로드: `AdminToaster.vue`(Toaster + style.css + admin-toast.css import)를 admin·admin-auth 레이아웃에만 배치 → vue-sonner JS·CSS는 관리자 레이아웃 청크(VMain.*.css 15,967B)에만 묶이고 사용자 entry CSS는 해시 동일(31,041B)·entry JS는 +123B(Nuxt 컴포넌트 레지스트리의 `AdminToaster` 이름·청크 참조·sonner 코드 문자열 0). Vuetify 동적 로딩(ensureVuetify)과 무관한 정적 import이며 v-app 하위에 렌더돼 충돌 없음.
- API: `useAdminToast()` success/info 3s·warning/error 5s·`action:{label,onClick}`. 일괄 결과는 `summarizeBulkResult`가 전부 성공 success / 일부 실패 warning / 전부 실패 error로 분기하고, 실패가 있으면 토스트 action "상세 보기"로 BulkResultDialog를 연다(1차의 자동 열림 → 사용자 선택). 메시지·발생 시점은 1차와 동일(표현만 교체). `useAdminSnackbar`·레이아웃 v-snackbar 제거.
- 톤: `assets/css/admin-toast.css`(폰트 스택 --font-sans·radius 12·연한 그림자·action 버튼 radius 8) — data-sonner 스코프만.
- 검증: vitest 30 files 130 tests(+useAdminToast 2·집계 분기 error 추가) · Playwright 12/12(products 5: success 우상단 bbox / warning + action → 상세 다이얼로그 / 409 안내 후 500 → error / 모바일 390 상단 전폭 · admin-shell ② 누수 가드에 sonner DOM·시트 0 추가) · 사용자 12장 0px · typecheck 0. 스크린샷 `frontend/playwright-report/fe-25/toast-{success,warning,error}-desktop.png`·`toast-success-mobile.png`.
- 트랩: 실행 중 dev 서버에서 typecheck(nuxt prepare) 후 재시작 전 Playwright를 돌리면 데모 로그인 status가 비활성으로 보여 관리자 케이스가 전부 skip된다(README 규칙 재확인: typecheck → restart → e2e). Vuetify v-menu 오버레이는 열린 메뉴 1개만 DOM에 있어 행 인덱스 nth로 메뉴 항목을 고르면 안 된다.

### FE-25 §의미 색상 규칙 — 토스트·뱃지 (2026-09-16)
- 원칙: 색은 "요청 성공 여부"가 아니라 **결과의 의미**로 고른다(부트스트랩 개념). 이후 관리자 화면 공통 적용.
- 4종 정의(`layers/admin/app/lib/constants/semantic.ts`·단일 소스): **danger**=부정(연한 빨강) / **warning**=경고(연한 노랑) / **success**=긍정(연한 녹) / **info**=중립·기본(연한 파랑). 매핑: Vuetify 색 키(danger→error·나머지 동명·lib/vuetify.ts 테마 #EF4444/#F97316/#22C55E/#0EA5E9와 동일 색상군) · sonner variant(danger→error·richColors 유지) · chip 연한 톤 CSS 토큰 `--adm-semantic-{danger|warning|success|info}-{bg|fg}`(admin-vuetify.css·`.adm-chip--*`).
- useAdminToast API를 danger·warning·success·info 4종으로 통일(기존 error 제거·호출부 전환·메시지/시점 불변).
- 적용(`admin-product-view.ts` 순수 함수·vitest): 품절 토글 ON=danger / OFF=success · 일괄 = 전부 실패 danger / 일부 실패 warning / 전부 성공은 의도별(품절 ON danger·OFF success·상태 변경 info) · 상태 전환 결과 info(중립) · 삭제 성공 danger · API 실패 danger · 409 안내 다이얼로그 확인 버튼 warning · 목록 품절 chip(수동·재고 표기 유지) danger·재고 있음 success · 상태 chip 판매중 success·판매중지/거부 danger·판매대기 warning·그 외 info.
- 판정: 토스트 도입 시 사용자 entry JS +123B(Nuxt 컴포넌트 레지스트리의 AdminToaster 이름·청크 참조·sonner 코드 0)는 **수용**.
- 검증: typecheck 0 · vitest 30 files 132 tests(+토글/일괄 의도/매핑 테이블) · Playwright 12/12(② 초기 chip 클래스 4종 → ON danger 토스트+chip danger → OFF success 토스트+chip success·⑤ 모바일 danger) · 사용자 12장 0px. 스크린샷 `frontend/playwright-report/fe-25/list-chips-desktop.png`·`toast-danger-desktop.png`·`toast-success-desktop.png`·`toast-warning-desktop.png`·`toast-danger-mobile.png`.

## FE-26: 관리자 상품 등록·수정 (2026-09-16)
배경: FE-25 목록 이후 등록·수정 화면. 정찰: BE 계약 = POST 등록(옵션 tempKey·variant initialStock·이미지 미포함·PENDING 생성) / GET 상세 / PUT 기본정보(셀러 불변) / PUT images(전체 치환·순서=display_order) / PUT variants(기존 메타·신규 options[{groupId,value}]+initialStock·누락 soft-delete·D9 α·옵션 그룹 구조 불변) / 기존 variant 재고는 POST inventories/{var}/adjust / POST files/images(파일별 결과·413) / 400 fieldErrors·409 PRODUCT_VARIANT_OPTION_CONFLICT.

### §1-A 갈림길·채택/기각 근거
- 드래그 정렬: α **vue-draggable-plus 0.6.1 + sortablejs 1.15.7(MIT·터치 지원·관리자 청크 한정) — 채택** / β 네이티브 HTML5 DnD 자작 — 기각(터치 미지원·드롭 인디케이터·자동 스크롤 자작 비용) / γ 버튼(↑↓)만 — 기각(운영자 편의 요구). 사용자 entry JS +341B(컴포넌트 레지스트리·sortable 코드 0)·CSS 동일.
- 저장 오케스트레이션: α **클라이언트 순차 호출(등록: POST→PUT images(있을 때)→PUT variants(상태/수동품절이 기본과 다를 때) / 수정: PUT basic→PUT images→PUT variants→adjust(delta별))·한 단계 실패 시 즉시 중단 + 실패 단계 표시 + danger 토스트 — 채택** / β BE 단일 트랜잭션 복합 엔드포인트 신설 — 기각(Track 76 계약 변경·재사용 불가) / γ 병렬 호출 — 기각(순서 의존·부분 실패 해석 불가). `lib/admin-product-save.ts` 순수 함수(API 주입·vitest).
- 등록 부분 실패: α **POST 성공 후 후속 단계 실패 → 생성된 상품의 수정 화면으로 replace 이동(?partial=1 안내·이탈 경고 없이) → 재시도 — 채택** / β 생성 상품 자동 삭제(롤백) — 기각(주문 이력 없어도 삭제 API 호출 추가·업로드 파일 고아) / γ 등록 화면 잔류·재제출 — 기각(중복 등록).
- 기존 variant 재고 변경 경로: **adjust API delta(폼 stock − 서버 stock·사유 고정 문구)** — PUT variants가 기존 행 재고를 바꾸지 않는 BE 계약이라 대안 검토 없음. 조합표 재고 입력에 "서버 n → 조정 ±k" 힌트.
- 판매기간 입력: **네이티브 datetime-local + ":00+09:00" 부착**(KST 고정·BE ISO offset 계약) — Vuetify 3.13 VDateInput은 labs·시각 미지원이라 대안 검토 없음.
- 미저장 이탈: **onBeforeRouteLeave(confirm) + beforeunload** — 취소 시 라우트 이동 자체가 중단돼 레이아웃 unmount가 없으므로 기존 이탈 리로드 가드(useAdminLeaveGuard)와 충돌 없음. 등록 부분 실패 전환·저장 성공 시 스냅샷 갱신으로 경고 억제.
- 복귀 URL: **?back=목록 fullPath(/admin/products 프리픽스만 허용·resolveBackPath)** — 오픈 리다이렉트 방지. 대안 검토 없음.
- 업로드 진행률: **파일별 1요청 순차 + 상태(대기/업로드 중/실패) + indeterminate 바** — useAdminApi($fetch)는 업로드 progress 이벤트가 없고 XHR 우회는 "API는 useAdminApi만" 원칙 위배라 백분율 대신 파일 단위 상태로 대체(대안 검토: XHR 기각).
- 옵션 조합 재생성: 그룹/값 변경 시 데카르트 곱 재계산·같은 조합의 기존 행 입력 유지·사라진 서버 variant는 removed 표시(soft-delete)·**누락 조합은 신규 행으로 생성**(수정 시 서버에 없던 조합도 새로 만들어짐·"신규" chip 표기). 대안(누락 조합 미생성 옵션) 검토 없음 → 결정 필요 항목으로 보고.

### §2 확정 구현 규칙
- 모델·순수 함수: `types/admin-product-form.ts` · `lib/admin-product-form.ts`(emptyForm·detailToForm·cartesian·regenerateVariants·validateForm·toCreateRequest·toUpdateRequest·toImagesRequest·toVariantsRequest·stockAdjustments·mapFieldErrors·ensureMainImage·formSnapshot) · `lib/admin-product-save.ts`(saveProduct·SaveStepError) · `lib/admin-image-upload.ts`(precheck·413 문구) · `lib/admin-back-path.ts`.
- 컴포넌트: `AdminProductForm`(셸·검증·저장·dirty·수정 시 상태 chip/전이 메뉴/수동 품절 즉시 반영) · `AdminProductBasicSection` · `AdminProductImageSection`(갤러리/상세 공용·드롭존·순차 업로드·정렬·대표·삭제·미리보기·실패 재시도) · `AdminProductOptionSection`(단일/옵션 전환 확인·그룹 3·값 칩·조합표·전체 적용·removed 행). 페이지 `new.vue`·`[id].vue`(404 안내·partial 안내). FE-25 목록 "수정"·"상품 등록"에 back query.
- 검증 규칙: 셀러(등록)·카테고리·상품명(≤200)·판매가≥0·공급가≥0·시작<종료·그룹명/값 중복·그룹 1~3·조합 ≤100·코드 필수·추가금/재고≥0. BE fieldErrors는 `variants[0].variantCode` → `variants.0.variantCode`로 매핑.
- 검증(실측): vitest 31 files 148 tests(+admin-product-form 16) · typecheck 0 · Playwright 19/19(form 7: 등록 호출 순서·back 복귀 / 수정 4단계+adjust delta / 2단계 실패→partial 전환·재등록 0 / 이탈 confirm / 대표·드래그 정렬→images 본문 / 409 warning / 모바일) · 사용자 12장 0px · 로컬 실데이터 왕복(실 업로드 800×600 png → _thumb 생성·등록 PENDING·수정: 이름·재고 +7 adjust·값 추가로 variant 신규 생성·thumbnail_url이 PUT images 동기화로 _thumb 유지) PASS. 스크린샷 `frontend/playwright-report/fe-26/` new·edit × desktop(1440)·mobile(390) + image-grid-desktop·options-desktop.
- 트랩: (1) Playwright `toHaveURL(/…products\?page=1$/)`은 현재 URL의 `?back=` 값(디코드됨)에도 매칭 → `^http://host/…$` 앵커 필수. (2) 글로브 `products/prd_*`의 `*`는 `/`를 넘지 않아 `/images` 요청이 다른 route로 새어 나감 → URL predicate 함수 사용. (3) fullPage 캡처는 고정 사이드바 잔상(FE-22b) → 문서 높이 뷰포트 캡처.

### §8 이월
- 누락 조합 자동 생성 정책(생성/제외 선택) · 업로드 백분율 진행률(XHR 허용 시) · 옵션 그룹 구조 변경(BE 계약 필요) · 이미지 확대 미리보기는 v-img 단일(줌 없음).

### FE-26 보강 — 입력 컴포넌트 전역 defaults 통일 · 신규 조합 제외 (2026-09-16)
- 입력 스타일 통일: 원인 = `lib/vuetify.ts` defaults가 VTextField·VSelect만 outlined·comfortable로 지정돼 VAutocomplete(셀러)·VTextarea(설명)가 Vuetify 기본(filled)으로 렌더 → 같은 행 높이·모양 불일치. 조치 = `INPUT_DEFAULTS`(outlined·comfortable·rounded lg·primary)를 VTextField·VSelect·VAutocomplete·VCombobox·VTextarea·VFileInput 6종에 동일 적용. hideDetails는 전역 미지정(폼 에러·힌트 영역 필요). 개별 `density="compact"` 잔존은 의도된 예외 3곳(조합표 안 입력·전체 적용 인라인·일괄 바/정렬 select)에 사유 주석. 같은 행 필드 높이 동일(Playwright bbox: 셀러=카테고리·판매가=공급가) · hint/에러는 메시지 영역만 늘리고 필드 높이는 불변.
- 신규 조합 제외: α **신규 행(서버 미생성) "제외" 체크(기본 포함·제외 시 입력 비활성·흐림·저장 요청 제외·전 행 제외는 검증 에러) — 채택** / β 데카르트 곱 전체 자동 생성 유지 — 기각: BE D-165 트랩(3슬롯 조합은 soft-delete 후 UK에 남아 재생성 409·NULL 슬롯 조합도 삭제 이력 잔존)이라 원치 않는 조합이 한 번 생성되면 되돌릴 수 없음. 기존 행은 제외 체크 미노출(옵션값 삭제로만 soft-delete). 도움말 1줄("한 번 생성된 조합은 삭제 후 다시 만들 수 없습니다"). 등록 응답 variantPublicIds는 포함 행 순서와 1:1 매핑(`includedVariants`).
- 옵션 그룹 3개 한도: `product_variant.option1~3_value_id` 3슬롯 컬럼 구조(V1·PRD-4) 한도를 그대로 따른 것이며 FE 임의 제한이 아니다.
- 판정 2건: 사용자 entry JS +341B(컴포넌트 레지스트리·sortable 코드 0) **수용** / 업로드 진행률은 **파일 단위 상태(대기·업로드 중·실패)로 확정**(XHR 우회 없음·useAdminApi 원칙 유지).
- 검증: typecheck 0 · vitest 31 files 151 tests(+제외 3) · Playwright 21/21(⑧ 필드 높이·variant 클래스 / ⑨ 값 추가→제외→variants 요청 기존 행만·전 행 제외 검증 에러) · 사용자 12장 0px. 스크린샷 `frontend/playwright-report/fe-26/new-desktop.png`·`options-desktop.png` 갱신.

## FE-27: 관리자 주문 목록·상세·취소 (2026-09-16)
배경: Track 79(D-168)로 관리자 주문 BE 계약(목록·상세·취소·송장)이 갖춰져 `/admin/orders` 7 라우트 중 전체 주문·상세를 배선한다. 정찰(`docs/frontend/recon-report-fe-27.md`·gitignored) 충돌 4건: C1 목록 행에 품목·배송 id 없음 / C2 409 detail 고정 문구 / C3 기간 date 입력 선례 없음 / C4 결제·배송·택배사 라벨 사용자 FE 미정의.

### §1-A 갈림길·채택/기각 근거
- 데모 환불 완료: α 상세 Claim 행 "환불 완료 모의" 버튼(BE `AdminOrderDetailResponse.ClaimRow`에 refund{status,pgRefundId} 노출 + FE가 `POST /api/webhooks/refunds` 호출 + `_admin-demo` 유형 env 게이트) — **기각** / β **BE 무변경·Claim CANCEL APPROVED="환불 진행 중"(warning)·COMPLETED="환불 완료"(success) 표기만 — 채택**. 이유: `pgRefundId`는 서버 ULID 합성이며 어떤 조회 API에도 없어 α는 BE 계약 변경이 필수(FE 트랙 범위 밖), permitAll·무서명 웹훅 트리거를 관리자 UI에 상주시키면 실 PG 전환 시 잔존 위험(가짜 완료 버튼 금지 취지), 결제 모의는 사용자 결제 흐름이라 불가피했지만 환불은 관리자 필수 흐름이 아님. α는 별도 소트랙(BE+FE+게이트)으로 이월(decisions.md D-168 §8 보충).
- C1 목록 송장·배송완료: α **행 액션 클릭 시 `GET /admin/orders/{id}` 선조회 → 다이얼로그에서 PAID 품목 / SHIPPING 배송 선택(BE 무변경) — 채택** / β BE 목록 행에 orderItemIds·deliveryIds 추가 — 기각(Track 79 계약 변경·목록 페이로드 증가·행당 품목 수 가변). 상세에서는 이미 읽은 detail을 그대로 넘긴다(다이얼로그 3종이 `detail` prop 공용).
- 복귀 경로: **`resolveBackPath(back, base = '/admin/products')` base 파라미터화 — 채택**(1함수·2소비처·FE-26 spec 무변경) / 주문 전용 함수 신설 — 기각(중복). `ADMIN_ORDERS_PATH` 상수 추가.
- C2 409: `ADMIN_ERROR_MESSAGES.OPTIMISTIC_LOCK_FAILURE`="이미 종료됐거나 결제가 완료된 주문입니다…" + 다이얼로그 `stale` emit → 부모가 상세 재조회. 422 `CLAIM_STATE_INVALID`·`DELIVERY_INVALID_STATE`도 같은 경로(상태 경합). 400 `VALIDATION_FAILED`는 `mapFieldErrors`로 필드 표시(`orderItemPublicIds[n]`은 items로 묶음).
- C3 기간: **네이티브 `type="date"` v-text-field 2개 + `toPeriodStart/End`(T00:00:00/T23:59:59·종료일 포함)** — VDateInput은 labs(FE-26 판정 동일). URL은 yyyy-MM-dd로 유지·역전(from>to)은 필터 카드 안내(BE 400 전).
- C4 라벨: 관리자 `lib/constants/admin-order.ts`에 결제상태·배송상태·택배사 라벨+semantic만 신설, 주문·품목·클레임·사유·결제수단은 사용자 상수 import(`~/lib/constants/{order,claim,payment}`).
- 다이얼로그 소유: **다이얼로그가 API 호출·토스트·에러 분기를 소유하고 `done/stale/cancel`만 emit** — FE-25(페이지가 호출·다이얼로그는 dumb)와 달리 목록·상세 2페이지가 3종을 공유해 페이지 중복이 더 크기 때문. 성공 후 응답 조립 대신 항상 재조회(서버 상태 재확인).

### §2 확정 구현 규칙
- 데이터: `types/admin-order.ts` · `lib/constants/admin-order.ts` · `lib/admin-order-query.ts`(parse/toRouteQuery/toApiParams/hasActiveFilters/isPeriodInverted·검색어 50자 절단) · `lib/admin-order-view.ts`(isUnpaidOrder·cancellable/shippable/deliverableItems·sellerNamesLabel·claimRefundLabel·validateCancel/ShipmentForm·mapFieldErrors·cancelResultMessage) · `composables/useAdminOrders.ts`(list·detail·cancel·prepareShipment·markDelivered·approveClaim·rejectClaim) · `admin-error-message.ts` +7코드.
- 화면: `pages/admin/orders/index.vue`(URL 단일 소스·requestSequence·`?back`·행 메뉴는 actions PREPARE_SHIPMENT/MARK_DELIVERED만·CANCEL은 상세 이동) · `AdminOrderFilterCard`(검색·기간·주문/결제/배송 상태·정렬) · `AdminOrderTable`(13컬럼·chip semantic) · `pages/admin/orders/[id].vue`(요약·주문자·배송지·결제 요약+이력·품목별 배송/클레임·PAYMENT_EXPIRED alert에 `cancelReasons` 병기·없으면 시스템 종료 문구·approvable 승인/거절 → AdminConfirmDialog) · `AdminOrderCancelDialog`(결제 후 체크 목록 기본 전체·미결제 안내만·사유 필수·메모 500) · `AdminShipmentDialog`(PAID 품목·택배사 4값·송장 ≤100) · `AdminMarkDeliveredDialog`(SHIPPING 배송 select).
- 의미 색상: 주문 PENDING_PAYMENT/PARTIAL_CANCEL warning·진행 info·DELIVERED/CONFIRMED success·CANCELLED/PAYMENT_EXPIRED danger / 품목 *_REQUESTED warning·CANCELLED/RETURNED danger / 클레임 REQUESTED warning·APPROVED info·REJECTED danger·COMPLETED success / 결제 PENDING warning·PAID success·그 외 danger / 배송 DELIVERED success·그 외 info. 토스트: 취소·거절 danger, 승인·송장 info, 배송완료 success, 상태 경합 warning.
- 검증(실측): typecheck 0 · vitest 33 files 174 tests(+23) · Playwright 27/27(admin-orders 6) · 사용자 12장 0px(main-dev 대비) · 사용자 entry JS 230,663→231,312B(+649B·라우트 테이블 `admin-orders-id` + 컴포넌트 레지스트리 5개명·주문 코드 문자열 0)·entry CSS 해시 동일 — **수용**(FE-25/26 판정과 동일 성격). 스크린샷 `frontend/playwright-report/fe-27/{list,detail,cancel-dialog,shipment-dialog}-desktop.png`.
- 트랩: (1) **레이어 안에서 `~/lib/constants/order` import가 레이어 자체 `lib/constants/order.ts`로 해석**(같은 상대 경로가 레이어에 있으면 레이어 파일 우선·없으면 base 앱으로 폴백). vue-tsc는 base 앱으로 해석해 typecheck가 통과하지만 런타임(vitest·dev)은 undefined → 관리자 상수 파일명을 `admin-order.ts`로 바꿔 충돌 제거. 규칙: 레이어에서 사용자 파일을 `~`로 import할 때 같은 상대 경로의 파일을 레이어에 만들지 말 것(기존 `product.ts`는 상호 import가 없어 무해). (2) router.replace 반영 전 연속 확정(기간 시작→종료)은 앞 변경이 유실 → `pendingQuery`에 마지막 전송 상태를 두고 병합. (3) AdminConfirmDialog 닫힘 애니메이션 중 `claimDecision=null`이면 제목·버튼이 "거절"로 뒤집힘 → `lastDecisionAction` 유지. (4) `v-checkbox-btn`은 flex 1 0이라 행 안에서 라벨을 밀어냄 → `flex: 0 0 auto`. (5) fullPage 캡처 사이드바 잔상(FE-26 트랩 3 재확인) → 문서 높이 뷰포트 캡처.

### §8 이월
- 데모 환불 완료(α: BE ClaimRow refund 노출 + FE 버튼 + env 게이트) · `/admin/orders/{payments,cancellations,returns,exchanges,refunds,deliveries}` 6 라우트는 Placeholder 유지(관리자 Claim 목록 API 부재·D-168 §8) · 목록 13컬럼은 1280에서 가로 스크롤(표 wrapper) — 컬럼 축약/고정 여부는 운영 피드백 후.

### FE-27 보충 — 결제일 필드·목록 폭 조정 (2026-09-16)
- paidAt BE 추가형 필드 혼입: FE-27은 BE 무변경 원칙이었으나 목록 "결제일"은 FE가 직접 의존하는 표시값이라 `AdminOrderSummaryResponse.paidAt`(nullable)을 추가했다. 이미 배치 조회 중인 대표 결제 행(PAID 우선)의 `Payment.paidAt`을 그대로 쓰므로 쿼리 수(예산 8)·기존 필드·정렬 규칙은 불변(비파괴·추가형). 미결제·실패·만료는 null → NON_NULL로 생략. `AdminOrderIntegrationTest` T6에 PAID isNotEmpty / PENDING doesNotExist 단언. 트랩 재확인: 로컬 backend dev 컨테이너는 pull·수정 후 `docker restart` 전까지 구 코드(500 NoResourceFound·STEP 159/160).
- 컬럼 병합 13→9: 주문번호(ord_ ID는 tooltip) / 일시(주문일시·결제일 2줄) / 주문자 / 셀러 / 상품 / 금액(결제금액·배송비) / 결제(상태 chip·수단) / 주문·배송(주문 chip + 배송 chip + "클레임 진행중" chip·flex-wrap) / 관리. 1440px 실측 표 1169px vs wrapper 1126px(+43) → 주문 표 한정 `.adm-table--compact`(셀 패딩 16→12px·상품 표 불변)로 0. Playwright ①이 1440에서 표 wrapper·document의 scrollWidth−clientWidth=0을, ⑥이 390에서 body 오버플로 0(표 내부 스크롤만)을 단언한다. semantic 톤·actions 메뉴·`?back` 링크 동작 불변.
- 검증: rerun-tasks 953 GREEN · typecheck 0 · vitest 33/174 · Playwright 27/27 · 사용자 12장 0px(1차 login-desktop 5px는 재캡처 0·래스터 레이스).
- §8 이월: **배송 일괄등록** — 배송 관리 메뉴(`/admin/orders/deliveries` Placeholder)·BE 일괄 API(품목 N건 송장 등록·부분 실패 결과 형식) 부재·입력 방식(표 인라인 vs CSV 업로드) 결정 필요. 본 보강은 단건 다이얼로그만 유지.

### FE-27 보충 2 — paidAt 시간대·배송 chip 중복 (2026-09-16)
- 시간대 원인: 목록 실응답에서 `orderedAt 19:41:22` vs `paidAt 10:41:24`(−9h). 직렬화는 두 필드 모두 오프셋 없는 LocalDateTime으로 동일 규칙이었고, **저장값 자체가 UTC 기준**이었다. 경로: 사용자 FE `useCheckout.sendPaymentCallback`이 `occurredAt = new Date().toISOString().slice(0, 23)`(UTC 벽시계에서 Z만 제거) 전송 → `PaymentCallbackRequest.occurredAt: LocalDateTime` 무변환 → `Payment.complete`(PaymentService:254)·`Order.markPaid`(OrderEventHandler:50)에 그대로 저장. 다른 시각(ordered_at·created_at)은 JVM TZ=Asia/Seoul의 now()라 KST.
- 수정 계층: **원인 지점인 FE 모의 콜백 1곳** — `app/lib/utils/datetime.ts` `toKstLocalDateTime(date)`(+09:00 고정·KstOffsetSerializer와 대칭) 추가·useCheckout occurredAt 교체. BE·계약(오프셋 없는 LocalDateTime)·기존 테스트 무변경. 상세 결제 이력 paidAt·Order.paidAt은 같은 저장값이라 신규 결제부터 함께 정렬. 대안(BE에서 occurredAt을 UTC로 간주해 +9h 변환) 기각: 실 PG는 자체 시각 형식을 보낼 것이라 어댑터에서 정규화할 문제이며 현재 잘못된 값을 만드는 쪽은 모의 페이지뿐. vitest `test/unit/datetime.spec.ts` +3(KST 변환·날짜 경계·주문일시 이후 비교).
- 운영 영향: 표시가 아니라 **저장값 결함**. 이 수정 이전에 모의 결제로 생성된 운영·로컬 `payment.paid_at`·`orders.paid_at` 행은 −9h로 남는다. `paid_at` 기반 조회·집계·정산은 없음(정산은 confirmedAt 기준·settlement.paid_at은 별개 테이블)이라 기능 영향은 관리자 상세/목록·사용자 주문 상세의 결제일 표시뿐. 기존 행 보정(UPDATE … paid_at = paid_at + 9h·모의 결제 행 한정)은 운영 데이터 보호 규칙상 zslab 승인 후 별도 처리.
- 배송 chip 중복: 주문상태가 배송 집계와 같은 라벨(배송중·배송완료)일 때 "주문 · 배송" 셀에 같은 chip이 2개 보였다 → `showDeliveryChip(orderStatus, deliveryStatus)` = 배송이 있고 배송상태 라벨 ≠ 주문상태 라벨일 때만 표시(부분취소·배송중처럼 정보가 추가될 때만). 클레임 chip은 그대로. vitest 6 단언.
- 검증: typecheck 0 · vitest 33 files 178 tests(+4) · Playwright 27/27(1440 표·본문 오버플로 0 유지) · 사용자 12장 0px · 스크린샷 fe-27/list-desktop.png 갱신.

## FE-28: 관리자 취소·반품·교환 목록 · 거부 사유 다이얼로그 · 사용자 거부 사유/환불 표기 (2026-09-16)

정찰 `docs/frontend/recon-report-fe-28.md` · BE 계약 Track 80 D-169(3077de5d) · 브랜치 `feat/fe-28-claims`

### §1-A 갈림길·채택/기각 근거
1. 유형 탭 URL: α **쿼리 `?type=CANCEL|RETURN|EXCHANGE`(없으면 전체) — 채택** / β 경로 `/claims/cancel` — 기각. `admin-order-query`와 같은 URL 단일 소스(parse/toRouteQuery/toApiParams)로 새로고침·뒤로가기·`?back` 복귀에 탭이 함께 보존되고, 탭 전환은 `applyQuery({type})`(page 초기화)이며 필터 초기화는 탭을 유지한다(탭은 필터가 아님·`hasActiveClaimFilters`도 type 제외).
2. 거부 사유 라벨 출처: α **FE constants 단일 소스(`~/lib/constants/claim.ts`·BE SMS 문구와 동일 라벨 "이미 발송됨·정책상 불가·구매자 철회·기타") — 채택** / β BE 응답 라벨 — 기각(4층위 ④·BE 재배포 없음). 유형 제약(ALREADY_SHIPPED는 CANCEL만)도 `isClaimRejectReasonApplicable`/`claimRejectReasonCodesFor`로 FE가 걸러 BE 400을 예방하며, 어긋난 경우 다이얼로그가 MALFORMED_REQUEST를 사유 필드 안내로 흡수한다.
3. 복귀 경로: α **`resolveBackPath` 주문 base에 클레임 목록(`/admin/orders/claims`·쿼리 포함) 추가 허용(`EXTRA_BACK_BASES`) — 채택** / β back 없이 이동 — 기각. 상품 base는 불변(FE-26 회귀 0)·주문 상세는 `ADMIN_ORDERS_PATH` 호출부 무변경.
4. 처리 대기 건수 표시: α 탭 라벨 배지 — **기각 → β 페이지 헤더 chip 1개("취소 처리 대기 N건"·전체 탭은 "처리 대기 N건"·0건 tonal 중립) — 채택**. 전환 사유: BE `pendingCount`는 현재 요청의 type만 반영하므로 탭마다 숫자를 붙이면 비활성 탭 숫자는 알 수 없는데도 "0"처럼 보이거나 추가 호출(탭 수만큼)이 필요해 오해를 유발한다. 헤더 chip은 "지금 보고 있는 유형의 대기 건수" 의미가 명확하다.
5. 승인 토스트: α **현행 중립 문구("…요청을 승인했습니다") 유지 + 재조회 — 채택** / β "승인·환불 완료" — 기각. RETURN/EXCHANGE는 승인 시 COMPLETED가 아니므로 유형별 문구 분기는 과잉이며, 취소는 재조회 후 환불 chip("환불 완료")이 결과를 보여준다. e2e mock은 유형별(CANCEL COMPLETED / RETURN APPROVED)로 응답한다.
- 거부 다이얼로그 소유: FE-27 규칙 그대로 **다이얼로그가 API·토스트·에러 분기를 소유하고 done/stale/cancel만 emit**. 목록·주문 상세가 `AdminClaimRejectDialog`(target {claimId,type,productName}) 1개를 공유하며, 주문 상세의 "거절"은 확인 다이얼로그에서 이 다이얼로그로 전환했다(승인은 `AdminConfirmDialog` + 공용 `approveConfirmMessage` 유지).
- 환불 표기 우선순위: `claimRefundLabel`은 BE `refundStatus`(PENDING/COMPLETED/FAILED)가 있으면 그 값을 쓰고 없으면 FE-27의 취소 상태 추론(APPROVED=진행 중)으로 폴백 — 기존 helper 테스트 무변경·목록/상세 chip 단일 함수.
- 송장 422: `AdminShipmentDialog`가 `CLAIM_STATE_INVALID`(활성 클레임 품목·Track 80 C2)를 `DELIVERY_INVALID_STATE`와 같은 warning+stale 경로로 처리(기존 else 분기 danger·다이얼로그 잔류 결함 수정). 에러 문구에 "클레임 진행 중 품목의 송장 등록" 케이스 추가.
- 사용자 표기: 목록은 상태 배지 아래 무채색 보조 배지(거부 사유·환불 상태·값 있을 때만), 상세는 dl에 거부 사유·거부 메모·환불 상태 행(값 없으면 행 미노출)·타임라인 "거절" 유지. 주문 상세는 클레임 행이 없어 무변경. 사용자 톤(무채색) 유지·픽셀 기준선 화면 미포함.

### §2 확정 구현 규칙
- 데이터: `app/lib/constants/claim.ts`(+ClaimRejectReasonCode 4값·라벨·CODES·applicable/codesFor·CLAIM_REJECT_MEMO_MAX 500·RefundStatus 3값·라벨) · `app/types/claim.ts`(ClaimSummary +2·ClaimDetail +3) · 관리자 `types/admin-claim.ts`(AdminClaimSummary 20필드·AdminClaimListResponse +pendingCount·AdminClaimRejectBody·AdminClaimListQuery) · `types/admin-order.ts`(AdminOrderClaim·AdminClaimResponse +3) · `lib/constants/admin-order.ts`(+ADMIN_REFUND_STATUS_SEMANTIC) · `lib/admin-claim-query.ts`(parse/toRouteQuery/toApiParams/hasActiveClaimFilters·기간 변환 FE-27 함수 재사용) · `lib/admin-claim-view.ts`(rejectReasonItems·validateRejectForm·refundStatusChip·approveConfirmMessage) · `lib/admin-back-path.ts`(+ADMIN_CLAIMS_PATH·EXTRA_BACK_BASES) · `composables/useAdminClaims.ts`(list) · `useAdminOrders.rejectClaim(id, body)`.
- 화면: `lib/constants/admin-menu.ts` 주문 관리 하위 취소/반품/교환 3항목 → "취소·반품·교환"(`/admin/orders/claims`) 1항목·placeholder 3파일 삭제 · `pages/admin/orders/claims/index.vue`(URL 단일 소스·requestSequence·pendingQuery 병합·v-tabs 4·헤더 chip·승인 확인/거부 다이얼로그·행→주문 상세 `?back`) · `AdminClaimFilterCard`(검색·요청일 기간·처리 상태·정렬) · `AdminClaimTable`(8컬럼 2줄 병합: 요청·처리 / 유형·상태 / 주문·구매자 / 상품·옵션·수량 / 금액 / 사유·거부 사유 / 환불 chip / 승인·거부 — `availableActions`만 따르며 반품·교환은 표시만) · `AdminClaimRejectDialog`(사유 select 유형별·메모 500 counter·submitting 중복 방지·400 fieldErrors/MALFORMED 사유 안내·422 warning+stale) · `pages/admin/orders/[id].vue`(거절 → 거부 다이얼로그·클레임 행 "거부: 라벨 — 메모") · 사용자 `pages/claims/index.vue`·`[claimPublicId].vue`.
- 검증(실측): typecheck 0 · vitest 35 files 196 tests(+17: admin-claim-query 8·admin-claim-helpers 9) · Playwright 27 passed·5 skipped(admin-shell ADMIN_E2E 미설정·데모 creds 주입 실행 6/6) — admin-claims 3 신규·admin-orders ⑦⑧ 신규·admin-shell ① 메뉴 단언 · 사용자 12장 0px(fe-27d 대비) · 사용자 entry JS 231,312→231,002B(−310B·placeholder 3라우트 삭제)·entry CSS 해시 동일(Dp22OyE1·31,041B) · 실 BE(로컬 v23) 수동: 취소 요청 2건 → 목록 "취소 처리 대기 2건" → 거부(이미 발송됨+메모) → 사용자 상세 거부 사유·메모 / 승인 → Mock 자동 콜백 → 목록 "환불 완료"·사용자 상세 "환불 완료"·SMS 로그 3건 마스킹(010-****-1234). 스크린샷 `frontend/playwright-report/fe-28/{claims-list-desktop,claims-list-cancel-tab,claims-reject-dialog,manual-admin-list-pending,manual-admin-list-after,manual-buyer-claim-rejected,manual-buyer-claim-completed}.png`.
- 트랩: (1) **페이지 파일 삭제 후 nuxt dev 라우트 테이블 stale** — 삭제된 `cancellations.vue`를 `virtual:nuxt routes.mjs`가 계속 import해 전 페이지 500(Playwright가 dev 서버를 못 써 build&&preview 폴백 → :3000 EADDRINUSE). 프론트 컨테이너 `docker compose restart`로 해소. (2) 픽셀 비교는 **데이터 의존**(장바구니 품목·헤더 배지 수) — 데모 계정 장바구니가 비어 있으면 cart 2장 SIZE DIFF·mypage 2장 배지 333px. 기준선과 같은 3품목을 API로 복원한 뒤 재캡처해 0px. (3) `/tmp` 경로를 Git Bash에서 docker exec 인자로 넘기면 Windows 경로로 치환됨 → PowerShell로 실행.

### §8 이월
- 반품·교환 처리 흐름(수거 확인·교환 발송 액션·유형별 진행 컬럼) — Track 81·82. 목록 8컬럼은 공통 컬럼만.
- 셀러 클레임 화면(목록·거부 사유 입력) — 셀러 트랙.
- 관리자 클레임 목록 모바일(390) 표 내부 스크롤 허용·컬럼 축약 여부 — 운영 피드백 후.
- 사용자 클레임 목록/상세 픽셀 기준선 편입 여부(현 12장 미포함).

## FE-29: 사용자 반품 요청(사유·사진)·회수 송장·6단 타임라인 · 관리자 회수 확인·검수 다이얼로그·첨부 표기 (2026-09-17)

정찰 `docs/frontend/recon-report-fe-29.md` · BE 계약 Track 81-A D-170(8da3640a)·81-B D-171(f9657ebf) + 본 트랙 BE 추가형 필드 1건 · 브랜치 `feat/fe-29-returns`(PR은 81-A·81-B와 1건)

### §1-A 갈림길·채택/기각 근거
1. 반품 기한 표시(Q1): α **FE 미판단 — DELIVERED면 버튼 노출·기한 초과는 BE 422 detail 문구로 안내 — 채택** / β 주문 응답에 deliveredAt 추가 — 기각. 기한 SoT는 BE `ReturnWindowPolicy`(원 발송·7일)이며 FE가 복제하면 재발송·교환 발송 제외 규칙까지 따라가야 한다. 422는 detail 부분 일치(`claimRequestErrorMessage`·기한/사유/불합격 이력/미배송완료/CLM-5)로 사용자 문구를 나누고 문구가 바뀌면 일반 422로 폴백한다.
2. 사용자 재발송 송장(Q2): α 문구만 — 기각 → **β BE `ClaimResponse.reshipment`(검수 불합격 재발송 OUTBOUND Delivery·null 생략) 추가형 필드 — 채택(FE 트랙에 BE 변경 혼입)**. 사유: 요구 3("불합격 시 재발송 송장 표기")을 응답 없이 만족할 수 없고, `getClaim`이 이미 클레임 연결 Delivery를 1쿼리로 읽던 것을 방향별로 나누기만 하면 되어 쿼리 수·Flyway 무변경. 통합 테스트 T3(PASS → 없음)·T5(FAIL → HANJIN/RESHIP-0001)로 박제.
3. 관리자 주문 상세 재발송 표기(Q3): α **품목 배송 블록(OUTBOUND 최신)에 "재발송" chip(FAIL 반품이 있을 때) + 클레임 행은 회수·검수만 — 채택** / β 클레임 행 송장 중복 표기 — 기각(D-170 "품목 배송 = 최신 발송" 규칙과 이중 표기).
4. 회수 확인·검수 진입점(Q4): α **목록 전용·주문 상세는 표기만 — 채택** / β 양쪽 — 기각. 상세 클레임 행은 1줄 flex가 이미 포화이고 검수 다이얼로그는 6필드라 목록에서만 연다(승인/거부는 FE-28대로 양쪽 유지).
5. 사진 업로드 시점(Q5): α **선택 즉시 업로드(`POST /claims/attachments`) → attachmentId 보관 → 요청 body `attachmentIds`(화면 순서) — 채택** / β 제출 시 일괄 — 기각. BE 계약이 2단계이며 파일별 부분 실패를 즉시 보여줄 수 있다. 삭제는 목록 제거만(미연결 첨부 정리는 D-171 §8 이월). 사유를 첨부 불가 사유로 바꾸면 목록을 비운다(BE 400 예방).
6. 검수 FAIL 사유(Q6): α **RETURN 적합 사유 select(`CLAIM_INSPECTION_FAIL_REASON_CODES`·기본값 INSPECTION_FAILED) — 채택** / β 고정 — 기각(BE가 사유를 받고 SMS 라벨에 실림).
- **INSPECTION_FAILED 드롭다운 제외**: 거부 사유 상수는 5값(라벨 "검수 불합격")이지만 일반 거부(REQUESTED → REJECTED)에서 이 사유는 BE 400이라 `isClaimRejectReasonApplicable`이 유형 무관 false·`CLAIM_REJECT_REASON_CODES`는 4값 유지. 검수 다이얼로그만 별도 목록을 쓴다(admin-claims e2e ② "반품 거부 목록" 회귀 0).
- 반품 진입: `claimableTypes` SHIPPING → `[]`(D-170 DELIVERED 한정·매트릭스 주석 정정). 사유 select는 `claimReasonCodesFor(type)`(RETURN 3값). 첨부 섹션은 `isClaimAttachmentAllowed`(RETURN + PRODUCT_DEFECT|WRONG_PRODUCT)일 때만.
- 사용자 업로드 위젯: admin 드롭존은 Vuetify·`#layers/admin`·MAX 20 결합이라 base 레이어에서 참조 불가(역방향) → `components/claim/AttachmentInput.vue`(hidden file input + 버튼·썸네일 grid·파일별 실패 목록·5장 잔여) 신설, 사전 검증은 `utils/claim-attachment.ts` 순수 함수(형식·10MB·5장). 택배사 상수도 사용자용 `constants/delivery.ts`(4값·admin 상수와 값 동일).
- 타임라인: `utils/claim-timeline.ts` 순수 함수. 반품 6단(요청→승인→회수 송장→회수 확인→검수→환불 완료)·현재 단계는 status/returnShipment/pickedUpAt/inspectionResult/COMPLETED 조합, 검수 불합격은 "검수 불합격" 5단 종결, 승인 전 거절 2단, 취소·교환 3단 유지. 시각은 BE 값만(요청·회수 송장 shippedAt·회수 확인·종결 processedAt / 승인·검수는 미표기).
- 관리자 목록: 컬럼 추가 없이 "환불 · 회수/검수" 2줄 병합(환불 chip + 검수 chip / caption "회수 확인 MM.dd HH:mm" 또는 "회수 택배사 송장" · "첨부 N")·회수 확인(secondary)·검수(primary) 버튼(BE `availableActions` CONFIRM_PICKUP|INSPECT). 1440 스크롤 0 유지(e2e ① 재검증).
- 검수 다이얼로그: `AdminClaimInspectDialog`(결과 radio → PASS 재입고 radio 필수 / FAIL 사유 select·메모 500·재발송 택배사·송장 ≤100 필수)·`validateInspectForm`으로 BE 조건부 필수를 제출 전 적용·400은 fieldErrors 없으면 결과별 대표 필드에 안내·422 warning+stale·PASS info/FAIL danger 토스트. 회수 확인은 `AdminConfirmDialog` + `confirmPickupMessage`.

### §2 확정 구현 규칙
- BE(추가형): `ClaimResponse +reshipment`(`ReturnShipmentResponse`)·`ClaimService.getClaim` 클레임 Delivery 1쿼리에서 RETURN/OUTBOUND 분리 · 테스트 ClaimReturnIntegrationTest T3/T5·BuyerClaimControllerTest.
- 데이터: `app/lib/constants/claim.ts`(claimableTypes·RETURN_REASON_CODES·claimReasonCodesFor·CLAIM_ATTACHABLE_REASON_CODES·CLAIM_ATTACHMENT_MAX·isClaimAttachmentAllowed·ClaimRejectReasonCode 5값·CLAIM_INSPECTION_FAIL_REASON_CODES·ClaimInspectionResult+라벨) · `constants/delivery.ts`(신규) · `utils/claim-attachment.ts`·`utils/claim-timeline.ts`·`utils/claim-request-error.ts`(신규) · `types/claim.ts`(ClaimRequestBody.attachmentIds·ClaimAttachmentUploadResponse·ClaimShipment·ReturnShipmentBody·ClaimDetail +6) · `composables/useClaim.ts`(uploadAttachments·registerReturnShipment) · 관리자 `types/admin-claim.ts`(AdminClaimAction 4값·Summary +7·AdminClaimInspectBody)·`types/admin-order.ts`(AdminOrderClaim +7)·`lib/admin-claim-view.ts`(confirmPickupMessage·inspectionChip·inspectFailReasonItems·validateInspectForm)·`useAdminOrders`(confirmPickupClaim·inspectClaim).
- 화면: 사용자 `components/claim/AttachmentInput.vue`(신규)·`pages/claims/new.vue`·`pages/claims/[claimPublicId].vue`(회수 송장 폼·회수/검수/재발송/첨부 행) · 관리자 `AdminClaimInspectDialog.vue`(신규)·`AdminClaimTable.vue`·`pages/admin/orders/claims/index.vue`·`pages/admin/orders/[id].vue`(회수·검수 chip·첨부 썸네일 40px→v-dialog 확대·"재발송" chip).
- 검증(실측): BE `--rerun-tasks` 187파일 987 tests 0 fail · typecheck 0 · vitest 36 files 214 tests(+18: claim-return 13·admin-claim-helpers 5·OrderDetailPage 1) · Playwright 36/36(ADMIN_E2E 주입·skip 0·2회 연속: claims 3 신규·admin-claims ①갱신+④신규·admin-orders ⑦ 갱신) · 사용자 12장 0px(fe-28 대비) · 사용자 entry JS 231,002→231,028B(+26)·entry CSS 31,041→31,597B(+556·첨부 grid/썸네일 Tailwind 유틸리티·해시 변경) · 실 BE(로컬 v25·Mock PG/SMS) 수동: A 반품(사진 2)→승인→회수 송장→회수 확인→검수 PASS 재입고→환불 COMPLETED·RETURNED·on_hand +1 / B PAID→송장·배송완료→반품(오배송·사진 1)→승인→회수 송장→회수 확인→검수 FAIL(메모·로젠 재발송)→사용자 상세 "검수 불합격"·메모·재발송 송장·관리자 상세 "재발송" chip·첨부 / SMS 로그 7건 마스킹 / 종료 후 데모 데이터 스냅샷 원복(6지표 일치)·업로드 파일 삭제. 스크린샷 `frontend/playwright-report/fe-29/{claim-new-return-attachments,claim-detail-fail,claims-list-desktop,claims-inspect-dialog,order-detail-return-claim,manual-*}.png`.
- 검수 FAIL 사유 고정 전환(D-172·외부 검토 A): 검수 다이얼로그의 불합격 사유 select를 제거하고 `INSPECTION_FAILED`를 고정 전송한다(BE가 그 외 사유를 400으로 봉인). §1-A 6(Q6 α)은 본 전환으로 대체·`CLAIM_INSPECTION_FAIL_REASON_CODE` 상수·메모에 불합격 근거 안내.
- 번들: entry CSS +556B는 사용자 첨부 위젯·상세 첨부 grid가 새로 쓰는 Tailwind 유틸리티(grid-cols-5·aspect-square·object-cover·bg-white/90 등)가 entry CSS에 합류한 것으로 컴포넌트 JS 증가(+26B)와 별개 — **수용**(사용자 영역 첫 파일 입력·썸네일 UI).
- 트랩: (1) **nuxt dev 서버가 신규 컴포넌트 파일을 해석하지 못함**(`Failed to resolve component: AdminClaimInspectDialog`·`.nuxt/components.d.ts`엔 등록됨) — 프론트 컨테이너 restart로 해소(FE-28 라우트 stale과 같은 계열). (2) **1440 가로 스크롤 측정이 병렬 워커 부하에서 12px 오탐**(admin-orders ①·웹폰트 적용 전 폴백 폰트 폭) — `document.fonts.ready` 대기 후 측정. (3) e2e 픽스처 ULID는 26자 정확히(25자면 `/claims/new` 정규식에 걸려 "잘못된 접근"). (4) 사용자 페이지 useFetch는 SSR이라 `page.goto`로는 route mock이 안 먹음 — `/login?redirect=`로 클라이언트 내비게이션.

### §8 이월
- 사용자 반품 요청 화면 기한 표시(남은 일수) — 주문 응답 deliveredAt 추가 시.
- 반품 422 세부 에러 코드 분리(현재 CLAIM_STATE_INVALID 단일 + detail 문구 부분 일치) — BE 코드 분리 시 `claimRequestErrorMessage` 문자열 매칭 제거.
- 첨부 삭제 API(업로드 후 제거한 파일은 미연결로 남음) — D-171 미연결 첨부 정리와 함께.
- 셀러 화면(회수 확인·검수) — 셀러 트랙. 교환(Track 82) 회수·검수 재사용.
- 사용자 클레임 화면 픽셀 기준선 편입 여부·관리자 목록 모바일 컬럼 축약 — 운영 피드백 후.

### FE-29 보충 — 업로드 한도·문구 D-174 동기화 (2026-09-17·검수 4단계)
- 구매자 첨부 사전 검증 파일당 5MB(`CLAIM_ATTACHMENT_MAX_MB` 1곳·`CLAIM_ATTACHMENT_MAX` 5장 유지)·파일별 코드 `IMAGE_TOO_LARGE`(해상도 8,000px 안내·사용자/관리자 공통 문구)·요청 단위 400은 `uploadRequestErrorMessage`(BE detail "연결되지 않은 첨부" 부분 일치 → 미연결 20장 안내·전용 코드 없음). 관리자 드롭존 20장·10MB 무변경. 검증: typecheck 0 · vitest 36 files 215 · Playwright 36/36 · 픽셀 12장 0px(fe-29 대비).

## FE-30: 교환 화면 — 사용자 옵션 선택·7단 타임라인 · 관리자 교환 발송·배송완료·옵션 라벨 (2026-09-17)

정찰 `docs/frontend/recon-report-track83.md` · BE 계약 Track 83 D-177(f68a47ff) + 본 트랙 BE 추가형 필드 1건(exchangeCompleted·cf0a26cc) · 브랜치 `feat/track-83-exchange`.

### §1-A 갈림길·채택/기각 근거
1. 교환 옵션 후보 소스(Q1): α **주문 상세 → `/claims/new` query에 `product·variant·unitPrice` 추가 + `useProductDetail`(SALE variant만) FE 필터(같은 판매가·품절 제외·현재 옵션 제외) — 채택** / β `/claims/new`에서 주문 재조회 — 기각(주문 pid까지 query 필요·조회 1회 추가) / γ BE "교환 가능 옵션" 엔드포인트 — 기각(커밋된 BE 계약 확대·규칙 보장은 이미 BE 422가 담당). 같은 가격 기준은 BE `ClaimExchangeService.validateExchangeOption`(교환 옵션 현재 판매가 base+additional == order_item.unit_price)과 동일하게 `variant.salePrice === 주문 unitPrice`.
2. 교환 타임라인(Q2): α 반품 6단 재사용(끝 "교환품 배송") — 기각 → **β 7단(신청→승인→회수→검수→교환품 발송→배송완료→완료) — 채택**. 회수는 회수 송장 등록~회수 확인, 교환품 발송 시각은 `reshipment.shippedAt`, 배송완료는 `reshipment.deliveredAt`, 검수 불합격은 4단 종결.
3. 관리자 교환 발송 UI 위치(Q3): α **목록 전용(회수 확인·검수와 동일) — 채택** / β 주문 상세에도 추가 — 기각(상세는 표기만·기존 `AdminMarkDeliveredDialog`가 교환 OUTBOUND를 품목 배송으로 받아 배송완료는 무수정 동작).
4. 교환 완료 품목의 교환 버튼(Q4): α 422 문구만 — 단독 기각 → **β BE `OrderItemResponse.exchangeCompleted`(추가형·D-177 보충)로 버튼 숨김 + 422 문구 매핑 병행 — 채택**. 반품 버튼은 유지.

### §2 확정 구현 규칙
- 데이터: `constants/claim.ts` `claimableTypes(status, exchangeCompleted)`·`isPickupBasedClaimType`·`claimReasonCodesFor`(RETURN·EXCHANGE 3종)·`isClaimAttachmentAllowed`(RETURN·EXCHANGE + 불량·오배송) / `utils/claim-exchange-options.ts`(`exchangeOptionCandidates`·`variantOptionLabel` — BE OptionLabelResolver와 같은 "그룹: 값 / …" 형식) / `utils/claim-request-error.ts` 교환 422 8종(재교환 "이미 교환한 상품은 다시 교환할 수 없습니다.")·400 교환 옵션 / `utils/claim-timeline.ts` EXCHANGE 7단 / 타입 `ClaimRequestBody.exchangeVariantId`·`ClaimDetail.exchangeOptionLabel·originalOptionLabel`·`OrderItem.exchangeCompleted`.
- 사용자: `pages/orders/[orderPublicId].vue`(EXCHANGE query 확장·exchangeCompleted 버튼 숨김) · `pages/claims/new.vue`(교환 query 검증·`useProductDetail(pid, { immediate })` 조건부 조회·radio 후보·로딩/실패(재시도)/판매중지/0건 상태·미선택 제출 불가·body `exchangeVariantId`·문구 "같은 가격의 다른 옵션으로만 교환됩니다") · `pages/claims/[claimPublicId].vue`(교환 옵션 행·회수 안내 분기·"교환품 배송 송장") · `composables/useProductDetail.ts` `immediate` 옵션(기본 true·기존 동작 보존).
- 관리자: 타입 `AdminClaimAction +REGISTER_EXCHANGE_SHIPMENT·MARK_EXCHANGE_DELIVERED`·라벨 2필드 / `lib/constants/admin-claim.ts`(신규·`ADMIN_CLAIM_ACTION_LABEL`) / `AdminClaimTable` 옵션 캡션(`row-exchange-option`)·버튼 2 / `AdminExchangeShipmentDialog`(신규·검수 FAIL 재발송 폼 패턴·400 fieldErrors·422 stale) / `claims/index.vue` 배선·배송완료 `AdminConfirmDialog` → 기존 `markDelivered(reshipment.deliveryPublicId)` / `useAdminOrders +registerExchangeShipment`·승인 주석 정정(refundAmount 폐기) / `admin-claim-view` 승인 문구(교환 재고 예약 안내)·`confirmPickupMessage(type)`·`inspectPassLabel/Toast`·`validateExchangeShipmentForm` / `AdminClaimInspectDialog` target.claimType 분기 / 주문 상세 클레임 행 옵션 라벨. 재고 부족 승인 422는 기존 `toAdminErrorMessage` warning toast(BE detail 그대로).
- 검증(실측): BE `--rerun-tasks` 193파일 1031 tests 0 fail(1030 → +1 T14) · typecheck 0 · vitest 36 files 221 tests(+6: claim-return 3·admin-claim-helpers 1·OrderDetailPage 1·exchange-options 2 → 반영분) · Playwright 40/40 skip 0(+4: claims ④⑤⑥·admin-claims ⑤·ADMIN_E2E_* 런타임 주입) · 픽셀 생략(baseline 12장 = home·products·product-detail·login·cart·mypage·클레임·관리자 화면 없음·영향 0) · 로컬 브라우저 실측(실 API): 교환 신청(후보 "색상: 화이트 / 사이즈: L" 1건·미선택 제출 불가) → 관리자 승인(재고 예약 안내·reserved 1·스냅샷 "색상: 블랙 / 사이즈: L") → 구매자 회수 송장(교환 안내 문구) → 회수 확인 → 검수 PASS("합격 (교환품 발송 대기)") → 교환품 발송(옵션 표기) → 배송완료 → 구매자 상세 7단 완료·"교환품 배송 송장"·주문 상세 교환 버튼 0·반품 버튼 1·품목 옵션 "색상: 화이트 / 사이즈: L"·DB claim COMPLETED·reserved 0·이력 ORDER −1/RETURN +1.
- 트랩: (1) Nuxt 4 `useFetch` `error` 초기값은 `undefined` — `!== null` 비교로 차단 판정하면 항상 true. `status === 'error'`로 판정. (2) `immediate: false`면 `pending` 초기값이 버전별로 달라 `status === 'pending'`으로 판정. (3) Playwright `page.goto`는 SSR fetch가 route mock을 거치지 않아 상태를 바꾼 재진입은 별도 테스트(새 컨텍스트·데모 로그인 리다이렉트)로 분리(claims.spec ⑥).

### §8 이월
- 교환 옵션 후보를 서버가 계산하는 엔드포인트(γ) — FE 필터와 BE 규칙이 어긋나는 사례가 생기면.
- 관리자 주문 상세의 교환 발송 chip("교환 발송"·현재 `isReshipment`는 RETURN FAIL만).

## FE-31: 관리자 회원 관리 화면(목록·탈퇴회원·상세·액션 4종·주문/클레임 탭) · 구매자 비밀번호 변경 강제 흐름 (2026-09-18)

정찰 `docs/frontend/recon-report-track84-admin-member.md` · BE 계약 Track 84 D-178(1fd9f08c·"API 계약 변경" 절이 SoT·본 항목에서 재기술하지 않음) · 브랜치 `feat/track-84-admin-member`.

### §1-A 갈림길·채택/기각 근거
1. 탈퇴회원 목록: **별도 페이지 `/admin/members/withdrawn`(기존 메뉴 사양 `admin-menu.ts` 4항목) — 채택**, 목록 컴포넌트 `AdminMemberListView`를 `status: AdminMemberStatus` props(AdminMemberListView.vue:27)로 일반회원(ACTIVE)·탈퇴회원(WITHDRAWN)이 공유. status는 URL query가 아니라 페이지 고정 주입이라 API 파라미터에서만 붙는다(`toAdminMemberApiParams(state, status)`·admin-member-query.ts:59). 대안 검토 없음.
2. 목록 컬럼(AdminMemberListView.vue:88): 번호(역번호 `memberRowNumber(totalCount, page, size, rowIndex) = totalCount − page×size − rowIndex`·admin-member-query.ts:76)·이름·이메일·연락처·가입일·최종구매일(null "-")·탈퇴일(WITHDRAWN만 조건 컬럼)·회원변경(→ `/admin/members/{publicId}?back=<목록 fullPath>`). 아이디 컬럼은 두지 않는다(D-178 결정 5·로그인 ID=이메일).
3. 주문/클레임 탭 표: α 기존 `AdminOrderTable`·`AdminClaimTable` 재사용 【기각】 — 행 액션 메뉴(송장·승인·검수 등)와 다이얼로그 배선이 묶여 있어 읽기 전용 탭에 넣으면 동작 없는 메뉴가 노출 / **β 읽기 전용 `AdminMemberActivityTable`(번호·주문번호(+상품 캡션)·상태·금액·일시) 신설 【채택】**. 주문·클레임 행을 `AdminMemberActivityRow`(AdminMemberActivityTable.vue:10)로 정규화하고 행 클릭은 주문 상세로만 이동(클레임 상세 라우트 없음). 탭·페이지·크기는 상세 URL query(`?tab=orders|cancel|return|exchange`·`?page=`·`?size=`·`applyActivityQuery`·[id].vue:133)가 단일 소스, 조회는 `useAdminMembers.listOrders(publicId, page, size)`(useAdminMembers.ts:53·`GET /admin/orders?buyerPublicId=`)·`listClaims(publicId, type, page, size)`(:58·`GET /admin/claims?buyerPublicId=&type=`), 늦은 응답 폐기(`loadActivity`·[id].vue:152).
4. 뒤로가기(`admin-back-path.ts`): `ADMIN_MEMBERS_PATH`·`ADMIN_MEMBERS_WITHDRAWN_PATH`(:8-9) 추가 — 회원 상세 base는 일반회원 목록 + 탈퇴회원 목록(쿼리 포함) 허용(EXTRA_BACK_BASES). 주문 상세는 회원 상세(`/admin/members/usr_…?tab=…`)에서도 진입하므로 base 정확·`base?` 매칭과 별도인 **prefix 허용 표 `EXTRA_BACK_PREFIXES`(:24·`/admin/members/`)** 를 추가(`resolveBackPath`·:32). 기존 base 정확 매칭 규칙·상품/주문/클레임 동작 불변, 내부 경로 prefix라 오픈 리다이렉트 없음.
5. 상세 액션([id].vue): 탈퇴 회원(`isWithdrawn`·admin-member-view.ts:78)은 정보 수정·탈퇴·임시 비밀번호·등급 변경 4종 전부 disabled + 안내 alert(`member-withdrawn-notice`), 연락처 없으면 임시 비밀번호 버튼 disabled + 안내(`canResetPassword`·:83·`resetAllowed`·[id].vue:60). 다이얼로그는 `AdminShipmentDialog` 패턴 — 로컬 검증(`validateMemberForm`·:23 name ≤50·phone `ADMIN_MEMBER_PHONE_PATTERN`(admin-member.ts:46) / `validateGradeForm`·:57 등급 필수·`lockedUntil` 오늘 이후) → 400 `mapFieldErrors` → 409 `MEMBER_ALREADY_WITHDRAWN`/404 `USER_NOT_FOUND`는 warning toast + stale(상세 재조회)(AdminMemberEditDialog.vue:56). 등급 유지 기한 date 입력 `min`은 내일(`minLockedUntil`·:50·AdminMemberGradeDialog.vue:27). 탈퇴·임시 비밀번호는 `AdminConfirmDialog`(탈퇴 성공 → 탈퇴회원 목록 이동·409 `MEMBER_ACTIVITY_IN_PROGRESS` warning toast 상세 유지 / 발급 성공 success toast·422·409·502 warning toast). 처리 중 버튼 loading·disabled(`actionBusy`).
6. 에러 문구(`admin-error-message.ts:23-28`) 5코드 추가: `USER_NOT_FOUND`·`MEMBER_ACTIVITY_IN_PROGRESS`·`MEMBER_ALREADY_WITHDRAWN`·`MEMBER_PHONE_MISSING`·`TEMPORARY_PASSWORD_DELIVERY_FAILED`. 상세 404(`USER_NOT_FOUND`)는 에러 화면(`member-not-found`) + "목록으로".
7. 변경 강제(구매자): `password_change_required` 쿠키(`PASSWORD_CHANGE_REQUIRED_COOKIE`·constants/auth.ts:12) — `auth_token`과 동일 옵션(path `/`·lax·secure·maxAge 3600·non-httpOnly), 값은 **boolean `true`**(stores/auth.ts:36·`useCookie<boolean | null>`) — 문자열 `'1'`은 useCookie가 JSON 직렬화해 raw 쿠키가 `%221%22`가 되는 트랩 회피. 로그인 응답 `passwordChangeRequired === true`일 때만 세팅(:72), `logout`·`clearPasswordChangeRequired`(:112)가 제거. 전역 미들웨어 `middleware/password-change.global.ts:7`는 순수 함수 `resolvePasswordChangeRedirect({ path, authenticated, required })`(lib/password-change-guard.ts:17)에 판정 위임 — 허용 경로 `/mypage/password`·`/login`(`ALLOWED_PATHS`·:14), `/admin/**` 제외(별도 admin_token 세션), 그 외는 `/mypage/password?reason=temporary`로 리다이렉트. 로그아웃은 라우트가 아닌 헤더 액션이라 허용 목록 불필요(쿠키 삭제로 해제). 변경 페이지는 쿠키 또는 `reason=temporary`면 안내 "임시 비밀번호로 로그인했습니다. 새 비밀번호로 변경해 주세요."(`temporaryNotice`·password.vue:20).
8. 셀프 비밀번호 변경 성공(D-178 결정 1: 204 계약 유지·현재 토큰 무효): `clearPasswordChangeRequired` → `auth.logout()` → `cart.clear()` → `/login?notice=password-changed`(password.vue:46-49), 로그인 페이지가 `passwordChangedNotice`(login.vue:10)로 "비밀번호가 변경되었습니다. 다시 로그인해 주세요." 표시. 기존 "토큰 유지·입력 초기화·successMessage"는 제거.
9. 셀프 탈퇴: 409 + code `MEMBER_ACTIVITY_IN_PROGRESS`면 "진행 중인 주문 또는 교환·반품이 있어 탈퇴할 수 없습니다." 표시하고 탈퇴·로그아웃·이동을 진행하지 않는다(withdraw.vue:29-34).
10. 프로필 phone: 기존 400 처리는 단일 문구("저장에 실패했습니다. 입력을 확인하세요")라 사유가 드러나지 않아 `PHONE_PATTERN`(constants/account.ts:23·BE `UpdateProfileRequest @Pattern` 미러)으로 제출 전 사전 검증·형식 문구 표시(profile.vue:43).

### §2 확정 구현 규칙
- 관리자: `lib/constants/admin-member.ts`(status·sort·페이지 크기·등급 3값·source 3값·phone 패턴·활동 탭 4값 `ADMIN_MEMBER_ACTIVITY_TABS`·:53) / `types/admin-member.ts`(D-178 응답 1:1·nullable optional·시각은 오프셋 없는 LocalDateTime 문자열) / `lib/admin-member-query.ts`(`parseAdminMemberQuery`·:36 keyword 50자 절단·sort·page·size 정규화 / `toAdminMemberRouteQuery` 기본값 생략 / `toAdminMemberApiParams` / `hasActiveFilters` / `memberRowNumber`) / `lib/admin-member-view.ts`(검증·라벨·`tabClaimType`·:88) / `composables/useAdminMembers.ts`(list·get·update·withdraw·resetPassword·changeGrade·listOrders·listClaims) / `AdminMemberListView.vue`(URL 단일 소스·`v-data-table-server`·빈 상태 필터 여부 분기·에러 다시 시도·늦은 응답 폐기 — orders/index.vue 패턴) / `pages/admin/members/index.vue`·`withdrawn.vue`(플레이스홀더 교체·sellers·admins는 유지) / `pages/admin/members/[id].vue` / `AdminMemberEditDialog`·`AdminMemberGradeDialog`·`AdminMemberActivityTable`. 정적 라우트 `withdrawn`이 `[id]`보다 우선 매칭됨을 e2e로 확인.
- 구매자: `stores/auth.ts`(쿠키·`passwordChangeRequired` computed·`clearPasswordChangeRequired`) / `lib/constants/auth.ts`(쿠키명·경로·query 상수) / `lib/password-change-guard.ts` / `middleware/password-change.global.ts` / `pages/login.vue`·`mypage/password.vue`·`mypage/withdraw.vue`·`mypage/profile.vue` / `lib/constants/account.ts` `PHONE_PATTERN`.
- 테스트: vitest `test/admin/admin-member-query.spec.ts`(7)·`admin-member-helpers.spec.ts`(10·검증·라벨·탭·에러 5코드·back-path 회원/주문 prefix)·`test/unit/password-change-guard.spec.ts`(6·순수 판정 3 + 미들웨어 3) / Playwright `e2e/admin-members.spec.ts`(6: 목록·검색·빈 상태·에러 재시도·탈퇴회원 탈퇴일·상세·탭 파라미터·주문 상세 back·수정·등급·탈퇴 409·임시 비밀번호 204/502·탈퇴 회원 비활성·연락처 없음·404)·`e2e/password-change.spec.ts`(2: 플래그 true → 강제 이동·차단·쿠키 / 변경 204 → 로그인 안내·쿠키 삭제·이동 자유) / 갱신: `admin-shell.spec.ts ⑤` 측정 경로 `/admin/members/sellers`(e2e/admin-shell.spec.ts:161)·`adminAuth-store.spec.ts` 사용자 스토어 쿠키 키 3개.
- 검증(실측): typecheck 0 · vitest 39 files 244 tests(221 → +23) · Playwright 48/48 skip 0(40 → +8·ADMIN_E2E_* 런타임 주입) · 픽셀 stage4 vs track84 12장 diff 0(0.000%) · 로컬 실 API: 관리자 토큰으로 `GET /api/v1/admin/members?size=1` 200(backend 재시작 후·LT-17).
- 트랩(1회차·후보): (1) 신규 컴포넌트가 Nuxt dev 서버에 자동 등록되지 않아 화면이 헤더만 렌더(Playwright 전부 실패) → 컨테이너 재시작. (2) 상세의 등급 표시(`member-grade-code`)와 다이얼로그 select가 같은 `data-testid`를 쓰면 strict mode 위반 → select는 `member-grade-select`. (3) 재시작 직후 전체 Playwright 1차에서 admin-orders·products·product-form ①(각 파일 첫 테스트) 첫 로드 타임아웃 → 단독·전체 재실행 48/48. (4) backend 기동 폴링에 `docker logs --tail N`을 쓰면 TRACE(SQL 바인딩) 로그에 밀려 `Started` 줄을 놓침 → 전체 로그 grep(LT-17 후속 영향에 기록).

### §2 결정 라운드 재진입
- 사전 확인(STEP 353)에서 `admin-shell.spec ⑤`가 `/admin/members` 플레이스홀더 카드 bbox를 측정하는 것을 확인 → 실화면이 된 경로 대신 아직 플레이스홀더인 `/admin/members/sellers`로 측정 경로 변경(검증 목적 유지).
- 실화면 확인 중 `GET /api/v1/admin/members` 로컬 500 → 조사(STEP 359) 결과 코드 결함이 아니라 backend 재시작 누락(bootRun·devtools 없음·STEP 342 재시작 이후 추가된 컨트롤러 미등록) → LT-17 승격·`docker restart` 후 200. 코드 변경 없음.

### §8 이월
- 셀러·관리자 회원 페이지(`/admin/members/sellers`·`/admin/members/admins`) — 셀러 트랙.
- 감사 이력 조회 화면(회원 상세 "변경 이력"·D-178 §8과 연동).
- 로그인 이외 경로의 강제 상태 동기화 — 다른 기기에서 비밀번호 변경을 마쳐도 이 기기의 `password_change_required` 쿠키는 남아 로그아웃(또는 만료 1h)까지 변경 페이지로 보낸다. BE 401(토큰 무효)로 로그인 페이지에 도달하면 해소되나 명시적 동기화는 없음.
- `NoResourceFoundException` 404 매핑(기존 BE 이월·D-178 §8) — 미매핑 경로가 500으로 새는 현상을 관리자 토큰 호출로 재현 확인.
- 외부 검토: C(PR 등급 A·BE는 D-178에서 수행) / 해당 없음.

## FE-32: 관리자 정산 화면(정산 내역·상세·액션 3종·셀러별 정산) (2026-09-18)

정찰 `docs/frontend/recon-report-track85-admin-settlement.md` · BE 계약 Track 85 D-179(2a085b29·"API 계약 변경" 절이 SoT·본 항목에서 재기술하지 않음·재생성 응답은 본 항목 §2에서 정정) · 브랜치 `feat/track-85-fe-settlement`.

### §1-A 갈림길·채택/기각 근거
1. 월 파라미터: α year·month도 기본값이면 URL에서 생략(기존 목록 관례) 【기각】 — 기본값(지난달·`defaultSettlementMonth`·admin-settlement-query.ts:25)이 시간에 따라 바뀌어 공유·북마크 URL이 다음 달에 다른 월을 가리킨다 / **β year·month는 URL에 항상 기록(`toAdminSettlementRouteQuery`·:75), 미지정 진입(메뉴 클릭)은 `router.replace`로 고정(index.vue:61-67) 【채택】**. 그 외 status·keyword·page·size는 기본값이면 생략. 반쪽 기간(year만·month만)·선택지 밖 연도(`settlementYearOptions`·:46·올해부터 과거 3년)·범위 밖 월은 둘 다 기본값(`parseAdminSettlementQuery`·:56). 초기화는 월 유지·상태/검색어/페이지만(`resetQuery`·index.vue:78·월은 필터가 아니라 조회 축·`hasActiveFilters`·:94).
2. 기간 400: BE가 연·월 범위 위반과 미마감 월을 같은 코드 `SETTLEMENT_PERIOD_INVALID`로 내리고 detail만 다르다(D-179 결정 15 검증) → 코드→문구 표 원칙(FE-25) 유지·**안내 문구 1개로 통합**(admin-error-message.ts:32 "연·월 범위를 확인하고, 아직 마감되지 않은 월은 생성할 수 없습니다"). detail 노출 분기 【기각】(서버 문구 의존).
3. 지급완료 버튼: **CONFIRMED에서만 노출**하고 지급 불가면 disabled + 캡션([id].vue:234·:239 `settlement-pay-blocked`), 사유 판정은 BE pay 검사 순서와 동일하게 **음수 → 계좌**(`payBlockedReason`·admin-settlement-view.ts:59·`canPay`·:51). PENDING은 정상처리·재생성(`canConfirm`·:43·`canRegenerate`·:47), PAID는 액션 없이 안내 문구(:235 `settlement-paid-notice`). 상태별 버튼을 항상 렌더하고 disabled만 바꾸는 안 【기각】 — 3상태 직진이라 무의미한 버튼이 최대 2개 남는다.
4. 재생성 결과 이동(`onRegenerated`·[id].vue:114): 성공 → **새 정산 id로 `replace` 이동(back query 유지)** 후 상세 재조회 — 이전 id는 삭제돼 뒤로가기로 돌아오면 404이므로 push 【기각】 / `deletedOnly`(재집계 대상 없음) → back 경로(목록)로 이동 + info 토스트. 사유 다이얼로그 `AdminSettlementRegenerateDialog`(1~200자·`validateRegenerateReason`·admin-settlement-view.ts:69·빈 값 버튼 disabled) — 422 `SETTLEMENT_INVALID_STATE`는 warning + stale(부모 재조회·AdminSettlementRegenerateDialog.vue:48·AdminClaimRejectDialog 패턴).
5. 품목 주문번호: 정산 품목 스냅샷에 orderNo가 없고 `orderPublicId`만 있다(D-179·SettlementItemResponse) → **`orderPublicId`를 링크 텍스트로 쓰고 `/admin/orders/{orderPublicId}?back=<상세 fullPath>`로 이동**(AdminSettlementItemTable.vue:69·`openOrder`·[id].vue:194). 주문 상세 back 허용 prefix에 `/admin/settlements/` 추가(admin-back-path.ts:30·`EXTRA_BACK_PREFIXES`), 정산 상세 base는 정산 내역 + 셀러별 정산(쿼리 포함) 허용(:21·`EXTRA_BACK_BASES`). 기존 상품/주문/클레임/회원 동작 불변.
6. 셀러별 정산의 셀러 선택: α 셀러 검색 API 신설 요청 【기각】(BE 무변경 트랙) / **β 상품 등록 폼과 같은 `GET /admin/sellers`(페이징 없음·`useAdminSettlements.sellers`·useAdminSettlements.ts:47)를 `v-autocomplete`로 상호 검색 【채택】** → 선택은 URL `?seller=slr_…`(sellers.vue:59 `applyQuery`)·이력은 `listBySeller(sellerPublicId, page, size)`(:42·`GET /admin/sellers/{slr_}/settlements`). 미선택 안내·`SELLER_NOT_FOUND` 404 안내·빈 상태 분기(sellers.vue:78 `load`).
7. 계좌 표시([id].vue 정산계좌 카드): BE `bankAccount.snapshot`으로 **"지급 시점 계좌(스냅샷)" vs "현재 주 정산계좌"** 라벨 chip 구분(`bankAccountSourceLabel`·admin-settlement-view.ts:85·D-179 결정 7 STL-3 의미 변경 반영), 계좌 없음은 안내 문구(`settlement-bank-missing`). 계좌번호는 BE가 끝 4자리만 내리므로 `formatBankAccount`(:79 "004 ···1234 (홍길동)").
8. 수수료율: BE basis-point 정수(1000 = 10.00%)를 **퍼센트로 표시**(`formatCommissionRate`·:28·`COMMISSION_RATE_BASIS_POINT_DIVISOR`·admin-settlement.ts:48·불필요한 소수 제거 "10%"·"12.5%"·"12.34%"). 환불 품목 수수료는 BE가 0으로 내리므로 그대로 "0원".
9. 규칙의 위치: 상태 라벨·기간/날짜 포맷·음수 판정·액션 활성·지급 차단 사유·사유 검증·계좌 표시를 **`lib/admin-settlement-view.ts` 순수 함수**로 모아 vitest로 고정하고 컴포넌트는 표시·배선만(admin-member-view 패턴). 상수는 `lib/constants/admin-settlement.ts`(status 3·itemType 2·라벨·semantic·페이지 크기·keyword 50·reason 200·연도 span 3) 단일 소스(4층위 enum 잠금 (4)).

- 목록 표(`AdminSettlementTable`·AdminSettlementTable.vue:14): 월별 목록(mode monthly·첫 컬럼 셀러 상호+기간 캡션·판매건수·계좌 등록/미등록 chip)과 셀러별 이력(mode seller·첫 컬럼 기간)이 같은 BE 행(AdminSettlementSummaryResponse)을 쓰므로 컬럼만 mode로 바꾼 읽기 전용 표. 지급액 음수는 `text-error` 강조(`row-net`). 합계 카드 `AdminSettlementTotals`(StatCard 4·지급액 캡션에 대기/확정/지급 건수·BE totals는 필터 무관·월 전체).
- 정산 생성(index.vue:103 `runCreate`): `AdminConfirmDialog`("{월} 정산을 생성합니다…") → `POST /admin/settlements {year, month}` → createdCount로 success/info 토스트 → 목록 재조회. 400 `SETTLEMENT_PERIOD_INVALID`·409 `SETTLEMENT_ALREADY_EXISTS`는 warning(409는 재조회).
- 전이([id].vue:86 `runTransition`): 정상처리 다이얼로그에 "셀러에게 공개되고 SMS가 발송" 명시·지급완료 다이얼로그에 계좌 표시. 422 3코드(INVALID_STATE·NET_NEGATIVE·BANK_ACCOUNT_MISSING)는 warning + 상세 재조회(조회~처리 사이 경합).
- 품목 탭: `?tab=SALE|REFUND`·`?page=`·`?size=`(`applyItemQuery`·[id].vue:149·back 보존)·탭 라벨에 건수(saleItemCount·refundItemCount)·늦은 응답 폐기.
- 에러 문구(admin-error-message.ts:30-35) 6코드: `SETTLEMENT_NOT_FOUND`·`SETTLEMENT_PERIOD_INVALID`·`SETTLEMENT_ALREADY_EXISTS`·`SETTLEMENT_INVALID_STATE`·`SETTLEMENT_NET_NEGATIVE`·`SETTLEMENT_BANK_ACCOUNT_MISSING`. 상세 404는 에러 화면(`settlement-not-found`·"재생성으로 삭제된 정산" 안내) + "목록으로".

### §2 확정 구현 규칙
- `lib/constants/admin-settlement.ts` / `types/admin-settlement.ts`(D-179 응답 1:1·nullable optional·periodStart/paidAt/occurredAt은 KST 오프셋 ISO → `formatDateTime`·scheduledPayDate는 LocalDate → `formatDateOnly`) / `lib/admin-settlement-query.ts` / `lib/admin-settlement-view.ts` / `composables/useAdminSettlements.ts`(list·get·listItems·listBySeller·sellers·create·confirm·pay·regenerate) / `AdminSettlementTable`·`AdminSettlementTotals`·`AdminSettlementItemTable`·`AdminSettlementRegenerateDialog` / `pages/admin/settlements/index.vue`·`[id].vue`·`sellers.vue`(플레이스홀더 2 교체·상세 신설). BE·`admin-menu.ts` 무변경.
- 테스트: vitest `test/admin/admin-settlement-query.spec.ts`(9·지난달 기본·연도 선택지·파싱·정규화·반쪽 기간·route·api·필터 판정)·`admin-settlement-helpers.spec.ts`(11·기간/날짜/수수료율 포맷·상태 라벨·계좌 표시·액션 활성·지급 차단 사유 우선순위·사유 검증·에러 6코드·back-path 정산/주문 prefix) / Playwright `e2e/admin-settlements.spec.ts`(6: ① 목록·지난달 URL 고정·합계·음수 강조·월/상태/검색/초기화 ② 에러 재시도·생성 409·성공 body·재조회 ③ 상세·REFUND 탭 파라미터·수수료율 %·주문 링크 back·목록 복귀 ④ confirm·pay 스냅샷 계좌·regenerate 사유 필수·새 id 이동 ⑤ 음수/계좌 미등록 비활성·deletedOnly 목록 이동·404 ⑥ 셀러별 선택·URL·이력·상세 back·미존재 404). `admin-shell.spec` 플레이스홀더 단언은 `/admin`·`/admin/members/sellers`라 갱신 불필요.
- 검증(실측): typecheck 0 · vitest 41 files 264 tests(244 → +20) · Playwright 54/54 skip 0(48 → +6·ADMIN_E2E_* 런타임 주입) · 픽셀 track84 vs track85 12장 diff 0(0.000%) · 로컬 실 API: 관리자 토큰으로 `GET /api/v1/admin/settlements?year=2026&month=6/7/8` 200(Track 85 BE 기동 확인·LT-17).
- 트랩(후보): (1) 신규 페이지 `[id].vue`가 dev 라우트 테이블에 미등록 → "Page not found: /admin/settlements/9103" → `docker restart zslab_mall_frontend`(LT-17의 FE판·FE-31 트랩 (1)과 동일 유형 2회차). (2) 재시작 직후 전체 Playwright 1차에서 admin-orders·products·product-form ①(각 파일 첫 테스트) 11.0s 타임아웃(Vite 최초 컴파일) → 전체 재실행 54/54(FE-31 트랩 (3)에 이어 2회차 → 누적 ≥2·LT 승격 후보).

### §2 결정 라운드 재진입
- 사전 확인(STEP 382)에서 D-179 "API 계약 변경" 절의 재생성 응답 `SettlementRegenerateResponse{deletedOnly, settlement}`가 실제 구현(flat 7필드 `deletedSettlementId·deletedOnly·settlementId·grossAmount·feeAmount·refundAmount·netAmount`·backend SettlementRegenerateResponse.java)과 다름을 확인 → **코드 기준으로 타입 작성**(types/admin-settlement.ts `AdminSettlementRegenerateResponse`)·BE 무수정·D-179 해당 줄 정정(2026-09-18)으로 종결.
- 로컬에 시연 데이터 없음(구매확정 order_item 0·settlement 0·주계좌 0) → 실화면 확인은 목록 200(빈 응답)까지, 행·상세·액션 검증은 mock e2e로 한정.

### §8 이월
- 시연용 데이터 준비(구매확정 주문·과거 월 정산·셀러 주계좌) 후 실화면 확인(행·상세·confirm/pay/regenerate 실 API).
- 상품 등록 화면 공급가 hint 문구(`AdminProductBasicSection.vue:78` "정산은 셀러 수수료율 기준으로 계산되며…")가 3단 판정(셀러 → 카테고리 → 기본율)과 어긋남 — 문구 갱신(admin-product-form.spec 문구 단언 확인).
- 셀러 정산 화면(셀러 트랙·BE 셀러 API 3종은 D-179에서 완료), 셀러·카테고리 수수료율 편집 화면(BE 편집 API와 함께).
- 정산 목록 CSV 내려받기(필요 시).
- 외부 검토: C(BE 계약 무변경) / 해당 없음.

## FE-33: 관리자 대시보드 화면 (2026-09-18)

BE 계약 Track 86 D-180(`GET /api/v1/admin/dashboard` 단일 응답·"API 계약" 절이 SoT·본 항목에서 재기술하지 않음) · 브랜치 `feat/admin-dashboard`(BE·FE 동일 브랜치·미커밋) · 정찰 `docs/track-86/recon-report.md` §5(FE) · 외부 검토 C / 생략.

### §1-A 갈림길·채택/기각 근거
1. 차트 라이브러리: **α apexcharts(vue3-apexcharts 1.11.1 + apexcharts 7.4.0) 【채택】** / β Vuetify `VSparkline` 【기각: 축·툴팁·범례 없음】 / γ chart.js(vue-chartjs) 【기각: SSR 래핑·툴팁 포맷 수작업】 / δ SVG 자작 【기각: 유지비】. 관리자 레이어 한정 로딩 — `AdminChart.vue`(AdminChart.vue:18)가 `defineAsyncComponent(() => import('vue3-apexcharts'))`로 클라이언트에서만 동적 import하고 `<ClientOnly>`로 감싼다(D-12 α 원칙·사용자 홈 HTML에 apexcharts 참조 0 실측). 페이지는 `AdminChart`만 쓰고 vue3-apexcharts를 직접 import하지 않는다.
2. 증감률 FE 계산: `changeRate(current, previous)`(admin-dashboard-view.ts:19) = (현재 − 비교) / 비교 × 100. **비교값 0(또는 음수·비정상)은 null → "—"**(`CHANGE_RATE_UNAVAILABLE`)로 Infinity·NaN을 만들지 않는다. 표기 `+17.4%`·`-18.8%`·`0.0%`(소수 1자리). 톤은 up 녹색(`adm-chip--success`)·down 빨강(`danger`)·0/비교 불가 회색(신규 `adm-chip--neutral`·admin-vuetify.css `--adm-semantic-neutral-*` 토큰).
3. 요약 카드 배지 위치: α 카드 위에 절대 배치(AdminStatCard 무수정) 【기각: 캡션 2줄 시 겹침 실측】 / **β `AdminStatCard`에 기본 슬롯 추가(캡션 아래 한 줄·미사용 시 렌더 없음·AdminStatCard.vue:21) 【채택】**. 매출 카드 캡션은 "환불 N원 · 순매출 N원", 캡션 없는 카드는 NBSP(AdminSettlementTotals 선례)·카드 `h-100`으로 행 높이 정렬.
4. 처리 대기 링크 연결 범위(`PENDING_TILES`·admin-dashboard-view.ts:60): 정산 대기 → `/admin/settlements?status=PENDING` · 클레임 요청 → `/admin/orders/claims?status=REQUESTED` · 배송 대기 → `/admin/orders?status=PAID`(BE는 품목 PAID 건수·주문 목록은 주문 단위라 **근사**) · **재고 임박 → 링크 없음**(재고 화면 플레이스홀더·상품 목록에 재고 필터 없음·카운트만). 톤은 0건 회색·1건 이상 정산/클레임/배송 노랑·재고 임박 빨강(`pendingChipClass`).
5. 하단 리스트 행 이동: 최근 주문 → `/admin/orders/{orderPublicId}?back=/admin` · 상위 상품 → `/admin/products/{productPublicId}?back=/admin`(둘 다 `resolveBackPath`에 `ADMIN_DASHBOARD_PATH` 허용 추가·admin-back-path.ts:5·:20-21) · **최근 클레임 → 클레임 상세 화면이 없어 `/admin/orders/claims?keyword={orderNo}`(주문번호 정확일치 검색·BE AdminClaimSpecifications.keyword)** · **상위 셀러 → 셀러 상세가 없어 `/admin/settlements/sellers?seller={sellerPublicId}`(셀러별 정산 이력)**. "전체 보기"는 각 목록 기본 경로.
6. 로딩·에러·빈 상태: `useFetch` 대신 기존 목록 페이지와 같은 `load()` + requestSequence 경합 가드(index.vue:33)·`toAdminErrorMessage` + 다시 시도. 빈 상태는 리스트 "데이터 없음"·차트는 BE가 6/30개 0 채움을 보장하므로 축만 그려지고 카드 우상단에 "데이터 없음" 캡션(`isAllZero`).
7. 트랩 — vue3-apexcharts 1.11 `updateOptions` 경로가 options를 JSON 깊은 복사(`copyData`)해 **formatter 함수가 사라진다**(최초 `init`은 `extend`로 함수 보존). 최초 렌더(빈 데이터) 후 응답 도착 시 y축이 원 포맷을 잃는 현상 실측 → `AdminChart`가 options 변경 시 `remountKey`를 올려 항상 init 경로로 다시 그린다(AdminChart.vue:26·애니메이션 옵션 off라 비용 없음).
8. 트랩 — `<component :is="'NuxtLink'">` 문자열은 전역 등록이 아니라 `<nuxtlink>` 원소로 렌더돼 href 없음 → `resolveComponent('NuxtLink')`로 실제 컴포넌트를 넘긴다(AdminDashboardPending.vue:22).

### §2 확정 구현 규칙
- `types/admin-dashboard.ts`(D-180 응답 1:1·nullable optional) / `composables/useAdminDashboard.ts`(`useAdminApi` 경유 GET 1개) / `lib/admin-dashboard-view.ts`(증감률·톤·PENDING_TILES·차트 옵션 빌더 `monthlyRevenueChart`(매출·환불 2계열 막대·y축/툴팁 `formatWon`)·`dailyOrdersChart`(area·"N건")·`AdminChartSeries` 레이어 타입).
- 컴포넌트: `AdminDashboardSummaryCards`(6장·`dashboard-card-{today|thisMonth}-{revenue|orders|members}`) · `AdminDashboardPending`(4칸) · `AdminDashboardListCard`(generic T·헤더 전체 보기·행 슬롯·행별 to) · `AdminChart`. 페이지 `pages/admin/index.vue`는 조립·상태만(AdminPlaceholder 제거).
- 반응형: 카드 `cols=12 sm=6 lg=4`, 처리 대기 `cols=6 md=3`(칩 flex-wrap), 차트 `lg=7/5`(좁은 폭 세로 스택), 리스트 `md=6`.
- 테스트: vitest `test/admin/admin-dashboard-helpers.spec.ts`(12·증감률/0 나눗셈/표기/톤·처리 대기 링크/톤·차트 라벨/포맷/빈 판정·formatWon·대시보드 back 허용) · Playwright `e2e/admin-dashboard.spec.ts`(1·데모 로그인 → /admin → 카드 6·배지 형식·처리 대기 href 3+없음 1·apexcharts SVG 2·리스트 4·실 BE) · `admin-shell.spec ①` 플레이스홀더 단언 → `admin-dashboard` 가시성으로 교체.
- 검증(실측): typecheck 0 · vitest 42 files 276(264 → +12) · Playwright 55/55 skip 0(54 → +1·ADMIN_E2E_* 주입·1회차 9건 실패=컨테이너 재시작 직후 Vite 최초 컴파일 + `#app-manifest` pre-transform 에러(typecheck의 `nuxt prepare`가 dev `.nuxt` 재생성) → 재시작 후 2회차 전부 GREEN) · 실화면 desktop/mobile 스크린샷 확인(데모 시드 데이터: 이번 달 1,501,500원·pending 3/2/8/1·6개월 막대·30일 area).
- 픽셀 기준선: track85 vs track86 12장 중 8장 diff 0, home/products 4장 SIZE DIFF → FE 변경을 stash한 상태로 재캡처(track86-nofe) vs track86 **12장 diff 0** = 차이는 데모 시드(STEP 392 상품 33)에 의한 데이터 변화이며 코드 영향 0. **대시보드는 관리자 화면이라 기준선(사용자 6페이지) 추가 대상 아님**·다음 트랙부터 사용자 기준선은 track86(데모 시드 반영본)과 비교.
- 신규 의존성: `apexcharts ^7.4.0`·`vue3-apexcharts ^1.11.1`(package.json·pnpm-lock +30·컨테이너 pnpm add).

### §8 이월
- 재고 임박 칸 링크(재고 화면 구현 또는 상품 목록 재고 필터 후) · 클레임 상세 화면(현재 목록 검색으로 대체) · 셀러 상세 화면(현재 셀러별 정산으로 대체).
- 배송 대기 링크의 품목/주문 단위 불일치(주문 목록에 품목 상태 필터 도입 시 정합).
- 통계 4페이지(매출·주문·회원·상품)는 여전히 플레이스홀더 — 대시보드 차트 빌더 재사용 가능.
- vue3-apexcharts formatter 소실 트랩은 상위 버전 수정 시 `remountKey` 제거 검토.

## FE-34: 매출 통계 화면 (2026-09-18)

BE 계약 Track 87 D-181(`GET /api/v1/admin/stats/sales`·`/breakdown`·`/breakdown.csv`·"API 계약" 절이 SoT·본 항목에서 재기술하지 않음) · 브랜치 `feat/sales-stats`(BE·FE 동일 브랜치·미커밋) · 정찰 `docs/track-87/recon-report.md` §6(FE) · 외부 검토 C / 생략.

### §1-A 갈림길·채택/기각 근거
1. 비교 계열 표시: **α 순매출 1계열만 점선 중첩 【채택】** / β 매출·환불·순매출 3계열 전부 점선 【기각: 6계열 과밀·색 구분 불가】 / γ 비교를 별도 차트로 분리 【기각: 같은 축에서 겹쳐 봐야 하는 비교 목적 훼손】. 현재 3계열(매출 #2563EB·환불 #F97316·순매출 #22C55E) + 비교 순매출 회색(#94A3B8) `stroke.dashArray [0,0,0,5]`·매출·환불 비교값은 툴팁 x 라벨에 병기(`salesTrendChart`·admin-sales-stats-view.ts). AdminChart 래퍼는 수정 없음(옵션 passthrough·remountKey) — `AdminChartSeries.data`만 `(number | null)[]`로 넓혀 선 끊김을 허용(1줄·기존 호출부 영향 0).
2. compareTrend 후행 0 → null: BE는 compareTrend를 trend 길이에 맞춰 뒤를 0으로 채우거나 절단한다(D-181 §1-A 4). 비교 구간이 실제로 더 적을 때 끝이 0으로 급락해 보이므로 **후행** 0 구간(revenue·refund·orderCount 모두 0)만 null로 바꿔 선을 끊는다(`compareNetSeries`). 중간 0은 실제 0이라 유지·환불만 있는 구간(순매출 음수)도 데이터로 유지. apexcharts line은 null에서 선을 끊는다(연결 금지 실측).
3. 환불 카드 색 반전: 증감 톤은 FE-33 `changeTone`을 쓰되 환불만 `inverse`로 up↔down을 바꿔 "환불 증가 = 빨강·감소 = 녹색"(`salesChangeTone`). flat(0·비교 불가)은 회색 그대로. 나머지 5장(매출·순매출·주문수·객단가·주문당 품목수)은 증가 녹색.
4. 비교 없음 표기: BE가 비교 기간 0건이면 compareSummary·compareTrend 키를 생략(전역 NON_NULL) → 타입은 optional·`normalizeSalesStats`가 `?? null`로 고정. compare≠NONE인데 null이면 안내 alert("비교 기간에 결제·환불 데이터가 없어 증감률을 표시하지 않습니다")·배지 "—"·회색. compareRevenue(행)도 같은 규약(생략 → null → "—").
5. CSV: **BE `/breakdown.csv` blob 다운로드** — Bearer 헤더가 필요해 `<a href>` 직링크 불가. `useAdminApi().raw(…, { responseType: 'blob' })` → Content-Disposition `filename*=UTF-8''` 우선·`filename` 폴백·둘 다 없으면 `sales-breakdown.csv`(`csvFileNameFrom`) → `URL.createObjectURL` → 임시 a 클릭 → **지연 revoke(10초·클릭 직후 동기 revoke는 브라우저가 다운로드를 시작하기 전에 URL을 무효화할 수 있음)**. 다운로드 중 버튼 disabled+spinner·성공/실패 토스트. 파라미터는 breakdown과 동일(현재 축·드릴다운·비교 반영).
6. 카테고리 귀속 안내: 카테고리 탭에서만 "상품의 현재 카테고리 기준으로 집계됩니다. 상품의 카테고리를 변경하면 과거 주문의 귀속도 함께 바뀝니다."(`CATEGORY_AXIS_NOTICE`·D-181 §1-A 5 α 채택 사유).
7. URL 단일 소스(`preset|from|to|unit|compare|axis|parent`·admin-order-query 패턴): 프리셋(7d·30d·3m·ytd)은 **오늘 기준으로 매번 계산**하므로 URL에는 preset만 두고 custom일 때만 from·to를 싣는다(어제 공유한 "최근 7일" 링크가 오늘 기준으로 열림). 기본값 생략·허용 외 값 정규화·custom인데 날짜 누락이면 기본 프리셋(30d). 요약·추이(statsKey)와 분해(breakdownKey)를 별도 watch로 두어 축·드릴다운 변경 시 breakdown만 재조회.
8. 기간 검증: from>to·custom 미입력이면 요청을 보내지 않고 피커 인라인 메시지(BE 400 의존 금지). 트랩 — 피커가 반대쪽 날짜를 props에서 읽어 함께 emit하면 router.replace 반영 전 연속 입력 시 stale 값이 새 값을 덮는다(E2E 실측) → 피커는 바뀐 키만 emit·프리셋→custom 전환 시 반대쪽 날짜 보충은 페이지 `applyQuery`가 현재 표시 기간으로 한다.
9. 분해 테이블 정렬: 기존 관리자 표는 전부 `v-data-table-server`+`sortable:false`(서버 정렬)라 **첫 클라이언트 정렬 선례**. Vuetify `v-data-table` 내장 정렬 대신 `v-table` + 헤더 클릭 + 순수 함수 `sortBreakdownRows`(null=비교 불가는 방향 무관 맨 뒤·동률 매출 DESC·이름 가나다) — null-last 규칙을 vitest로 고정하기 위함. 기본 매출 DESC(BE 순서와 동일). 비중은 셀 안 `v-progress-linear`.
10. 드릴다운: drillable 행 클릭 → `parent`(key) URL 반영 → 상품 목록·브레드크럼("전체 › {축} · {이름} — 상품별") + "전체로". 상위 이름은 클릭 시 메모리에만 두어 새로고침 후엔 key로 표기(BE 응답에 parent 이름 없음·추가 조회는 YAGNI). key null(soft-delete) 행은 drillable false.
11. x축 라벨: yyyy-MM-dd(일·주 시작일)는 연도를 떼 MM-DD(`axisLabel`) — 30일·7일 조회에서 라벨 겹침 실측. 툴팁은 전체 bucketLabel. 월은 yyyy-MM 그대로.
12. 트랩 — Playwright Chromium은 blob: URL 다운로드의 `suggestedFilename()`을 "download"로 보고한다(앱의 `a.download`는 한글 파일명·스크래치 E2E로 실측). E2E는 다운로드 이벤트 발생 + 토스트의 파일명(Content-Disposition 추출값)으로 단언한다.

### §2 확정 구현 규칙
- `lib/constants/admin-sales-stats.ts`(유니온 3·프리셋·라벨·삭제 대체 표기·카테고리 안내) / `types/admin-sales-stats.ts`(D-181 1:1·비교 필드 optional) / `lib/admin-sales-stats-query.ts`(URL·프리셋·API 파라미터·역전 판정) / `lib/admin-sales-stats-view.ts`(정규화·카드 6·톤 반전·차트·후행 0·분해 행·정렬·CSV 파일명·축 라벨) / `composables/useAdminSalesStats.ts`(sales·breakdown·breakdownCsv).
- 컴포넌트: `AdminPeriodPicker`(재사용 가능·프리셋 토글+date 2+단위+비교·testid `period-*`) · `AdminSalesSummaryCards`(6장·`sales-card-{key}`·배지 `sales-card-rate`) · `AdminSalesBreakdownTable`(`breakdown-sort-{key}`·`breakdown-row(-drillable)`·`breakdown-empty`) · 페이지 `pages/admin/stats/sales.vue`(조립·상태·URL·CSV·AdminPlaceholder 제거).
- 삭제 행 표기: name null → "(삭제된 카테고리/셀러/상품)"(parentKey 있으면 상품)·이탤릭·회색.
- 반응형: 카드 `cols=12 sm=6 lg=4`, 피커 프리셋 `lg=5`·날짜 `6/3/2`·단위 `lg=1`·비교 `lg=2`, 표는 v-table 래퍼 가로 스크롤·숫자 셀 nowrap.
- 테스트: vitest `test/admin/admin-sales-stats-helpers.spec.ts`(16·프리셋/월말·URL parse/toRoute·PRODUCT parentKey 제거·톤 반전·카드·normalize·후행 0→null·차트 계열/dashArray/축 라벨·분해 행·정렬 null-last·CSV 파일명) · Playwright `e2e/admin-sales-stats.spec.ts`(1·데모 로그인 → 진입(카드 6·"—"·차트 SVG·카테고리 안내) → 7일+주+직전(URL·비교 열 2) → 셀러 축 → 드릴다운(parentKey·브레드크럼) → 전체로 → 시작일 직접 입력(custom) → 역전(요청 0·CSV disabled) → 해소 → CSV 다운로드 이벤트+토스트 파일명·실 BE).
- 검증(실측): typecheck 0 · vitest 43 files 292(276 → +16) · Playwright 56/56 skip 0(55 → +1·1회차 3건 실패=컨테이너 재시작 직후 Vite 최초 컴파일(FE-33 트랩 동일)·2회차 settlements ⑥ 30s 타임아웃 1건(무관 파일·단독 재실행 6/6)·3회차 56/56) · 라이브 응답 키 집합(sales top 4·summary 7·trend 6·breakdown top 3·row 8)이 D-181 계약과 정확히 일치·누출 필드 0 · 데모 8/21~9/9 결제 0 구간은 0 기준선 평탄선으로 표시(직전 비교 점선이 그 위를 지남·급락 없음) · 실화면 desktop/mobile 스크린샷 확인 · 사용자 홈 HTML apexcharts 참조 0.
- 픽셀: track86 vs track87 12장 diff 0(관리자 화면이라 사용자 기준선 추가 대상 아님).
- 신규 의존성: 없음.

### §8 이월
- 드릴다운 상위 이름의 새로고침 유지(BE breakdown 응답에 parentName 추가 시).
- 통계 주문·회원·상품 3페이지는 여전히 플레이스홀더 — `AdminPeriodPicker`·`AdminSalesBreakdownTable` 재사용 가능.
- 비교 계열 확장(매출·환불 점선 토글)은 요구 시.

## FE-35: 주문·클레임 / 회원 통계 화면 (2026-09-18)

BE 계약 Track 88 D-182(`GET /api/v1/admin/stats/orders`·`/members`·"API 계약" 절이 SoT·본 항목에서 재기술하지 않음) · 브랜치 `feat/order-member-stats`(BE·FE 동일 브랜치·미커밋) · 정찰 `docs/track-88/recon-report.md` §6(FE) · 외부 검토 C / 생략.

### §1-A 갈림길·채택/기각 근거
1. 퍼널 시각화: **α 가로 막대 4단(폭 = 결제 대비 도달률) + 단계별 건수·도달률·직전 대비 이탈률 병기 【채택】** / β apexcharts 가로 bar 【기각: 이탈률·건수 병기가 툴팁에 갇히고 4행이라 차트 이점 없음】 / γ 단계형(사다리꼴) 【기각: 신규 SVG·의존성 없이 구현 비용만】. 비율은 FE 순수 함수 `funnelStages`(BE는 건수만·D-182). 취소·반품은 단계가 아니라 이탈 사유라 카드 우상단 칩으로 분리. 코호트 안내 1줄 고정(`funnel-notice`).
2. 비교 계열 표시 범위: **α 클레임률·환불률 2계열 + 비교는 환불률만 점선 【채택】**(FE-34 α와 동일·과밀 방지) / β 비교 2계열 【기각】. `compareRefundRateSeries`가 후행 0 → null(FE-34 `compareNetSeries` 규칙 그대로·중간 0 유지). 툴팁 x 라벨에 건수·환불액 병기.
3. 가입 추이 이중 축: **α 혼합 차트(신규 가입 column + 활성 누적 line·y축 2개 opposite) 【채택】** / β 2계열 line 【기각: 혼합 가능 확인】. 혼합은 AdminChart 래퍼 수정 없이 `AdminChartSeries`에 `type?: 'line' | 'column' | 'area'` 1필드 추가(정찰 §6-1 예고·기존 호출부 영향 0)로 성립 — apexcharts는 chart.type 'line' + 계열별 type으로 혼합을 그린다. `yaxis[]`의 `seriesName`을 계열명과 맞춰 축을 대응시킨다.
4. 사유 미매핑 원문 표기: `claimReasonLabel(code)` = `CLAIM_REASON_LABELS[code] ?? code`(단일 소스 claim.ts·BE reason_code 무제약 VARCHAR·D-182 §1-A 9). 유형도 같은 방식.
5. 탈퇴 카드 색 반전: `memberSummaryCards`가 탈퇴만 `inverse`(FE-34 `salesChangeTone` 재사용) — 증가 빨강·감소 녹색. 클레임 요약 4장(건수·클레임률·환불액·환불률)은 전부 inverse.
6. AdminChart·AdminPeriodPicker 확장 범위: AdminChart `type`에 `'donut'`·`series`에 `number[]` 유니온 2줄(도넛 라벨은 options.labels) · AdminPeriodPicker `unit?`·`compare?` optional + 미전달 시 v-select `v-if` 숨김(탭 2·3은 둘 다 노출하므로 현재 숨김 사용처 없음·확장만). **회귀 확인**: 확장 직후 typecheck 0·vitest 292(변경 전 기준선 그대로)·E2E admin-dashboard·admin-sales-stats 2/2 GREEN → 다음 단계 진행. 최종 전체 E2E 58/58·대시보드·매출 통계 스크린샷 육안 확인(탭 행 추가 외 동일).
7. 통계 3탭 이동: 좌측 메뉴에 통계 4항목이 이미 있으나 화면 안에서 오가는 UI가 없어 `AdminStatsTabs`(v-tabs `:to`·매출/주문·클레임/회원·미구현 "상품" 제외)를 3페이지 헤더 아래에 둔다. sales.vue는 이 1줄(+빈 줄)만 수정.
8. 클레임 요약 카드 4장 추가(요청 목록 외): D-182가 비교 기간을 "추이·요약 카드"에 적용하고 compareClaimSummary를 내리므로 소비처를 두었다(비교 선택 시 증감 배지). 없으면 compare 선택이 추이 점선 하나에만 영향을 줘 어색하다.
9. URL 동기화: 탭 2·3 공통 `admin-stats-period-query.ts`(preset|from|to|unit|compare·축 없음)로 분리하고 기간 계산·역전 판정은 매출 헬퍼 재사용(`resolvePeriod` 위임). 페이지 흐름(pendingQuery·sequence·requestKey watch)은 sales.vue와 동일.
10. 트랩: E2E 퍼널 단계 locator를 `[data-testid^="funnel-stage-"]`로 잡으면 하위 `funnel-stage-count/reach/drop`까지 15개가 잡힌다 → `[data-testid$="Items"]` 접미 조건으로 4단계만 선택. 픽셀 1회차 login-desktop 5px diff는 재캡처 0(글리프 레이스·FE-22b 트랩).

### §2 확정 구현 규칙
- `types/admin-order-stats.ts`·`types/admin-member-stats.ts`(D-182 1:1·비교·leadTime 구간·topBuyers 식별 필드 optional) / `lib/admin-stats-period-query.ts` / `lib/admin-order-stats-view.ts`(정규화·funnelStages·formatHours 24h 경계 "N일 M시간"·leadTimeCards "데이터 없음"·claimSummaryCards·claimTrendChart·분포 라벨·donutChart 합 0 empty) / `lib/admin-member-stats-view.ts`(정규화·카드 6·signupTrendChart 혼합 이중 축·gradeRows·buyerSplitView·topBuyerRows null → "—") / `composables/useAdminOrderStats.ts`·`useAdminMemberStats.ts`.
- 컴포넌트: `AdminStatsTabs` · `AdminStatsSummaryCards`(공용·icons/colors 맵·testid 접두) · `AdminOrderFunnel`(`funnel-stage-{key}`·`funnel-cancelled/returned`·`funnel-empty`) · `AdminLeadTimeCards`(`lead-time-card-{key}`·`lead-time-median`) · `AdminDonutCard`(도넛 + 기본 표·슬롯으로 표 대체·`chartCols`·`{testid}-empty/-row/-notice`) · `AdminBuyerSplitCard`(가로 누적 막대 + 2분할) · `AdminTopBuyersTable`(행 클릭 → `/admin/members/{publicId}?back=`·`top-buyers-row(-navigable)`) · 페이지 `stats/orders.vue`·`stats/members.vue`(플레이스홀더 제거).
- 빈 상태: 차트 축만 + "데이터 없음" 캡션·표 "데이터 없음"·도넛 합 0이면 빈 문구·소요시간 표본 0 "데이터 없음"·퍼널 결제 0 "데이터 없음". 등급 분포는 3등급 고정 행(한 종류뿐이어도 도넛 1조각·표 3행).
- 테스트: vitest `test/admin/admin-order-member-stats-helpers.spec.ts`(17·URL 매핑·퍼널 도달/이탈·formatHours 경계·소요시간 카드·클레임 카드 톤·후행 0→null·추이 계열·정규화·사유 미매핑 원문·도넛 empty·탈퇴 반전·분리·상위 회원·혼합 차트 이중 축) · Playwright `e2e/admin-order-member-stats.spec.ts`(2·① 주문: 퍼널 4단·소요 3·요약 4·추이 SVG·분포 2·단위/비교 URL·역전 요청 0·탭 → 회원 / ② 회원: 카드 6·차트·등급 3행·분리·상위 표·탭 → 매출).
- 검증(실측): typecheck 0 · vitest 44 files 309(292 → +17) · Playwright 58/58 skip 0(56 → +2·1회차 3건 실패 = 컨테이너 재시작 직후 첫 로드 9s 타임아웃·FE-33/34 트랩 동일·2회차 58/58) · 라이브 응답 키 집합(orders 최상위 6·funnel 6·leadTime 3·metric 3·claimSummary 6·trend 7·type/reason 3 · members 최상위 7·summary 6·signup 4·grade 5·split 4·top 5)이 D-182 계약과 정확히 일치·누출 0 · 실화면 스크린샷(주문·회원·매출·대시보드) 확인.
- 픽셀: track87 vs track88 12장 diff 0(login-desktop 1회차 5px → 재캡처 0).
- 신규 의존성: 없음.
- 데이터 빈약 지점(데모): 등급 분포 전원 SILVER(도넛 1조각·골드/플래티넘 0행) · 재구매율 100%·1회 구매자 0(분리 막대 단색) · 가입 추이 2월 13명 후 산발 1명 · 결제 실패/만료 없음(범위 밖). 등급 재산정은 머지 후 별도 STEP.

### §8 이월
- compareSignupTrend는 응답에 있으나 차트에 그리지 않음(혼합 차트에 점선 추가 시 과밀·요구 시).
- 퍼널 "진행 중"(미도달·미종결) 건수 표기·소요시간 분포(히스토그램)·클레임 셀러/상품 축.
- AdminPeriodPicker unit/compare 숨김 사용처 없음(확장만) — 탭별 축소 요구 시 사용.

## FE-36: 관리자 메뉴 정리·기능 흡수 화면 (2026-09-18)

BE 계약 Track 89-A D-183(`stockFilter`·`refundStatus`·`INITIATE_REFUND`·mark-cancelled body·`payments[].pgTid/failureCode`가 SoT·본 항목에서 재기술하지 않음) · 브랜치 `feat/admin-menu-cleanup`(BE·FE 동일 브랜치·미커밋) · 정찰 `docs/track-89/recon-report.md` §4·§5 · 외부 검토 C / 생략.

### §1-A 갈림길·채택/기각 근거
1. 메뉴 제거 범위: `ADMIN_MENU`(`lib/constants/admin-menu.ts`)에서 결제 내역·환불·재고·통계 상품 4항목 삭제 + 페이지 4파일 삭제(`orders/payments.vue`·`orders/refunds.vue`·`products/inventory.vue`·`stats/products.vue`). 상위 그룹은 비지 않는다(주문 3·상품 3·통계 3). 정찰이 "vitest 영향 0"이라 했으나 `admin-product-helpers.spec.ts`의 `resolveActiveMenuPath('/admin/orders/payments')` 케이스가 제거 항목에 기대고 있어 `/admin/orders/claims`로 교체(정찰 오판 정정). E2E·pixel 단언은 영향 0. 남은 플레이스홀더 4(셀러·관리자·배송 관리·카테고리)는 후속 트랙.
2. 흡수 경로: **재고 → 상품 목록 필터 카드 "재고" select**(`filter-stock`·`ADMIN_PRODUCT_STOCK_FILTER_OPTIONS` 3값·행 끝 추가라 md 2 wrap) + URL query `stockFilter`(parse/route/api/hasActiveFilters) + 대시보드 "재고 임박" 타일 `to: /admin/products?stockFilter=LOW`(E2E "링크 없음" 단언 반전). **결제 → 주문 상세 결제 표**에 PG 거래번호·실패코드 컬럼과 PAID 행 "취소 처리" 버튼(`payment-cancel`·BE 전이는 PAID→CANCELLED뿐이라 PAID에만). **환불 → 클레임 목록** 필터 카드 "환불 상태" select(`filter-refund-status`·라벨은 사용자 `REFUND_STATUS_LABELS`·검색어 md 5→4·처리 상태 md 3→2로 한 줄 유지) + 행 액션 "환불 개시"(`row-initiate-refund`·BE `availableActions` INITIATE_REFUND에만 노출·"행 액션은 BE availableActions로만" 원칙 유지).
3. 신규 액션 확인 다이얼로그 정책(AdminClaimRejectDialog 패턴 1:1·호출·토스트는 다이얼로그 소유·부모는 done 시 재조회): **`AdminPaymentCancelDialog`** — 금액 표시·사유 textarea 필수(비면 확인 비활성·200자)·응답 status가 PAID 그대로면 warning "전액 환불이 완료된 결제만 취소 처리됩니다"(BE NO-OP 200을 사용자에게 드러냄)·CANCELLED면 danger 토스트·400은 필드 표시. **`AdminRefundInitiateDialog`** — 품목 금액 표시 + 환불 금액 number 입력(기본 = 품목 금액·1 이상 정수만 확인 활성)·응답 FAILED면 warning·그 외 info "N원 환불을 개시했습니다"·422(CLAIM_STATE_INVALID·REFUND_INVARIANT_VIOLATION)는 warning 후 stale(부모 재조회)·400/MALFORMED는 금액 필드에 표시. 두 액션 모두 E2E는 다이얼로그 노출까지만 검증하고 POST 0건을 단언한다(실행 시 데이터 변경).
4. 트랩: 페이지 파일 삭제·신규 컴포넌트 추가는 dev 서버가 반영하지 못한다(Vite "Failed to load url payments.vue"·새 다이얼로그 미렌더) → `docker restart zslab_mall_frontend`. Vuetify `v-textarea`는 `locator('textarea').first()`(sizer 포함 2개).

### §2 확정 구현 규칙
- `lib/constants/product.ts` `AdminProductStockFilter`·옵션 3 / `lib/constants/admin-order.ts` `ADMIN_PAYMENT_CANCEL_REASON_MAX` / `lib/constants/admin-claim.ts` 액션 라벨 +`INITIATE_REFUND: '환불 개시'` / `types/admin-product.ts`·`admin-order.ts`(`AdminOrderPayment.pgTid/failureCode`·`AdminPaymentCancelRequest/Response`)·`admin-claim.ts`(`AdminClaimAction` +INITIATE_REFUND·`AdminClaimListQuery.refundStatus`·`AdminRefundInitiateBody/Response`) / `lib/admin-product-query.ts`·`admin-claim-query.ts`(refundStatus 정규화·직렬화·API·활성 필터) / `lib/admin-dashboard-view.ts` lowStock 타일 링크 / `composables/useAdminOrders.ts` +`markPaymentCancelled`·`initiateRefund`.
- 컴포넌트: `AdminProductFilterCard`(재고 select) · `AdminClaimFilterCard`(환불 상태 select) · `AdminClaimTable`(initiateRefund emit·버튼) · `AdminPaymentCancelDialog`(신규) · `AdminRefundInitiateDialog`(신규) · 페이지 `orders/[id].vue`(컬럼 2·관리 열·다이얼로그 배선) · `orders/claims/index.vue`(다이얼로그 배선).
- 테스트: vitest `admin-product-query`·`admin-claim-query`(refundStatus)·`admin-claim-helpers`(라벨 7종)·`admin-dashboard-helpers`(타일 링크)·`admin-product-helpers`(메뉴 케이스 교체) · Playwright `admin-products ⑥`(재고 select → URL·API·타일 클릭 → LOW) · `admin-orders ⑨`(pgTid·실패코드·취소 처리 다이얼로그 노출·사유 필수·POST 0) · `admin-claims ⑥`(환불 상태 필터 → URL·API·환불 개시 다이얼로그 금액·POST 0) · `admin-dashboard` 단언 반전.
- 검증(실측): typecheck 0 · vitest 44 files 309(기존 케이스 갱신·건수 불변) · Playwright 61/61 skip 0(58 → +3·1회차 3건 첫 로드 플레이크·2회차 GREEN) · 픽셀 track88 vs track89a 12장 diff 0 · 라이브 화면: 사이드바 17링크(제거 4 없음)·타일 "재고 임박 1건" 클릭 → 목록 1행·select "재고 임박(1~5)"·주문 상세 결제 표 컬럼·다이얼로그 금액 98,000원·클레임 환불 완료 필터 14행.
- 신규 의존성: 없음.

### §8 이월
- 결제·환불 독립 화면(실 PG 전환 후)·재고 이력 화면·남은 플레이스홀더 4(Track 89-B~E) — D-183 §8과 동일.
- 제거 경로 3건이 `[id]` 동적 라우트로 흡수돼 "찾을 수 없음" 카드로 보이는 것은 사양대로 방치(전용 404로 바꾸려면 `[id].vue`에서 접두 검사 필요).

## FE-37: 관리자 배송 관리 화면 (2026-09-18)

BE 계약 Track 89-B D-184(`scope`·keyword 3종·발송일 기간·상세·송장 정정 SHIPPING만·409 중복이 SoT·본 항목에서 재기술하지 않음) · 브랜치 `feat/admin-deliveries`(BE·FE 동일 브랜치·미커밋) · 정찰 `docs/track-89/recon-report.md` §2-5·§5 · 외부 검토 B / 판단 후 보고(생략 권고·D-184).

### §1-A 갈림길·채택/기각 근거
1. 화면 구성: `orders/index.vue` 복제(URL query 단일 소스·`pendingQuery` 병합·requestSequence 경합 방어·FilterCard·Table·행 다이얼로그). 필터 카드 = 검색어(placeholder "송장번호·주문번호는 정확히, 수령인명은 일부") · 발송일 시작/종료 · **조회 범위 select(`filter-scope`·4값·기본 원 발송·clearable 아님)** · 배송상태 · 택배사 · 정렬(발송 최신순/오래된순). 방향·클레임 연계를 select 2개로 두는 안 【기각: BE 단일 축과 1:1이 아니고 항상-빈 조합이 생김】.
2. 테이블 8컬럼(주문번호 / 상품 / 수령인 / 구분 / 상태 / 택배사 / 송장번호 / 발송·완료 2줄): 주문번호 클릭 → `/admin/orders/{ord_}?back=`(FE-26 패턴) · 구분 열 = 회수 chip(RETURN·warning) + 클레임 chip(방향×유형 라벨 "교환품 발송/재발송/반품 회수/교환 회수"·warning·클릭 → 클레임 목록) · 원 발송은 chip 없이 "원 발송" 텍스트 · 송장번호 옆 복사 버튼(클립보드·실패 시 warning 토스트) · 행 클릭 → 상세 다이얼로그. 행 액션 메뉴(송장 등록·배송완료) 【기각: 주문 목록 행 액션과 중복·이번 범위는 조회+송장 정정】.
3. 클레임 배지 이동 = `/admin/orders/claims?keyword=<주문번호>&type=<유형>` — 클레임 목록에 클레임 id 필터가 없어 주문번호 정확 검색+유형으로 좁힌다(BE 무변경·D-184 §8 이월). `toClaimListPath` 순수 함수로 고정.
4. 배지 색: 배송 상태는 기존 `ADMIN_DELIVERY_STATUS_SEMANTIC` 재사용(SHIPPING=info 파랑·DELIVERED=success 녹색·READY=info — 관리자 의미색 4종에 회색이 없어 신규 상수 없이 유지) · 회수·클레임 연계 = warning(노랑 계열). 신규 상수는 `lib/constants/admin-delivery.ts`(scope·direction·클레임 배지 라벨·정렬·사유 200·정정 허용 상태)만.
5. 상세 다이얼로그(`AdminDeliveryDetailDialog`·읽기 전용): 배송(ID·구분·택배사·송장+복사·발송/완료일) / 배송지(수령인·연락처·주소·메모·마스킹 없음) / 주문·품목(주문번호 링크·상품(옵션)·수량·품목 상태) / 클레임(유형·상태 chip·ID). "송장 수정" 버튼은 `canCorrectTracking(status)`(SHIPPING만) 아니면 **비활성 + 감싸는 span 툴팁**에 사유("배송완료된 배송은…" / "배송중 상태에서만…"). 비활성 버튼은 이벤트를 받지 않아 툴팁은 wrapper에 건다.
6. 송장 수정 다이얼로그(`AdminDeliveryTrackingDialog`·AdminPaymentCancelDialog 패턴): 택배사 select·송장번호(현재 값 기본·≤100)·사유 textarea 필수(비면 확인 비활성·200자). 성공 info 토스트 후 done(상세 닫고 목록 재조회) · 400 fieldErrors 필드 표시 · **409 DELIVERY_TRACKING_NO_CONFLICT는 송장번호 필드 에러로 표시(다이얼로그 유지·재입력)** · 422/404는 warning 후 stale(재조회). 별도 확인 다이얼로그 【기각: 사유 필수 폼 자체가 확인 단계·89-A 동일】.
7. 트랩: 신규 페이지·컴포넌트 추가는 dev 서버 재시작 필요(89-A 동일) → `docker restart zslab_mall_frontend` 후 E2E.

### §2 확정 구현 규칙
- `lib/constants/admin-delivery.ts` / `types/admin-delivery.ts`(`AdminDeliverySummary`·`AdminDeliveryDetail extends Summary`·`AdminDeliveryTrackingCorrectionRequest`·`AdminDeliveryListQuery`) / `lib/admin-delivery-query.ts`(parse/route/api/hasActiveFilters·scope 기본값 URL 생략·`hasActiveFilters`는 scope≠ORIGINAL도 필터로 봄·기간 헬퍼는 admin-order-query 재사용) / `lib/admin-delivery-view.ts`(`deliveryClaimChip`·`canCorrectTracking`·`trackingCorrectionBlockedReason`·`toClaimListPath`) / `composables/useAdminDeliveries.ts`(list·detail·correctTracking PATCH) / `lib/admin-error-message.ts` +`DELIVERY_TRACKING_NO_CONFLICT`·`DELIVERY_INVALID_STATE` 문구에 송장 수정 병기.
- 컴포넌트: `AdminDeliveryFilterCard` · `AdminDeliveryTable` · `AdminDeliveryDetailDialog` · `AdminDeliveryTrackingDialog` · 페이지 `orders/deliveries.vue`(플레이스홀더 교체).
- 테스트: vitest `admin-delivery-query`(8)·`admin-delivery-helpers`(6) · Playwright `admin-deliveries ①`(진입 원 발송 2행·chip 색 → scope RETURN URL·API·회수/반품 배지 → 전체 3행 → 행 클릭 상세(배송지·주문·송장) → 송장 수정 다이얼로그(현재 값 기본·사유 비면 비활성) → DELIVERED 행 비활성 툴팁 → PATCH 0).
- 검증(실측): typecheck 0 · vitest 46 files 323(309 → +14) · Playwright 62/62 skip 0(61 → +1·1회차 5건 첫 로드 플레이크(BE 전체 테스트 동시 실행 부하)·2회차 GREEN) · 픽셀 track89a vs track89b 12장 diff 0(1·2회차 로그인 화면 25px/8px가 desktop/mobile 번갈아 나타나 캡처 노이즈로 판정·3회차 12장 0·로그인 화면 무수정) · 라이브 화면(playwright-report/step443-admin 5장·실 BE): 조회 범위 157/4/10/171·배송중 8·송장 정확 검색 1·배송중 행 상세 → 송장 수정 활성 → 다이얼로그 현재 값(로젠택배·DEMO00001158)·사유 비면 "수정" 비활성·회수 행 비활성·PATCH 0.
- 신규 의존성: 없음.

### §8 이월
- 클레임 목록 클레임 id 딥링크(현재 주문번호+유형 근사) · 배송 목록 정렬 인덱스(D-184 §8) · 구분 열의 회수 chip + 클레임 chip 이중 표기는 정보 중복이나 클레임 정보 부재 시 방향만 남도록 의도(단일 chip으로 합칠지 사용 후 판단).

## FE-38: 관리자 카테고리 관리 화면 (2026-09-18)

BE 계약 Track 89-C D-185(목록 `{defaultCommissionRate, items[]}`·PUT 전체 치환·삭제 상품 0건 가드·PATCH /order 전체 배열이 SoT·본 항목에서 재기술하지 않음) · 브랜치 `feat/admin-categories`(BE·FE 동일 브랜치·미커밋) · 정찰 `docs/track-89/recon-report.md` §2-6·§5 · 외부 검토 B / 생략 확정(D-185).

### §1-A 갈림길·채택/기각 근거
1. 화면 구성: 6건 규모라 `AdminMemberListView.vue` 계열의 단순 목록(필터·페이징 없음·`v-data-table` + `hide-default-footer`). URL query 단일 소스 패턴 【기각: 필터·페이지가 없어 URL에 실을 상태가 없음】. 테이블 6컬럼 = 순서(번호 + 위/아래 버튼) / 카테고리명 / 수수료율 / 상품 수 / 등록일 / 관리(수정·삭제). 헤더 액션 "카테고리 등록"(기존 생성 API·이름만·sortOrder=목록 건수).
2. **율 표기 = 화면 %·내부 bp(1000 ↔ 10%)**: 표시는 `formatPercent`(불필요한 소수 0 제거·5.25%), 입력은 `parsePercentInput`(빈 값=미설정·0~100·소수 2자리=bp 정수 정밀도·범위 밖은 입력 단계에서 차단). **미설정은 "미설정 (기본율 10% 적용)"으로 기본율을 병기**하고 다이얼로그 hint에도 같은 값을 보여준다 — 기본율은 응답 `defaultCommissionRate`만 소비(FE 상수 없음·환경변수 유래라 복제 불가·D-185 §7). 응답 non_null 정책으로 NULL 율은 키가 생략되므로 타입은 `commissionRate?: number | null`.
3. 편집 다이얼로그(`AdminCategoryEditDialog`·등록/편집 겸용): 편집은 이름·수수료율만(순서는 표의 위/아래 버튼·`sortOrder`는 현재 값 그대로 전송). **경고 문구 3문장(`COMMISSION_RATE_CHANGE_WARNING` 상수·D-185 §3 조사 결론) 항상 노출**, 율이 원값과 달라지면 alert가 info→warning으로 바뀌고 사유가 필수(라벨 "변경 사유 (필수)"·비면 확인 비활성). 400 fieldErrors 필드 표시 · MALFORMED_REQUEST(BE 사유 필수 판정)는 사유 필드 · 409 CATEGORY_DUPLICATE는 이름 필드 · 404는 stale. 사유를 항상 필수로 두는 안 【기각: D-185 §4 필드별 정책과 1:1】.
4. **삭제 비활성 정책**: `canDeleteCategory`(productCount === 0)만 활성, 아니면 비활성 + 감싸는 span 툴팁 "연결된 상품 N건"(FE-37 패턴·비활성 버튼은 이벤트를 받지 않음). 활성 행은 `AdminConfirmDialog`(제목 "카테고리 삭제"·드롭다운·카탈로그 즉시 제외·동일 이름 재등록 가능 안내·confirm error). 409/404는 warning 토스트 후 재조회(그 사이 상품이 연결된 경합).
5. 정렬 UI = 위/아래 버튼(경계·요청 중 비활성) → `moveCategory`(순수 함수·인접 교환한 전체 id 배열) → `PATCH /order` → 재조회. 드래그 【기각: 라이브러리 필요】. 400(그 사이 목록 변경)은 warning 후 재조회.
6. 상품 수 클릭 → `/admin/products?categoryId=N`(상품 목록 `categoryId` 필터가 이미 있음·`admin-product-query.ts`) · 0건은 비링크.
7. 트랩: (1) 로컬 `frontend/node_modules`는 7월 stale → vitest·typecheck·Playwright·픽셀 전부 `docker exec zslab_mall_frontend pnpm …` (2) 컨테이너 `pnpm typecheck`(nuxt prepare)가 dev 서버 `.nuxt`를 덮어 `#app-manifest` 해석 실패 → 로그인 페이지 데모 버튼 미노출 → E2E skip. `docker restart zslab_mall_frontend`로 해소(89-A 신규 파일 재시작 트랩과 별개) (3) Vuetify 툴팁 내용은 활성화 시 지연 렌더라 `nth` 인덱스가 행과 어긋남 → `filter({ hasText })`.

### §2 확정 구현 규칙
- `lib/constants/admin-category.ts`(이름·사유 200·bp/% 계수·범위 0~10000 bp·소수 2자리·경고 3문장) / `types/admin-category.ts` / `lib/admin-category-view.ts`(`formatPercent`·`formatCommissionRate`·`toPercentInput`·`parsePercentInput`·`commissionRateChanged`·`canDeleteCategory`·`deleteBlockedReason`·`moveCategory`·`toProductListPath`) / `composables/useAdminCategories.ts`(list·create POST·update PUT·remove DELETE·reorder PATCH) / `lib/admin-error-message.ts` +`CATEGORY_DUPLICATE`·`CATEGORY_HAS_PRODUCTS`.
- 컴포넌트: `AdminCategoryTable` · `AdminCategoryEditDialog` · 페이지 `products/categories.vue`(플레이스홀더 교체) · 삭제 확인은 공용 `AdminConfirmDialog`.
- 테스트: vitest `admin-category-helpers`(10) · Playwright `admin-categories ①`(3행·율 표기·기본율 병기 → 아래로 이동 PATCH [2,1,3] → 수정 다이얼로그(값·경고 3문장·율 변경 시 확인 비활성·사유 필수 라벨) → 삭제 비활성 툴팁 7건·활성 행 확인 다이얼로그 → PUT/DELETE/POST 0).
- 검증(실측·컨테이너 pnpm): typecheck 0 · vitest 47 files 333(323 → +10) · Playwright 1회차 54/63(4건 각 스펙 ① 첫 로드 플레이크·5 skip ADMIN_E2E 미주입) → 2회차 ADMIN_E2E 주입 62/63(settlements ⑥ 30s 타임아웃·무관 스펙) + settlements 단독 6/6 → 63 전건(62 → +1) · 픽셀 track89b vs track89c 12장 diff 0(1회차) · 라이브 화면(playwright-report/step449-admin 5장·실 BE·일회성 step449-live.mjs): 6행·상품 수 7/5/5/5/7/5·율 6행 전부 "미설정 (기본율 10% 적용)"·순서 1~6·첫 행 위/끝 행 아래 비활성·삭제 6행 전부 비활성·툴팁 "연결된 상품 7건"·수정 다이얼로그(데모·율 빈 값·경고 3문장·확인 활성) → 율 5 입력 시 확인 비활성·등록 다이얼로그 노출·상품 수 클릭 → `/admin/products?categoryId=1` · **PUT/DELETE/PATCH/POST 0건**(데모 데이터 보존).
- 신규 의존성: 없음.

### §8 이월
- 등록 다이얼로그에서 율 동시 입력(생성 API에 commissionRate 추가 필요·D-185 §5) · 드래그 정렬 · 2차 카테고리 칩(기존 백로그).

## FE-39: 관리자 운영자 관리 화면 (2026-09-18)

BE 계약 Track 89-E D-186(목록 `GET /admin/admin-operators`·`GET /admin/me`·부여 `userPublicId`·회수 DELETE+사유 본문이 SoT·본 항목에서 재기술하지 않음) · 브랜치 `feat/admin-operators`(BE·FE 동일 브랜치·미커밋) · 정찰 `docs/track-89/recon-report.md` §2-2 · 외부 검토 B / 생략 가능 판단(D-186).

### §1-A 갈림길·채택/기각 근거
1. 화면 구성: `AdminMemberListView.vue` 계열의 URL query 단일 소스 목록(역할 select·상태 select·검색어·페이지 → `router.replace` → `route.query` watch → 조회). 테이블 = 이름(+"나" chip) / 이메일 / 역할 배지(복수·슈퍼 warning·운영 info) + "일반회원 겸직" chip(outlined) / 상태(활성·탈퇴) / 가입일 / (탈퇴 필터 시 탈퇴일) / 관리("역할 회수"). 헤더 액션 "신규 운영자 등록". 회원 목록처럼 페이지를 활성·탈퇴로 나누는 안 【기각: 1건 규모·한 화면 select가 충분】.
2. **권한별 비활성 정책 = `GET /admin/me`의 `superAdmin`·`userPublicId`만 근거**(JWT는 coarse ADMIN·내부 id라 판정 불가·D-186 §4). 등록 버튼·행 회수 버튼은 `provisionBlockedReason`·`revokeBlockedReason`(null=활성)으로 비활성 + 감싸는 span 툴팁(FE-37·38 패턴): me 로드 실패 → 전부 비활성("불러오지 못해")·목록 열람은 유지 / 비SUPER_ADMIN → "슈퍼 관리자만 …" / **자기 행은 역할과 무관하게 비활성**("자기 자신의 역할은 회수할 수 없습니다"). BE는 자기 ADMIN_OPERATOR 회수를 허용(Track 53 ⑧)하지만 화면은 더 보수적으로 막는다(실수 방지·필요하면 다른 SUPER_ADMIN이 회수). 실인가는 BE 403/409가 SoT.
3. **마지막 SUPER_ADMIN 사전 비활성 = 목록이 완전할 때만**(`superAdminCountInList`: 검색어 없음·역할 필터 전체 또는 SUPER_ADMIN·첫 페이지·hasNext false → SUPER_ADMIN 행 수, 아니면 null). 인원 ≤1이면 회수 다이얼로그의 SUPER_ADMIN 선택지를 비활성 + 사유 문구, 모르면(null) 서버 409 `LAST_SUPER_ADMIN`을 토스트로. 탈퇴 SUPER_ADMIN은 BE 인원수에 포함되지만 ACTIVE 목록에 없어 화면이 더 보수적(비활성 쪽)으로 판정할 수 있다 — 허용 방향 오판은 없음. 인원수 API 추가 【기각: 요청 범위 밖·현재 1명】.
4. 회수 다이얼로그(`AdminOperatorRevokeDialog`): 대상 행의 ADMIN 계열 역할 라디오(선택 가능한 첫 역할 기본 선택) + 확인 문구 + 사유 textarea(필수·200·비면 확인 비활성). **확인 문구 최종안** — SUPER_ADMIN: "{이름 (이메일)}의 슈퍼 관리자 역할을 회수합니다. / 회수 즉시 운영자 등록·역할 회수 권한을 잃습니다. 슈퍼 관리자 역할은 화면에서 다시 부여할 수 없으므로(부여 API 없음) 되돌리려면 DB 작업이 필요합니다. / (남은 역할 있으면) 남은 역할(운영 관리자)은 유지됩니다." · ADMIN_OPERATOR: "…의 운영 관리자 역할을 회수합니다. / (남은 역할 없으면) 회수 즉시 관리자 화면에 로그인할 수 없습니다. 필요하면 운영자 등록에서 다시 부여할 수 있습니다. / (있으면) 남은 역할(슈퍼 관리자)은 유지되어 관리자 화면 접근은 계속 가능합니다." 403(SUPER_ADMIN 아님·자기 SUPER_ADMIN)은 code가 공용 FORBIDDEN이라 `toOperatorErrorMessage`가 서버 detail(구체 사유)을 우선 표시 · 409 LAST_SUPER_ADMIN·404 ROLE_ASSIGNMENT_NOT_FOUND는 코드 문구 → 토스트 후 재조회.
5. 등록 다이얼로그(`AdminOperatorProvisionDialog`): 프로비저닝 API가 기존 회원 승격만이라(D-186 §7) 회원 목록 API(keyword·ACTIVE·size 10) 검색 → 결과 리스트 선택 → 확인 문구 → POST(사유 없음). **임시 비밀번호 표시 없음**(발급 자체가 없음·앞 프롬프트 지시 무효화 확정). 409 ADMIN_OPERATOR_ALREADY_EXISTS는 토스트 후 다이얼로그 유지(다른 회원 선택 가능). 이미 운영자인 회원을 검색 결과에서 제외하는 안 【기각: 교차 조회 필요·서버 409로 충분】.
6. 트랩: (1) Vuetify `v-radio` 루트의 data-testid 클릭·`input.check({force})`로는 선택이 바뀌지 않음 → `label` 클릭 (2) `v-textarea`는 textarea 2개(auto-grow sizer) → `.first()`(FE-27 선례) (3) Playwright 전체 실행을 BE `--rerun-tasks`와 동시에 돌리면 첫 로드 플레이크 7건 → BE 종료 후 재실행 64/64.

### §2 확정 구현 규칙
- `lib/constants/admin-operator.ts`(`AdminOperatorRole` 2값·라벨·semantic·역할/상태 옵션·키워드 50·사유 200·회원 검색 10) / `types/admin-operator.ts`(`AdminOperatorSummary` roles 배열+hasBuyerRole·`AdminMe`·ListQuery·요청 2종) / `lib/admin-operator-query.ts`(parse·route·api·hasActiveFilters) / `lib/admin-operator-view.ts`(`operatorRoleChip`·`operatorDisplayName`·`isSelf`·`superAdminCountInList`·`provisionBlockedReason`·`revokeBlockedReason`·`roleRevokeBlockedReason`·`revokeConfirmMessage`·`toOperatorErrorMessage`) / `composables/useAdminOperators.ts`(list·me·provision POST·revoke DELETE+body) / `lib/admin-error-message.ts` +`ADMIN_OPERATOR_ALREADY_EXISTS`·`ROLE_ASSIGNMENT_NOT_FOUND`·`LAST_SUPER_ADMIN`.
- 컴포넌트: `AdminOperatorTable` · `AdminOperatorRevokeDialog` · `AdminOperatorProvisionDialog` · 페이지 `members/admins.vue`(플레이스홀더 교체·`/admin/me`는 onMounted 1회).
- 테스트: vitest `admin-operator-helpers`(14: URL 매핑 4·배지/표시명/self 3·비활성 판정 2·인원 계수/마지막 SUPER_ADMIN 2·확인 문구/오류 문구 3) · Playwright `admin-operators ①`(2행·배지·겸직·나 chip → 자기 행 비활성 툴팁 → 타 행 회수 다이얼로그(SUPER_ADMIN 2명이라 선택 가능·재부여 불가 문구·역할 전환 시 문구·사유 비면 비활성) → 역할 필터 URL/API → 등록 다이얼로그(검색 파라미터·선택·확인 문구) → POST/DELETE 0).
- 검증(실측·컨테이너 pnpm): typecheck 0 · vitest 48 files 347(333 → +14) · Playwright 1회차 57/64(7건 첫 로드 플레이크·BE 전체 테스트 동시 실행) → 2회차 64/64(63 → +1) · 픽셀 track89c vs track89e 12장 diff 0 · 라이브 화면(playwright-report/step455-admin 5장·실 BE·일회성 step455-live.mjs): 1행(이름 "—"·admin@zslab-mall.local·슈퍼 관리자 배지·겸직 chip 0·"나" chip 1·활성·회수 비활성 툴팁 "자기 자신의 역할은 회수할 수 없습니다.")·등록 버튼 활성 → 등록 다이얼로그 회원 검색 "@" 10건·demo 선택 → "demo 회원에게 운영 관리자 역할을 부여합니다."·확인 활성 → 닫기 · 탈퇴 필터 빈 상태 · 모바일 390px 목록 · **POST/DELETE 0건**(user_role·audit_log 불변).
- 신규 의존성: 없음.

### §8 이월
- SUPER_ADMIN 부여 UI(BE API 부재·D-186 §8 선행) · 운영자 상세(마지막 로그인 등 볼 데이터 없음) · 데모용 운영자 계정 시드(화면 1행·D-186 §8).

## FE-40: 셀러 관리 화면 (2026-09-19)

BE 계약 Track 89-D D-187(페이징 목록 `GET /admin/sellers/page`·상세·`PATCH /status`(응답 = 전이 후 상세)·`PUT`(204)·입점 `ownerUserPublicId`·409 `SELLER_ACTIVITY_IN_PROGRESS` + `blocks`가 SoT·본 항목에서 재기술하지 않음) · 브랜치 `feat/admin-sellers`(BE·FE 동일 브랜치·미커밋) · 정찰 `docs/track-89d/recon-report.md` · 외부 검토: 등급 A / BE와 함께 진행.

### §1-A 갈림길·채택/기각 근거
1. **상세 배치 α 별도 라우트 `/admin/members/sellers/[id]` 【채택】 / β 다이얼로그 【기각】** — 상세가 기본 정보·구성원·계좌·상품 상태별·거래·정산 상태별·경고·전이 버튼·수정으로 카드 6개 + 다이얼로그 2종을 가져 다이얼로그 안에 다이얼로그(전이·수정)가 중첩되고, 회원 상세·상품 목록·정산 이력으로의 링크 왕복(`?back=`)이 필요하다. 회원 상세(Track 84)와 같은 구조가 운영자 학습 비용도 낮다. 라우팅은 플레이스홀더 `members/sellers.vue`를 지우고 `members/sellers/index.vue` + `[id].vue`로 둔다(`sellers.vue`가 남으면 중첩 레이아웃이 돼 `<NuxtPage>`가 필요).
2. **상태 상수 추출 = `lib/constants/admin-seller.ts` 단일 소스** — `types/admin-product.ts:74`의 인라인 유니온 `'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'TERMINATED'`(정찰 지적)을 `AdminSellerStatus` import로 교체. 사용처 13파일(상품 목록 필터·등록 폼·상세 readonly·셀러별 정산 autocomplete)은 문자열 비교라 무수정. 회귀: typecheck 0·vitest 347 유지·Playwright admin-products/admin-product-form/admin-settlements 21/21.
3. **배지 톤**: ACTIVE `success`(녹) / PENDING `warning`(amber 노랑 배경) / SUSPENDED `danger` / TERMINATED `neutral`(회색·FE-33 토큰). 지시의 "정지 = 주황 계열"은 chip 팔레트(danger·warning·success·info·neutral)에 주황 전용 톤이 없어 **구매 차단 의미의 danger**로 두고 새 CSS 토큰은 만들지 않았다(한 화면용 팔레트 추가는 과잉). `AdminSellerStatusTone = AdminSemantic | 'neutral'`.
4. **전이 버튼 노출 규칙**: `ADMIN_SELLER_TRANSITIONS`(BE `canTransitionTo`와 같은 6전이)로 현재 상태에서 가능한 목표만 렌더(PENDING → 활성화·종료 / ACTIVE → 정지·종료 / SUSPENDED → 활성화·종료 / TERMINATED → 없음). **종료는 응답 `terminable=false`면 비활성 + 감싸는 span 툴팁 + 카드 하단 문구**("종료 불가: 미지급 정산 2건 / 진행 중 주문 6건 / 처리 중 클레임 1건 — 정산 지급·주문 처리·클레임 종결 후 종료할 수 있습니다."). FE는 가드를 재계산하지 않는다(응답이 SoT·BE와 같은 판정). `terminable=false`인데 blocks가 비면 "지금은 종료할 수 없습니다."로 방어. 전이 성공(200)은 응답 상세로 즉시 교체(재조회 없음·terminable 갱신 포함), 409/422/404는 토스트 후 재조회(stale).
5. **terminationBlocks 문구 최종안** — `formatTerminationBlocks`: `{라벨} {count}건`을 " / "로 연결. 라벨 UNPAID_SETTLEMENT=미지급 정산·ORDER_ITEM_IN_PROGRESS=진행 중 주문·CLAIM_ACTIVE=처리 중 클레임. 상세 카드·툴팁·409 토스트("종료할 수 없습니다: …")가 같은 함수를 쓴다.
6. **종료 확인 문구 최종안**(`transitionConfirmMessage`·error alert·불가역 문장 bold): "{상호} 셀러를 종료합니다. / **종료 후에는 어떤 상태로도 되돌릴 수 없습니다.** / 종료 즉시 이 셀러의 상품은 카탈로그에서 사라지고 담기·주문·재결제가 차단됩니다. 사유는 종료 아카이브와 감사 이력에 기록됩니다. / (판매중 상품 N건이 있습니다(상품 상태는 바뀌지 않습니다).) / (주 정산계좌가 없습니다. 남은 매출의 정산 지급이 불가능할 수 있습니다.)". 정지: "정지 즉시 … 차단됩니다. 진행 중인 주문·정산은 계속 처리됩니다. / 정지 해제(활성화)로 되돌릴 수 있습니다." 활성화는 PENDING(입점 승인)·SUSPENDED(정지 해제) 분기. 사유 필수(200자·비면 확인 비활성).
7. **수수료율 경고 문구**(`SELLER_COMMISSION_RATE_CHANGE_WARNING`·율 변경 시 warning 강조·사유 필수): "수수료율은 변경 시점 이후 새로 생성되는 주문부터 적용됩니다. / 이미 생성된 주문·정산(재생성 포함)에는 영향이 없습니다. / **셀러 개별 수수료율은 카테고리 수수료율보다 우선 적용됩니다(미설정이면 카테고리율 → 플랫폼 기본율).**" 89-C 문구와 같은 근거(D-179 결정 2·주문 시점 스냅샷)에 3단 판정 최우선을 추가. 율은 % 입력·내부 bp(89-C `parsePercentInput`·`toPercentInput`·`formatPercent` 재사용). PUT 204 → 상세 재조회.
8. **PENDING 가시성** — 정렬이 등록일 desc 고정이라 승인 대기가 묻힐 수 있어, 목록 로드 시 `status=PENDING&size=1` 1회를 병행 조회해 건수가 있으면 목록 위 warning 배너("승인 대기 셀러 N건 … 활성화(입점 승인)하거나 종료(승인 거부)하세요") + "승인 대기만 보기" 프리셋(status=PENDING)을 둔다(status=PENDING 필터 중엔 미조회). 정렬 파라미터 추가 【기각: BE 정렬 고정·5건 규모】. 배지도 warning 톤으로 강조.
9. **입점 등록 다이얼로그** = 1단계 회원 검색(FE-39 운영자 등록의 회원 목록 API 검색 재사용) → owner 선택 → 2단계 사업자 정보(상호·대표자 필수·사업자번호·이메일·연락처·초기 상태 select "즉시 활성 / 승인 대기"·기본 즉시 활성: 단일 운영자가 직접 입점시키는 MVP라 승인 단계를 기본으로 두면 클릭만 늘어남). 409 `SELLER_BUSINESS_NO_DUPLICATE` → 사업자번호 필드 오류 "이미 등록된 사업자번호입니다." / `SELLER_USER_ALREADY_EXISTS`·`USER_NOT_FOUND` → 토스트 후 owner 재선택(입력값 유지). 성공 시 상세로 이동.
10. **구성원 표기**: 탈퇴 = "탈퇴 (일시)"·soft-delete = "삭제됨"·활성 = "활성" + 회원 상세 링크. `loginableMemberCount`(user 해소 ∧ 미탈퇴) = 0이면 상세 상단 warning("로그인 가능한 구성원이 없습니다(구성원 N명 중 활성 0명). 셀러 계정으로 주문·송장·클레임을 처리할 사람이 없습니다.") — 셀러 2(유일 owner 탈퇴) 실사례. 주 계좌 없음도 상단 warning + 계좌 카드 "미등록 — 정산 지급이 차단됩니다.".
11. 트랩: (1) 컨테이너에서 vitest와 Playwright 전체를 동시에 돌리면 각 spec ① 첫 로드 플레이크 7건 + vitest Hook timeout 15파일 → 각각 단독 재실행으로 65/65·356/356 (2) `v-tooltip` 내용 span은 전이 버튼마다 렌더돼 `.first()`가 숨은 것을 잡을 수 있음 → E2E는 `toContainText`(가시성 무관)·라이브는 스크린샷으로 확인 (3) `admin-shell.spec ⑤`(첫 렌더 카드 위치)가 마지막 플레이스홀더였던 셀러 화면을 측정하고 있어 대상을 `admin-seller-filters` 카드로 교체(`AdminPlaceholder` 소비처 0·삭제하지 않음).

### §2 확정 구현 규칙
- `lib/constants/admin-seller.ts`(`AdminSellerStatus` 4값·라벨·톤·옵션·`ADMIN_SELLER_TRANSITIONS`·전이 라벨·차단 코드 라벨·초기 상태 옵션·키워드 50·사이즈·사유 200·컬럼 길이 5종·회원 검색 10·율 경고 3문장·불가역 문구) / `types/admin-seller.ts`(목록 행·상세·구성원·계좌·정산 합계·차단·경고·ListQuery·요청 3종) / `lib/admin-seller-query.ts`(parse·route·api·hasActiveFilters) / `lib/admin-seller-view.ts`(chip class·라벨·`availableTransitions`·`canTransitionTo`·`formatTerminationBlocks`·`terminateBlockedReason`·`loginableMemberCount`·`memberDisplayName`·`transitionConfirmMessage`·경로 2·`toSellerErrorMessage`) / `composables/useAdminSellers.ts`(list·countPending·get·changeStatus PATCH·update PUT·provision POST) / `lib/admin-error-message.ts` +`SELLER_INVALID_STATE`·`SELLER_ACTIVITY_IN_PROGRESS`·`SELLER_BUSINESS_NO_DUPLICATE`·`SELLER_USER_ALREADY_EXISTS` / `lib/admin-back-path.ts` +`ADMIN_SELLERS_PATH`·회원 상세 back에 `/admin/members/sellers/` prefix 허용 / `types/admin-product.ts` `AdminSellerSummary.status` → 상수 import.
- 컴포넌트: `AdminSellerTable`(상호+이메일·사업자번호·대표자·배지·상품 수 링크 `/admin/products?sellerPublicId=`·계좌·등록일·행 클릭) · `AdminSellerProvisionDialog` · `AdminSellerStatusDialog` · `AdminSellerEditDialog` · 페이지 `members/sellers/index.vue`·`members/sellers/[id].vue`(플레이스홀더 `sellers.vue` 삭제).
- 테스트: vitest `admin-seller-helpers`(9: URL 매핑 2·배지 1·전이 1·차단 문구 1·409 문구 1·구성원 1·확인 문구 1·경로/율 1) · Playwright `admin-sellers ①`(목록 배지·승인 대기 배너·프리셋 → 상세(차단 사유·종료 비활성 툴팁·구성원 0 경고·율 12%·계좌 끝4자리) → 정지 다이얼로그(사유 필수) → 수정 다이얼로그(율 경고·사유 없으면 비활성) → 입점 다이얼로그(검색·선택·폼) · PATCH/PUT/POST 0) · `admin-shell ⑤` 측정 대상 교체.
- 검증(실측·컨테이너 pnpm): typecheck 0 · vitest 49 files 356(347 → +9) · Playwright 1회차 58/65(동시 실행 플레이크) → 단독 2회차 **65/65**(64 → +1) · 픽셀 track89e vs track89d 12장 중 11장 diff 0·`login-mobile` 8px(0.002%·사용자 헤더 검색 아이콘 원 테두리 안티앨리어싱·3회 재캡처 동일·FE-40 무관 영역·시각 동일) · 라이브 화면(`playwright-report/step467-admin` 8장·실 BE·일회성 `step467-live.mjs`): 목록 5행 전원 활성·상품 수 2/2/9/11/10·계좌 미등록/미등록/등록/등록/등록·승인 대기 배너 없음(0건) · 셀러 3 상세: 율 "미설정 (카테고리율 → 기본율)"·계좌 "KB ····0001"·주문 55건·매출 1,882,600원·정산 대기 1/확정 1/지급완료 4·**종료 차단 "미지급 정산 2건 / 진행 중 주문 6건 / 처리 중 클레임 1건"**·종료 비활성 + 툴팁 동일 문구·정지 활성·정지 다이얼로그 문구·사유 비면 확인 비활성·수정 다이얼로그 율 경고 3문장 · 셀러 2: "로그인 가능한 구성원이 없습니다(구성원 1명 중 활성 0명)" + 계좌 미등록 경고 + 구성원 "탈퇴 (2026.09.18 00:26)"·종료 차단 진행 중 주문 1건 · 셀러 1: 종료 비활성 툴팁 "종료 불가: 진행 중 주문 2건"(만료 ORDERED 5건 제외 확인) · 입점 다이얼로그 회원 검색 "demo" 10건 → 선택 → 폼 노출·확인 비활성 → 닫기 · 모바일 390px 목록 · **PATCH/PUT/POST 0건**.
- 신규 의존성: 없음.

### §8 이월
- 셀러 계좌 등록 UI(BE 89-F 선행) · 셀러 구성원 추가·역할 변경 UI(BE API 부재) · 상세 → 주문 목록 링크(관리자 주문 목록 seller 필터 부재·D-187 §8) · `AdminPlaceholder` 컴포넌트(소비처 0·삭제 여부) · 상품 등록 드롭다운의 비-ACTIVE 셀러 표기(현재 셀러명만·BE는 422로 차단).

## FE-41: 셀러 정산계좌 관리 화면 (2026-09-19)

BE 계약 Track 89-F D-188(`POST /admin/sellers/{slr_}/bank-accounts` 201·`PUT …/{id}` 204·`PATCH …/{id}/primary` 204·셀러 상세 `bankAccounts[]`(등록순·isPrimary)·409 `SELLER_BANK_ACCOUNT_REFERENCED`·422 `SELLER_BANK_ACCOUNT_INVALID_STATE`·404 `SELLER_BANK_ACCOUNT_NOT_FOUND`·응답은 끝 4자리만)가 SoT·본 항목에서 재기술하지 않음 · 브랜치 `feat/seller-bank-account`(BE·FE 동일 브랜치·미커밋) · 정찰 `docs/track-89f/recon-report.md` · 외부 검토: 등급 A / BE와 함께 진행.

### §1-A 갈림길·채택/기각 근거
1. **수정 다이얼로그에서 기존 계좌번호 미표시·새로 입력 【채택】 / 기존 번호 프리필 【기각】** — BE는 복호화된 전체 번호를 어떤 응답에도 내리지 않는다(D-188 결정 12·`AdminSellerBankAccountResponse.accountNumberSuffix`뿐). 프리필하려면 전체 번호 조회 API가 생겨야 하고 그 순간 계좌 실값이 브라우저·네트워크·로그로 흐른다. 수정 = "운영자가 셀러와 확인한 새 값을 다시 입력"이며 오기 정정 시나리오에서도 전체를 다시 치는 편이 안전하다. 다이얼로그는 현재 계좌를 "KB국민은행 ····0001 (예금주) — 보안상 기존 번호는 표시되지 않습니다"로만 참고 표시하고 계좌번호 입력은 빈 값으로 시작한다(E2E ②·라이브 실측 `numberValue=""`). 은행·예금주는 프리필(민감정보 아님).
2. **끝 4자리 표기 = `maskedAccountNumber` "····0001"(응답 suffix만·화면이 자를 것 없음)** — 89-D 계좌 카드·정산 상세(`formatBankAccount`)와 같은 규칙. 입력 중 마스킹은 하지 않는다(오타 확인 필요·`inputmode=numeric`·hint 명시). 목록·다이얼로그·토스트·감사 어디에도 전체 번호가 없음을 E2E(body 정규식)·라이브(응답 본문 8건 정규식·`accountNumber` 키 부재)로 단언.
3. **정산 참조 계좌 수정 비활성 = 화면 선판정 불가 → 서버 409 처리 + 카드 안내** — BE 응답에 "정산 참조 여부" 플래그가 없다(`bankAccounts[]` 키 9종에 `referencedBySettlement` 없음·D-188 결정 8은 서버 `existsByBankAccountId`로만 판정). 화면은 셀러 `settlements`에 PAID가 있어도 어느 계좌가 스냅샷됐는지 알 수 없어 행 단위 비활성을 하지 않고, (a) 수정 버튼은 전 행 활성 (b) 409면 토스트 "정산 지급 이력이 있어 수정할 수 없습니다. 새 계좌를 등록해 주세요." + stale 재조회 (c) PAID 정산이 있는 셀러는 카드 하단에 "지급 이력이 있는 계좌는 수정할 수 없습니다 — 새 계좌를 등록한 뒤 주 계좌로 전환하세요." 안내. **BE 보강 제안(임의 수정 안 함·외부 검토 시 판단)**: `AdminSellerBankAccountResponse`에 `referencedBySettlement: boolean`(상세당 계좌 수 만큼 `existsByBankAccountId`·3행 규모) 추가 시 버튼 비활성 + 툴팁("정산 지급 이력이 있어 수정할 수 없습니다. 새 계좌를 등록해 주세요")로 전환 가능·FE는 플래그 유무만 분기하면 됨.
4. **주 계좌 전환 안내 문구 최종안**(`SELLER_BANK_ACCOUNT_PRIMARY_CHANGE_NOTICE`·warning alert·첫 문장 bold): "**이후 정산 지급은 이 계좌로 이루어집니다.** / 아직 지급되지 않은 정산(지급 대기·확정)도 지급 시점의 주 계좌인 이 계좌로 지급됩니다. / 이미 지급완료된 정산은 지급 당시 계좌가 기록되어 있어 영향이 없습니다." — BE 동작 근거: `SettlementTransitionService.pay`가 지급 시점에 `findPrimaryBankAccountIds`로 재조회해 `markPaid`에 스냅샷(D-179 결정 7)·PENDING/CONFIRMED는 `bank_account_id` NULL·PAID는 행 id 고정. 머리글 "신한은행 ····5678 (홍길동) 계좌를 주 정산계좌로 지정합니다." + "현재 주 계좌: … — 전환 후 해제됩니다." 사유 필수(200자·비면 확인 비활성). 이미 주 계좌인 행은 버튼 비활성 + 툴팁 "이미 주 정산계좌입니다."(BE 422와 같은 판정·`canMakePrimary`).
5. **카드 배치 = 셀러 상세 기본 정보 바로 아래 전폭·목록(v-table) / 기존 md=5 카드 【기각: 6열 표가 들어가지 않음】** — 구성원 카드는 md=12로 넓힘. 주 계좌 행은 `success` chip(별 아이콘) + 행 배경 tint로 시각 강조(지급이 이 계좌로 나감). 0건이면 "미등록 — 정산 지급이 차단됩니다." + 등록 버튼 `flat` 강조 + 상단 warning 배너 문구를 "아래 정산계좌 카드에서 계좌를 등록하세요"로 교체(종전 "별도 트랙").
6. **등록 다이얼로그**: 은행 `v-select`(`ADMIN_BANK_OPTIONS` 21·코드=시드와 같은 영문 약칭·옵션 밖 코드는 코드 그대로 표기 `bankLabel`) · 계좌번호(BE `^[0-9-]+$` 6~30 동일 검증·공백 제거·`validateAccountNumberInput`) · 예금주(50) · 계좌 0건이면 info "첫 번째 계좌는 자동으로 주 정산계좌가 됩니다. 등록 즉시 정산 지급이 가능해집니다." · 사유 없음(D-188 결정 10). 400 `VALIDATION_FAILED` → fieldErrors, 409/404 → 토스트 + stale. 성공 토스트는 끝 4자리만("KB국민은행 ····4321 계좌를 등록하고 주 정산계좌로 지정했습니다").
7. **상태 라벨** PENDING 인증 대기 / VERIFIED 인증 완료 / REJECTED 거부(`AdminSellerBankAccountStatus` 유니온·4층위 (4)). 관리자 등록은 항상 VERIFIED라 화면은 표시만.
8. 트랩: (1) E2E mock `DETAIL_A`에 `bankAccounts`가 없으면 카드가 빈 배열 접근으로 깨짐 → mock에 2행 추가·BE는 항상 배열(NON_NULL은 빈 배열을 생략하지 않음) (2) 전체 Playwright 동시 실행 시 각 spec ① 첫 로드 플레이크 5건(FE-40 §1-A 11 동일) → 단독 재실행 21/21 (3) 기존 ① 단언 `seller-bank-number` → 목록 행 `seller-bank-row-number`로 교체.

### §2 확정 구현 규칙
- `types/admin-seller.ts`(+`AdminSellerBankAccountRow`·`AdminSellerDetail.bankAccounts`·요청 3종) / `lib/constants/admin-seller.ts`(+status 3값 라벨·`ADMIN_BANK_OPTIONS`·계좌번호 6~30 패턴·예금주 50·첫 계좌 안내·전환 안내 3문장) / `lib/admin-seller-bank-view.ts`(`bankLabel`·`maskedAccountNumber`·`bankAccountStatusLabel`·`primaryBankAccount`·`canMakePrimary`·`validateAccountNumberInput`·`primaryChangeHeadline`) / `lib/admin-error-message.ts` +3 / `composables/useAdminSellers.ts` +`registerBankAccount`·`updateBankAccount`·`changePrimaryBankAccount`(204 → 부모 재조회·89-C PUT 선례).
- 컴포넌트: `AdminSellerBankAccountCard`(목록·액션·두 다이얼로그 소유·`changed` → 상세 `load`) · `AdminSellerBankAccountDialog`(등록/수정 겸용·`target` null=등록) · `AdminSellerBankAccountPrimaryDialog`. 페이지 `members/sellers/[id].vue`는 카드 삽입·경고 문구·구성원 폭만 변경(전이·수정 다이얼로그 무변경).
- 테스트: vitest `admin-seller-bank-helpers`(6: 끝 4자리·은행 라벨·주 계좌/전환 판정·입력 검증·문구·에러 코드) · Playwright `admin-sellers ②`(목록 2행·끝 4자리·배지 1·주 계좌 행 비활성·참조 안내 → 등록 다이얼로그 문자 검증 → 수정 다이얼로그 번호 미표시·사유 필수 → 전환 다이얼로그 headline·현재 주 계좌·안내 3문장·사유 필수 → body 전체 번호 패턴 없음·writes 0) · ① 계좌 단언 교체.
- 검증(실측·컨테이너 pnpm): typecheck 0 · vitest 50 files 362(356 → +6) · Playwright 1회차 56/66(동시 실행 플레이크 5·연쇄 skip 5) → 실패 5 spec 단독 21/21 = **66/66**(65 → +1) · **89-D 회귀**: `admin-sellers ①`(목록·상세·전이·수정·입점) GREEN·전이/수정 다이얼로그 코드 무변경 · 픽셀 track89d vs track89f 12장 중 11장 diff 0·`login-mobile` 8px(0.002%·FE-40과 동일 안티앨리어싱·FE-41 무관 영역) · 라이브(`playwright-report/step481-live.mjs`·실 BE·`step481-admin` 11장): 셀러 3/4/5 카드 1행 "KB국민은행/신한은행/우리은행 ····0001/0002/0003·주 계좌 배지·인증 완료·주 계좌로 비활성·지급 이력 안내" · 셀러 2 미등록 경고 + 등록 버튼 + 등록 다이얼로그 첫 계좌 안내 · 셀러 3 등록 다이얼로그(첫 계좌 안내 없음·확인 비활성)·수정 다이얼로그(현재 계좌 ····0001 참고·번호 입력 빈 값) · **쓰기 1건**: 셀러 1(데모 상점) 검증용 가짜 계좌 id 4 수정(PUT 204·번호·예금주 변경·사유) → 재조회 후 "····9999 데모예금주" → "····8888 데모예금주(수정)"·verifiedAt 갱신·주 계좌 유지 · 셀러 3·4·5 실데모 계좌 mutation 0(`mutations` = PUT 1건뿐) · 상세 응답 본문 8건에 `\d{3}-\d{3}-\d{6}` 패턴·`accountNumber` 키 없음 · curl 상세 키 20·`bankAccounts[0]` 키 9(`accountHolder·accountNumberSuffix·bankCode·createdAt·id·isPrimary·status·updatedAt·verifiedAt`)·`primaryBankAccount` 키 6.
- 신규 의존성: 없음.

### §8 이월
- ~~BE `referencedBySettlement` 플래그(§1-A 3) 도입 시 수정 버튼 비활성 + 툴팁 전환~~(외부 검토 반영으로 완료) · 셀러 본인 계좌 등록 화면(Track 90·같은 command service) · 실명인증 상태(PENDING/REJECTED) 액션(현재 표시만) · 은행 코드 표준(금융결제원) 전환 시 `ADMIN_BANK_OPTIONS` 값 교체·기존 행 코드 마이그레이션 · 계좌 삭제(BE 없음·FK RESTRICT).

### 외부 검토 반영 (2026-09-19·BE D-188과 함께)
- **Q6 참조 플래그 도입 → 수정 비활성 정책 전환**: 종전 "화면 선판정 불가 → 전 행 활성 + 409"에서 BE 응답 `bankAccounts[].referencedBySettlement`(BE 409와 같은 판정) 기반 **행 단위 비활성 + 툴팁**으로 전환(89-D `terminable` 미리보기 선례와 일관). `canEditBankAccount(row) = !referencedBySettlement`·`hasReferencedBankAccount(rows)`(admin-seller-bank-view)·FE는 재계산하지 않음(응답이 SoT). 서버 409 처리는 유지(조회~요청 사이 변화 → 토스트 + stale 재조회).
- **안내 문구 최종안(지적 13·계좌 단위 의미)**: 행 툴팁 `SELLER_BANK_ACCOUNT_REFERENCED_TOOLTIP` = "정산 지급에 사용된 계좌입니다. 새 계좌를 등록한 뒤 주 계좌로 전환하세요." / 카드 하단 `SELLER_BANK_ACCOUNT_REFERENCED_NOTE`(참조 행이 하나라도 있을 때) = "정산 지급에 사용된 계좌는 수정할 수 없습니다. 수정이 필요하면 새 계좌를 등록한 뒤 주 계좌로 전환하세요." / 409 토스트 `SELLER_BANK_ACCOUNT_REFERENCED` = 카드 문구와 동일. 종전 `hasPaidSettlement`(셀러 정산 합계 기준·계좌 1개만 참조돼도 전체가 막힌 것처럼 읽힘) 제거.
- 테스트: vitest +1(수정 가능 판정·카드 안내 조건) → 363 · Playwright `admin-sellers ②` 참조 행 수정 비활성·툴팁·미참조 행 활성 단언 추가(mock에 플래그 2행) · 라이브(읽기 전용·mutation 0): 셀러 3 수정 비활성 + 툴팁 + 카드 안내 / 셀러 1(가짜 계좌·참조 0) 수정 활성·안내 없음(`playwright-report/step483-admin` 2장).
- §8 이월 갱신: "BE `referencedBySettlement` 플래그 도입 시 비활성 전환" 항목은 완료로 닫음.

## FE-42: 셀러 구성원 관리 화면 (2026-09-19)

BE 계약 Track 89-G D-189(`POST /admin/sellers/{slr_}/members` 201(`userPublicId` XOR `newUser`·사유 없음)·`DELETE …/members/{usr_}` 204 + `{reason}`·`PATCH …/members/{usr_}/role` 204·상세 `members[].joinedAt`·입점 `ownerUserPublicId` null 허용·409 `SELLER_LAST_OWNER`/`MEMBER_ALREADY_WITHDRAWN`/`SELLER_USER_ALREADY_EXISTS`/`EMAIL_ALREADY_EXISTS`·422 `SELLER_MEMBER_INVALID_STATE`·404 `SELLER_MEMBER_NOT_FOUND`·502 `TEMPORARY_PASSWORD_DELIVERY_FAILED`이 SoT·본 항목에서 재기술하지 않음) · 브랜치 `feat/seller-members`(BE·FE 동일 브랜치·미커밋) · 정찰 `docs/track-89g/recon-report.md` · 외부 검토: 등급 A / BE와 함께 2라운드 진행(T1 인증 경계·가드 → T2 나머지).

### §1-A 갈림길·채택/기각 근거
1. **추가 2경로 UI = 한 다이얼로그 안 `v-tabs` 2탭("기존 회원 검색" / "새 계정 생성") + 공통 역할 select 【채택】 / β 다이얼로그 2개(버튼 2개) 【기각: 역할 선택·권한 안내·성공 후 처리가 같아 중복】 / γ 토글 스위치 【기각: 두 경로가 대등한 선택지라 탭이 의미에 맞음】** — 기존 회원 탭은 FE-39 운영자 등록·FE-40 입점 owner 검색과 같은 회원 목록 API(keyword·ACTIVE·size 10) 검색 → 선택. **이미 이 셀러의 구성원인 회원은 결과 항목을 비활성 + "이미 이 셀러의 구성원입니다"로 선택 자체를 막는다** — BE 409 `SELLER_USER_ALREADY_EXISTS`는 V12 user_id 단독 UK라 같은 셀러·타 셀러를 코드로 구분하지 못하므로(D-189 §1-A 7) 같은 셀러 여부는 화면이 members로 판정하고, 서버 409는 "타 셀러 소속(또는 화면이 오래됨)" 문구 + stale 재조회. 새 계정 탭은 이메일·이름·휴대폰 3필드(BE `newUser`와 1:1·`validateNewUserInput` = BE @NotBlank·@Size·이메일 @Pattern + 휴대폰은 SMS 수신처라 `ADMIN_MEMBER_PHONE_PATTERN` 국내 휴대폰 형식) + 안내 3문장. 확인 버튼 라벨을 경로별로 바꾼다("구성원 추가" / "계정 생성 후 추가"). 추가는 사유 없음(D-189 §1-A 5).
2. **마지막 활성 대표 미리보기 판정 = BE `assertNotLastActiveOwner`와 동일**: `lastActiveOwnerBlockedReason(members, target)` — 대상이 활성(userPublicId 있음 ∧ withdrawnAt 없음) OWNER이고 그 외 활성 OWNER가 0명일 때만 제거·역할 변경 버튼 비활성 + 툴팁. 탈퇴·삭제 OWNER는 세지 않으며 탈퇴 OWNER 행 자체의 제거는 허용(셀러 2 정리 경로·D-189 §1-A 2). 판정 근거는 상세 응답 `members`뿐이고 FE는 별도 조회 없이 같은 정의(`isActiveMember` = `loginableMemberCount` 조건)로 센다 — 조회~요청 사이 변화는 서버 409가 막고 토스트 후 stale 재조회(89-D terminable·89-F referencedBySettlement 선례·"미리보기 = 실제"). vitest가 BE IT T5와 같은 경계(활성 OWNER 1 + 탈퇴 OWNER / 유일 탈퇴 OWNER / 활성 OWNER 2 / 탈퇴 OWNER 2)를 고정하고, 라이브에서 셀러 3(활성 OWNER 1 → 비활성·툴팁)·셀러 2(탈퇴 OWNER → 제거 활성)로 실측했다. **탈퇴 회원의 역할 변경은 화면에서 막는다**(툴팁 "탈퇴한 회원의 역할은 변경할 수 없습니다(제거만 가능)") — BE는 허용하지만 로그인 불가 계정의 역할은 의미가 없고 SMS fallback도 탈퇴자를 제외(D-189 §1-A 9)해 화면이 더 보수적. soft-delete 구성원(userPublicId 없음)은 경로로 지정할 수 없어 두 버튼 모두 비활성(현재 데이터 0·user soft-delete 경로 없음).
3. **역할이 권한에 영향 없다는 안내(`SELLER_MEMBER_ROLE_NOTICE`·추가·역할 변경 다이얼로그 공통) 최종안**: "역할은 구분·표시용입니다. 현재 모든 구성원이 같은 셀러 기능(상품·송장·클레임·정산 조회)을 사용하며, 역할에 따라 제한되지 않습니다. / 대표(OWNER)는 셀러 연락처가 없을 때 정산 안내 SMS의 대체 수신처가 되며, 마지막 활성 대표는 제거·강등할 수 없습니다." — D-189 §1-A 2(데이터만 유지·권한 분기 없음·셀러 API 16개 전부 셀러 단위 판정) + OWNER가 실제로 쓰이는 두 지점(SMS fallback·가드)을 그대로 적는다. 역할 라벨 대표/매니저/담당자(V11 시드 표시명에서 "판매자" 접두 제거)·배지 톤 대표 info / 나머지 neutral. 역할 변경 select는 현재 역할을 제외해 BE 422를 화면에서 막는다.
4. **새 계정 생성 안내(`SELLER_MEMBER_NEW_USER_NOTICE`·info alert 3문장) 최종안**: "입력한 이메일로 새 회원 계정이 만들어지고, 일반 회원(구매자) 자격도 함께 부여됩니다. 이 계정은 회원 목록에도 표시됩니다. / 임시 비밀번호는 입력한 휴대폰으로 SMS 발송됩니다. 화면에는 표시되지 않으며, 첫 로그인 후 비밀번호를 변경해야 합니다. / SMS 발송에 실패하면 계정은 만들어지지 않습니다(계정·구성원 등록이 함께 취소됩니다)." — D-189 §1-A 3(BUYER 겸직·임시 비밀번호 SMS·변경 강제·502 전체 롤백)과 1:1. 임시 비밀번호는 응답에 없어 화면 어디에도 없다(E2E·라이브 응답 본문에 password 키 없음). 이메일 중복 409는 이메일 필드 오류("…기존 회원이면 '기존 회원 검색'으로 추가하세요"), SMS 실패 502는 휴대폰 필드 오류로 되돌린다.
5. **제거 확인(`SELLER_MEMBER_REMOVE_NOTICE`·warning) 최종안**: "**제거 즉시 이 계정의 셀러 로그인과 셀러 기능 접근이 차단됩니다(이미 로그인한 세션도 다음 요청부터 차단).** / 일반 회원(구매자) 계정·주문 이력은 그대로 유지되며, 필요하면 다시 구성원으로 추가할 수 있습니다." — D-189 §1-A 1(리졸버 매 요청 조회·credentials_changed_at 미호출·BUYER 세션 유지). 탈퇴 회원 행은 정리 성격이라 info 문구("탈퇴한 회원의 구성원 행을 정리합니다. … 접근 변화는 없으며 구성원 목록에서만 사라집니다")로 분기. 사유 필수(200자).
6. **409 코드별 문구(`toSellerMemberErrorMessage`)** — MEMBER_ALREADY_WITHDRAWN "탈퇴한 회원은 구성원으로 추가할 수 없습니다." / SELLER_USER_ALREADY_EXISTS "이미 다른 셀러에 소속된 회원입니다(한 회원은 한 셀러에만 …). 이 셀러의 구성원이면 화면을 새로 고치세요." / SELLER_LAST_OWNER "마지막 활성 대표(OWNER)는 제거·강등할 수 없습니다. …" / EMAIL_ALREADY_EXISTS / 422 SELLER_MEMBER_INVALID_STATE "이미 같은 역할입니다." / 404 SELLER_MEMBER_NOT_FOUND / 502 — 전부 서로 다른 문장(vitest가 집합 크기로 단언). 공용 `admin-error-message`에도 4코드 추가(다른 화면 폴백).
7. **입점 등록 순서 교체(D-189 §1-A 4)**: 1단계 사업자 정보(상호·대표자 필수) → 2단계 대표 계정(owner) 선택 **— 선택 사항**. 안내 "비워 두면 구성원 없이 등록되며, 등록 후 상세의 구성원 카드에서 기존 회원을 추가하거나 새 계정을 만들어 연결할 수 있습니다. 지정하면 그 회원이 대표(OWNER)로 연결됩니다". 확인 활성 조건에서 owner를 제거하고 `ownerUserPublicId: owner?.publicId ?? null` 전송·성공 토스트에 "구성원 없음" 병기·검색 결과 없음 문구에 "비워 두고 등록한 뒤 새 계정을 만들 수 있습니다". 회원 검색 UI·409 처리(사업자번호 필드 오류·owner 문제는 지정 해제)는 유지.
8. **구성원 0명 = 정상 상태 표기**: 카드 하단 `SELLER_MEMBERS_EMPTY_NOTE` "구성원이 없어도 셀러는 정상 운영됩니다(상품·주문·정산은 관리자가 대신 처리). 셀러가 직접 로그인해 처리하려면 구성원을 추가하세요."(구성원 0일 때만) · 헤더 "구성원 (N) · 활성 M명" · 활성 0이면 추가 버튼 `flat` 강조 · 상단 warning("로그인 가능한 구성원이 없습니다 …")에 "아래 구성원 카드에서 추가할 수 있습니다" 추가. 탈퇴·삭제 행은 `adm-member-row--inactive`(회색 텍스트·옅은 배경).
9. **회원 탈퇴 다이얼로그 셀러 소속 경고 — STEP 498에서 반영(FE-42 시점엔 BE 응답에 소속 정보가 없어 제안으로만 남겼던 항목)**: BE `AdminMemberDetailResponse.sellerMembership { sellerPublicId, companyName, roleCode, lastActiveMember }`(D-189 STEP 498 절·상세만·목록 없음) 추가 후, 회원 상세 탈퇴 다이얼로그(`AdminConfirmDialog` +`warningLines`/`warningEmphasis` optional prop·warning alert)에 조건부 경고. 문구(`withdrawSellerWarning`) — 일반: "이 회원은 {상호} 셀러의 구성원({역할})입니다. 탈퇴해도 셀러 소속은 유지되지만 로그인할 수 없게 됩니다." / `lastActiveMember`(회원이 활성이고 셀러 활성 구성원이 본인뿐·역할 무관 — 로그인은 seller_user 존재만으로 결정)면 한 줄 추가·굵게: "탈퇴하면 이 셀러에 로그인할 수 있는 구성원이 없어집니다." **확인 버튼은 그대로 활성(차단 없음·확정 4)**. 검증: vitest +1(문구 조합 4케이스) · Playwright `admin-members ⑤` 경고 2줄·굵은 줄·확인 활성 후 409 경로 그대로 · 라이브 셀러 3 OWNER 상세 경고 2줄 / 일반 구매자 경고 없음(탈퇴 실행 0·`step498-live.mjs`).
10. 트랩: (1) Git Bash에서 `<<'EOF'` 히어독 안에 `'`가 많은 TS 코드를 넣으면 "unexpected EOF" — 파일 Write 후 `sed r`로 삽입 (2) 전체 Playwright 동시 실행 시 무관 spec ① 첫 로드 플레이크 4건(FE-40·41과 같은 패턴) → 해당 4 spec 단독 12/12 (3) `v-tabs` 값 전환 시 필드 오류를 함께 비운다(`watch(mode)`) — 경로 간 오류 잔존 방지.

### §2 확정 구현 규칙
- `lib/constants/admin-seller.ts`(+`AdminSellerMemberRole` 3값·라벨·톤·옵션·newUser 길이 3·이메일 패턴·`SELLER_MEMBER_ROLE_NOTICE`·`SELLER_MEMBER_NEW_USER_NOTICE`·`SELLER_MEMBER_REMOVE_NOTICE`·`SELLER_MEMBER_LAST_OWNER_TOOLTIP`·`SELLER_MEMBERS_EMPTY_NOTE`) / `types/admin-seller.ts`(`AdminSellerMember.roleCode` 상수 유니온·`joinedAt` 필수·요청 3종·`AdminSellerProvisionRequest.ownerUserPublicId: string | null`) / `lib/admin-seller-member-view.ts`(`isActiveMember`·`activeOwnerCount`·`lastActiveOwnerBlockedReason`·`memberRoleChip`·`selectableRoles`·`validateNewUserInput`·`toSellerMemberErrorMessage`) / `composables/useAdminSellers.ts` +`addMember`·`removeMember`(DELETE+body)·`changeMemberRole` / `lib/admin-error-message.ts` +4.
- 컴포넌트: `AdminSellerMemberCard`(목록·액션·세 다이얼로그 소유·`changed` → 상세 `load`) · `AdminSellerMemberAddDialog`(v-tabs 2경로) · `AdminSellerMemberRemoveDialog` · `AdminSellerMemberRoleDialog` · `AdminSellerProvisionDialog`(순서 교체·owner 선택) · 페이지 `members/sellers/[id].vue`(인라인 구성원 표 → 카드 컴포넌트 교체·경고 문구 1줄·전이·수정·계좌 카드 무변경).
- 테스트: vitest `admin-seller-member-helpers`(5: 활성 판정·마지막 활성 대표 경계 6케이스·배지/선택지·입력 검증·코드별 문구 유일성) · Playwright `admin-sellers ③`(구성원 3행 배지·등록일·활성 2명 → 마지막 활성 대표 제거/역할 비활성 툴팁·탈퇴 OWNER 제거 활성/역할 비활성·매니저 활성 → 추가 다이얼로그 기존 회원 검색·선택·역할 안내 → 새 계정 탭 안내 3문장·필수 입력 후 활성 → 역할 변경(현재 역할 선택지 없음·사유 필수) → 제거(즉시 차단·계정 유지·사유 필수·탈퇴 행 정리 문구) · POST/DELETE/PATCH 0) · `①` 입점 단계 순서 단언 교체(사업자 폼 먼저·owner 없이 확인 활성·선택 후에도 활성·지정 해제).
- 검증(실측·컨테이너 pnpm): typecheck 0 · vitest 51 files 368(363 → +5) · Playwright 전체 1회차 58/67(무관 spec ① 첫 로드 플레이크 4·연쇄 skip 5) → 4 spec 단독 12/12 = **67/67**(66 → +1) · **89-D·89-F 회귀**: `admin-sellers ①·②`(목록·상세·전이·수정·입점·계좌 카드·등록/수정/전환 다이얼로그) GREEN·전이·수정·계좌 컴포넌트 코드 무변경 · 픽셀 track89f vs track89g **12/12 diff 0** · 라이브(`playwright-report/step497-live.mjs`·실 BE·`step497-admin` 10장): 셀러 3(데모 리빙샵) 활성 OWNER 1 → 제거·역할 변경 비활성 + 툴팁 "마지막 활성 대표(OWNER)는 …" / 셀러 2(T75 옵션샵) 탈퇴 OWNER만 → 회색 행·"활성 0명"·제거 활성·역할 변경 비활성 툴팁·제거 다이얼로그 정리 문구·상단 경고 / 셀러 6(89G 라이브검증 셀러) 구성원 0 → "소속 구성원이 없습니다" + 정상 운영 안내 + 추가 버튼 강조 → 추가 다이얼로그 검색 1건·새 계정 탭 안내 3문장·역할 안내 / 입점 다이얼로그 사업자 폼 먼저·owner 없이 확인 활성 · **쓰기 2건(셀러 6 한정·순 변화 0)**: 기존 회원 live89g@local.test STAFF 추가 POST 201 → 카드 1행·활성 1명·경고 사라짐 → 제거 DELETE 204(사유) → 0명 복귀 · 데모 셀러 1~5 mutation 0 · curl 상세 `members[0]` 키 6(`email·joinedAt·name·roleCode·userPublicId·withdrawnAt`)·응답 본문에 password 키 없음 · DB seller_user 셀러 6 = 0·audit CREATE/DELETE 2건.
- 신규 의존성: 없음.

### §8 이월
- ~~회원 탈퇴 다이얼로그 셀러 소속 경고(§1-A 9)~~(STEP 498 반영) · 회원 상세 → 소속 셀러 링크(`sellerMembership.sellerPublicId`로 가능·요구 없음) · 셀러 역할별 권한 분기 시 안내 문구 교체(Track 90) · 구성원 정렬(현재 seller_user id 순) · 미가입 셀러 구성원의 비밀번호 변경 화면(구매자 FE `/mypage/password`뿐·Track 90 셀러 FE).

### 외부 검토 R2 반영 (2026-09-19·조회·역할 변경·입점 변경·SMS·FE)
- **외부 검토 R2: 등급 A / 지적 7건 중 수용 3(지적 4·5·15), 기각 3(지적 2·3·13), 자체 점검 1(지적 19). BE 변경 없음.**
- **지적 4 수용 — BE·FE 검증 제약 1:1 대조**(`AdminSellerMemberNewUserRequest` ↔ `constants/admin-seller.ts`·`validateNewUserInput`·`User` 컬럼): 이메일 = BE `@Pattern ^[^@\s]+@[^@\s]+\.[^@\s]+$`·`@Size(254)` ↔ FE 동일 정규식·254 **일치** / 이름 = BE `@Size(50)` ↔ FE 50 **일치** / 휴대폰 = BE `@NotBlank·@Size(20)`(형식 없음) ↔ FE 20 + `ADMIN_MEMBER_PHONE_PATTERN`(국내 휴대폰) → **FE가 더 엄격(의도)**: 임시 비밀번호 SMS 수신처라 발송 불가 번호를 화면에서 먼저 거른다(BE 허용값 중 국내 휴대폰이 아닌 번호는 SMS를 받을 수 없어 실질 손실 없음). `User` 엔티티 email 254·name 50·phone 20 = DTO 일치. 조치: 상수·헬퍼 주석의 "BE와 동일"을 "이메일·이름 동일 / 휴대폰은 BE보다 엄격 + 사유"로 정정(값 변경 없음·vitest 무변경).
- **지적 5 수용 — `validateNewUserInput` 주석**: "빈 객체면 통과" → "세 필드 모두 필수이며 BE 제약을 로컬에서 선검증(빈 값·형식 위반 필드마다 오류·오류 없으면 빈 객체)"로 실제 동작대로 정정.
- **지적 15 수용 — SMS 발송 실패 오류 배치**: `TEMPORARY_PASSWORD_DELIVERY_FAILED`를 휴대폰 필드 오류에서 **다이얼로그 전역 오류 alert**(`seller-member-submit-error`·입력 유지·재제출 시 초기화)로 옮기고 문구를 원인 비단정형으로 조정: "임시 비밀번호 SMS 발송에 실패해 계정을 만들지 않았습니다. 잠시 후 다시 시도하거나 휴대폰 번호를 확인해 주세요."(계정 미생성·롤백 사실 유지).
- **지적 19 자체 점검(자료 제외 2컴포넌트·검토자 체크리스트) — 전 항목 충족·수정 없음**: `AdminSellerMemberRemoveDialog` — 사유 빈 값 차단(`confirmDisabled` :39 + submit 내 trim 검사 :43-47) · DELETE body `{reason}`(:50 `removeMember(…, { reason: trimmed })`) · `SELLER_LAST_OWNER`·`SELLER_MEMBER_NOT_FOUND` → `toSellerMemberErrorMessage` 토스트 + `emit('stale')`(:62-63·부모 `onDone` → 상세 재조회) · 성공 `emit('done')`(:54) · 중복 submit 방지(:42 `submitting` 가드 + `:loading`/`:disabled` :103 + `:persistent` :72) · 대상 null 안전(:42 `!props.target?.userPublicId` 조기 반환·표시는 `lastTarget` 보관 :26-27로 닫힘 애니메이션 중 공백 방지). `AdminSellerMemberRoleDialog` — 현재 역할 제외(`selectableRoles` :45·`role` 초기 null·reset on open) · PATCH body `{role, reason}`(:57) · 사유 필수(:46·:51-54) · 같은 역할 미요청(선택지 자체에 없음) · `SELLER_LAST_OWNER`/`SELLER_MEMBER_INVALID_STATE`/`SELLER_MEMBER_NOT_FOUND` → 토스트 + stale(:67-68) · 중복 submit·null 안전(:49).
- **기각 3건**: 지적 2(`activeOwnerCount` 미사용) — vitest가 BE의 "활성 OWNER" 집합 정의를 코드로 고정하는 용도(경계 6케이스)이며 제거하면 그 정의가 코드에서 사라짐 / 지적 3(`member !== target` 중복 조건) — DB UK(user_id 단독) 전제에서 publicId 비교가 안전장치이고 제거해도 얻는 것이 없음 / 지적 13(잘못된 role 문자열 런타임 방어) — 검토자도 "TS 타입 책임 범위를 넘어가며 현재는 문제로 볼 필요 없음"으로 결론.
- 검증: typecheck 0 · vitest 369(불변) · Playwright 62 passed + admin-shell 5 env skip = 67(1회차 재시작 직후 첫 로드 플레이크 6 → 2회차 전체 62/62) · 픽셀 track89g2 vs track89g3 12/12 diff 0 · BE 변경 없음.
- 재검토 필요 여부: 설계 변경 없음(주석 2·오류 배치 1·문구 1) → 재검토 불필요 판단(최종 판단은 운영자).

## FE-43: 데모 로그인 서버 라우트 전환 (구매자·공통 코어화) (2026-09-19)

배경: 구매자 데모 로그인 자격증명(`NUXT_PUBLIC_DEMO_EMAIL/PASSWORD`)이 `runtimeConfig.public`에 있어 클라이언트 번들·SSR payload에 평문으로 실렸다(개발자 도구 실측). 관리자 데모(FE-23)는 이미 서버 라우트(`/_admin-demo/*`)가 비공개 runtimeConfig로 BE 로그인을 대행하는 방식이었으므로, 구매자도 같은 방식으로 전환하고 코어를 공통화한다.

결정:
- **공통 코어 `frontend/server/lib/demo-login.ts`**(Nitro 무의존·vitest 직접 검증): `layers/admin/server/lib/admin-demo-login.ts`를 이동·일반화. `DemoCredentials { email, password }`·`loginAsDemo(credentials, role, apiInternalBase, fetcher)`·`role: 'BUYER' | 'ADMIN' | 'SELLER'` 인자화·`fetchBackendLogin`(Node fetch·비-2xx throw) 라우트 공용. 미설정 404·BE 실패 401 단일 응답·실패 사유 은닉·자격증명 미반환 원칙은 FE-23 그대로.
- **구매자 라우트 `/_demo/login`(POST·`{ token, passwordChangeRequired }`)·`/_demo/status`(GET·`{ enabled }`)** 신설. `runtimeConfig.public.demoEmail/demoPassword` **삭제** → 비공개 `buyerDemoEmail/buyerDemoPassword`(기본 ''=비활성). `stores/auth.ts` `loginDemo()`는 `login()`과 같은 `storeLoginResponse`로 토큰·`password_change_required` 쿠키를 저장하고, `/login`은 `onMounted` status 조회로 `demoEnabled`일 때만 버튼(`data-testid="demo-login"`)을 렌더한다(`/admin/login`과 동형). 관리자 라우트 응답은 `AdminDemoLoginResponse { token }`으로 반환 타입을 고정해 `{ token }`만 투과(BE 필드 추가 시 실수 방지).
- **env 키 개명**: `NUXT_PUBLIC_DEMO_*` → `NUXT_BUYER_DEMO_*`(compose `zslab_mall_frontend.environment` 동기 치환), `NUXT_PUBLIC_SELLER_*` → `NUXT_SELLER_DEMO_*`(개명만·소비처는 Track 90-A). `.env.example` 주석을 "서버 라우트 전용·비공개 runtimeConfig·`NUXT_PUBLIC_` 접두사 금지"로 교체.
- **rate limit `frontend/server/lib/demo-rate-limit.ts`**: 고정 윈도우 60초/키당 10회(슬라이딩 아님)·모듈 스코프 Map·만료 엔트리는 접근 시 정리(타이머 없음). 키 = `${라우트식별자}:${ip}`(`demo-login:` / `admin-demo-login:`·라우트별 독립 카운터)·IP는 `getRequestIP(event, { xForwardedFor: true })`(gateway_nginx 경유 전제·미확보 시 `'unknown'` 단일 버킷)·초과 시 429 + `Retry-After`(남은 윈도우 초·h3 typed header라 number)·본문에 사유·자격증명 힌트 없음. `status.get.ts`(boolean만)에는 미적용.

### §1-A 갈림길·채택/기각 근거
1. **rate limit 위치 = α 애플리케이션 메모리(Nitro 라우트 내 순수 함수) 【채택】 / β gateway nginx `limit_req` 【기각】** — β는 nginx.conf 단일 파일 수동 편집·inode 트랩(D-148)으로 재현성·테스트성이 열위이고 외부 스택이라 이 저장소의 vitest·Playwright로 검증할 수 없다. α는 단일 인스턴스 전제(다중 인스턴스 전환 시 Redis 이관·§8)이며 데모 라우트 2개에 한정된 저부담 가드로 충분.
2. 그 외 항목(코어 추출·라우트 신설·public 키 제거·status 조회 기반 버튼 노출)은 대안 검토 없음 — FE-23 선례를 구매자에 1:1 확장.

### §2 확정 구현 규칙
- 데모 자격증명은 **비공개 runtimeConfig에만** 둔다(`public` 금지). 브라우저 코드(store·page)는 `/_demo/*`·`/_admin-demo/*` 경로 상수만 알고 값은 모른다(`lib/constants/auth.ts` `DEMO_STATUS_PATH`/`DEMO_LOGIN_PATH`).
- 서버 라우트는 코어(`demo-login.ts`·`demo-rate-limit.ts`) 결과를 `createError`로 매핑만 하는 얇은 어댑터. 테스트는 코어 단위(`test/admin/admin-demo-login-server.spec.ts` 4·`test/server/demo-rate-limit.spec.ts` 4).
- 번들 미노출 검증 절차: `.env` 값은 PowerShell 메모리 내 비교로만 다루고 **건수만** 기록(값·키 라인 출력 금지). 양성 대조(`.env` 내 1건) 후 prod `.output` 전 파일·`.nuxt`·dev SSR HTML·dev 변환 모듈(pages/login.vue·stores/auth.ts·constants/auth.ts·entry) 검색.
- 검증(실측·컨테이너 pnpm): typecheck 0 · vitest 52 files 374(369 → +5) · 라이브(실 BE): `/login` 데모 버튼 → `GET /_demo/status 200 {enabled}` → `POST /_demo/login 200 {token,passwordChangeRequired}` → `/` 복귀·`auth_token` JWT 쿠키 · `/admin/login` 데모 → `/admin`·`admin_token` · `/_admin-demo/login` 응답 키 `[token]`만(불변) · 같은 IP 연속 호출 10회 200 → 11회차부터 429(구매자·관리자 라우트 각각 독립)·`Retry-After` 56/58 · `/_demo/status` 12회 연속 200(미적용) · **번들 미노출**: `.output` 1,218파일 0건·`.nuxt` 0건·dev SSR HTML 0건·dev 모듈 0건(비밀번호·이메일 모두) · 컨테이너 healthy.
- 트랩: (1) 컨테이너 `pnpm typecheck`·`pnpm build`(nuxt prepare)가 dev `.nuxt`를 덮어 dev 서버가 깨짐 → 매번 `docker restart zslab_mall_frontend` (2) h3 typed header `Retry-After`는 number — `String()` 래핑 시 TS2345 (3) dev 변환 모듈 경로는 `/_nuxt/app/app/...`(컨테이너 root `/app` + srcDir `app/`) (4) `.env` 편집은 Edit 도구(사전 Read 필수→값 노출) 대신 메모리 내 문자열 치환(줄 시작 키명만·개행 그대로·출력 없음).
- 신규 의존성: 없음.

### 외부 검토 반영 (2026-09-19)
- **외부 검토: 등급 A / 지적 7건 중 수용 1·부분 수용 1·기각 5.**
- **수용 — `/_demo/login`·`/_admin-demo/login` rate limit 부재**(관리자 데모가 인증 없이 실제 ADMIN JWT를 무제한 발급) → 위 결정 4항·§1-A 1.
- **부분 수용 — 관리자 응답 타입 명시**: `AdminDemoLoginResponse { token }` 추가·라우트 반환 타입 명시. projection 코드는 현행 유지.
- **기각 5건**: 404·401 분기(미설정 노출) — `/status`가 이미 `enabled`를 공개하므로 추가 정보 없음 / role 문자열 — `DemoRole` union + 라우트 literal + BE `ROLE_MISMATCH` 3중 방어로 충분 / `onMounted` flicker — 보안 무관·관리자 로그인과 동일 패턴(SSR 조회 전환은 §8 UX 이월) / JSON `{ token }` 응답 — 기존 non-httpOnly 쿠키 설계 그대로이며 별도 이월 등록분 / 구키(`NUXT_PUBLIC_DEMO_*`) 잔존 — `.env` 개명 완료·신규 코드에 영향 없음(docs 내 옛 키명 언급은 이력).

### §8 이월
- 다중 인스턴스(수평 확장) 전환 시 rate limit 저장소 Redis 이관 · gateway_nginx의 `X-Forwarded-For` 전달 여부 운영 실측(미전달이면 `'unknown'` 단일 버킷으로 전체 차단 위험) · 데모 버튼 SSR 조회 전환(UX·`onMounted` flicker) · `NUXT_SELLER_DEMO_*` 소비처(Track 90-A).
- 운영 실측 후속(FE-43a·2026-09-19): 클라이언트 위조 X-Forwarded-For로 rate limit 우회 확인(nginx $proxy_add_x_forwarded_for가 위조값 뒤에 실 IP를 append·`getRequestIP(event, { xForwardedFor: true })`는 첫 값을 읽어 매 요청 새 버킷) → 양 라우트 `getRequestIP(event)` 소켓 IP로 교정(단일 gateway 전제·다단 구성 시 재검토·`unknown` 폴백 유지) · 회귀 `test/server/demo-rate-limit-client-ip.spec.ts` 4 · 실측: 컨테이너 내부·gateway_nginx HTTPS 경유 모두 위조 XFF 회전 11회차 429 유지 · vitest 378.
- 운영 후속(FE-43b·2026-09-19): 키가 gateway_nginx 컨테이너 IP(소켓 remoteAddress)라 클라이언트별이 아닌 라우트별 전역 버킷으로 동작(남용 억제 목적엔 부합) → 동시 방문자 고려해 한도 60s/10회 → **60s/30회** 조정(`RATE_LIMIT_MAX_ATTEMPTS`·테스트 문구·경계는 상수 참조) · 클라이언트별 제한은 nginx `X-Real-IP` 참조 전환으로 이월(getRequestIP 방식 불변).

## FE-44: 셀러 패널 레이어 골격·독립 인증 (Track 90-A) (2026-09-20)

배경: 관리자(FE-22·`layers/admin`·`admin_token`)·구매자(루트 `app/`·`auth_token`)에 이어 셀러 패널을 신설한다. 세 역할은 같은 회원 계정이 각각 다른 role 토큰을 받을 수 있으므로(STEP 505 실측) 세션이 독립이어야 하고, 최우선 요구는 **관리자 레이어 무영향**(관리자 Playwright·픽셀 전량 그대로 통과)이다. 화면 기능(주문·상품·정산)은 90-B 이후이며 이 트랙은 골격·인증까지만.

결정:
- **`frontend/layers/seller` 신설**(`nuxt.config.ts` 필수·`/seller/**` ssr:false + `X-Robots-Tag: noindex, nofollow`·비공개 `sellerDemoEmail/Password`). UI 스택은 **Vuetify(관리자 스타일 확장)**·자체 `createVuetify` 인스턴스·자체 테마 **primary teal `#0D9488`**(밴드 연한 청록)·설치 플래그 `__sellerVuetifyInstalled`.
- **세션 `seller_token` path=`/seller`·maxAge 3600**(`stores/sellerAuth.ts`·명시 import). 응답 role≠SELLER 저장 거절. `auth_token`(path `/`)·`admin_token`(path `/admin`)과 **3중 세션 공존**(path 스코프·로그아웃 상호 무간섭).
- **`/seller/login` + 셀러 데모** `/_seller-demo/login`(POST·`{ token, passwordChangeRequired }`)·`/_seller-demo/status`(GET). 공용 코어 `server/lib/demo-login.ts`를 role `SELLER`로 재사용·rate limit 키 `seller-demo-login`(라우트별 독립 버킷)·status는 미적용. 실패는 단일 문구(사유 은닉·상태 차단 D-190 포함).
- **BE 응답 분기(`useSellerApi`·D-190)**: 401(만료·무효·PENDING/TERMINATED·소속 없음) → `seller_token`만 제거 후 `/seller/login` / **403 `SELLER_SUSPENDED` → 세션 유지 + 레이아웃 상단 정지 안내 배너**(`sellerAuth.suspended`·조회는 계속 가능) / 그 외 403·4xx·5xx는 호출부로 throw.
- **`passwordChangeRequired` 구매자형 강제(D-3)**: `seller_password_change_required` 쿠키(path `/seller`) → `seller` 미들웨어가 `/seller/settings/password` 외 진입을 차단. 이번 트랙은 안내 placeholder(로그아웃 탈출구 + 구매자 `/mypage/password` 새 탭 링크 — 같은 회원의 user 단위 플래그라 구매자 경로 변경으로 해소됨)만 두고 실제 폼은 90-D.
- **사용자 영역 수정은 `app/lib/password-change-guard.ts` 1줄(D-4)**: `/admin` 예외 → `/admin`·`/seller` 예외(buyer 임시 비밀번호 상태가 독립 세션인 셀러 영역을 막지 않도록).
- 메뉴(대시보드/주문·배송·클레임/상품·재고/통계 매출·주문클레임·상품/정산/설정)는 `lib/constants/seller-menu.ts` 단일 소스. 화면이 없는 항목은 `to` 없이 비활성 렌더·라우트 미생성.

### §1-A 갈림길·채택/기각 근거
1. **UI 스택 = α Vuetify(관리자 동형) 【채택】 / β Tailwind+shadcn(구매자 동형) 【기각】** — 관리자 콘솔과 같은 운영 화면이라 스타일·컴포넌트 구조를 동형으로 유지한다. β는 `app/components/ui`가 2개뿐이라 테이블·다이얼로그·폼을 신규로 만들며 결국 Vuetify 룩앤필을 흉내 내게 된다.
2. **코드 공유 = α 복제(≈165줄·admin 무수정) 【채택】 / β 공용 레이어 즉시 승격 【기각】** — 두 번째 소비처 단계에서 추상화하면 쓰지 않을 구조를 만든다(과잉개발 회피). 승격은 세 번째 소비처 또는 복제본 괴리 시 판단(§8).
3. **vite-plugin-vuetify 등록 = α 셀러 config 중복 등록 【채택】 / β 관리자 등록에 암묵 의존 【기각】** — 플러그인 체인은 앱당 1개라 admin 등록만으로도 동작하지만, 관리자 변경 시 원인 불명으로 파손된다. 중복 등록은 `importPlugin`만 2개(styles 옵션 미지정)·`generateImports` 멱등(첫 플러그인이 `_resolveComponent` 선언을 지운 뒤라 두 번째 매치 0)으로 무해.
4. e2e 데모 테스트 단언: 계정 미생성 상태(90-A-2b 이전)에서는 **401·단일 문구 고정**(외부 검토 지적 반영 — 응답 코드 분기 단언은 계정이 사라져도 통과해 검증력 0). 계정 생성 후 200·홈 진입 단언으로 교체.

### §2 확정 구현 규칙 (격리 원칙)
- **`layers/seller` → `layers/admin` import 0건** — `test/seller/no-admin-import.spec.ts`(import·동적 import·require·CSS @import 구문 스캔·주석 언급 제외)가 CI `pnpm test`에서 강제한다(recon §10-4 β·ESLint 미도입).
- **`layers/admin` 무수정** — `git diff --name-only main -- frontend/layers/admin` 0 실측. PR 리뷰 시 동일 명령으로 확인.
- 공유는 **빌드타임 vite-plugin-vuetify(autoImport transform·앱 전역)뿐**. 런타임 공유 없음(Vuetify 인스턴스·테마·미들웨어·가드 전부 셀러 자체).
- 컴포넌트·유틸은 재사용이 아니라 **복제**. 접두사 `Seller*`(components·전역 auto-import 충돌 회피)/`useSeller*`(composables)/`seller-*`(named 미들웨어·레이아웃 파일명 = 이름이라 admin `vuetify`·`admin`과 겹치지 않게 `seller-vuetify`·`seller`)/CSS `slr-`·`.seller-*`.
- **leave guard 필수**(`lib/seller-leave-guard.ts`·레이아웃 2종 부착) — 셀러 밖(사용자·관리자)으로 SPA 이동 시 전체 새로고침. 없으면 seller→admin 이동에서 관리자 `ensureVuetify`가 자기 플래그 부재로 두 번째 인스턴스를 설치해 **두 테마가 한 문서에서 섞인다**(recon §10-3). 페이지는 반드시 `definePageMeta({ layout: 'seller' | 'seller-auth', middleware: [...] })` 명시(누락 시 `default.vue`가 감싸는 트랩).
- 트랩: (1) **실행 중 dev 서버 위에서 `pnpm typecheck`(nuxt prepare)를 돌리면 `.nuxt` 재생성으로 `#app-manifest` 미해석 오버레이 → 페이지 파손. e2e 전 `docker restart zslab_mall_frontend` 필수**(FE-43 트랩 1과 동일 원인·증상 상이). (2) **Playwright 기본 워커 8(16코어)로 전량 실행 시 관리자 데모 로그인이 FE-43b rate limit(라우트별 60s/30회)에 걸려 `/admin` 도착 타임아웃 9~20건 → `pnpm test:e2e --workers=2`로 분당 30회 이내 유지**(이전 트랙의 65/65는 rate limit 도입 전 결과).
- 검증(실측·컨테이너 pnpm): typecheck 0 · vitest 64 files **428**(378 → +50·`test/seller/` 11파일) · Playwright 73건(67 + 셀러 6·ADMIN_E2E·SELLER_E2E 주입·skip 0) **72/73** — admin-shell ⑤는 기존 결함(main 상태 stash 복원 후 3/3 동일 실패 실측·로컬 DB PENDING 셀러 배너가 API 응답 후 필터 카드를 밀어 bbox 2종·데이터 의존) · **관리자·사용자 픽셀 12장 diff 0**(변경 전 `track90a-pre` 대비) · `layers/admin` diff 0 · 라이브(seller01·실 BE): `seller_token@/seller`만 생성·`/_seller-demo/status {enabled:true}`·`POST /_seller-demo/login` 401(계정 미생성·예정)·SSR HTML 자격증명 0 · 셀러 스크린샷 기준선 `playwright-report/step552-seller` 10장·`step555-seller` 1장.
- 신규 의존성: 없음. compose `NUXT_SELLER_DEMO_EMAIL/PASSWORD` 추가·`.env.example` 동기화.

### 외부 검토 반영 (2026-09-20)
- **외부 검토: 등급 A / 2라운드.**
- **R1(인증 경계·세션): blocker 0** · 질문 1·4·5 통과 · 확인 요청 2건 중 1건 실제 결함으로 반영 — 임시 비밀번호 dead-end(placeholder라 상태 해소 불가·패널에 갇힘) → 로그아웃 버튼(탈출구)·구매자 `/mypage/password` 새 탭 링크·안내 문구. 추가 지적 — SUSPENDED 배너는 `markSuspended()` 호출까지만 검증 → `test/seller/seller-layout.spec.ts`(배너 미표시/표시·문구·반응형 전환 3건) 추가. 쓰기 실패 호출부 처리는 현재 셀러 화면 쓰기 호출부 0건이라 §8 이월.
- **R2(레이어 격리·Vuetify): blocker 0** · L1~L3는 증거 제시 누락으로 판정, 수정 없음.

### §8 이월
- **90-B 이후 각 쓰기 호출부가 403(SELLER_SUSPENDED 포함)을 toast/문구로 표시할 것 — 배너에만 의존 금지**(호출부가 삼키면 배너만 남는다).
- 셀러 비밀번호 변경 폼(90-D 설정·`PATCH /users/me/password`는 SELLER 토큰으로 호출 가능) → placeholder 교체.
- 공용 레이어 승격 판단(세 번째 소비처 또는 admin·seller 복제본 괴리 발생 시).
- SUSPENDED e2e(로컬 DB 셀러 상태 변경 필요·vitest만 보유).
- admin-shell ⑤ 데이터 의존 결함(PENDING 셀러 배너) / Playwright 워커 수·rate limit 충돌(config `workers` 고정 여부) → 별도 트랙.
- 데모 계정 `seller@zslab-mall.com` 생성·데모 e2e 200 단언 교체(90-A-2b) · 셀러 `me` 조회 API 부재로 상단바에 소속 셀러명·역할 미표시(90-B).

## FE-45: 셀러 데모 계정 생성·seed 입점 호출 정정 (Track 90-A) (2026-09-20)

배경: `seller@zslab-mall.com`은 `.env`(`NUXT_SELLER_DEMO_*`)에 키만 있고 DB에 없어 FE-44 데모 버튼이 401로 끝났다(정찰 실측). 같은 정찰에서 `scripts/demo-seed/seed.py` 입점 호출이 `ownerUserId`를 보내는데 API(Track 89-D)는 `ownerUserPublicId`로 바뀌어 있고, Jackson `FAIL_ON_UNKNOWN_PROPERTIES` 미설정(Spring Boot 기본 false)이라 **409가 아닌 무증상 실패**(미지 필드 무시 → 구성원 0명 셀러가 조용히 생성)임을 발견했다.

결정:
- **seed.py 입점 호출 `ownerUserId` → `ownerUserPublicId`**, 값은 가입 응답(`SignupResponse.userPublicId`)을 직접 사용(user id DB 조회 제거).
- **입점 직후 구성원 검증**: `seller_user` COUNT가 0이면 `SeedError`로 즉시 중단(메시지에 "owner 키가 API에서 무시됐을 가능성" 1줄). 키가 다시 어긋나도 무증상이 아니라 여기서 잡힌다.
- **셀러 데모 계정 신설**(기존 셀러 구성원 추가가 아님): 회원 `usr_01M2X6T65DE2C6RCA638V2C2AE`(데모 셀러) → 입점 `slr_01M2X6XBT41JPYZ4ZMKF4YC3AN`(데모 셀러샵·ACTIVE·`ownerUserPublicId`로 OWNER 동시 생성). 데모 사용자가 시드 셀러(데모 리빙샵 등)의 실제 데모 데이터를 건드리지 않도록 격리한다. 로컬만 생성·운영은 별도 세션(PROGRESS STEP 565 절차 메모).
- **e2e ⑤ 401 → 200·홈 진입 단언 교체**(`seller-shell.spec.ts`·`seller_token` path=/seller 생성·`auth_token` 미생성 포함).

### §1-A 갈림길·채택/기각 근거
1. **seed 수정 범위 = α 키 치환만 【기각】 / β 치환 + publicId 직접 사용 + 구성원 검증 【채택】** — α는 무증상 실패 재발 방지가 없다(다음 계약 변경 때 같은 방식으로 조용히 어긋난다). β는 가입 응답을 그대로 써 DB 왕복 1회를 없애고, 검증 1회(COUNT)로 fresh 실행에서 즉시 드러나게 한다.
2. **데모 계정 소속 = α 신규 입점 【채택】 / β 기존 셀러(데모 상점 1·시드 셀러 3~5)에 구성원 추가 【기각】** — β는 데모 사용자의 조작(90-B 이후 주문·상품·정산 쓰기)이 시드 데이터를 오염시킨다. 신규 셀러는 상품·주문 0에서 시작하며 데모 시나리오는 90-B 이후 별도로 채운다.

### §2 확정 구현 규칙
- seed의 입점 요청 키는 `SellerProvisioningRequest`를 SoT로 하며, 요청 후 `seller_user` COUNT 검증을 반드시 유지한다(멱등성·가드는 현행 유지·개선 범위 밖).
- 데모 계정 값(이메일·비밀번호)은 `.env`에서 메모리로만 전달·출력 금지(FE-43 §2 동일).
- 트랩: (1) **Nuxt dev 서버의 `/api/**` 프록시가 스크립트발 연속 POST에 200/400을 교대 응답한다**(같은 요청 4회 → 200·400·200·400·브라우저 경로는 Playwright 전량 정상). 스크립트로 API를 호출할 때는 **컨테이너 내부 `mall-backend:8080` 직결**(`docker exec zslab_mall_frontend node …`·env로 값 전달)로 수행할 것. 게이트웨이 HTTPS는 자체 서명이라 검증 해제가 필요해 사용하지 않는다.
- 검증(실측): `py_compile` 통과(seed 실행은 fresh DB 전용이라 미실행) · 계정: 상세 ACTIVE·members `[SELLER_OWNER]`·DB seller 7/seller_user 1행·role SELLER 로그인 200(`passwordChangeRequired` false)·`GET /seller/settlements` 200 · typecheck 0 · vitest 64 files 428 불변 · Playwright `--workers=2` 72/73(⑤ 셀러 데모 200·홈 진입 pass·admin-shell ⑤ 기존 결함) · 라이브 스크린샷 `playwright-report/step566-seller` 6장.

### 외부 검토
- 등급 C — 생략(FE 전용·BE 계약 무변경).

### §8 이월
- seed 멱등성(현재 fresh DB 전용·master 가드 409 즉시 중단) — 재실행 가능한 `firstOrCreate` 전환은 별도 트랙.
- Jackson `FAIL_ON_UNKNOWN_PROPERTIES` 전역 활성화 — 미지 필드를 400으로 거부하면 같은 유형의 무증상 실패를 API 계층에서 차단하지만 기존 클라이언트·테스트 회귀 범위가 커 별도 트랙.
- 운영 데모 계정 생성·운영 `.env` `NUXT_SELLER_DEMO_*` 추가(별도 세션·운영엔 Track 75 잔여 BUYER 계정 존재 가능성 실측 선행).

## FE-46: E2E 로그인 헬퍼 전환·레이아웃 테스트 데이터 통제 (2026-09-20)

배경: E2E 부채 2건(정찰 `docs/frontend/recon-report-e2e-debt.md`). ① `admin-shell.spec ⑤`(첫 렌더 카드 위치·FE-22h)가 로컬 DB의 PENDING 셀러 1건(seller 6·89-G 라이브 검증 잔재) 때문에 항상 실패 — main 상태 3/3 실패 실측. 셀러 목록의 승인 대기 배너(FE-40 결정 8)가 API 응답 후 필터 카드 위에 삽입돼 bbox가 144→208로 이동(첫 프레임 +84~173ms). ② E2E 73건이 데모 로그인 경로를 63회(관리자 53·구매자 9·셀러 1) 사용해 FE-43b rate limit(라우트별 60s/30회)과 충돌 → 기본 워커(16코어=8)로는 429 타임아웃, 매번 `--workers=2`를 지정해야 했다.

결정:
- **공용 로그인 헬퍼 `frontend/e2e/helpers/login.ts` `loginAs(page, role)`** — `POST /api/v1/auth/login`(BE 직접)으로 받은 JWT를 역할별 세션 쿠키에 `addCookies`(BUYER `auth_token`@`/` · ADMIN `admin_token`@`/admin` · SELLER `seller_token`@`/seller` · secure·lax·쿠키명은 각 스토어와 일치). BE 로그인에는 rate limit이 없어(backend `429` 미구현) 충돌이 구조적으로 사라진다. 자격증명은 `ADMIN_E2E_*`·`SELLER_E2E_*`·**`BUYER_E2E_*`(신설·`.env.example` 동기)** env로만 받고 미설정 시 skip.
- **13개 admin spec의 `loginByDemo` 6줄 복제 제거** → `loginAs(page, 'ADMIN')` + 명시 `goto`. claims·admin-shell ③④·seller-shell ⑥의 구매자 데모 클릭도 `loginAs(page, 'BUYER')`. 데모 버튼 사용처는 **데모 자체를 검증하는 2건(admin-shell ⑥·seller-shell ⑤)만** 남김. admin-shell ①②④⑤·seller-shell ③④의 폼 로그인은 유지(rate limit 무관·① 이 폼 흐름 검증).
- **구매자 데모 e2e 신설**(`password-change.spec.ts` "구매자 데모 로그인 (FE-43)" ③: 버튼 표시 → 클릭 → `POST /_demo/login` 200 → 홈 → `auth_token`@`/` 생성·`admin_token`/`seller_token` 미생성). FE-43 이후 구매자 데모 전용 케이스가 0건이던 검증 공백 해소.
- **admin-shell ⑤는 `status=PENDING&size=1` 조회를 `page.route`로 `totalCount:0` 고정**. 승인 대기 배너는 의도된 기능이자 이 테스트의 무관 변수이며, 측정 대상은 배너 유무가 아니라 셸 첫 페인트의 레이아웃 이동 없음(FE-22h 목적 유지). mock 시 2/2 pass 실측(정찰).
- **`playwright.config.ts` `workers`는 기본값(논리 코어 50%) 유지**. 헤더 주석("Smoke 1개 전용")을 현황으로 갱신하고 "데모 라우트 rate limit이 있으니 데모 검증 케이스를 늘릴 때는 워커 수 고려" 1줄 명시.
- **`toHaveURL` 직후 `captured.listQueries.at(-1)`를 읽던 레이스 3곳(admin-claims ⑥ refundStatus · admin-products ⑥ stockFilter OUT/LOW)을 `expect.poll`로 전환**(admin-claims ①의 기존 관례). `at(-1)` 읽기 25곳 중 나머지 22곳은 응답 의존 DOM 단언 뒤라 무수정.

### §1-A 갈림길·채택/기각 근거
1. **⑤ 처치 = A 배너 영역 고정 높이 【기각】 / B 배너 렌더 후를 기준선으로 【기각】 / C `status=PENDING` 조회 mock 【채택】 / C' 로컬 DB seller 6 정리 【기각】 / D 측정 대상을 정적 첫 카드로 변경 【기각】** — A는 테스트를 위해 제품 UI(승인 대기 0건일 때 64px 빈 공간)를 바꾸고 FE-40 UX와 상충. B는 기록기가 `addInitScript`(문서 로드 시점)라 재작성이 필요한 데다 첫 페인트 창을 측정에서 빼 FE-22h가 잡으려던 구간을 놓친다. C'는 PENDING 셀러가 다시 생기면(입점 등록 "승인 대기"·CI·타 PC) 재발. D는 측정 대상이 이미 3회 이동(플레이스홀더 소멸)했고 새 대상 위에 조건부 요소가 생기면 같은 결함. C는 spec 4줄·`admin-sellers.spec`과 같은 mock 관례·첫 페인트 창 측정 유지.
2. **로그인 전환 = β-1 API 직접 호출 헬퍼 【채택】 / β-2 β-1 + setup project·storageState 【기각】 / β-3 config `workers:2` 고정만 【기각】** — β-2는 로그인 API 호출을 역할당 1회로 줄이지만 쿠키 부재를 단언하는 5건(admin-shell ③④⑥·seller-shell ⑤⑥)에 컨텍스트 예외가 필요해 config·projects 재편 범위가 커진다(β-1이 선행 단계이므로 필요 시 후속). β-3은 원인(데모 로그인 63회)을 두고 증상만 덮으며 실행 시간도 3.6분에 고정. β-1은 헬퍼 1개 + 호출부 교체로 부채 2가 해소되고 로그인당 1.6~1.9s → 0.06~0.37s.
3. **구매자 데모 케이스 위치 = password-change.spec 【채택】 / smoke.spec 【기각】** — smoke는 헤더에 "인증 흐름은 범위 밖"(FE-15 STEP3)을 명시. 구매자 spec 중 `/login` 페이지를 다루는 곳은 password-change뿐.

### §2 확정 구현 규칙
- 세션은 `loginAs`가 쿠키로 심는다. **헬퍼는 페이지 이동을 하지 않으므로 호출 뒤 반드시 대상 경로로 `goto`**(데모 리다이렉트로 `/admin`에 도착하던 전제에 기댄 admin-dashboard ①·admin-claims ①은 `goto('/admin')` 추가). 구매자 SSR 페이지에 `page.route` mock을 적용하려면 `e2e/helpers/navigation.ts` `gotoClientSide`(Nuxt 라우터 `vueApp.$nuxt.$router.push`)로 클라이언트 내비게이션한다(홈을 먼저 열어 hydration 확보).
- `captured.*Queries.at(-1)`는 응답 의존 DOM 단언 뒤 또는 `expect.poll`로만 읽는다.
- 데모 버튼은 데모 검증 케이스 3건(admin-shell ⑥·seller-shell ⑤·password-change ③)에서만 클릭한다. 새 spec은 `loginAs`.
- 트랩: (1) **쿠키를 먼저 심으면 `/login`이 SSR 단계에서 `navigateTo(redirect)`로 리다이렉트**해 대상 페이지가 SSR 렌더(useFetch가 컨테이너→BE)되고 `page.route` mock이 적용되지 않는다 → `gotoClientSide` 헬퍼로 우회(claims.spec 6곳). (2) **`toHaveURL`은 내비게이션만 보장하고 API 도착은 보장하지 않는다** — `--workers=2`가 가려주던 레이스가 기본 워커 8에서 간헐 노출(4회 중 1회). (3) **컨테이너 재시작 직후 최초 1회 전량 실행은 콜드 로드(Vite dev 첫 컴파일)로 각 spec ①의 첫 `toHaveCount`(5s)가 0으로 다수 실패**(2세션 모두 재현·7~10건) → 2회차부터 정상. (4) 헬퍼 도입 후에도 `secure:true` 쿠키는 `http://localhost`에서 저장·전송됨(Chromium 보안 문맥 취급·실측).
- 검증(실측·컨테이너 pnpm): typecheck 0 · vitest 64 files 428 불변 · Playwright 전량 **74/74(73 + 구매자 데모 1) · 기본 워커 8 · 1.2분**(기준선 정찰: `--workers=2` 3.6분 · 71/73) · `--workers=2` 비교 74/74 2.6분 · 429 0건 · 레이스 전환 후 재시작 → 콜드 1회 → **웜 3회 연속 74/74**. 제품 코드(app·layers) 무변경.
- 신규 의존성: 없음.

### 외부 검토
- **C 생략** — 테스트·config·env·README 전용·제품 코드 무변경.

### §8 이월
- 컨테이너 재시작 직후 콜드 로드 실패(워밍업 goto 1회 또는 각 spec ① 첫 로드 expect timeout 상향).
- admin-settlements ① `networkidle` 30s 플레이크(STEP 557 기록·이번 4회 실행에선 미발생).
- 로컬 DB seller 6 PENDING 잔재(89-G 라이브 검증) 정리.
- CI에 Playwright 미포함(FE-15 STEP4 이월분·정찰에서 재확인·러너 4 vCPU=워커 2).
- storageState(setup project·로그인 API 역할당 1회) — β-2·필요 시 β-1 위에 후속.

## FE-47: 셀러 첫 실화면 4종 — 대시보드·주문·배송·정산 (Track 90-B-3) (2026-09-20)

배경: 90-A(FE-44)에서 만든 셀러 셸(레이어·독립 인증·Vuetify 자체 인스턴스)에 첫 실화면을 붙인다. 소비 API는 D-191(`GET /seller/me`·주문 품목·배송·송장 정정)·D-192(대시보드)·기존 정산 3종(Track 85)·기존 쓰기 2종(prepare-shipment·mark-delivered). 브랜치는 `feat/track-90b2-seller-dashboard`를 계속 써 BE 커밋(D-191·D-192) 위에 FE 커밋을 쌓아 "셀러가 첫 화면을 쓸 수 있다"는 완결 단위로 PR 1개에 묶는다.

결정:
- **공통 기반** — `lib/seller-error-message.ts`(ProblemDetail 코드 우선 → HTTP 상태 폴백 401/403/404/409/422 → 서버 detail → 일반 문구)·`useSellerToast`+`SellerToaster`(vue-sonner·관리자 복제·seller 레이아웃에만 배치)·의미 색상 `constants/semantic.ts`+`slr-chip`/`slr-grad` 토큰·`useSellerMe`(`useState('seller-me')` 공유·레이아웃이 1회 로드)를 셸에서 소비해 상단바에 상호·상태 chip·역할을 표시하고, `status=SUSPENDED`면 진입 시점에 `markSuspended()`로 배너를 켠다(FE-44 이월 "진입 시 자기 상태를 볼 경로").
- **403 SELLER_SUSPENDED는 쓰기 3종 다이얼로그(출고·배송완료·송장 정정)가 각각 danger 토스트로 직접 표시**(배너는 `useSellerApi`가 병행). FE-44 §8 이월분 종결 — 배너에만 의존하면 어느 동작이 막혔는지 알 수 없다. 분기는 공통: SUSPENDED → danger 토스트 + cancel / 422·404 상태 경합 → warning + stale(목록 재조회) / 409 송장 중복 → 송장번호 필드 오류(다이얼로그 유지) / 400 → fieldErrors.
- **대시보드(`/seller`)** — 요약 4(매출·환불·순매출·주문 건수) 캡션에 품목 축과 "내 품목이 포함된 주문 수(품목 수와 다를 수 있음)"를 명시. D-192의 `orderCount`(COUNT DISTINCT order)와 주문 목록 `totalCount`(품목 행)가 다르기 때문. 처리 대기 4 중 배송 대기만 `/seller/orders?status=PAID` 링크(품목 목록이 품목 단위라 정확)·클레임(90-D)·재고(90-C)·정산 예정(PENDING 404)은 링크 없이 힌트 문구. 차트 2종은 `dailyTrend` 단일 배열에서 파생(`SellerChart`·apexcharts 동적 import). 최근 클레임은 상세 화면이 없어 링크하지 않음(90-D). 기간은 로컬 상태(프리셋 7/30/90 + 직접 입력·92일 클라이언트 검증·BE 400 예방).
- **주문(`/seller/orders`·`/[id]`)** — 품목 행 단위 목록(URL query 단일 소스: status·paid_at 기간·keyword·page·size·정렬은 결제일 최신순 고정)·상세에 배송지 전체(마스킹 없음·출고 라벨용)와 원 발송 배송 상태·출고 다이얼로그(택배사 4값·송장 ≤100·PAID 행/상세 양쪽 진입).
- **배송(`/seller/deliveries`)** — 출고는 주문 화면, 배송완료·송장 정정은 배송 화면. 출고 대상(PAID 품목)은 Delivery 행이 없어 배송 목록에 나오지 않으므로 **양쪽 화면에 진입점 안내**(배송 상단 info alert → 주문 `?status=PAID` 링크 / 품목 상세 배송 카드 → 배송 화면 링크). 상세 다이얼로그 없음(셀러 배송 상세 API 부재·행에 필요한 정보가 다 있음)·행 메뉴는 SHIPPING만(배송완료는 원 발송만·송장 정정은 회수도 가능).
- **정산(`/seller/settlements`·`/[id]`)** — 관리자 `AdminSettlementTable`·`AdminSettlementItemTable`을 `Seller*`로 복제(import 금지)·읽기 전용(액션 없음). PENDING은 BE가 404로 숨기므로 목록에 없는 게 정상이며, `GET /seller/me.pendingSettlementCount`로 "확정 전 정산 N건 = 대시보드 정산 예정과 같은 건수"를 안내 문구로 설명. 상세 404는 "아직 확정되지 않은 정산" 안내.
- **배송 표는 1280px 오버플로를 피해 택배사를 송장 셀 캡션으로 병합(8컬럼)**. 주문 표 7컬럼·정산 표 9컬럼.
- 메뉴(`seller-menu.ts`) 4종 활성(대시보드·주문·배송·정산)·비활성 7(클레임·상품·재고·통계 3·설정). 신규 43파일(layers/seller 40·e2e 4·test 8·수정 8)·`layers/admin` 무수정.

### §1-A 갈림길·채택/기각 근거
- 대안 검토 없음 — 화면 구성은 D-191·D-192의 계약과 FE-44 격리 원칙(복제·접두사·import 0)에서 파생. 갈림길은 배송 표 컬럼 병합뿐이며(9컬럼 유지 → 1280px 가로 스크롤·관리 컬럼 화면 밖 / 택배사 캡션 병합 【채택】) 가로 스크롤 대비 명백한 우위.

### §2 확정 구현 규칙·트랩
- **로컬 backend는 `gradle bootRun` 상주(Dockerfile.dev)라 브랜치의 BE 변경이 반영되지 않는다** → 셀러 API 404(대시보드 "서버 오류" 표시)로 나타남. `docker restart zslab_mall_backend`(헬스 ~2분)로 해소. BE+FE를 한 브랜치에 쌓는 전략에서 반복될 트랩.
- **픽셀 기준선은 재시작 직후 캡처를 기준으로 삼는다.** e2e 직후 warm 상태 캡처 2회는 login·products 네이티브 컨트롤(검색 input 테두리·정렬 select 글리프) AA 노이즈 25~317px(≤0.02%)가 섞였고, 이번 변경과 무관함을 stash 복원 후 diff 0·사용자 페이지 CSS 누수 프로브(vuetify/sonner/slr 시트 0) 0으로 확인 → `docker restart` 직후 재캡처 `track90b3c` 12장 diff 0.
- v-dialog(VOverlay) vitest는 `window.visualViewport` stub 필요(`vi.stubGlobal`)·`v-textarea auto-grow`는 높이 계산용 textarea가 하나 더 렌더돼 Playwright는 `.first()`.
- 시각 파싱 주의 — 셀러 응답은 `KstOffsetSerializer`(오프셋 포함), 관리자 주문 응답은 오프셋 없는 `LocalDateTime`(D-191 §2). 표시는 공용 `formatDateTime`(앞 16자 정규식)이라 두 형식 모두 안전하나 파서를 새로 쓸 때 관리자 것을 복제하면 안 된다. `period`·`scheduledPayDate`는 LocalDate(yyyy-MM-dd).
- Playwright 재시작 직후 1차 실행은 콜드 로드 5s expect 타임아웃(기존 트랩·FE-46)으로 13 fail → 2·3차 82/82. e2e는 seller01(실계정·`SELLER_E2E_*`=seed-state.json 값·비밀번호 비출력) 로그인 + API `page.route` mock(`e2e/helpers/seller-mock.ts`)·데모 셀러 빈 상태 1건만 실 API.
- 검증(실측·컨테이너 pnpm): typecheck 0 · vitest 72파일 **468**(428 → +40·test/seller +8) · Playwright 82/82(기본 워커 8·1.3분·3역할 env 주입·skip 0) · 사용자 픽셀 12장 diff 0 · `layers/admin` diff 0(`git diff --name-only main -- frontend/layers/admin`) · no-admin-import 통과 · 셀러 기준선 `playwright-report/step611-seller` 12장 신규(4화면+품목 상세+정산 상세 × desktop/mobile·seller01 실데이터·pageerror 0).
- 신규 의존성: 없음.

### 외부 검토
- **등급 C → 생략.** FE 전용·BE 계약 무변경(D-191·D-192 A/B 검토 완료)·격리 원칙은 스캔 테스트·admin diff 0으로 기계 검증.

### §8 이월
- 클레임 상세 화면(90-D) — 대시보드 최근 클레임·처리 대기 클레임 칸 링크.
- 재고 임박 칸 링크(셀러 상품/재고 화면·90-C).
- 환불 추이(일별 refund 버킷)·비교 기간(전 기간 대비 증감) — D-192 §8·FE 요구 시.
- 셀러 비밀번호 변경 폼(90-D 설정)·공용 레이어 승격 판단(FE-44 §8 유지).

## FE-48: 셀러 상품·재고 화면 (Track 90-C-3·90-C-4) (2026-09-20)

배경: D-193·D-194로 셀러 상품 조회·변경 API가 생겼다. 셀러 셸(FE-44)에 상품 목록·재고·등록·수정 4화면을 붙이고 사이드바 비활성 7 중 상품·재고 2종을 활성화한다. 브랜치는 `feat/track-90c-seller-products`에 BE 커밋 위로 FE 커밋을 쌓는다(FE-47과 같은 완결 단위 전략).

결정:
- **신설**: `/seller/products`(목록) · `/seller/products/inventory`(재고) · `/seller/products/new`(등록) · `/seller/products/[id]`(수정). `seller-menu.ts` 상품·재고 활성(비활성 5 잔존: 클레임·통계 3·설정).
- **격리 유지**: `layers/admin` import 0건(`test/seller/no-admin-import.spec.ts`)·무수정, 컴포넌트 복제(`SellerProduct{FilterCard,Table,Form,BasicSection,ImageSection,OptionSection}`·`SellerInventory{FilterCard,Table,AdjustDialog}`), 관리자 픽셀 12장 diff 0.
- **저장 오케스트레이션**(`lib/seller-product-save.ts`): 등록 = create → images / 수정 = basic → images → variants, `changedSections`로 바뀐 섹션만 호출. 실패 시 `SellerSaveStepError`로 실패 단계·완료 단계·생성 상품 id를 전달하고 즉시 중단(뒤 단계 미호출·failure 원본 객체 보존).
- **등록 후 이미지 실패 시 수정 화면 `?partial=1`로 전환**(중복 등록 방지·경고 배너). 저장 성공 시 `partial` 쿼리를 `router.replace`로 제거(검토 반영 — 잔존 시 재진입마다 경고).
- **수정 화면은 옵션 그룹·값 편집 UI를 렌더하지 않음**(구조 잠금·D-194). 기존 variant 재고는 읽기 전용 + 재고 화면 링크, 아직 없는 조합만 "추가" 체크로 신규 행(initialStock은 신규 행만 전송·기존 행은 0·`[]`). `HIDDEN` 토글 = 비활성화(삭제 없음).
- **dirty 판정은 저장 payload 의미 기준으로 통일** — `formSnapshot` = `sectionSnapshots` + create 요청 직렬화(localId 제거). 이탈 경고·섹션 저장 양쪽이 같은 기준을 써 "공백만 바꿈·제외 행 입력·표시 전용 status"는 dirty가 아니다(검토 반영).
- **기본가·추가금·초기재고에 0 이상 정수 검증**(`isNonNegativeInteger`·BE Long/int 계약·`step="1"`).
- 재고 조정 다이얼로그는 입고/출고(delta·사유)만(셀러 `adjust` API 부재). 목록·재고 화면은 URL query 단일 소스(FE-47 관례).
- 외부 검토: A / FE 3라운드(r2b1·r2b2·r2b3) / 지적 중 수용 4(partial 잔존·정수 검증·dirty 기준·테스트 보강).

### §1-A 갈림길·채택/기각 근거
- **이미지 부착 — α 기존 셀러 이미지 4종 API 사용 【기각】 / β 치환형 PUT 사용 【채택】**(D-193 §1-A 1 γ와 동일 근거·식별자 통일·관리자 저장 오케스트레이션 복제).
- **재고 편집 — α 폼에서 delta 조정(관리자 방식) 【기각】 / β 읽기 전용 + 재고 화면 분리 【채택】** — 셀러에겐 `adjust` API가 없고 입출고만 있다(BE 계약 종속·대안 없음에 가까움).

### §2 확정 구현 규칙·트랩
- 이미지 업로드는 `POST /seller/files/images` 결과의 `thumbnailUrl || url`을 등록 thumbnailUrl로 보낸다 — 둘 다 실제 저장 키라 BE `exists` 검증(D-193)을 통과한다(소형 이미지는 썸네일 미생성 → 원본 URL). 실패 파일은 `pending` 목록에만 남고 `form.images`에 들어가지 않는다.
- 숫자 입력은 전부 `toNumber`(Number 변환·빈값 null) 경유 — 문자열이 폼에 유입되지 않는다.
- **CSS 주석 안에 슬래시 포함 문자열(`adm-image-*/adm-variant-*`)을 쓰면 주석이 조기 종료** → tailwind 파싱 오류로 셀러 화면 전체 파손. typecheck·vitest는 CSS를 보지 않아 미검출·Playwright 대량 실패로만 드러남 → LT-21. 대량 e2e 실패 시 `docker logs zslab_mall_frontend` 먼저.
- Playwright 재시작 직후 1차 콜드 실행은 버림(FE-46·FE-47 동일)·admin ① 케이스는 3차까지 필요할 수 있음. `locator('header')` strict-mode 위반 → `.slr-page-header`.
- 검증(실측·컨테이너 pnpm): typecheck 0 · vitest 79파일 **518**(FE-47 468 → +50) · Playwright **91/91**(콜드 81 → 88 → 91) · 관리자 픽셀 12장 diff 0(`track90b3c` 대비) · `layers/admin` diff 0 · no-admin-import 통과.
- 신규 의존성: 없음(vue-draggable-plus는 관리자와 동일 기존 의존).

### 외부 검토
- **등급 A · FE 3라운드(r2b1·r2b2·r2b3)** — 수용 4: partial 쿼리 잔존 제거·정수 검증·dirty 기준 통일·테스트 보강(vitest 9·Playwright 2). 기각분은 BE 계약(옵션 구조 잠금·재고 읽기 전용)에서 파생된 설계라 FE 단독 변경 불가.

### §8 이월
- 옵션 그룹·값 편집 UI(D-194 §8 구조 수정 API 이후).
- 재고 이력 화면(D-193 §8).
- 승인 대기 상태 표시(D-194 §1-A 1 β 도입 시).

## FE-49: 셀러 클레임 목록·상세·첨부 열람 화면 (Track 90-D-1) (2026-09-20)

배경: D-195로 셀러 클레임 조회 API와 첨부 열람 SELLER 인가가 생겼다. 셀러 셸(FE-44)에 클레임 2화면을 붙이고 사이드바 비활성 5 중 클레임을 활성화한다(잔여 4: 통계 3·설정). 처리 UI는 만들지 않는다(관리자 전용).

결정:
- **신설**: `/seller/claims`(목록·URL query 단일 소스·`SellerClaimFilterCard`·`SellerClaimTable`·액션 컬럼 없음) · `/seller/claims/[id]`(상세·사유·상세 사유·거부 사유 코드·환불 상태·교환품 배송 상태·읽기 전용 진행 타임라인 `claimTimeline`·첨부 썸네일·확대 다이얼로그·404 안내). `useSellerClaims`는 `list`·`detail` 2개만 노출(vitest로 키 집합 고정).
- **첨부 blob 로더** `useSellerAttachmentImage`: `fetch(url, Authorization Bearer, cache no-store)` → `URL.createObjectURL` → `img src`. URL 변경·늦은 응답·스코프 소멸 시 `revokeObjectURL`. 404·403은 "열람 권한이 없거나 삭제된 사진"(상태 코드를 구분해 문구화하지 않음)·그 외 "불러오지 못함"·클릭 재시도.
- **object URL 소유권은 `SellerClaimAttachmentImage`(컴포저블)에만**: `variant='thumb'|'preview'`. 부모(상세 페이지)는 확대 대상 `attachmentId`만 보관하고 다이얼로그 안에서 `variant="preview"`를 다시 렌더한다. 부모는 URL 문자열을 들지도 revoke하지도 않는다.
- **주문 품목 클레임 칩**: 목록 행·품목 상세에 대표 클레임 `유형 상태`(2건 이상이면 ` · N건`) → `/seller/claims/{id}`(back=목록 또는 품목 상세·`seller-back-path`에 클레임 복귀 규칙 추가).
- 공용 `~/lib/constants/claim`(유형·상태·사유·거부 사유·환불 라벨)만 사용·`layers/admin` import 0·무수정(관리자 픽셀 12장 diff 0).
- 외부 검토: **등급 A · r4 · 수용 2(blob 소유권 통일·`useSellerClaims` 표면 고정) · 기각 1**(404/403 플레이스홀더 문구 — 이미 상태 코드를 합쳐 표기).

### §1-A 갈림길·채택/기각 근거
- **확대 다이얼로그 blob 소유권 — α 부모가 attachmentId만 보관·다이얼로그에서 이미지 컴포넌트 재렌더 【채택】 / β 부모가 자식의 object URL을 보관하고 닫을 때 revoke 【기각: 자식 컴포저블도 revoke하므로 이중 revoke·타이밍에 따라 썸네일이 깨짐】 / γ 확대 시 별도 fetch로 새 blob 【기각: 같은 이미지 중복 요청·수명 관리 지점 2개】.** preview 재렌더는 fetch 1회가 더 발생하지만(`no-store`) 수명 관리가 컴포넌트 1곳으로 닫힌다.
- **첨부 표시 방식 — `<img :src>` 직접 로딩(구매자·관리자 관례) 【기각: seller_token path=/seller라 이미지 요청에 쿠키가 실리지 않음】 / fetch+blob 【채택】**(D-195 §1-A 1).

### §2 확정 구현 규칙·트랩
- `v-btn :to`는 link role로 렌더된다 — e2e에서 `getByRole('button')`이 아니라 `getByRole('link')`.
- 새 페이지 디렉토리(`pages/seller/claims/`) 추가·typecheck(`nuxt prepare`) 후에는 dev 컨테이너 재시작(LT-15·기존 트랩) — 재시작 전 404 페이지·`dev.json` 매니페스트 404 console.error.
- 검증(실측·컨테이너 pnpm): typecheck 0 · vitest 81파일 **532**(FE-48 518 → +14) · Playwright 웜 **93/94**(콜드 81~83 → 웜) · 사용자 픽셀 12장 diff 0(`track90b3c` 대비) · `layers/admin` diff 0 · no-admin-import 통과 · 셀러 스크린샷 `playwright-report/step676-seller`·`step679-claim-attachment`.
- 신규 의존성: 없음.

### §8 이월
- ~~**seller-dashboard ② spec**(데모 셀러 데이터 0 기대·실 API)이 `NUXT_SELLER_DEMO_EMAIL`=seller02(실데이터)와 불일치해 웜 93/94의 잔여 1건으로 남는다. 하드코딩 기대값을 API 대조로 교정 — 90-D-2에서 처리.~~ → **FE-50에서 해소**(waitForResponse 응답 대조).
- 대시보드 최근 클레임·처리 대기 클레임 칸 링크(FE-47 이월).
- 회수 송장 표시(D-195 §8).

## FE-50: 셀러 비밀번호 변경 폼·seller-dashboard ② spec 교정 (Track 90-D-2) (2026-09-21)

배경: FE-44 placeholder(`/seller/settings/password`·구매자 페이지 링크 안내)를 실제 폼으로 교체한다. 정찰(recon-report-password) 실측: BE `PATCH /api/v1/users/me/password`는 `anyRequest().authenticated()`(role 불문)라 SELLER 토큰으로 이미 호출 가능하고(셀러 상단바가 같은 원리로 `/v1/users/me`를 호출 중), 토큰 무효화는 user 단위(`credentials_changed_at`·AuthenticatedUserStateVerifier)라 같은 계정의 BUYER 세션도 함께 끊긴다. 관리자 자기 비밀번호 변경 화면은 없다(복제 참조 = 구매자 `mypage/password.vue` + 셀러 Vuetify 폼 관례). 등급 C·BE 무변경.

결정:
- **BE 무변경·기존 계약 재사용**: 페이지가 `useSellerApi()`로 `PATCH /v1/users/me/password { currentPassword, newPassword }` 직접 호출(단일 사용처라 composable 미신설). 계약 박제 = `ChangePasswordIntegrationTest (4)` SELLER 토큰 204(BE 테스트 1건 추가·main 코드 0).
- **폼**(`pages/seller/settings/password.vue`): 현재/새/확인 3필드 · 클라 검증은 순수 함수 `lib/seller-password-form.ts`(길이 8~72 = `~/lib/constants/account` PASSWORD_MIN/MAX 미러·확인 일치·현재 비번 필수·trim 없음) · `errors: Record` → `v-text-field :error-messages` · submitting 중 재제출 무시(SellerInventoryAdjustDialog 관례).
- **사전 안내 1줄**(폼 상단 info alert): "모든 기기의 셀러 로그인과 같은 계정의 구매자 로그인이 함께 로그아웃" — BE 무효화가 user 단위인 사실을 사용자에게 미리 알린다. 임시 비밀번호 세션(`passwordChangeRequired`)이면 warning alert 추가.
- **성공(204)**: `sellerAuth.logout()`(seller_token·강제 상태 쿠키 제거) → `/seller/login?notice=password-changed` → 로그인 페이지 success alert(`SELLER_LOGIN_NOTICE_*` 상수·구매자 `LOGIN_NOTICE_*` 동형).
- **에러 분기**: 400 `MALFORMED_REQUEST`(현재 비번 불일치·BE 사유 은닉) → 현재 비밀번호 필드 "현재 비밀번호가 일치하지 않습니다." / 400 `VALIDATION_FAILED` → `mapFieldErrors` 필드별(없으면 새 비밀번호 필드에 공통 문구) / 그 외 → danger 토스트(`toSellerErrorMessage`). 401은 useSellerApi가 로그인으로 보낸다.
- **사이드바**: 마지막 항목 `{ label: '비밀번호 변경', to: '/seller/settings/password' }` 단일 링크(비활성 "설정" 대체). 계좌 화면이 들어오는 90-D-3에서 "설정" 그룹(children)으로 승격. 비활성 항목 통계 3만 남음(spec 4곳 4→3 동반 수정).
- **정지(SUSPENDED) 셀러**: 비밀번호 변경은 셀러 도메인 밖(`/api/v1/seller/**` 아님)이라 D-190 차단을 받지 않는다 — 현행 유지(계정 보안 행위·BE 무변경).
- **seller-dashboard ② spec 교정**: "데이터 0" 하드코딩 8건 제거 → 데모 버튼 클릭 전 `waitForResponse('/api/v1/seller/dashboard')` 등록 → 응답 JSON으로 요약 4(`seller-stat-card-value` = formatWon/formatCount 재현 문자열)·대기 4·목록 3(길이 0이면 `-empty`·아니면 `-row` 수)·차트(`dailyTrend` 전부 0일 때만 `-empty`) 대조. 데모 계정(seller02·실데이터)·seller01/03 모두 데이터가 있어 계정 교체로는 풀 수 없었다(정찰 §D-12).
- **비밀번호 변경 E2E 전용 계정**: `SELLER_PASSWORD_E2E_EMAIL/PASSWORD`(신설 env·미설정 skip·`.env.example`·README 동기). SELLER_E2E_*(loginAs·병렬 spec 공유)나 데모 계정으로 바꾸면 변경 즉시 그 계정의 모든 토큰이 무효라 다른 워커 세션이 끊긴다. ①은 `finally`에서 "새 비밀번호로 로그인되면 원래로 되돌린다"(단언 실패·타임아웃 중단에도 원복). 로컬 실행은 seller03.

### §1-A 갈림길·채택/기각 근거
- **성공 후 세션 — α `sellerAuth.logout()` → 로그인 페이지 안내 【채택】 / β 세션 유지·안내만 【기각: 변경 시점에 BE가 요청 토큰까지 무효화해 다음 API가 401로 끊긴다 — "갑작스런 401" UX·구매자 D-178 선례와도 불일치】**.
- **400 표시 — 구매자형 단일 문구 【기각: 셀러 폼 관례(필드 단위 `error-messages`)와 어긋나고 fieldErrors를 버림】 / 코드별 필드 분기 【채택】**. `seller-error-message.ts`의 MALFORMED_REQUEST 일반 문구("잘못된 요청입니다")는 현재 비번 불일치 안내로 부적합해 페이지에서 전용 문구를 쓴다.
- **사이드바 — "설정" 그룹(children 1) 【기각: 현재 유스케이스에 항목 1개·YAGNI】 / 단일 링크 "비밀번호 변경" 【채택】**.

### §2 확정 구현 규칙·트랩
- Vuetify `v-text-field`의 `data-testid`·`class`·`id`(attrs)는 루트 `.v-input`에 붙고(`filterInputAttrs`), `id` prop은 input 요소에 간다 → e2e는 `page.fill('#seller-…')`·오류 문구는 `getByTestId(...)` toContainText(루트가 messages 영역 포함).
- **픽셀 캡처 트랩**: Playwright 전량 실행 직후(dev 서버 웜 상태) 캡처는 코드 무변경 사용자 화면에서도 login 25/74px·products ~300px 노이즈가 났다(stash로 main 상태 캡처 = track92a 대비 0 확인). **재시작 직후 캡처**하면 0 — 픽셀 diff는 컨테이너 재시작 후 캡처를 기준으로 판정한다.
- 검증(실측·컨테이너): gradlew --rerun-tasks 229파일 **1319·0 fail**(1318 + 1) · typecheck 0 · vitest 82파일 **545**(533 + 12) · no-admin-import 통과 · Playwright 4쌍 env 96건 콜드 85 → 웜 **96/96**(seller-dashboard ② 포함·seller03 원복 로그인 200) · 사용자 픽셀 12장 diff 0(재시작 직후 `track90d2c` vs main 상태 `track90d2-main` = `track92a`) · `layers/admin` diff 0.
- 신규 의존성: 없음.

### §8 이월
- **D-189 Javadoc 불일치**: `AdminMemberProvisioningService.java:38-39` "구매자 FE의 비밀번호 변경 화면이 유일한 변경 경로"는 본 트랙 후 사실과 다르다(셀러 폼 추가). BUYER 동시 부여 결정 자체(회원 목록·탈퇴·재발급이 BUYER 기준)는 유효 — 문구 수정은 별건.
- 90-D-3: 계좌 화면 + 사이드바 "설정" 그룹 승격(비밀번호 변경·계좌).
- 대시보드 최근 클레임·처리 대기 클레임 칸 링크(FE-47 이월)·회수 송장 표시(D-195 §8) — FE-49 §8 그대로.

## FE-51: 셀러 정산계좌 화면·설정 그룹 승격·은행 상수 공용화 (Track 90-D-3) (2026-09-21)

배경: D-199(셀러 본인 등록 `GET/POST /api/v1/seller/bank-accounts`·SELLER_OWNER 한정 쓰기)의 셀러 화면. 정찰(recon-report-account §F) 실측: 사이드바 마지막 항목은 단일 링크 "비밀번호 변경"(FE-50 예고대로 그룹 승격 대상)·`GROUP_BADGES['설정']` 아이콘 기존 · 셀러 FE 역할은 `useSellerMe().me.roleCode`(useState 공유·소비처 상단바 1곳) · 은행 옵션·형식 한도 상수는 admin 레이어 한정(`ADMIN_BANK_OPTIONS`)인데 셀러 레이어는 `no-admin-import.spec`으로 admin import 0건 강제.

결정:
- **은행 상수 공용 이동**: `app/lib/constants/bank.ts`(`BANK_OPTIONS`·`BANK_CODE_MAX`·`ACCOUNT_NUMBER_MIN/MAX`·`ACCOUNT_HOLDER_MAX`·`ACCOUNT_NUMBER_PATTERN`·`bankLabel`) 신설. admin `constants/admin-seller.ts`는 기존 `ADMIN_*` 이름으로 **re-export만**(관리자 코드·spec 무수정·admin → `~/lib` 방향 import는 기존 관례).
- **사이드바 "설정" 그룹 승격**(`seller-menu.ts`): `{ label: '설정', children: [비밀번호 변경 → /seller/settings/password, 정산계좌 → /seller/settings/bank-account] }`. 렌더는 `SellerSidebar` children 분기·배지 기존이라 컴포넌트 무변경. `seller-menu.spec`(라벨·경로 배열·활성 판정) · `e2e/seller-shell.spec ③`(라벨 루프에 설정·정산계좌·href 2건) 갱신.
- **페이지 `pages/seller/settings/bank-account.vue`**: 좌 목록 카드(로딩 progress·빈 상태·에러+다시 시도·행 = `은행표시명 ···끝4자리` + 주 정산계좌 chip + 예금주·상태 라벨·등록일) / 우 등록 폼 카드는 **`roleCode === 'SELLER_OWNER'`일 때만**, 그 외 "셀러 대표(OWNER)만 등록·조회만 가능" info alert. 폼 안내 1줄 "첫 번째로 등록한 계좌가 주 정산계좌로 지정됩니다. 계좌 변경·수정은 운영자에게 문의하세요." 서버가 같은 판정(403)을 하므로 화면 분기는 안내용.
- **폼**: v-select(은행·`BANK_OPTIONS`)·계좌번호(inputmode numeric·maxlength 30·hint)·예금주(maxlength 50). 클라 검증은 순수 함수 `lib/seller-bank-account.ts` `validateSellerBankAccountForm`(은행 필수·계좌번호 숫자/하이픈 6~30·예금주 1~50·trim) — 관리자 `admin-seller-bank-view.validateAccountNumberInput`과 같은 규칙이나 admin import 금지라 셀러 레이어 구현. 제출 본문은 trim.
- **성공(201)**: success 토스트 "정산계좌를 등록했습니다." → 폼 초기화 → 목록 재조회(GET). **에러**: 400 `VALIDATION_FAILED` → `mapFieldErrors` 필드 매핑(없으면 계좌번호 필드) / `SELLER_OWNER_REQUIRED`(`seller-error-message.ts` 문구 추가 "정산계좌 등록은 셀러 대표(OWNER)만 할 수 있습니다.")·`SELLER_SUSPENDED`·그 외 → danger 토스트. 중복 제출은 `submitting` 가드.
- **타입** `types/seller-bank-account.ts`(BE `SellerBankAccountResponse` 1:1·`accountNumber` 필드 없음) · composable `useSellerBankAccounts`(list·register·상태는 호출부 소유).
- **관리자 정산 지급 화면 계좌 표시**: 이미 있음(Track 85·`admin/settlements/[id].vue` 정산계좌 카드) → 무변경.

### §1-A 갈림길·채택/기각 근거
- **은행 상수 — α 공용 `app/lib/constants/bank.ts` + admin re-export 【채택】 / β 셀러 레이어 사본 【기각: 목록 21행 이중 관리·추가 시 동기화 누락】**.
- **역할 분기 데이터 — α `useSellerMe().me.roleCode` 기존 공유 상태 【채택】 / β 계좌 목록 응답에 `canRegister` 같은 권한 플래그 【기각: BE 응답 확장·me가 이미 역할을 싣고 있음】**.
- **폼 노출 — α 비OWNER는 폼 대신 안내 alert 【채택】 / β 폼 표시 + 제출 시 403 토스트 【기각: 등록할 수 없는 폼을 보이면 입력 후 거부되는 경로만 남김】**. 403은 역할 변경 직후 화면 잔존 케이스로 토스트 처리 유지.

### §2 확정 구현 규칙·트랩
- vitest `test/seller/seller-bank-account-page.spec.ts` 13: 순수 함수 3(유효·경계·trim / 필드별 오류 / 표기·목록 밖 코드) + 페이지 10(OWNER 폼·안내·GET 1회 / MANAGER·STAFF 폼 없음·안내 / 빈 상태·2행·chip 1·마스킹·전체 번호 없음 / 목록 실패·다시 시도 / 검증 실패 미호출 / 201 trim 본문·토스트·재조회·초기화 / 중복 제출 1회 / 400 fieldErrors / 403 OWNER_REQUIRED·SUSPENDED 토스트). v-select는 `findComponent({ name: 'VSelect' }).setValue`.
- Playwright `e2e/seller-bank-account.spec.ts` 2: ① 실 me·실 GET(로그인 셀러 실제 목록 또는 빈 상태·전체 계좌번호 프로퍼티 부재·사이드바 활성·roleCode 대조 폼/안내) ② STAFF me mock + 목록 mock 2건(폼 없음·안내·chip 1·은행 표시명). **POST는 실행하지 않는다**(실 DB 누적·BE IT가 커버).
- **트랩(재확인)**: 신규 페이지 파일은 dev 서버 재시작 전 404(`seller-bank-account` testid 미발견) → `docker restart zslab_mall_frontend` 후 통과(89-A 신규 파일 트랩 동일). 브랜치 BE 변경은 `docker restart zslab_mall_backend`(헬스 폴링 ~2분) 후 gateway 경유 401 확인.
- 검증(실측·컨테이너): typecheck EXIT 0 · vitest 84파일 **561**(548 + 13) · no-admin-import 통과 · Playwright 3역할 env 98건: 콜드 85/98(기존 콜드 트랩) → 웜 96/98(admin-operators ①·admin-deliveries ① 플레이크·기존) → 웜 **98/98**(seller-bank-account 2·seller-shell 갱신 포함·2 skip = seller-password 전용 env 미주입) · 재시작 직후 픽셀 track90d3 12장 track93-main 대비 **diff 0** · layers/admin diff = `admin-seller.ts` re-export 교체만(+10/−32) · 수동 스크린샷 `playwright-report/step783-manual/`(셀러 계좌 페이지·관리자 셀러 상세 계좌 카드·관리자 정산 상세 계좌).
- 신규 의존성: 없음. `@mdi/js` `mdiBankOutline`·`mdiStar` 기존 패키지.

### §8 이월
- 셀러 계좌 페이지에서 정산 상세로의 링크·정산 상세 계좌 카드에서 계좌 페이지 링크(요구 없음).
- 대시보드 최근 클레임·처리 대기 클레임 칸 링크(FE-47 이월)·회수 송장 표시(D-195 §8) — FE-50 §8 그대로.

## FE-52: 통계 상수·순수 함수 공용화 · 셀러 매출 통계 화면 · 통계 메뉴 활성 (Track 90-E-1) (2026-09-21)

배경: D-200(셀러 매출 통계 3 endpoint)의 셀러 화면. 정찰(recon §F) 실측: 관리자 통계 FE는 전부 layers/admin(페이지 3·lib 4·컴포넌트 10)이고 셀러 레이어는 admin import 금지(no-admin-import.spec) · 공용화 선례 2종(컴포넌트 복제 = SellerChart / 상수 공용 이동 = bank.ts + admin re-export) · 사이드바 통계 그룹은 `to` 없는 placeholder(seller-shell ③ disabled 3 단언).

결정:
- **공용화 범위 β(D-200 결정 6)**: 레이어 CSS·색·라우트에 묶이지 않는 것만 `app/`으로 이동하고 관리자 모듈은 기존 이름 그대로 **re-export만**(관리자 페이지·컴포넌트·spec 무수정).
  - `app/lib/constants/stats.ts`: `STATS_UNITS/LABELS`·`STATS_COMPARES/LABELS`·`PERIOD_PRESETS/LABELS`·`DEFAULT_PERIOD_PRESET/STATS_UNIT/STATS_COMPARE`·`CATEGORY_AXIS_NOTICE` ← admin `constants/admin-sales-stats.ts`(축 `STATS_AXES`·`DELETED_NAME_LABELS`는 관리자 잔류).
  - `app/lib/stats-period.ts`: `toDateOnly`·`presetPeriod`·`resolveStatsPeriod`·`isPeriodInverted` ← admin `admin-sales-stats-query.ts`(`resolvePeriod`는 위임 함수로 유지) + 셀러용 `periodDayCount`(UTC 자정 차·365 상한 판정).
  - `app/lib/stats-view.ts`: `ChangeTone`·`changeRate`·`formatChangeRate`·`changeTone` ← `admin-dashboard-view.ts` / `normalizeSalesStats`·`formatItemsPerOrder`·`salesChangeTone`·`isZeroBucket`·`compareNetSeries`·`axisLabel`·`isTrendEmpty`·`sortBreakdownRows`(제네릭 `SortableBreakdownRow`)·`showsCompare`·`csvFileNameFrom` ← `admin-sales-stats-view.ts`. 배지 클래스(`changeChipClass` adm-chip/slr-chip)·차트 색·카드 정의·`breakdownRowViews`(drillable)는 각 레이어.
  - `app/types/stats.ts`: `SalesSummary`·`SalesTrendBucket`·`SalesStatsResponse` ← admin `types/admin-sales-stats.ts`(`AdminSalesSummary` 등은 type alias re-export).
- **셀러 레이어 신규**: `lib/constants/seller-stats.ts`(축 PRODUCT·OPTION·CATEGORY·`SELLER_STATS_MAX_PERIOD_DAYS=365`·삭제 표기) · `types/seller-stats.ts` · `lib/seller-stats-query.ts`(URL 단일 소스·`sellerStatsPeriodError` 역전/365일) · `lib/seller-stats-view.ts`(slr-chip 톤·카드 6장 = 매출·환불·순매출·주문수·객단가·**판매수량**(관리자의 주문당 품목수 대신)·셀러 teal 차트·행 뷰 `linkable`) · composable `useSellerSalesStats`(sales·breakdown·breakdownCsv raw blob) · 컴포넌트 복제 4(`SellerPeriodPicker`(periodError·maxDays prop)·`SellerStatsTabs`(SELLER_MENU 통계 그룹에서 탭 파생·`to` 없는 탭 disabled)·`SellerSalesSummaryCards`·`SellerSalesBreakdownTable`(드릴다운 대신 linkable 행 → emit open)) · 페이지 `pages/seller/stats/sales.vue`(관리자 FE-34 골격·URL query preset/from/to/unit/compare/axis·sales/breakdown 분리 요청·요청 키 watch·CSV blob 다운로드·정산 안내 링크 `/seller/settlements`·상품 행 → `/seller/products/{publicId}?back=통계 URL`).
- **`seller-back-path.ts`**: `SELLER_STATS_SALES_PATH` 추가 · 상품 상세 back 허용 목록에 통계 매출 등록(오픈 리다이렉트 방지 관례 유지).
- **메뉴**: `seller-menu.ts` 통계 그룹 `매출 → /seller/stats/sales`(주문·클레임·상품은 `to` 없음 유지) · `seller-menu.spec`·`seller-product-query.spec`(비활성 3 → 2)·`e2e/seller-shell ③`(disabled 3 → 2 + 매출 href 1).

### §1-A 갈림길·채택/기각 근거
- **공용화 — α 전부 복제 【기각: 기간·증감·정렬·CSV 파일명 12함수 이중 관리】 / β 상수·순수 함수만 공용 + 컴포넌트 복제 【채택】 / γ 컴포넌트까지 app/components 이동 【기각: 관리자 import 경로 전면 수정·픽셀 회귀 범위】**.
- **요약 카드 6번째 — 주문당 품목수(관리자 동일) 【기각】 / 판매수량 【채택: D-200 요약 정의(매출·주문수·객단가·판매수량)·품목 축 셀러에겐 수량이 직접 지표】**.
- **상품 행 이동 — 드릴다운(관리자) 【기각: 셀러 분해는 축 3종 전환으로 충분·BE parentKey 없음】 / 상품 상세 링크 【채택】**.

### §2 확정 구현 규칙·트랩
- vitest: `test/unit/stats-helpers.spec.ts` 8(공용 상수·기간·일수·증감·정규화·후행 0·정렬 제네릭·CSV 파일명) · `test/seller/seller-stats-helpers.spec.ts` 9(URL 매핑·축 정규화·기간 오류·API 파라미터·back 경로·카드 6장 slr-chip·행 뷰 linkable·차트 계열) · `test/seller/seller-stats-sales-page.spec.ts` 8(진입 요청 파라미터·카드·탭 / 기간 오류 미요청 / 축 전환 = router.replace → breakdown만 재조회 / 비교 데이터 0 안내 / 빈 상태 / 에러·재시도 / CSV raw·파일명 토스트·실패 / 상품 행 이동). 관리자 `admin-sales-stats-helpers.spec` 등은 re-export 경유로 무수정 통과.
- **트랩(페이지 spec)**: (1) `mockNuxtImport('useRouter')`는 nuxt test-utils 셋업(`afterEach`)을 깨뜨림 → 실제 라우터 + `mountSuspended({ route })` (2) router.replace → route.query 반영은 마이크로태스크로 끝나지 않아 `vi.waitFor` 폴링 (3) 라우터가 파일 내 공유라 마운트한 페이지는 `afterEach`에서 unmount(살아 있으면 다음 라우트 변경에 watch 반응·요청 수 혼입) (4) jsdom에서 v-tabs 클릭은 모델을 바꾸지 않음(슬라이드 그룹 ResizeObserver) → VTabs vm `update:modelValue` emit (5) 셀러 미들웨어의 로그인 리다이렉트도 `navigateTo` mock에 잡히므로 객체 인자만 필터.
- Playwright `e2e/seller-stats-sales.spec.ts` 1(데모 셀러 seller02·실 API): 사이드바 매출 링크·disabled 2 → 진입 → 통계 탭 선택/비활성 2 → 요약 6 = waitForResponse 응답 포맷(하드코딩 없음) → 차트 svg·빈 상태 = trend 전부 0 → 분해 행 = rows(linkable = key 있는 행) → 옵션 탭 → breakdown axis=OPTION 재요청·URL·sales 재요청 0 → CSV export 200 text/csv·axis=OPTION·filename* 한글.
- 검증(실측·컨테이너): typecheck EXIT 0 · vitest 87파일 **586**(561 + 25: unit 8·seller helpers 9·page 8) · no-admin-import 통과 · 재시작 → Playwright 3역할 env 101건: 콜드 41 pass/2 fail/58 skip(admin-dashboard ① 콜드 트랩 + seller-products ② disabled 3 → 2 갱신 필요·env 미주입 skip) → 컨테이너 NUXT_*_DEMO_* 재주입 웜 **99/101**(2 skip = seller-password 전용 env) · 재시작 직후 픽셀 track90e1 12장 track90d3 대비 **diff 0** · layers/admin diff 5파일(+57/−229·전부 re-export 치환) · 수동 스크린샷 6장 playwright-report/step804-manual(관리자 통계 3탭 렌더·차트 1/3/2·셀러 매출 통계 기본/비교+옵션/올해+월+카테고리·pageerror 0·console error 0).
- 신규 의존성: 없음.

### §8 이월
- 90-E-2·3 탭 화면(주문·클레임·상품) — `SellerStatsTabs`·`SellerPeriodPicker`(unit/compare 미전달 시 숨김) 재사용.
- 관리자 `admin-stats-period-query.ts`의 `resolveStatsPeriod` 우회 호출(`resolvePeriod({...state, axis, parent})`)은 동작 무변경으로 두었다 — 공용 `resolveStatsPeriod` 직접 호출로 정리만 이월.

### 90-E-2 셀러 주문·클레임 통계 화면 (2026-09-21)
- **공용 추가(β 범위·관리자 re-export)**: `app/types/stats.ts`에 `LeadTimeMetric`·`ClaimSummary`·`ClaimTrendBucket`·`ClaimTypeShare`·`ClaimReasonShare`(admin types는 alias re-export) · `app/lib/stats-view.ts`에 `formatRate`·`formatPercent`·`formatHours`·`LEAD_TIME_EMPTY`·`percentOf`·`isZeroClaimBucket`·`compareRefundRateSeries`·`isClaimTrendEmpty`·`claimTypeLabel`·`claimReasonLabel`·`claimTypeRows`·`claimReasonRows`·`DistributionRowView` ← `admin-order-stats-view.ts`(동작 무변경·관리자 spec 무수정 통과). 퍼널 단계 정의(관리자 4·셀러 3)·도넛 색·카드 정의는 각 레이어.
- **셀러 신규**: `types/seller-order-stats.ts` · `lib/seller-order-stats-view.ts`(퍼널 3단계·소요시간 카드 값 = **중앙값**·요약 4 톤 반전·teal 추이/도넛·상품별 행 linkable) · `seller-stats-query.ts`에 기간 전용 `SellerStatsPeriodQuery`(축 없음·parse/toRoute/api) · composable `useSellerOrderStats` · 컴포넌트 복제 5(`SellerOrderFunnel`·`SellerLeadTimeCards`·`SellerDonutCard`·`SellerStatsSummaryCards`·셀러 전용 `SellerClaimProductTable`) · `SellerChart` type에 `donut`·`series: number[]` 허용(관리자 AdminChart 동형) · 페이지 `pages/seller/stats/orders.vue`(관리자 FE-35 골격·상품 행 → 상품 상세 back) · `seller-back-path` `SELLER_STATS_ORDERS_PATH` 허용 · 메뉴 주문·클레임 활성(disabled 2 → 1: seller-menu·seller-product-query spec·e2e seller-shell·seller-products·seller-stats-sales 갱신) · `SellerSalesSummaryCardView` → `SellerStatsSummaryCardView`(key string·매출·클레임 공용).
- 테스트: vitest `seller-order-stats-helpers.spec` 8 · `seller-stats-orders-page.spec` 5(진입 파라미터·렌더·탭 / 기간 오류·비교 안내 / 빈 상태 / 에러·재시도 / 상품 행 이동) · Playwright `seller-stats-orders.spec` 1(seller02 실 API·퍼널/소요시간/요약/분포/상품별 = 응답·단위 WEEK 전환 재요청·URL).
- 검증(실측·컨테이너): typecheck EXIT 0 · vitest 89파일 **599**(586 + 13) · no-admin-import 통과 · 재시작 → Playwright 3역할 env 102건: 콜드 99/1 fail(admin-categories ① 콜드 트랩·기존)/2 skip → 웜 **100/102**(2 skip = seller-password env) · 재시작 직후 픽셀 track90e2 12장 track90e1 대비 **diff 0** · layers/admin diff 2파일(admin-order-stats-view·types/admin-order-stats·re-export 치환만).

### 90-E-3 셀러 상품 통계 화면 · 통계 메뉴 완성 (2026-09-21)
- **셀러 신규**(관리자 대응 화면 없음·공용 추가 없음): `types/seller-product-stats.ts` · `lib/seller-product-stats-view.ts`(`formatDepletionDays` 판매 없음/재고 없음/N일·상위/하위 행 linkable·재고 회전 행 임박(≤7일·재고 없음) 강조·품절 캡션 "현재 시점(기간과 무관)"·전체 빈 판정) · `seller-stats-query.ts`에 기간 전용 `SellerStatsRangeQuery`(preset|from|to) · composable `useSellerProductStats` · 컴포넌트 `SellerProductRankTable` · 페이지 `pages/seller/stats/products.vue`(`SellerPeriodPicker` unit/compare 미전달 = 숨김 · 품절 카드 → `/seller/products/inventory` · 상위/하위/미판매/재고 회전 행 → 상품 상세 back) · `seller-back-path` `SELLER_STATS_PRODUCTS_PATH` 허용.
- **메뉴**: 통계 그룹 상품 활성 → 셀러 사이드바 비활성 항목 0(seller-menu·seller-product-query spec·e2e seller-shell·seller-products·seller-stats-sales/orders 단언 갱신).
- 테스트: vitest `seller-product-stats-helpers.spec` 5 · `seller-stats-products-page.spec` 5(진입 from·to만·단위/비교 없음·카드·표 4 / 기간 오류·custom / 빈 상태 / 에러·재시도 / 행 이동 3종) · Playwright `seller-stats-products.spec` 1(seller02 실 API·품절 카드·상위/하위·미판매·재고 회전 소진 표기 = 응답·프리셋 7일 재요청).
- 검증(실측·컨테이너): typecheck EXIT 0 · vitest 91파일 **609**(599 + 10) · no-admin-import 통과 · 재시작 → Playwright 3역할 env 103건: 콜드 100/1 fail(admin-categories ① 콜드 트랩·기존)/2 skip → 웜 **101/103**(2 skip = seller-password env) · 재시작 직후 픽셀 track90e3 12장 track90e2 대비 **diff 0** · 수동 스크린샷 6장 playwright-report/step821-manual(관리자 통계 3탭 + 셀러 통계 3탭·pageerror 0·console error 0) · layers/admin diff 0(90-E-3).
- 트랩: 컨테이너에서 test/seller 전량을 단독 실행 시 "Hook timed out 10000ms" 1회(콜드 transform 부하·재실행·전량 vitest에서는 재현 없음).

## FE-53: 운영 편의 소규모 개선 묶음 — C-14·16·06·10·12·15·17 (Track 96-1) (2026-09-21)

배경: Track 96 정찰(`docs/track-96/recon-report-ops.md` §7)의 개선 후보 중 "소 비용·B/C 등급" 7건을 한 PR로 묶었다. 원칙: BE는 조회 응답 필드 추가만(상태 전이·쓰기·Flyway 무변경·C-12 1필드 = D-202) · 결정 외 기능 추가 없음 · 운영 데이터를 바꾸는 E2E 실행 없음.

결정(항목별):
- **C-14 셀러 대시보드 타일**: `PENDING_TILES` 4칸 전부 링크(클레임 `/seller/claims?status=REQUESTED` · 재고 `/seller/products/inventory` · 정산 `/seller/settlements`)·"준비 중" 문구 삭제. `PendingTile.to`는 `string`(null 분기·`div` 폴백 제거).
- **C-16 사용자 상태 안내**: `claim-timeline.ts`에 `claimStageGuide()`(타임라인과 같은 단계 판정·유형별 1줄: 무엇을 기다리는지·구매자가 할 일) → 클레임 상세 타임라인 아래 `claim-stage-guide`. 주문 상세: DELIVERED 품목 아래 `AUTO_CONFIRM_GUIDE`("배송완료 7일 후 자동 구매확정됩니다.") · PENDING_PAYMENT 헤더 아래 `PAYMENT_EXPIRE_GUIDE`("30분 내 결제되지 않으면 주문이 자동 취소됩니다."). **문구 원칙: 시스템이 실제로 보장하는 기간만**(BE 설정값 = `ReturnWindowPolicy.WINDOW_DAYS=7`·`PaymentService.PENDING_TTL=30분`) — "검수 1~2일" 같은 소요 추정은 넣지 않는다(vitest가 `N일` 패턴 부재를 단언). 값은 `lib/constants/order.ts` 한 곳(`AUTO_CONFIRM_DAYS`·`PAYMENT_EXPIRE_MINUTES`) + 주석에 BE file:line.
- **C-06 구매확정 버튼**: 주문 상세 DELIVERED 품목에 "구매확정" → **인라인 확인 패널**(경고 "확정 후에는 반품·교환을 신청할 수 없습니다.") → `useOrderActions().confirmPurchase`(POST `/v1/orders/{ord}/items/{oit}/confirm`) → 성공: 재조회 + 인라인 성공 안내 / 실패: 서버 `detail` 우선 인라인 안내 + 재조회(401은 로그인 유도) / `confirming` 플래그로 중복 제출 차단.
- **C-10 회수 확인 + 검수**: `AdminClaimTable` 검수 버튼을 `CONFIRM_PICKUP` 행에도 노출(outlined) · `AdminClaimInspectDialog` `target.pickupRequired`면 체크박스 "회수 확인 후 검수" 필수 → `confirm-pickup` → `inspect` 순차. confirm-pickup 실패 = 검수 미시작(기존 회수 확인 버튼과 같은 분기). confirm-pickup 성공·inspect 실패 = warning "회수 확인은 반영되었습니다. 검수는 처리되지 않았습니다: …" + `stale`(부모 재조회·행은 INSPECT만 남음). 기존 회수 확인 단독 버튼 유지.
- **C-12 결제 유실 경고**: `isPaymentCancelLost(payment)` = `PAID && amount > 0 && refundedAmount === amount`(BE `markCancelled` D-71 전액 가드와 같은 조건). 충족 시 결제 행에 warning 배지 "환불 전액 완료·취소 미반영" + "취소 처리" 버튼(**조건부 노출**·기존 PAID 상시 노출 제거). `refundedAmount`는 D-202 신규 응답 필드.
- **C-15 경과 일수**: 공용 `app/lib/utils/elapsed-days.ts`(`elapsedDays`·`elapsedTone`·`elapsedChip`·임계 `ELAPSED_WARNING_DAYS=3`·`ELAPSED_DANGER_DAYS=7` 한 곳). 관리자·셀러 6표에 `row-elapsed` chip: 클레임 = 진행 중(REQUESTED·APPROVED)만 requestedAt · 출고 대기 = 관리자 주문 PAID·PREPARING(paidAt) / 셀러 품목 PAID(paidAt) · 배송중 = SHIPPING(shippedAt). 톤은 각 레이어 chip 클래스 접미(`adm-chip--`/`slr-chip--` warning·danger·neutral).
- **C-17 셀러 온보딩 체크리스트**: `sellerOnboardingChecklist(detail)` 4항목(ACTIVE·주 계좌·로그인 가능 구성원·판매중 상품 ≥1) — 기존 응답 필드만(`status`·`warnings.primaryBankAccountMissing`·`members`·`warnings.saleProductCount`) → 관리자 셀러 상세 최상단 카드(`seller-onboarding-{key}` `data-done`). 미충족 항목 "이동" = 같은 화면 카드 scrollIntoView(`seller-info`·`seller-bank-account`·`seller-members`) 또는 상품 목록(셀러 필터) 라우트. TERMINATED는 숨김.

### §1-A 갈림길·채택/기각 근거(대안이 있던 항목만)
- **C-06 확인 UI — 인라인 패널 【채택】 / `window.confirm`(addresses.vue 선례) 【기각: 경고 문구 스타일·테스트 불가】 / 모달·토스트 컴포넌트 신설 【기각: 구매자 앱에 다이얼로그·토스트 인프라 없음(claims/new 인라인 성공 상태 선례)·1회 사용 추상화】**. 성공·실패 피드백도 같은 이유로 인라인 안내(role=status).
- **C-10 부분 실패 후 — 같은 다이얼로그에서 inspect만 재시도 【기각: 부모 목록이 stale 상태로 남아 회수 확인 버튼이 계속 보임】 / stale로 닫고 목록 재조회 【채택】**.
- **C-12 판별 — FE 추론(claims[].refundStatus COMPLETED 존재) 【기각: 금액 없음·부분 환불과 전액 구분 불가】 / BE 결제별 환불 합 1필드 【채택·D-202】**.
- **C-15 위치 — 별도 컬럼 【기각: 6표 헤더·너비 회귀】 / 기존 날짜 셀 아래 chip 【채택】**.
- **C-17 판정 — BE warnings 확장 【기각: 4항목 전부 기존 필드로 판별 가능】 / FE 순수 함수 【채택】**.

### §2 확정 구현 규칙·트랩
- vitest: `test/unit/ops-quick-wins.spec.ts` 7(단계 안내 반품·거절/교환/취소·기간 문구 부재·상수 정합·경과 일수·임계·chip) · `test/component/OrderDetailPage.spec.ts` +5(버튼 노출·PENDING 안내·패널 경고→확정→재조회·중복 차단·422 detail) · `test/admin/admin-claim-inspect-dialog.spec.ts` 4(체크 없음 = inspect만 · 체크 전 비활성→순차 호출 순서 · confirm-pickup 422 → stale·inspect 0 · 성공·실패 구분 안내) · `admin-seller-helpers.spec` +2(C-17) · `admin-order-helpers.spec` +1(C-12) · `seller-dashboard-view.spec` 갱신(C-14). 트랩: `mockResolvedValueOnce` 뒤에 `mockImplementationOnce`를 쌓으면 앞의 Once가 먼저 소비돼 호출 순서 배열이 비어 보인다(한 가지만 쓴다).
- Playwright(렌더·mock API만·데모 데이터 불변): `admin-claims` ③ row-inspect 2·row-elapsed 4 / ④ C-10 순차 POST 2건(mock) + 합격 토스트 소멸 대기(뒤 단언 strict mode 충돌 방지) · `admin-sellers` ① 온보딩 4항목 data-done · `admin-orders` ⑨ 경고 배지(fixture `refundedAmount`) · `seller-dashboard` ① 4칸 href·"준비 중" 부재 · `claims` ① 구매확정 버튼 2·안내·패널 경고·취소 / ② 단계 안내.
- 검증(실측·컨테이너): BE `gradlew.bat test --rerun-tasks` 235파일 **1384·0 fail**(IT 단언 추가만) · typecheck 0 · vitest 93파일 **628**(609 + 19) · no-admin-import 통과 · 재시작 → Playwright 3역할 env 103건 콜드 **101/103**(2 skip = seller-password env) → 웜 1차 100/1 fail(④ 토스트 strict mode·spec 보정) → admin-claims 3회 6/6 → 웜 전량 **101/103** · 재시작 직후 픽셀 track96-1 12장 track95b 대비 login-mobile 8px(0.002%·track95 동일 노이즈) → 재캡처 track96-1b **diff 0**(구매자 픽셀 6페이지는 본 변경 범위 밖).
- 신규 의존성: 없음.

### §8 이월
- C-05(구매자 송장 노출)·C-01(관리자 승인 대기 타일)·C-03(정산 월 배치) 등 정찰 §7의 나머지 후보는 별 트랙.
- 재고 임박 타일은 재고 화면에 임박 필터가 없어 화면 진입만 연결 — 필터 추가 시 `?lowStock=1`류로 좁힌다.

## FE-54: 구매자 주문 상세 배송 정보 블록(C-05) + 관리자 대시보드 승인 대기 타일 2칸(C-01) (Track 96-2) (2026-09-21)

배경: Track 96 정찰 §7 C-05(구매자 송장 미노출·A3)·C-01(관리자 승인 대기 타일 없음·A5). BE는 D-203(조회 응답 필드 추가만). 택배사 코드→이름 매핑은 `app/lib/constants/delivery.ts`에 이미 공용이라 이동 없음(admin·seller 레이어는 자체 사본 유지 — 기존 설계·무변경).

결정:
- **C-05 배송 정보 블록**: `app/components/order/ItemDeliveryInfo.vue`(props `delivery`·`itemStatusCode`) — 주문 상세 품목 상태 행 바로 아래. `delivery` 있으면 택배사 라벨·송장번호(`select-all`)·**"송장번호 복사"** 버튼·발송일·배송완료일(있을 때) / 없고 품목이 **발송 전 상태(PAID·PREPARING)** 면 "발송 준비 중" / 그 외(ORDERED·CANCELLED·CONFIRMED 등)는 미렌더 — 취소·미결제 품목에 "발송 준비 중"이 찍히는 오안내 방지. 복사는 `navigator.clipboard.writeText` → 인라인 안내(성공 "송장번호를 복사했습니다." / 실패 `console.warn` + "복사하지 못했습니다. 송장번호를 길게 눌러 복사해 주세요.") — 구매자 앱은 토스트 인프라 부재(FE-53 §1-A와 같은 이유). 외부 추적 링크·외부 API 없음(결정 범위 외). 교환품 송장은 클레임 상세 `claim-reshipment` 기존 표시로 충분(무작업).
- **타입**: `types/order.ts` `OrderItemDelivery`(carrier·trackingNo·status·shippedAt·deliveredAt) · `OrderItem.delivery?: OrderItemDelivery | null`. `constants/delivery.ts` `DeliveryStatus`를 BE 정합 `'READY' | 'SHIPPING' | 'DELIVERED'`로 정정(기존 `'PREPARING'`은 BE에 없는 값·비교 사용처 0이라 동작 변화 없음).
- **C-01 타일**: `PENDING_TILES`에 `productPending`("상품 승인 대기" → `/admin/products?status=PENDING`)·`sellerPending`("셀러 승인 대기" → `/admin/members/sellers?status=PENDING`) 추가(6칸·0건이어도 표시·warning 톤·기존 `cols=6 md=3` 그리드 유지 = 데스크톱 4+2). 두 목록 모두 URL query로 필터 복원(`parseAdminProductQuery`·`parseAdminSellerQuery` 기존) → 목록 페이지 수정 없음. 아이콘 `mdiTagArrowDownOutline`·`mdiStoreClockOutline`.

### §1-A 갈림길·채택/기각 근거
- **"발송 준비 중"을 delivery 없는 모든 품목에 표시 【기각: 취소·미결제 품목 오안내】 / PAID·PREPARING만 【채택】** — 프롬프트 "미발송 = 발송 준비 중"의 해석. 다르게 원하면 `PRE_SHIPMENT_STATUSES` 한 곳.
- **6칸 그리드를 md=4(3+3)로 재배치 【기각: 기존 4칸 레이아웃·픽셀 기준 변경 최소화】 / 기존 md=3 유지(4+2) 【채택】**.
- **복사 안내 자동 소멸 【기각: 타이머·추가 상태】 / 다음 조작까지 유지 【채택】**.

### §2 확정 구현 규칙·트랩
- 변경: app 5(`types/order.ts`·`constants/delivery.ts`·`components/order/ItemDeliveryInfo.vue` 신규·`pages/orders/[orderPublicId].vue`) · admin 3(`types/admin-dashboard.ts`·`lib/admin-dashboard-view.ts`·`components/admin/AdminDashboardPending.vue`) · vitest 2(`test/component/OrderItemDeliveryInfo.spec.ts` 신규 6·`test/admin/admin-dashboard-helpers.spec.ts` 6칸) · e2e 3(`claims.spec.ts` ① ORDER_DETAIL delivery 2건·블록 단언·복사 → clipboard readText / `admin-dashboard.spec.ts` ① 6칸·링크 2 / `admin-products.spec.ts` ⑥ 상품 승인 대기 타일 → `status=PENDING` URL·select "판매대기"·API 파라미터).
- 트랩: 컴포넌트 자동 import는 디렉터리 접두(`components/order/ItemDeliveryInfo.vue` → `<OrderItemDeliveryInfo>`) — 파일명을 `OrderItemDeliveryInfo.vue`로 두면 `<OrderOrderItemDeliveryInfo>`가 된다. Playwright clipboard 검증은 `context().grantPermissions(['clipboard-read','clipboard-write'])` 필요(chromium).
- 검증: typecheck 0 · vitest 94파일 **634**(628 + 6) · no-admin-import 통과 · Playwright 콜드 100/103(admin-categories ① 콜드 트랩 1) → 웜 **101/103**(2 skip = seller-password env) · 픽셀 12장 track96-1b 대비 **diff 0**(주문 상세·관리자 대시보드는 기준 12장에 미포함).

### §8 이월
- 주문 목록(`/orders`) 카드에는 송장 미표시 — 상세만. 요구 시 `OrderSummaryResponse` 확장 별 트랙.

## FE-55: 임시 비밀번호 1회 표시 결과 다이얼로그(C-07 b) — 회원 재발급·셀러 구성원 신규 계정 공용 (Track 96-3) (2026-09-21)

배경: Track 96 정찰 A1(임시 비밀번호 Mock SMS로 전달 불가)·C-07 b. BE는 D-204(P1 200 + `temporaryPassword` · P2 201 `AdminSellerMemberAddResponse.temporaryPassword` nullable · no-store). 평문은 결과 다이얼로그 DOM과 복사 버튼 외 어디로도 흐르지 않는다(토스트·console·스토어·useState 금지).

결정:
- **공용 컴포넌트 `layers/admin/app/components/admin/AdminTemporaryPasswordDialog.vue`**: props `open`·`temporaryPassword: string | null`·`recipientLabel?`·`testId?` · emit `closed`만. 평문은 `<code>`(monospace·`user-select: all`)·복사 버튼(`navigator.clipboard.writeText` → 성공 "임시 비밀번호를 복사했습니다." / 실패 "복사하지 못했습니다. 화면의 비밀번호를 직접 옮겨 적어 주세요." 인라인·`console.warn`에는 오류 이름만) · 안내 `TEMPORARY_PASSWORD_DIALOG_NOTICE`(`constants/admin-member.ts`) 2줄 · **닫기 2단**(닫기 → 경고 alert "닫으면 다시 볼 수 없습니다…" + "계속 보기"/"닫기") · `persistent`(바깥 클릭·ESC 불가) · `open`이 false가 되면 복사 안내·확인 단계 소거. **부모 규약: `closed`에서 값을 null로 지운다**(컴포넌트는 값을 소유하지 않는다).
- **P1 회원 상세(`members/[id].vue`)**: `activeDialog` 값에 `'reset-result'` 추가·`temporaryPassword` 로컬 ref. 확인 다이얼로그(문구 "화면에 1회 표시하고 … SMS도 발송") → `resetPassword` → `temporaryPassword` 세팅·결과 다이얼로그(`test-id="member-reset-result"`)·성공 토스트 **제거** → `load()`. `closeResetResult`가 null + 닫기. 422 `MEMBER_ADMIN_ROLE_ASSIGNED` 문구 추가(warning 토스트 + 재조회).
- **P2 구성원 추가 다이얼로그(`AdminSellerMemberAddDialog.vue`)**: `issuedPassword` 로컬 ref. 신규 계정 201에 `temporaryPassword`가 있으면 성공 토스트(평문 없음·"임시 비밀번호를 확인해 전달해 주세요") → 결과 다이얼로그(`seller-member-password-result`·형제 루트) → 닫기 확인 뒤 `closeIssuedPassword`가 null + `emit('done')`(부모 재조회·추가 다이얼로그 닫힘). 기존 회원 연결(키 없음)은 즉시 done(무변경). 결과 창이 열린 동안 제출 버튼 disabled·`reset()`이 `issuedPassword`도 비운다.
- **문구**: `SELLER_MEMBER_NEW_USER_NOTICE` 2번째 줄 "임시 비밀번호는 생성 직후 화면에 1회만 표시됩니다(창을 닫으면 다시 볼 수 없음). 입력한 휴대폰으로 SMS도 발송됩니다. 첫 로그인 후 비밀번호를 변경해야 합니다."(FE-42 "화면에는 표시되지 않으며" 대체).
- **타입·API**: `types/admin-member.ts` `AdminMemberTemporaryPasswordResponse` · `useAdminMembers.resetPassword(): Promise<AdminMemberTemporaryPasswordResponse>` · `types/admin-seller.ts` `AdminSellerMemberAddResponse extends AdminSellerMember { temporaryPassword?: string }` · `useAdminSellers.addMember(): Promise<AdminSellerMemberAddResponse>`.

### §1-A 갈림길·채택/기각 근거
- **결과를 `AdminConfirmDialog` 확장(slot) 【기각: 확인 전용 컴포넌트에 표시·복사·2단 닫기 상태가 섞임】 / 전용 공용 다이얼로그 【채택】**.
- **평문을 컴포넌트가 소유(prop 1회 수신 후 내부 ref) 【기각: 닫힘 뒤 잔존 여부를 부모가 보장할 수 없음】 / 부모 소유 + closed에서 null 【채택】** — 두 부모 모두 로컬 ref.
- **P2 done 즉시 emit + 결과 창을 부모(카드)가 띄움 【기각: 카드·상세 2곳에 평문 상태가 생김】 / 다이얼로그 내부에서 결과 창 → 닫힌 뒤 done 【채택】**.
- **닫기 1단 【기각: 실수 닫힘 = 재발급 강제(세션 재차 종료)】 / 2단 + persistent 【채택】**.

### §2 확정 구현 규칙·트랩
- 변경: admin 9(`AdminTemporaryPasswordDialog.vue` 신규 · `AdminSellerMemberAddDialog.vue` · `pages/admin/members/[id].vue` · `composables/useAdminMembers.ts`·`useAdminSellers.ts` · `types/admin-member.ts`·`admin-seller.ts` · `lib/constants/admin-member.ts`·`admin-seller.ts` · `lib/admin-error-message.ts`) · vitest 2 신규(`test/admin/admin-temporary-password-dialog.spec.ts` 3 · `admin-seller-member-add-dialog.spec.ts` 2) · e2e 2(`admin-members.spec.ts` ⑤ 200 mock·결과 창·복사 readText·토스트 무평문·2단 닫기·body 무평문 / `admin-sellers.spec.ts` ③ 문구·④ 신규 201 mock → 결과 창 → done 순서·상세 재조회 카운트).
- 트랩: vitest에서 `vi.stubGlobal('navigator', {...navigator, clipboard})`를 `createVuetify()` 마운트 **전에** 걸면 `navigator.userAgent` 소실로 vuetify display가 throw(이전 spec의 unstub 누락이 다음 spec까지 오염) → 마운트 뒤 스텁 + `afterEach(unstubAllGlobals)`. 컨테이너에서 `pnpm typecheck`(nuxt prepare) 후에는 dev 서버가 새 컴포넌트를 못 찾아 E2E가 "element not found"로 실패 → E2E 전 컨테이너 재시작(기존 트랩 재확인).
- 검증: typecheck 0 · vitest 96파일 **639**(634 + 5) · no-admin-import 통과 · Playwright 콜드 101/104(admin-sellers ① 콜드 트랩 1) → 웜 **102/104**(101 + 1 신규 ④·2 skip = seller-password env) · 픽셀 12장 track96-3 track96-2 대비 **diff 0**(관리자 다이얼로그는 기준 12장에 미포함).

### 외부 검토 반영(R2 Q6·Q7)
- **P2 fail-closed**: 신규 계정 모드(`mode === 'new'`)에서 201 응답에 `temporaryPassword`가 없거나 빈 문자열이면 성공 토스트·결과 다이얼로그 없이 `toast.danger("… 계정은 생성되었지만 임시 비밀번호를 받지 못했습니다. 회원 상세에서 재발급해 주세요.")` → `emit('done')`(목록 갱신·계정은 이미 존재). 기존 회원 연결(`existing`)은 평문 부재가 정상이라 무변경. vitest RED 선증명: 적용 전 성공 토스트 1회 호출 → 적용 후 GREEN.
- vitest 추가: `admin-seller-member-add-dialog.spec.ts` +2(키 없음·빈 문자열) · `admin-member-detail-reset.spec.ts` 신규 2(P1 페이지 — 모든 토스트 호출 인자에 평문 없음·성공 토스트 0 / 닫기 → 다른 값 재발급 → 이전 평문 DOM 부재 → 닫으면 둘 다 부재). 페이지 spec 트랩: `useRoute`/`useRouter`를 `mockNuxtImport`로 바꾸면 Nuxt 초기화(`router.beforeEach`)가 깨진다 → `mountSuspended(..., { route: '/admin/members/usr_A' })`로 실제 라우터 사용.

### §8 이월
- 관리자 영역 강제 변경(adminAuth 플래그·관리자 비밀번호 페이지)은 D-204 §8.

## FE-56: 관리자 클레임 목록 "필요 액션" 필터 + 대시보드 클레임 처리 대기 타일(C-02) (Track 96-4) (2026-09-22)

배경: Track 96 정찰 C-02(관리자가 후속 처리 대기 클레임을 눈으로 탐색). BE는 D-205(`GET /admin/claims?action=`·`pending.claimFollowup`). 목록 화면은 URL query 단일 소스(FE-28)라 필터 1개·타일 1개 추가로 끝난다.

결정:
- **타입** `AdminClaimActionFilter = Exclude<AdminClaimAction, 'APPROVE' | 'REJECT'> | 'FOLLOWUP'`(BE enum 1:1) · `AdminClaimListQuery.action: AdminClaimActionFilter | null`.
- **상수** `ADMIN_CLAIM_ACTION_FILTER_OPTIONS`(`lib/constants/admin-claim.ts`): "후속 처리 전체"(FOLLOWUP) + 5종 — **라벨은 행 액션 버튼 문구 `ADMIN_CLAIM_ACTION_LABEL`을 그대로 재사용**(필터와 버튼이 같은 말) · 순서 = 처리 흐름 순 · `isAdminClaimActionFilter` 가드.
- **URL ↔ 상태 ↔ API**(`admin-claim-query.ts`): parse는 허용 외 값(APPROVE·REJECT·소문자·미지 값)을 null로 정규화(전체 조회·BE 400 예방) · toRoute/toApi는 값 있을 때만 `action` · `hasActiveClaimFilters`에 포함(빈 상태 문구 분기). `resetQuery`는 기존대로 type·size만 보존 → action 해제.
- **FilterCard** `data-testid="filter-action"` v-select(clearable·`cols=6 md=2`) — 기존 12칸이 차 있어 md에서 2줄째로 wrap(레이아웃 재배치 없음).
- **대시보드 타일** `PENDING_TILES[6] = claimFollowup`("클레임 처리 대기" → `/admin/orders/claims?action=FOLLOWUP`·warning·0건도 "0건"). 7칸 = 기존 `cols=6 md=3` 유지(데스크톱 4+3·모바일 2열 마지막 1칸). 아이콘 `mdiClipboardCheckOutline`. 타일 링크 → 목록이 `?action=FOLLOWUP`을 parse해 select에 "후속 처리 전체" 표시.

### §1-A 갈림길·채택/기각 근거
- **필터를 탭(유형 탭 옆 "처리 필요" 탭)으로 【기각: 탭은 type 소유·유형과 직교하는 축】 / select 【채택】**.
- **허용 외 URL 값을 400으로 노출 【기각: 기존 status·refundStatus와 같이 기본값 정규화가 관례】 / null 정규화 【채택】**.
- **FilterCard 12칸 재배치(keyword md=3 등) 【기각: 픽셀·기존 e2e 스크린샷 변동 최소화】 / wrap 허용 【채택】**.

### §2 확정 구현 규칙·트랩
- 변경: admin 7(`types/admin-claim.ts`·`types/admin-dashboard.ts`·`lib/constants/admin-claim.ts`·`lib/admin-claim-query.ts`·`lib/admin-dashboard-view.ts`·`components/admin/AdminClaimFilterCard.vue`·`components/admin/AdminDashboardPending.vue`) · vitest 3(`admin-claim-query.spec.ts` 기존 4 케이스 action 포함 + 허용 값 케이스 1 · `admin-dashboard-helpers.spec.ts` 7칸·톤 · `admin-claim-filter-card.spec.ts` 신규 2) · e2e 2(`admin-claims.spec.ts` ① mock에 action 필터 + 회수 확인 → `action=CONFIRM_PICKUP`·행 1·새로고침 유지 → 후속 처리 전체 2행 → 초기화 해제 / `admin-dashboard.spec.ts` ① 7칸·href·"N건" 정규식·**테스트 마지막에** 타일 클릭 → `?action=FOLLOWUP`·select 표시).
- 트랩: (1) Vuetify clearable 해제는 `.v-field__clearable .v-icon` 클릭(래퍼 div 클릭은 무반응) (2) e2e에서 타일 클릭 → `goBack()` 뒤 리스트 단언을 두면 재렌더 경합으로 빈 상태 단언이 실패 → 페이지 이동 단언은 테스트 끝에.
- 검증: typecheck 0 · vitest 98파일 **646**(643 + 3) · no-admin-import 통과 · Playwright 콜드 100/104(admin-categories ① 콜드 트랩·admin-dashboard ① goBack 경합 → 수정) → 웜 **102/104**(2 skip = seller-password env) · 픽셀 12장 track96-4 track96-3 대비 **diff 0**(관리자 화면은 기준 12장 미포함).

### §8 이월
- 필터 조합 상호 배타 안내(D-205 §8) · 셀러 진행 단계 필터 없음(D6).

## FE-57: 셀러 판매중지·재판매·품절 셀프 전환 + 관리자 중지 주체 라벨(C-08) (Track 96-5) (2026-09-22)

배경: Track 96 정찰 C-08. BE는 D-206(`POST /seller/products/{id}/sale-status`·`PATCH …/soldout`·`saleStopSource`·422 `PRODUCT_STOPPED_BY_ADMIN`). 셀러 상품 목록은 행 액션이 수정 1개뿐이었고 폼은 "상태는 관리자가 처리" 안내만 있었다.

결정:
- **상수** `SellerSaleStopSource = 'ADMIN' | 'SELLER'`·`SELLER_SALE_STOP_SOURCE_LABEL`("관리자 판매중지"/"셀러 판매중지")·`SellerSaleAction = 'STOP' | 'RESUME'`·`SELLER_SALE_ACTION_LABEL`(`lib/constants/seller-product.ts`) / 관리자 `AdminSaleStopSource`·`ADMIN_SALE_STOP_SOURCE_LABEL`("관리자 중지"/"셀러 중지"·`lib/constants/product.ts`) — 레이어 격리(no-admin-import)라 두 벌.
- **분기 순수 함수** `lib/seller-product-sale-status.ts` `resolveSellerSaleAction(status, saleStopSource)`: SALE → STOP · STOPPED+SELLER → RESUME · STOPPED+ADMIN **또는 주체 불명** → RESUME 비활성 + `SELLER_ADMIN_STOPPED_NOTE`("관리자가 판매중지한 상품입니다. 재판매는 운영자에게 문의하세요."·fail-closed) · 그 외 → 액션 없음(메뉴 미노출). `toSaleStatusTarget`·`saleActionConfirmMessage`(판매중지 문구에 "진행 중 주문은 영향 없음"·재판매 즉시 재노출).
- **목록 행 메뉴** `SellerProductTable`: 수정 버튼 옆 `row-menu`(⋮·액션 있을 때만) → 항목 1(`row-sale-action`·비활성 시 subtitle에 문의 안내) → `saleAction` emit → 페이지가 `SellerProductSaleStatusDialog` 오픈. STOPPED 행은 칩 아래 주체 라벨(`stop-source`).
- **확인 다이얼로그** `SellerProductSaleStatusDialog`(SellerInventoryAdjustDialog 복제): 성공 → success 토스트 + done(목록 재조회) · 422 `PRODUCT_STOPPED_BY_ADMIN`/`PRODUCT_INVALID_STATE`·404 → warning + stale(재조회·그사이 관리자 변경) · 403 SELLER_SUSPENDED → danger 토스트 직접 표시 + cancel(FE-44 §8).
- **수정 화면 판매 관리 카드** `SellerProductSaleStatusCard`(폼 위): 상태 칩·주체 · 판매중지/재판매 버튼(비활성 + 안내) · **수동 품절 스위치 즉시 PATCH**(관리자가 켠 품절도 해제) → changed → 상세 재조회(폼 재마운트·저장 전 수정 내용 초기화됨을 카드 문구로 고지). PENDING·REJECTED는 버튼 없이 "승인·반려는 관리자가 처리" 안내. `SellerProductBasicSection` 안내 문구를 "판매중지·재판매·품절은 위 판매 관리 카드에서" 로 교체(chip은 유지).
- **에러 메시지** `PRODUCT_INVALID_STATE`·`PRODUCT_STOPPED_BY_ADMIN` 추가(`seller-error-message.ts`). `useSellerProducts` +`changeSaleStatus(id, action)`·`changeSoldOut(id, soldOut)`.
- **관리자**: 목록 상태 칩 아래·수정 폼 상태 섹션에 주체 라벨(`stop-source`). `ProductForm.saleStopSource`(표시 전용) · 관리자 상태 전환 후 `STOPPED ? 'ADMIN' : null`로 채움(BE 응답에 주체 없음·D-206 §1-A).

### §1-A 갈림길·채택/기각 근거
- **목록 행 메뉴에 품절 토글 【기각: 목록 응답에 soldoutManual 없음·BE 계약 확장은 결정 밖】 / 상세 카드 스위치 【채택】.**
- **카드 처리 후 상세만 갱신(폼 유지) 【기각: 기본정보 chip·폼 상태가 stale】 / 상세 재조회·폼 재마운트 + 고지 【채택】.**
- **주체 불명 STOPPED를 재판매 가능으로 【기각: BE 백필로 운영에선 불가 상태이나 FE는 fail-closed】.**
- **AdminBulkResultDialog 재사용 【해당 없음: D5 α 단건】.**

### §2 확정 구현 규칙·트랩
- 변경: seller 9(`lib/constants/seller-product.ts`·`lib/seller-product-sale-status.ts`(신규)·`lib/seller-error-message.ts`·`types/seller-product.ts`·`composables/useSellerProducts.ts`·`components/seller/SellerProductTable.vue`·`SellerProductSaleStatusDialog.vue`(신규)·`SellerProductSaleStatusCard.vue`(신규)·`SellerProductBasicSection.vue`·`pages/seller/products/index.vue`·`[id].vue`) · admin 6(`lib/constants/product.ts`·`types/admin-product.ts`·`types/admin-product-form.ts`·`lib/admin-product-form.ts`·`components/admin/AdminProductTable.vue`·`AdminProductForm.vue`).
- vitest: `seller-product-sale-status.spec.ts` 신규 8(순수 함수 2 · 표 행 메뉴 3/5·주체 라벨 · 다이얼로그 성공/422 2종/403 · 카드 버튼 분기 3상태·품절 스위치 성공/실패) · `admin-product-form.spec.ts` +saleStopSource 매핑.
- e2e: `seller-products.spec.ts` ③(mock 항목 주체 부여·sale-status route가 mock 상태 갱신 → 행 메뉴 19/20·판매중지 → POST STOPPED·칩·라벨 → 재판매 → POST SALE · 관리자 중지 행 비활성+문의 문구·Escape) · `seller-product-form.spec.ts` ②(카드 칩·버튼·스위치·안내 문구) · `admin-products.spec.ts` ⑦(`items` 옵션·"셀러 중지"/"관리자 중지").
- 트랩: (1) v-dialog 컴포넌트 vitest는 `open:false`로 mount 후 `setProps({open:true})` + `visualViewport` stub(SellerInventoryAdjustDialog 선례) (2) v-switch는 내부 모델이 먼저 바뀌므로 두 번째 토글 테스트는 `setProps`로 서버 값을 내려준 뒤 클릭 (3) 픽셀 첫 캡처 cart-mobile 10px는 재시작 노이즈(재캡처 diff 0).
- 검증: typecheck 0 · vitest 99파일 **654**(646 + 8) · no-admin-import 통과 · Playwright 콜드 **104/106**(2 skip = seller-password env·신규 3 포함·콜드 실패 0) · 픽셀 track96-5 track96-4 대비 **12장 diff 0**(재캡처·셀러/관리자 화면은 기준 12장 미포함).

### §8 이월
- 일괄 판매중지(D-206 §8) · 목록 행 품절 토글(목록 응답 soldoutManual 추가 시).

### 보정(2026-09-22 · 같은 브랜치) — 관리자 "관리자 중지로 전환" 액션(D-206 보정)
- 순수 함수(`lib/admin-product-view.ts`): `isSellerStopped`·`isEscalation(item, target)`·`statusTargetsFor(item)`(셀러 중지 상품만 기본 전이표 + STOPPED·주체 불명은 fail-closed로 안 열림)·`statusTargetTitle`(그 STOPPED 항목만 "관리자 중지로 전환")·`escalateConfirmMessage`(전환 후 셀러 재판매 불가 고지)·`saleStopSourceAfterAdminChange`(STOPPED→ADMIN·SALE→undefined)·`countEscalated`(bulk 성공 code).
- 목록 `AdminProductTable` 행 메뉴 라벨 분기 → 페이지 `requestStatusChange`가 전환이면 `AdminConfirmDialog`(`admin-escalate-dialog`·warning) 경유 후 `changeStatus` → 행 patch에 saleStopSource 반영·토스트 "→ 관리자 중지로 전환". 수정 폼 `AdminProductForm` 상태 전환 메뉴 동일(다이얼로그 내장). `AdminBulkResultDialog`에 "성공 중 N건 관리자 중지로 전환" 줄(`bulk-result-escalated`).
- 트랩: Playwright `page.route`는 **나중 등록이 우선** — `**/admin/products/prd_*`(continue) 핸들러보다 뒤에 sale-status mock을 등록해야 실 BE로 새지 않는다.
- 테스트: vitest `admin-product-helpers.spec.ts` +1(전이·라벨·문구·주체·집계) · e2e `admin-products.spec.ts` ⑧(관리자 중지 행 STOPPED 비활성 / 셀러 중지 행 "관리자 중지로 전환" → 다이얼로그 → POST STOPPED 캡처 → 라벨 "관리자 중지"·토스트).

### 외부 검토 반영(2026-09-22 · 같은 브랜치) — 판매 관리 조작 시 수정 폼 보존(R2 Q9 major 수용)·fail-closed 실측(R2 Q10)
- **폼 보존 구조**: `SellerProductForm`은 `initial`을 마운트 시 `structuredClone`해 자기 상태로 쓰므로 부모의 `detail` 교체는 폼에 닿지 않는다. 카드 조작은 `formKey`를 올리지 않고 `detail`만 바꾼다 — 품절 성공은 PATCH 응답(셀러 상세)을 `updated`로 올려 부모가 `detail`을 국소 교체(추가 GET 0) · 판매중지/재판매 성공·stale은 부모 `refreshCard()`(상세 GET → `detail`만·404는 "찾을 수 없음" 화면·그 외 실패 토스트) · 품절 실패는 재조회 없이 카드 로컬 스위치 값(`soldOut` ref·prop watch 동기화)을 서버 값으로 원복하고, 상태가 바뀐 사유(422 PRODUCT_STOPPED_BY_ADMIN·PRODUCT_INVALID_STATE)와 404만 `stale`. 폼 재마운트는 초기 로드·저장 성공(`onSaved`)만. 고지 문구 "저장 전 수정 내용은 초기화됩니다" 삭제 → "(아래 폼의 저장 전 수정 내용은 유지)". 기본정보 섹션의 상태 chip은 폼 clone 값이라 카드 조작 후 stale(저장·재진입 시 갱신·카드 chip이 최신).
- **fail-closed 실측(R2 Q10)**: 비활성 `v-list-item`의 `@click`은 루트 요소 fallthrough라 프로그램 클릭·키보드로 발화한다(vitest에서 saleAction emit 실측) → `SellerProductTable` 항목 emit 앞에 `!disabled` 가드 추가. 카드 버튼은 실제 `<button disabled>`라 발화하지 않음.
- 테스트: vitest `seller-product-sale-status.spec.ts` 10(+2: 카드 비활성 버튼 클릭 → saleAction/changeSaleStatus 0 · 표 행 메뉴 ADMIN/주체 없음 항목 비활성+문의 문구+클릭 emit 0 / SELLER 항목 → RESUME; 품절 케이스를 updated/500 원복·재조회 없음/404 stale로 재작성) · e2e `seller-product-form.spec.ts` ⑥(설명 입력 미저장 → 품절 성공 PATCH·입력값 유지·상세 GET 0 → `unrouteAll` 후 500 mock → 입력값 유지·스위치 원복·GET 0).
- 트랩: (1) Vuetify `v-textarea`는 sizer용 textarea가 하나 더 있어 `locator('textarea')`가 2개 → `getByRole('textbox', { name })` (2) PowerShell `Get-Content -Raw`+`Set-Content -Encoding utf8`은 cp949로 읽어 한글을 깨뜨리고 BOM을 붙인다 → 소스 편집은 Python UTF-8로만.
- 검증: typecheck 0 · vitest 99파일 **657**(655 + 2) · Playwright 콜드 104/108(admin-categories ①·seller-bank-account ① 콜드 트랩) → 웜 **106/108**(2 skip = seller-password env) · 픽셀 track96-5r track96-4 대비 **12장 diff 0**.

## FE-58: 관리자 비활성 v-list-item 클릭 가드 — :disabled 조건을 핸들러가 재검사 (Track 96-7) (2026-09-22)

배경: 정찰(docs/track-96/recon-report-cleanup.md STEP 1). Vuetify 3.13.4 `VListItem.js:195-198`(컨테이너 `/app/node_modules/.pnpm/vuetify@3.13.4_…/lib/components/VList/VListItem.js`)은 `onClick`에서 `emit('click', e)`를 **`isClickable`(= `!props.disabled && …`, :115) 검사보다 먼저** 실행한다. 비활성 방어는 CSS `.v-list-item--disabled { pointer-events: none }`(VListItem.css:132-136)와 tabindex 미부여(:246)·onKeydown 미부착(:250)뿐이라 마우스·키보드는 막히지만 프로그래밍 클릭(`HTMLElement.click()`·`dispatchEvent`·Playwright force)은 부모 `@click`까지 도달한다. 관리자 레이어에서 `:disabled`+`@click`을 동시에 가진 v-list-item 5곳(v-btn 제외·전수) 모두 핸들러에 재검사가 없었고, 그중 2곳은 BE `POST /admin/products/{id}/sale-status|approve|reject` → 422로 새는 경로였다. FE-57(:2392) 외부 검토 R2 Q10에서 셀러 `SellerProductTable`에 같은 가드를 넣으며 "LT 후보"로 남긴 항목.

결정:
- **가드 방식 = 조건 공유**: `:disabled`가 쓰는 식을 함수로 뽑아 템플릿과 핸들러가 **같은 함수 값**을 쓴다(단순 ref 1개면 ref 직접 재검사). 템플릿 구조·표시·data-testid 무변경(속성 식만 교체).
  - `AdminProductTable.vue` :64 `statusTargetDisabled(item, target)` → :190 `:disabled` · :68-71 `requestStatusChange` 첫 줄 가드 후 `emit('changeStatus')` → :192 `@click`
  - `AdminProductForm.vue` :125 `statusTargetDisabled(target)`(computed `allowedTargets` 기반) → :211 · :129-130 `requestStatusChange` 첫 줄 가드 → :213
  - `AdminSellerMemberAddDialog.vue` :106 `resultDisabled(member)`(= `submitting || alreadyMember`) · :109-112 `selectMember` → :222
  - `AdminOperatorProvisionDialog.vue` :68-71 `selectMember`(`submitting` 재검사) → :124
  - `AdminSellerProvisionDialog.vue` :97-100 `selectOwner`(`submitting` 재검사) → :209
- 원인 박제: Vuetify `VListItem.js:195-198` emit 선행(위 배경). 신규 v-list-item에 `:disabled`+`@click`을 함께 쓰면 같은 가드를 둔다(v-btn은 실제 `<button disabled>`라 불요).

### §1-A 갈림길·채택/기각 근거
- **5곳 전부 핸들러 가드 【채택: 관례 1개로 통일·이후 API 연결이 바뀌어도 함정 없음】** / 422 실재 2곳(ProductTable·ProductForm)만 【기각: 나머지 3곳(제출 중 선택 교체 → 토스트 대상 불일치)에 동일 함정 잔존】.
- **`v-if` 미렌더(AdminOrderTable 방식) 【기각: 비활성 사유(왜 못 고르는지·"이미 이 셀러의 구성원입니다" subtitle)의 가시성 상실】**.
- **템플릿 인라인 `!disabled && …`(FE-57 셀러 방식) 【기각: 조건식이 두 곳에 복제돼 어긋날 수 있음·함수 공유로 통일】**.

### §2 확정 구현 규칙·트랩
- 변경: admin 컴포넌트 5(위) · vitest 2: `test/admin/admin-seller-member-add-dialog.spec.ts` +1(:146 이미 구성원 결과 → `v-list-item--disabled` 확인 → `HTMLElement.click()` → `seller-member-selected` 없음·`addMember` 0·done 없음) · `test/admin/admin-product-table.spec.ts` 신규 1(SALE 행 `row-menu` 열기 → `row-status-SALE` 비활성 클릭 → `changeStatus` emit 없음 → `row-status-STOPPED` 클릭 → emit 1회 `[SALE_ITEM, 'STOPPED']`).
- **RED 선증명**: 각 컴포넌트 .vue만 `git stash` → SellerMemberAdd `expected <div …> to be null`(선택 단계로 넘어감) · ProductTable `expected [ [ {…}, 'SALE' ] ] to be undefined` → pop 후 GREEN.
- 트랩: (1) `AdminProductTable`은 `defineModel('selected', { required: true })`라 mount props에 `selected: []` 필수(누락 시 Vue warn) (2) v-menu 항목은 활성자 `row-menu` 클릭 후에만 DOM에 존재(`attachTo: document.body` + `visualViewport` stub·FE-57 트랩(1) 동일) (3) 컨테이너 vitest 전량 실행이 기본 워커(16)에서 `Hook timed out 10000ms`(setupNuxt) 13~1파일 반복 → `--maxWorkers=2`로 100파일 전부 통과(호스트 잔류 `find` 프로세스·gradle 데몬 종료 후에도 재현·환경 부하 트랩).
- 검증: typecheck 0 · vitest `--maxWorkers=2` 100파일 **659**(657 + 2) · no-admin-import 통과 · Playwright 3역할 env 주입 `--workers=2` 콜드 105/108(admin-categories ① 콜드 트랩) → 단독 재실행 1 passed = **106/108**(2 skip = seller-password env·기준 동일) · e2e `admin-products.spec.ts` ①⑧의 `v-list-item--disabled` 단언 통과(표시 무변경).
- LT 후보 해소: FE-57 :2392 "비활성 v-list-item @click fallthrough"는 본 박제(관리자 5곳 전수 + 원인 라인)로 해소 표시 — live-traps 승격 없음.

### §8 이월
- 셀러 `SellerProductTable.vue:143` 인라인 `!disabled &&` 가드를 함수 공유 방식으로 통일 — 동작 동일이라 요구 발생 시.
- 외부 검토: C(FE 전용).

## FE-59: 결제창 URL 분기 — redirectUrl origin으로 Mock 내부 경로/실 PG 외부 이동 판정 (Track 97 D-209) (2026-09-22)

배경: 체크아웃 응답 `payment.redirectUrl`을 `checkout/index.vue`의 `goToMockPayment`가 `new URL()`로 파싱해 쿼리 `attemptKey`·`amount`·`method`를 내부 `/payment/mock`으로만 옮겼다(정찰 docs/track-97/recon-report-readiness.md §1-4). 실 PG 결제창 URL이 오면 쿼리 파싱 가정이 깨져 attemptKey 빈 문자열로 `/payment/mock` "결제 정보가 없습니다"에 빠지고, 외부로 이동하는 분기가 없었다. BE는 D-209로 `zslab.payment.gateway` 프로퍼티 게이트를 갖췄으므로 FE도 실 PG URL을 받았을 때 코드 수정 없이 동작해야 한다.

결정:
- **판정은 순수 함수** `frontend/app/lib/payment-redirect.ts:13-29` `resolvePaymentRedirect(redirectUrl, location)` → `{ kind: 'mock', path }` | `{ kind: 'external', url }`. `url.origin !== MOCK_PG_ORIGIN`(:15)이면 external(URL 그대로), 같으면 기존과 동일한 `/payment/mock?attemptKey&amount&method&orderPublicId` 경로(:18-27·Location 헤더 마지막 세그먼트 = orderPublicId). URL 파싱 실패는 `new URL()`의 TypeError를 그대로 전파(기존 동작·호출자 catch가 일반 오류 안내).
- `MOCK_PG_ORIGIN = 'https://mock-pg.zslab.local'` — `frontend/app/lib/constants/payment.ts:7`(BE `MockPaymentGateway.java:21` `MOCK_CHECKOUT_BASE`와 같은 리터럴·주석으로 상호 참조).
- 호출부 `frontend/app/pages/checkout/index.vue:149-156` `goToPayment`: external → `navigateTo(redirect.url, { external: true })`, mock → `navigateTo(redirect.path)`. 호출 지점 :203. `/payment/mock` 페이지·`useCheckout.sendPaymentCallback` 무변경.

### §1-A 갈림길·채택/기각 근거
- **origin 판별 【채택: BE Mock URL이 고정 리터럴이라 판정이 결정적·쿼리 유무보다 명확·실 PG는 코드 무수정】** / FE 설정 키(runtimeConfig `paymentGateway` 등)로 mock/실 분기 【기각: BE `PAYMENT_GATEWAY`와 이중 관리·둘이 어긋나면 결제창 진입 실패】 / 쿼리 `attemptKey` 존재 여부 【기각: 실 PG URL도 임의 쿼리를 가질 수 있음】 / BE 응답에 provider 필드 추가 【기각: 계약 변경·이번 범위 밖(D-209 §1-A)】.
- **`navigateTo(url, { external: true })` 【채택: Nuxt 관례·SSR/CSR 공통】** / `window.location.assign` 【기각: SSR 가드 필요·테스트 어려움】.

### §2 확정 구현 규칙·트랩
- 변경: `lib/payment-redirect.ts` 신규 · `lib/constants/payment.ts` +`MOCK_PG_ORIGIN` · `pages/checkout/index.vue`(`goToMockPayment` → `goToPayment`·판정 위임) · `test/unit/payment-redirect.spec.ts` 신규 5(Mock origin 경로·Location 없음·외부 origin·스킴/포트 상이 → external·잘못된 URL TypeError).
- 검증: typecheck 0 · vitest `--maxWorkers=2` 전체 651 passed·13 skipped(664 = 659 + 5·1회차 Hook timeout 2파일은 단독 재실행 통과·LT-33) · Playwright 106/108(기준선) — `e2e/mock-payment.spec.ts` 무수정 통과가 Mock 경로 회귀 증거 · 로컬 API 수동 1회에서 redirectUrl origin = `https://mock-pg.zslab.local` 확인.
- 트랩: 호스트 `node_modules`에 `vite-plugin-vuetify`가 없어 호스트 vitest 기동 실패 → 컨테이너 `docker exec zslab_mall_frontend` 실행(D-209 §2 트랩 3).

### §8 이월
- 실 PG 전환 시 결제창에서 돌아오는 return URL 페이지 신설·`pages/payment/mock.vue`·mock e2e 처리(docs/architecture-baseline/real-service-switch-guide.md §1-1·§1-6).
- 외부 검토: C(FE 전용).

## FE-60: 워크스루 도구 — 운영 시나리오 클릭 수 실측 + 매뉴얼 스크린샷 (Track 98) (2026-09-22)

배경: 개선 라운드(운영 편의)와 매뉴얼 3파트가 같은 입력을 필요로 한다 — "이 업무를 끝내는 데 몇 번 눌러야 하는가"와 "각 단계 화면". 정찰(docs/track-98/recon-report-screenshot.md)에서 (1) 액션 카운터 헬퍼 0건(유사 선례는 `page.on('request')` 4곳)·`test.step`·`framenavigated`·tracing 직접 사용 0건 (2) `playwright.config.ts:22` `trace: 'on-first-retry'` + 로컬 `retries: 0`이라 trace.zip이 생성되지 않음 (3) 매뉴얼 스크린샷은 `playwright-report/stepNNN-live.mjs` 26개가 이미 같은 일을 하고 있으나 1회성·비재현(러너 밖 독립 스크립트·번호 파일명 관례만 공유) (4) 시드 데이터는 시나리오가 소비하면 복원되지 않고(`--force` 가드·초기화 명령 없음) 배송완료 품목은 7일 뒤 자동 구매확정으로 사라짐 — 을 실측했다.

결정:
- **데이터 전략 α(기준 상태 덤프 → 복원 → 실행)**: `scripts/walkthrough/prepare.py`(멱등 보충 + 충족 표) → `dump.py`(`frontend/playwright-report/walkthrough-db/baseline.sql`·gitignored) → 매 실행 전 `restore.py --yes`. 복원 0.8초·백엔드 헬스 재확인까지 포함.
- **실행 형태 = 별도 설정** `frontend/playwright.walkthrough.config.ts`(testDir `walkthrough` · testMatch `**/*.walkthrough.ts` · workers 1 · retries 0 · trace on · 뷰포트 1440×900 · fullPage false · webServer 미설정). 기존 `playwright.config.ts`(testDir `e2e`·기본 testMatch `*.spec.ts`)와 디렉토리·파일명이 모두 달라 섞이지 않는다.
- **인증 = 기존 `e2e/helpers/login.ts` `loginAs` 재사용**(러너 안에서 실행하므로 `test.skip`·`test.info()`가 그대로 동작). 데모 로그인 버튼(rate limit 60s/30회)을 쓰지 않는다.
- **계측 2가지**: 액션 래퍼(`Walkthrough.click`·`check` = 클릭 / `fill`·`selectOption`·`select` = 입력 필드 수)와 URL pathname 변경 감지(`page.on('framenavigated')` 메인 프레임·진입 화면 제외·query만 바뀌는 필터/탭은 미계상). 래퍼를 거치지 않은 조작은 세지 않는다.
- **산출물**: `playwright-report/walkthrough/{역할}/{시나리오}/{NN}-{단계}.png` + `metrics.json`, globalTeardown이 `summary.json`·`summary.md`로 합친다. 전부 gitignored(`frontend/.gitignore:34`).
- **시나리오 7**: 관리자 3(클레임 취소 승인 · 정산 확정→지급 · 셀러 입점 승인) · 셀러 2(출고 · 판매중지→재판매) · 구매자 2(구매확정 · 반품 신청).

### §1-A 갈림길·채택/기각 근거
- **데이터 전략 α(덤프·복원) 【채택: 복원 0.8초·시나리오가 상태를 전이시켜도 매 실행이 같은 출발점·2회 연속 실행 계측 완전 일치로 재현성 실증】** / β(매 실행 전 API로 필요한 데이터를 새로 만들기) 【기각: 시나리오 7개마다 선행 데이터 생성 경로를 따로 구현해야 하고(클레임·정산·셀러 상태까지) 작업량이 도구 본체보다 커진다】 / γ(1회성 스크립트 + 수동 복구) 【기각: `stepNNN-live.mjs` 26개가 이미 그 방식이고 재실행이 안 돼 개선 라운드의 전/후 비교에 쓸 수 없다】.
- **실행 형태 별도 config 【채택: 기존 e2e 기준선(106/108)을 건드리지 않고 workers·retries·trace를 계측 전용으로 잡을 수 있다】** / 독립 `.mjs`(pixel.mjs 선례) 【기각: `loginAs` 재사용 불가(러너 API 의존)·fixture·리포터·trace를 직접 만들어야 한다】 / 기존 config에 project 추가 【기각: `e2e/` 아래 spec으로 들어가면 전량 실행 기준선이 바뀐다】.
- **입력 계측 단위 = 필드 수 【채택: "몇 개를 채워야 하는가"가 운영 부담 지표】** / 키 입력 수 【기각: 값 길이에 좌우돼 시나리오 간 비교 불가】. Vuetify select는 DOM 클릭 2회지만 입력 1로 센다(주석 명시).
- **화면 이동 = pathname 변경 【채택: 탭·필터(query 변경)는 같은 화면 안 조작이므로 이동으로 보지 않는다】** / history 변경 전부 【기각: 필터 1회가 이동 1회로 잡혀 과대계상】.
- **구매자 두 시나리오의 데이터 분리**: 배송완료 **주문** 2건을 `prepare.py`가 보장하고 반품은 첫 번째·구매확정은 마지막 주문을 쓴다 【채택: 실행 순서(알파벳)가 고정이라 결정적】 / 같은 주문 공유 【기각: 1차 실행에서 반품 신청이 주문 상태를 바꿔 구매확정 시나리오가 대상 주문을 찾지 못했다(실측 실패)】.

### §2 확정 구현 규칙·트랩
- 신규: `scripts/walkthrough/{common,prepare,dump,restore}.py` + `README.md` · `frontend/playwright.walkthrough.config.ts` · `frontend/walkthrough/helpers/{walkthrough,summary}.ts` · 시나리오 7 · `package.json` script `walkthrough`.
- 운영 데이터 보호(CLAUDE.md): `restore.py`는 덤프의 DROP/CREATE를 실행하므로 `SPRING_PROFILES_ACTIVE=local` 가드 + `--yes` 필수 + 로컬 컨테이너(`zslab_mariadb`) 존재 확인. DB 비밀번호는 `MYSQL_PWD` 환경변수로만 전달(argv·로그 노출 0).
- 검증(로컬·2026-09-22): 복원 → 실행 **2회 연속 7/7 통과**, 두 실행의 (클릭·입력·화면 이동·스크린샷) 7행 **완전 일치**, PNG 36장 동일. 기존 설정 목록 `npx playwright test --list` = 108 tests in 32 files·walkthrough 0건. 워크스루 설정 목록 = 7 tests in 7 files. `npm run typecheck` 0(에러 없음).
- 계측 결과 1차 기준선: 관리자 클레임 승인 4클릭·0입력·1이동 / 정산 확정→지급 6·0·2 / 셀러 입점 승인 4·1·2 / 셀러 출고 3·2·1 / 판매중지→재판매 6·0·0 / 구매자 반품 신청 3·2·2 / 구매확정 3·0·1 — **합계 29클릭·5입력·9이동**.
- 트랩: (1) gateway는 `/api/`만 backend로 넘기므로(`docs/infra/05-ssl-domain.md:72-77`) `/actuator/health`는 프런트로 가 404 — 복원 후 헬스 확인은 `docker exec zslab_mall_backend curl`로 한다. (2) 출고 완료 조건을 "행 상태 칩 = 배송중"으로 잡으면 실패한다 — 출고된 품목은 `status=PAID` 목록에서 빠지므로 "행이 사라짐"이 완료 조건이다. (3) mock 결제 콜백은 200 + 빈 본문이라 API 헬퍼가 본문 없음을 오류로 보면 안 된다. (4) `typecheck`(vue-tsc)는 `.nuxt/tsconfig.*`의 include에 `walkthrough/`가 없어 **워크스루 파일을 검사하지 않는다**(e2e도 동일) — 별도 `npx tsc --noEmit --ignoreConfig`로 0건 확인했다.

### §8 이월
- 스크린샷 가변 값(경과 N일·일시·주문번호) 마스킹은 매뉴얼 단계에서 결정(현재 미적용).
- 시간 기반 배치(자동 구매확정 7일·자동 취소 30분) 킬스위치는 README에 절차만 적고 기본 미사용 — 기준 상태를 오래 재사용하게 되면 재검토.
- 관리자 반품 검수·교환 5단계, 셀러 재고 입고, 관리자 상품 승인 등 나머지 시나리오는 개선 라운드에서 필요해지면 추가.
- 구매자 주문 상세의 클레임 요청 버튼(`반품 요청`·`교환 요청`)과 클레임 접수 완료 문구에 `data-testid`가 없어 텍스트 셀렉터를 쓴다 — 문구 변경 시 시나리오가 깨진다.
- 외부 검토: C(개발 도구·CI 무관).

## FE-61: 라운드 2 개선 — 교환 검수 연결 · 출고 택배사 기억 · 가격 변경 확인 · 구매자 문구 (Track 99-A) (2026-09-23)

배경: 라운드 2 워크스루(18개 시나리오 실측·`docs/track-99/recon-report-round2.md`)에서 나온 관찰을 근거로 고친다. 교환 전 과정이 16클릭·같은 목록으로 3번 돌아와 같은 행을 다시 찾았고, 출고는 건당 2입력 중 1개가 매번 같은 택배사였으며, 판매가는 확인 절차 없이 바로 저장됐다(판매중 상품에 즉시 반영). 구매자 화면은 승인·검수 주체를 "판매자"라고 안내하지만 실제 주체는 관리자였다(셀러 클레임 API는 조회 전용).

결정:
- **교환 검수 합격 → 교환품 발송 이어 열기**: `AdminClaimInspectDialog`의 `done`이 검수 결과(PASS/FAIL)를 싣고, 목록이 갱신된 뒤 같은 클레임 행을 다시 찾아 `shouldChainExchangeShipment(type, result, availableActions)`가 참일 때만 발송 다이얼로그를 연다. 세 조건 중 하나라도 어긋나면(반품·불합격·경합으로 액션 소멸) 현행대로 목록에 머문다.
- **출고 택배사 직전 값 기억**: 성공한 택배사를 역할별 쿠키(`admin_last_carrier` path=/admin · `seller_last_carrier` path=/seller · 30일)에 담고 다음 출고 다이얼로그의 기본 선택으로 쓴다. 해석은 `parseLastCarrier`가 전담하며 값 집합 밖이면 null(= 현행 "기본 선택 없음").
- **판매가 변경 확인**: 수정 모드에서 직전 저장 시점 판매가와 다를 때만 저장 전 확인 다이얼로그("전 → 후 (변동률)"). 절대 변동률 50% 이상이면 강조 문구를 함께 보인다. 가격 불변 저장·신규 등록은 현행(확인 없음).
- **구매자 문구**: 승인·검수 주체를 "판매자" → **"쇼핑몰"**(8곳). 환불 반영 시점 안내 1줄(`REFUND_TIMING_NOTICE`)을 클레임 상세 환불 상태 아래와 취소·반품 접수 완료 화면에 둔다 — 기간·숫자는 적지 않는다.
- **소규모 교정**: 검수 다이얼로그 제목 유형 분기(`inspectDialogTitle`) · 교환품 배송완료 확인 다이얼로그의 `data-testid` → `test-id` prop.

### §1-A 갈림길·채택/기각 근거
- **택배사 저장소 = 쿠키 【채택: 관리자·셀러 화면은 SSR이라 localStorage는 서버 렌더 단계에서 쓸 수 없고, 이 저장소에 브라우저 저장 선례가 0건이다(정찰 §5-3). 세션은 전부 `useCookie`라 관례가 이미 있다】** / localStorage 【기각: SSR 가드(`import.meta.client`)를 새로 깔아야 하고 선례가 없다】 / 서버 저장(셀러 설정 필드) 【기각: BE 계약 변경·이번 트랙 범위 밖】.
- **가격 확인 = 변동 시에만 【채택: 매번 확인을 받으면 상품명·설명만 고치는 저장까지 한 단계 늘어 안전장치가 소음이 된다】** / 모든 수정 저장 확인 【기각: 위와 같음】 / 확인 없이 토스트로만 알림 【기각: 이미 반영된 뒤라 되돌릴 수단이 없다】.
- **교환 연결 조건에 `availableActions` 포함 【채택: PASS를 보냈어도 갱신된 행이 발송 등록을 허용하지 않으면(경합·중복 처리) 열어야 할 다이얼로그가 아니다. BE 판정을 그대로 따르는 기존 원칙(FE-29)과 같다】** / 유형·결과만 보고 열기 【기각: 경합 시 빈 다이얼로그가 열린다】.
- **구매자 문구 = "쇼핑몰" 【채택: 실제 주체는 관리자지만 구매자에게 운영 조직도를 설명할 이유가 없다. "쇼핑몰"은 주체를 틀리게 말하지 않으면서 구매자 시점에서 자연스럽다】** / "운영자·관리자" 【기각: 구매자에게 내부 역할 용어】 / 현행 유지 【기각: 사실과 다르다】.
- **환불 안내 = 기간 미기재 1줄 【채택: 단계 안내가 이미 "소요 기간은 적지 않는다(시스템이 보장하지 않음)"를 원칙으로 두고 있다(`claim-timeline.ts`)】** / "영업일 3~5일" 등 기간 명시 【기각: 결제수단·PG에 좌우돼 보장할 수 없다】.

### §2 확정 구현 규칙·트랩
- 신규: `app/lib/utils/last-carrier.ts`(parseLastCarrier·수명 상수) · `app/lib/utils/price-change.ts`(priceChangeOf·Message·Warnings·임계 50) · `admin-claim-view.ts`에 `inspectDialogTitle`·`shouldChainExchangeShipment`.
- `priceChangeOf`는 폼 값이 `number | null`이라 null을 받아 null을 돌려준다(미입력은 확인 대상 아님 — 저장 전 검증이 막는다).
- 원래 가격은 `props.initial`이 아니라 **직전 저장 시점 값**(`savedBasePrice`)과 비교한다 — `props.initial`로 비교하면 같은 화면에서 두 번째 저장 때 안 바뀐 가격도 확인을 묻는다.
- 셀러 레이어에는 공용 확인 다이얼로그가 없어 `SellerProductForm` 안에서 v-dialog를 조립한다(관리자는 `AdminConfirmDialog` 재사용 + `warningLines`·`warningEmphasis`).
- `exchange-delivered-dialog`는 속성으로 넘겨도 v-dialog 루트에 fallthrough돼 **찾기는 됐다**(e2e가 사용 중). 어긋난 것은 ok/cancel 버튼 접미사였고, `test-id` prop으로 고치면서 워크스루 셀렉터를 함께 바꿨다(e2e는 셀렉터 그대로 유효).
- 워크스루 전후(2회 연속 동일): 교환 전 과정 16·4·5 → **15**·4·5(발송 다이얼로그 자동 열림) · 3건 연속 출고 7·**6**·1 → 7·**4**·1(2·3건째 택배사 재선택 불필요) · 상품 가격 수정 **3**·1·2 → **4**·1·2(확인 1클릭 증가·의도한 안전장치). 합계 86·**33**·28 → 86·**31**·28. 나머지 15개는 불변.
- 검증(로컬·2026-09-23): typecheck 0 · vitest 676(신규 `test/unit/ops-round2-helpers.spec.ts` 12) · Playwright 106 passed / 2 skipped(기준선 유지·대시보드 칸 수·가격 확인으로 e2e 4 spec 갱신) · 워크스루 18/18 2회 연속 수치 일치.

### §8 이월
- 라운드 2 관찰 리포트(`docs/track-99/recon-report-round2.md`) 정정 2건: (1) 송장 정정 다이얼로그는 이미 현재 값을 prefill한다 — 재입력 필드는 사유 1개뿐 (2) `exchange-delivered-dialog`는 실제로 렌더된다. 두 건 다 정찰(`recon-report-round2-impl.md` §7)에서 근거가 뒤집혔다.
- 구매자 주문 목록 카드의 클레임 진행 표시는 BE `OrderSummaryResponse`에 품목·클레임 필드가 없어 FE만으로는 불가(PROGRESS 다음 4번 "구매자 주문내역 통합"으로 이월).
- 대시보드 칸 수가 관리자 8(md 4+4)·셀러 5(md 4+1)로 늘어 셀러는 한 칸 남는 줄이 생긴다 — 배치는 매뉴얼 스크린샷 단계에서 판단(96-2 보류 항목과 함께).
- 워크스루 확장(Track 99·시나리오 7 → 18개)·계측 헬퍼 확장(segment·note·브라우저 오류 수집)도 이 트랙 산출물이다 — 실행 절차는 `scripts/walkthrough/README.md`.
- 외부 검토: A(PR 등급 적용·FE 범위 지적 없음).

## FE-62: 라운드 3 예외·실수 복구 — 거부 확인·철회 · 회수 대기 표시·대행 · 처리 이력 · 재고 경고 · 구매자 취소 (Track 101-A) (2026-09-23)

배경: 라운드 3 정찰(`docs/track-101/recon-report-round3.md`)이 화면 쪽에서 잡은 것은 넷이다. (1) FE 전체에서 확인 없이 1클릭으로 실행되는 **불가역** 조작은 상품 거부 하나뿐이었다(§2-1 — 나머지 즉시 반영 조작은 전부 가역). (2) 회수 송장을 기다리는 클레임 행은 `availableActions`가 빈 목록이라 "관리" 칸에 **"—"만** 남고 `returnCaption`도 비어, 무엇을 기다리는지 화면에 한 글자도 없었다(§3-1). (3) 감사 로그를 읽는 화면이 관리자 주문 상세의 취소 사유 1곳뿐이었다(§2-4). (4) 가격·재고·금액에 상·하한이 없어 자릿수 오타가 그대로 통과했다(§2-3).

결정:
- **상품 거부 확인**: 관리자 상품 **목록 행·상세(수정 화면)** 양쪽에서 `REJECTED` 목표만 확인 다이얼로그를 경유한다(판정 `isRejection`·문구 `rejectConfirmMessage` 공유)("거부된 상품은 판매대기로 돌아가지 않으며, 되돌리려면 거부 철회를 해야 합니다"). 승인·판매중지·재판매는 가역이라 현행 즉시 반영 유지. 일괄 변경은 기존 확인 그대로이고 애초에 `SALE|STOPPED`만 허용해 일괄 거부가 불가능하다.
- **거부 철회 액션**: 상태 전환 메뉴와 **별도 항목**("거부 철회")으로 목록 행·상품 상세 양쪽에 둔다. 사유가 필요해 전용 다이얼로그(`AdminProductWithdrawRejectionDialog`)가 호출·토스트까지 소유한다. `ADMIN_PRODUCT_ALLOWED_TRANSITIONS`의 `REJECTED: []`는 그대로 둔다(상태 전환 메뉴 목표가 아니다).
- **회수 대기 표시·대행 등록**: 순수 함수 `isWaitingForReturnShipment`/`pickupWaitingLabel`(유형 RETURN·EXCHANGE + APPROVED + 회수 송장·회수 확인 둘 다 없음)로 판정해 "관리" 칸에 **"구매자 회수 송장 등록 대기"** + **"회수 송장 대행 등록"** 버튼을 낸다. 다른 이유로 액션이 없는 행은 기존 "—" 유지.
- **이력 행위자 표기**: 행에 "역할 + 이름"(`auditActorText`)을 찍고 이메일은 `title` 속성으로만 보조한다. 이름이 없는 행(스케줄러·해소 불가한 과거 행)은 역할만 보여 준다.
- **처리 이력 섹션**: 공용 `AdminAuditLogSection`(loader 주입)을 정산 상세에 상시, 클레임은 관리자 주문 상세의 클레임 행마다 "처리 이력" 토글로 편다(한 번에 하나). 첫 페이지만 읽고 더 있으면 건수만 알린다. 라벨링(행위·행위자·필드)은 전부 `admin-audit-view.ts` 순수 함수가 맡는다.
- **재고 대량 조정 경고**: 공용 임계 `INVENTORY_ADJUST_WARN_THRESHOLD = 1000`(절댓값). 셀러 입·출고 다이얼로그는 인라인 경고 + 버튼 문구 변경("확인하고 입고")으로 1회 확인, 관리자 상품 폼은 저장 체인에 확인 다이얼로그를 하나 더 끼운다. **값 자체는 막지 않는다**.
- **구매자 클레임 취소**: 클레임 상세에 접수(REQUESTED) 상태에서만 "요청 취소" → 인라인 확인 패널("취소하면 이 요청은 종료되며 되돌릴 수 없습니다") → 호출. 422는 최신 상태 재조회.
- **422 문구**: `CLAIM_STATE_INVALID`만 BE detail을 코드 문구보다 우선(`DETAIL_FIRST_CODES`·관리자/셀러 동일). 구매자는 이미 detail 기반 문구 매칭(`claim-request-error.ts`)이라 변경 없음.

### §1-A 갈림길·채택/기각 근거
- **거부 확인 판정·문구 = `admin-product-view.ts` 공유 【채택: 목록과 상세가 같은 전환을 서로 다른 문구로 물으면 운영자가 두 화면을 다른 기능으로 읽는다. 목록에 인라인으로 있던 문구를 함수로 뽑아 양쪽이 같은 것을 쓴다(`escalateConfirmMessage` 선례와 같은 자리)】** / 상세에 문구 복제 【기각: 중복 정의·표류】.
- **이력 행위자 = "역할 + 이름", 이메일은 title 【채택: 이력 행은 시각·행위자·행위·변경 요약이 한 줄에 들어가는 compact 레이아웃이라 이메일까지 본문에 넣으면 줄이 접힌다. 동명이인 구분이 필요한 순간에만 커서를 올리면 된다】** / 이름·이메일 모두 본문 【기각: 줄 접힘】 / 이름만 응답에 싣기 【기각: 동명이인을 구분할 수단이 사라진다】.
- **거부 철회 = 상태 전환 메뉴 밖 별도 액션 【채택: 메뉴 항목은 "목표 상태"를 고르는 자리이고 클릭 즉시 실행된다. 철회는 사유 입력이 필수라 같은 자리에 넣으면 규칙이 갈린다. 별도 항목이면 `ADMIN_PRODUCT_ALLOWED_TRANSITIONS`·`statusTargetsFor`를 손대지 않아 기존 전환 로직·테스트가 그대로다】** / 메뉴에 `PENDING` 목표 추가 【기각: 전이표·비활성 판정·테스트 동반 변경 + 사유 입력 규칙 불일치】.
- **회수 대행 노출 = FE 도메인 판정 【채택: BE `availableActions`에 값을 더하면 "필요 액션" 필터와 대시보드 처리 대기 타일이 함께 바뀐다(D-212 §1-A). 판정 필드가 이미 행에 다 있어 FE만으로 정확히 같은 조건을 만든다】** / BE 액션 추가 【기각: 필터·타일 회귀】.
- **대기 문구 자리 = "관리" 칸(액션 자리) 【채택: 운영자가 "여기서 뭘 하지?"를 보는 자리가 그 칸이다. 회수/검수 칸의 caption은 이미 회수 송장·첨부 수를 쓰고 있어 성격이 다르다】** / 회수/검수 칸 caption 【기각: 액션이 없는 이유를 액션 칸이 아닌 곳에서 설명하게 된다】.
- **처리 이력 배치 = 클레임은 토글·정산은 상시 【채택: 정산 상세는 한 화면 = 한 대상이라 상시로 둬도 소음이 없다. 관리자 주문 상세는 품목마다 클레임이 여러 건이라 상시로 깔면 화면이 이력으로 덮인다】** / 둘 다 상시 【기각: 주문 상세 과밀】 / 둘 다 토글 【기각: 정산은 펼치는 수고가 순손해】.
- **관리자 클레임 "상세" = 주문 상세의 클레임 행 【채택: 관리자 클레임 전용 상세 페이지가 없다(목록 + 다이얼로그 구성). 새 페이지를 만드는 것은 이번 라운드 범위 밖이고, 클레임 1건의 맥락(주문·품목·결제)이 모여 있는 곳이 주문 상세다】** / 클레임 상세 페이지 신설 【기각: 라우트·가드·복귀 경로·e2e 동반 — 범위 초과】 / 목록 행 확장 【기각: `v-data-table-server` 행 확장은 8컬럼 compact 레이아웃과 충돌】.
- **재고 경고 = 확인 1회, 차단 없음 【채택: BE에도 상한이 없고(정찰 §2-3) 정당한 대량 입고를 막으면 안 된다. 재고는 반대 방향 재조정으로 되돌릴 수 있어 "되돌릴 수 없는 조작"과 같은 강도의 장치가 필요하지 않다】** / 상한을 두고 차단 【기각: 정당한 입고를 막고 BE와 규칙이 갈린다】 / 경고 없음 유지 【기각: 자릿수 오타가 그대로 통과】.
- **셀러 경고 = 인라인(전용 확인 컴포넌트 없이) 【채택: 셀러 레이어에는 공용 확인 다이얼로그가 없다(FE-61에서 같은 이유로 인라인 조립). 다이얼로그 위에 다이얼로그를 겹치지 않는다】** / 셀러용 확인 다이얼로그 신설 【기각: 소비처 1곳】.
- **구매자 취소 확인 = 인라인 패널 【채택: 같은 화면의 구매확정 확인이 이미 인라인 패널 방식이다(`item-confirm-panel`). 구매자 화면에 모달 선례가 없다】** / window.confirm 【기각: 구매자 화면 3곳 중 삭제 1곳만 쓰는 예외 패턴】.

### §2 확정 구현 규칙·트랩
- 신규: `app/lib/utils/inventory-adjust.ts`(임계·판정·문구) · `layers/admin/app/lib/admin-audit-view.ts` · `layers/admin/app/types/admin-audit.ts` · `AdminAuditLogSection.vue` · `AdminProductWithdrawRejectionDialog.vue` · `AdminReturnShipmentDialog.vue`.
- `AdminAuditLogSection`은 **loader 주입**형이다(클레임·정산이 서로 다른 endpoint). `watch(() => props.loader, …, {immediate:true})`라 부모가 `computed`로 새 클로저를 만들면 자동 재조회된다.
- 이력 조회 실패는 섹션 안에서만 알리고(재시도 버튼) 본문 조회를 막지 않는다 — 보조 정보라 페이지 전체를 실패로 만들 이유가 없다.
- `auditActorLabel`은 `actorRole === 'SYSTEM'`도 "시스템"으로 읽는다 — BE `AuditContext.system()`이 스케줄러 행에 그 문자열을 싣는다(vitest가 먼저 잡아낸 누락).
- 관리자 주문 상세의 이력 섹션은 클레임 `v-for` **안**에 있어야 `claim`이 스코프에 있다 — 행 `div` 밖에 두면 `Property 'claim' does not exist` typecheck 오류다. `<template v-for>`로 행과 섹션을 함께 감쌌다.
- 관리자 상품 폼의 저장 체인은 이제 **판매가 확인 → 대량 재고 확인 → 저장** 2단이다. 가격 확인을 지난 뒤에도 재고 확인을 한 번 더 받는다(`confirmPriceChange`에서 `openLargeStockConfirm` 재호출).
- 셀러 입·출고 경고는 **수량 필드를 고치면 확인이 리셋**된다(`clearError('quantity')`에서 `largeWarning` 비움) — 경고를 본 뒤 값을 바꿨는데 그대로 통과하면 안 된다.
- `admin-orders ⑧` e2e 단언을 코드 문구 → BE detail 문구로 갱신했다(422 우선순위 변경의 의도된 결과이자, detail이 실제로 사용자에게 닿는지의 증거).
- 상세 폼의 확인 체인은 이제 **판매가 → 대량 재고 → 저장**(폼 저장)과 **거부 확인 → 상태 전환**(즉시 반영)이 서로 독립이다 — 상태 전환은 폼 저장과 무관한 경로다.
- 검증(로컬·2026-09-23): typecheck 0 · vitest **702**(`test/admin/track-101a-recovery.spec.ts` 26) · Playwright **106 passed / 2 skipped**(기준선 유지) · 워크스루 18/18 2회 연속 수치 완전 일치(86·31·28 — 라운드 2와 동일).

### §8 이월
- ~~상품 상세의 거부 확인 부재~~ → 보완 완료(목록과 같은 `isRejection`·`rejectConfirmMessage` 공유).
- 처리 이력은 첫 페이지(20건)만 읽고 "이전 이력 N건이 더 있습니다"로 끝난다. 페이지네이션 UI는 두지 않았다(대상 1건의 이력이 20건을 넘는 경우가 사실상 없다는 전제).
- ~~이력 행이 역할까지만 보여 줌~~ → 보완 완료(이름 + 이메일 표기). 다만 이름은 조회 시점의 현재 값이라 개명하면 과거 이력 표기도 바뀐다(D-212 §8).
- 재고 경고 임계 1000은 운영 실측 없이 정한 값이다 — 실제 입고 규모를 보고 조정 필요.
- 구매자 클레임 목록(`/claims`)에는 취소 액션이 없다(상세에서만). 목록 카드 액션은 Track 101-B 통합과 함께 판단.
- 외부 검토: A(PR 등급 적용·FE 범위 지적 없음)

## FE-63: 주문내역 탭 통합 — /orders 단일 라우트 · URL 단일 소스 · 주문 카드 클레임 배지 (Track 101-B D-213) (2026-09-23)

배경: `/orders`와 `/claims`는 스크립트 4블록·템플릿 5블록이 1:1로 같은 페이지였고(정찰 `docs/track-101/recon-report-101b.md` STEP 3-1), 구매자는 "주문이 어떻게 됐나"와 "내 반품이 어떻게 됐나"를 다른 메뉴에서 봐야 했다. 페이지네이션은 양쪽 다 로컬 `ref`라 새로고침·뒤로가기로 복원되지 않았고, 진입점은 헤더에 2개·마이페이지에 1개(주문내역 없음)로 비대칭이었다.

결정:
- **탭 1라우트**: `/orders?tab=order|cancel|return|exchange`. 주문 탭은 `useOrderList`, 나머지는 `useClaimList(page, type)`이며 로딩·에러·빈 상태·페이저는 탭이 공유한다.
- **URL 단일 소스**: `tab`·`page`를 `route.query`에서 읽고 `router.replace`로 되쓴다(ProductListView의 `sort` 동기화 선례·history 미증가). 탭 전환은 `page=0`으로 되돌린다.
- **단일 소스 파일 2개**: `lib/constants/order-tabs.ts`(탭 유니온·라벨·`ClaimType` 양방향 매핑·쿼리 파서) · `lib/utils/active-claim-badge.ts`(배지 접기 규칙). 둘 다 순수 함수라 vitest가 직접 고정한다.
- **카드 컴포넌트 2개 분리**: `OrderSummaryCard`·`ClaimSummaryCard`. 페이지는 탭·조회·4상태·페이저만 갖는다.
- **배지**: 진행 중 클레임을 유형별로 2종까지(`취소 1`·`반품 2`) 보여주고 초과분은 `외 N` 하나로 접는다. 유형 배지는 해당 탭으로 이동하는 링크이고 `외 N`은 링크가 아니다. 진행 중이 없으면 영역 자체를 그리지 않는다.
- **진입점 일원화**: 헤더 드롭다운에서 클레임 항목 제거(6 → 5) · 마이페이지 허브의 클레임 항목을 "주문 내역"으로 교체 · 클레임 상세의 복귀 버튼은 자기 유형 탭으로 간다.
- **`/claims`**: `nuxt.config.ts` `routeRules` 리다이렉트(`/orders?tab=cancel`)로 흡수하고 `pages/claims/index.vue`는 삭제한다.

### §1-A 갈림길·채택/기각 근거
- **라우팅 = 쿼리 탭(`?tab=`) 【채택: `/orders` URL이 그대로 살아 기존 링크 6곳(체크아웃 폴백·claims/new 3·주문 상세 복귀 등)을 건드리지 않는다. 중첩 라우트(`/orders/claims`)는 `/orders/[orderPublicId]`와 충돌해 회피 장치가 필요하고, 2라우트 유지 + 공통 탭바는 "한 화면"이 되지 않는다】** / 중첩 라우트 【기각: 동적 세그먼트 충돌】 / 2라우트 + 탭바 【기각: 통합 목적 미달】.
- **page를 URL로 승격 【채택: 탭이 생기면 "3페이지에서 반품 탭으로 갔다가 돌아오기"가 일상 동작이 된다. 로컬 ref면 새로고침·뒤로가기·배지 링크가 전부 1페이지로 튕긴다. 선례(ProductListView)가 같은 방식으로 sort를 URL에 둔다】** / 로컬 ref 유지 【기각: 위】.
- **배지 접기 = FE 【채택: "2종까지"는 카드 폭이 정하는 표현 규칙이라 화면이 바뀌면 같이 바뀐다. BE는 유형별 건수(사실)만 내려주고 FE가 접는다】** / BE가 문자열로 접어 내려주기 【기각: 표현이 API에 굳는다】.
- **배지 색 = badge-new 토큰(강조 칩) 【채택: 주문 상태 배지가 무채색 칩(`bg-gray-100`)이라 같은 무채색을 쓰면 둘이 구분되지 않는다. FE-07 토큰에 이미 있는 강조 칩을 쓴다 — 새 색을 만들지 않는다】** / 테두리형 강조색 신설 【기각: 디자인 토큰 추가】 / 무채색 보조 배지 【기각: 주문 상태와 섞인다】.
- **카드 링크 = 오버레이 앵커(stretched link) 【채택: 카드 전체가 상세 링크인데 배지도 각자 탭으로 이동해야 한다. `<a>` 중첩은 불가하고, 배지를 `<button>`으로 바꿔도 앵커 안 인터랙티브 요소라 같은 문제다. 상세 링크를 `absolute inset-0` 오버레이로 깔고 배지를 `z-10`으로 올리면 둘 다 정상 링크가 된다】** / 배지를 카드 밖으로 빼기 【기각: 어느 주문의 배지인지 흐려진다】 / 배지 클릭 시 상세로만 이동 【기각: 지시된 동작이 아니다】.
- **`/claims` 페이지 파일 삭제 【채택: routeRules가 경로를 흡수하므로 남겨 두면 도달하지 않는 페이지가 된다(죽은 코드). 상세·신청 경로는 정확 일치라 영향 없다】** / 리다이렉트 전용 페이지로 축소 【기각: 같은 일을 하는 파일이 둘】.

### §2 확정 구현 규칙·트랩
- **`useFetch` 고정 key 트랩**: 한 라우트에서 탭별로 다른 질의를 돌리면 고정 key(`'claim-list'`)가 SSR 페이로드를 덮는다 — key에 유형을 넣었다(`claim-list:<type>`). 주문 탭 진입은 `immediate: type.value !== null`로 클레임 조회 자체를 하지 않는다.
- **카드 앵커에 텍스트가 없어졌다**(오버레이 구조). 워크스루 4파일의 `a[href^="/orders/ord_"]` + hasText 필터가 깨져 `getByTestId('order-card')` 기준으로 갱신했다. 목록 카드 셀렉터를 쓰는 테스트는 앞으로 이 testid를 쓴다.
- 워크스루의 구매자 `/claims` 진입 2건은 파일명이 `admin-*`이지만 첫 구간이 구매자다(`admin-claim-return-inspect`·`admin-claim-exchange-full`) — `/orders?tab=return|exchange`·heading `주문 내역`으로 갱신.
- `dev` 서버는 페이지 파일 삭제를 감지하지 못하고 `routes.mjs`가 옛 경로를 계속 import한다(Vite 500) — **페이지 파일을 지우면 `docker restart zslab_mall_frontend`**가 필요하다.
- 클레임 상세의 복귀 탭은 조회 전(`data` null)에는 유형을 모르므로 `cancel`을 기본값으로 둔다.
- `AppHeader.spec.ts`의 `EXPECTED_LINKS`는 드롭다운 순서·href·라벨을 전수 고정한다 — 메뉴를 건드리면 여기가 먼저 깨진다(의도된 게이트).
- 검증(로컬·2026-09-23): typecheck 0 · vitest **713**(기준선 702 → +11·신규 `test/unit/order-tabs.spec.ts` 11) · Playwright 105 passed/1 failed(콜드·단독 재실행 통과)/2 skipped · 워크스루 18/18 2회 연속 86·31·28 일치 · 픽셀 `/mypage` 2장만 의도된 변경(메뉴 문구 1줄·diff 2306px).

### §8 이월
- 클레임 목록에 `getCachedData` 미적용 — 탭 왕복 시 stale 노출 여부 **미측정**(상세에는 적용돼 있다).
- 목록 카드에 액션(취소 등)은 없다 — FE-62 §8의 "목록 카드 액션은 101-B와 함께 판단"은 **이번에도 넣지 않는 것으로 결론**: 카드가 배지·주문번호·상품명까지 갖게 돼 액션까지 얹으면 카드가 상세를 대신하게 된다.
- 주문 탭과 클레임 탭의 카드 높이가 달라 탭 전환 시 목록 높이가 뛴다(기능 영향 없음·미조치).
- 픽셀 `products` 2장 diff 87px는 시드 데이터 차이(코드 무관)다 — 픽셀 기준선을 restore 직후로 고정할지는 D-213 §8과 함께 검토.
- 외부 검토: A(PR 등급 적용·FE 범위 지적 없음)

## FE-64: 처음 쓰는 운영자 — 용어 표준 · 위험 조작 시각 규약 · 도움말 자리 · 결제 재개 (Track 102 라운드 4) (2026-09-23)

배경: 라운드 4 정찰(`docs/track-102/recon-report-round4.md`)에서 같은 DB 값을 역할마다 다르게 부르는 곳 8쌍(상품 `REJECTED` 거부됨/반려, `PENDING` 판매대기/승인대기, 셀러 `ACTIVE` 활성/정상, `SELLER_MANAGER` 매니저/관리자 — 셀러 화면의 "관리자"는 플랫폼 관리자와 동음이의 —, 정산 `PENDING` 대기/확정 전 등)과 한 화면 안 혼용 4건(정산 버튼 "정상처리" ↔ 상태 "확정", 거부 다이얼로그 제목 거부/본문 거절 등)이 나왔다. 위험 조작은 색이 제각각이었고(정산 확정 primary·지급완료 success·환불 개시 primary) 가역성 고지는 9곳에만 있었다. 도움말·매뉴얼 문자열은 frontend 전체에 0건이었고, 결제대기 주문에는 "30분 내 결제" 안내만 있고 결제를 다시 시작할 진입점이 없었다.

결정:
- **용어 표준 9건**: 승인대기 · 거부됨 · 관리자/셀러 판매중지 · 활성 · 매니저 · 확정 대기 · 거부(클레임) · 판매가 · 발송.
- **라벨 공용 단일 소스 3파일 신설**: `app/lib/constants/product-status.ts` · `seller.ts` · `settlement.ts`. 관리자·셀러 레이어 6파일은 기존 export 이름을 유지한 채 값만 re-export한다(호출부 무변경 · `admin-seller.ts` 은행 상수 선례).
- **위험 조작 시각 규약 1종**: `app/assets/css/risk-action.css`의 `.op-risk-action`(흰 배경 + 2px 빨강 테두리 + 굵은 글자) · `.op-risk-menu-item`(빨강 글자 + 위 구분선). 관리자·셀러 `vuetify-styles.ts`가 함께 import한다. `AdminConfirmDialog`에 `risk` prop을 두고 참이면 `confirmColor`를 무시한다.
- **확인 문구 형식**: `app/lib/utils/risk-confirm.ts`의 `riskConfirmMessage(effect, reversal)`만으로 만든다. 가역성 줄은 예외 없이 마지막 한 줄(`되돌릴 수 없습니다.` / `되돌릴 수 있습니다: <방법>`).
- **도움말 자리 선확보**: `/admin/help`·`/seller/help`·`/help` 껍데기 + 상단바 계정 메뉴·구매자 푸터 "고객센터" 진입점 + `ALLOWED_PATHS`에 `/help`. 본문은 매뉴얼 단계에서 채운다.
- **결제 재개**: BE에 이미 있던 재결제(`POST /api/v1/orders/{id}/payments`·D-60·FE 미사용이던 경로)를 쓴다. 이동은 기존 `resolvePaymentRedirect`를 재사용하되 **주문번호는 호출부가 직접 넘긴다**(보완·§2 Location 규약 트랩). 실패 8종은 `paymentResumeFailure()` 순수 함수가 문구·재조회·로그인 유도를 정한다.

### §1-A 갈림길·채택/기각 근거
- **표준어 선정 = 관리자 화면 용어 기본 + 더 쉽거나 더 정확한 쪽이 있으면 그쪽 【채택: `PENDING`은 관리자의 "판매대기"가 아니라 셀러의 "승인대기" — 관리자 자신의 대시보드 타일이 이미 "상품 승인 대기"라 관리자 화면 안에서도 갈려 있었다. 정렬·폼의 "판매가"는 실제 정렬 필드가 `basePrice`(`AdminProductQueryService.java:351`)라 셀러의 "기본가"가 정확하지만, 공용 util(`price-change.ts`)·셀러 통계가 이미 "판매가"를 쓰고 일상어라 판매가로 통일했다】** / 역할별 어휘 유지 【기각: 운영자가 관리자·셀러 화면을 오가며 같은 값을 다른 말로 읽는다. 그것이 정찰이 잡은 결함 자체다】 / 전부 새 말로 재명명 【기각: 변경 폭만 커지고 기존 알림 문구와도 어긋난다】.
- **정산 `PENDING` = "확정 대기"(제3의 말) 【채택: 관리자의 "대기"는 무엇을 기다리는지 말하지 않고, 셀러의 "확정 전"은 다음 상태(확정·지급완료)와 어미가 다르다. BE SMS가 이미 "정산이 확정되었습니다"라 조작 이름 "확정"과도 이어진다】** / 둘 중 하나 채택 【기각: 위】.
- **발송 ↔ 출고 = "발송" 【채택: 구매자 화면·관리자 통계·배송 방향 라벨이 이미 발송이고 일상어다. 재고의 입고/출고는 다른 개념이라 그대로 두면 오히려 두 "출고"가 분리된다】** / "출고" 【기각: 물류 용어 + 재고 출고와 충돌】.
- **공용화 범위 = 갈렸던 라벨만 【채택: 택배사·배송 상태처럼 값이 이미 같은 중복 맵은 고장난 적이 없다. 최소 변경 원칙】** / 레이어 상수 전면 통합 【기각: 이번 결함과 무관한 리팩터링】.
- **위험 버튼 = 채움 없는 빨강 테두리 1종 【채택: 일상 조작(채워진 primary/teal)과 "채움 여부"에서 먼저 갈리고 색으로 한 번 더 갈린다 — 색 하나에만 기대지 않는다. 기존처럼 error/success/primary를 섞으면 규약이 다시 흩어진다】** / 색만 error로 통일 【기각: 채움이 같아 일상 버튼과 스캔 시 구분이 약하다】 / 아이콘 추가 【기각: Vuetify 버튼 내부 구조에 의존】.
- **확인 문구 앞부분은 여러 줄 허용 【채택: "1줄 + 1줄"을 엄격히 적용하면 셀러 종료의 경고(판매중 상품 수·주 정산계좌 없음)와 운영자 회수의 남은 역할이 지워진다. 고정하는 규칙은 "가역성 줄이 항상 마지막"이다】** / 엄격 2줄 【기각: 판단 정보 손실】.
- **구매자는 shadcn `variant="destructive"` 【채택: 구매자 앱은 Vuetify가 아니라 같은 CSS 클래스를 쓸 수 없고, 이미 탈퇴 버튼이 destructive다. 규약(채움 아닌 강조 + 빨강)은 같다】** / 구매자에도 같은 CSS 주입 【기각: Tailwind 앱에 Vuetify용 `!important` 규칙을 끌어온다】.
- **결제 재개 진입점 = 상세에 결제수단 선택 + 목록 카드는 상세로 보내는 링크 【채택: BE 재결제가 결제수단을 따로 받는다(`RetryPaymentRequest.method` — "원 결제와 다를 수 있어 별도 입력"). 카드에서 수단을 고르게 하면 카드가 폼이 된다】** / 카드에서 바로 결제 시작(기본 수단 고정) 【기각: 결제수단을 사용자 대신 말없이 고른다】 / 체크아웃으로 보내기 【기각: 새 주문이 생긴다】.

### §2 확정 구현 규칙·트랩
- **BE 알림 본문은 이미 표준과 일치**했다 — "요청이 거부되었습니다"(`NotificationService.java:225`) · "정산이 확정되었습니다"(`:448`) · `ALREADY_SHIPPED("이미 발송됨")`. 화면이 알림과 어긋나 있었던 것이고, 이번 변경으로 화면이 알림 쪽으로 맞춰졌다. **BE 변경 0**(`NotificationService` Javadoc에만 "정상처리" 표현이 남아 있다 — §8).
- 정찰 정정 1건: "셀러 상품 상태 라벨 두 벌"은 실제로 `ProductStatus`와 `ProductVariantStatus` **두 enum**이었다. 같은 code가 다른 말이던 `SALE`·`STOPPED`만 맞추고 `HIDDEN`은 변형의 "사용" 스위치 조작 이름과 같은 "비활성"으로 남겼다.
- **관리 칸 폭 트랩**: 액션 부재 사유를 "처리 완료 — 남은 작업 없음"처럼 길게 쓰면 클레임 목록이 1440px에서 가로 스크롤을 만든다(e2e `admin-claims` ①가 이것을 잡는다). "처리 완료"·"거부로 종결"로 줄이고 `text-no-wrap`을 뺐다.
- **testid를 문구로 덮는 트랩**: 환불 개시 다이얼로그에서 금액 `span`의 testid를 문구 전체를 감싸는 `p`로 옮겼더니 `toHaveText('19,900원')`가 깨졌다 — 문구 단락과 금액 요소를 분리해 복원했다.
- **신규 라우트는 dev 재시작 필요**: layers에 페이지 파일을 추가해도 dev 서버가 라우트를 등록하지 않아 `/admin/help`·`/seller/help`가 404였다(페이지 삭제 시 재시작이 필요하다는 FE-63 §2와 같은 계열). `docker restart zslab_mall_frontend` 후 통과.
- `SELLER_TERMINATE_IRREVERSIBLE_NOTICE`는 규약 문장이 대신하므로 삭제했다(소비처 0).
- **Location 규약 트랩(보완·실측)**: 체크아웃의 Location은 `/api/v1/orders/{orderPublicId}`인데 재결제는 `/api/v1/payments/{paymentPublicId}`다(`CheckoutService:417` vs `:140`). `resolvePaymentRedirect`가 Location 말미를 주문번호로 쓰고 있어 재개한 결제의 완료 화면에 **결제 id가 주문번호로** 찍혔다 — 신규 e2e ①이 잡았다. `orderPublicIdOverride` 3번째 인자를 더해 주문번호를 아는 호출부가 직접 넘기고 회귀를 단위 테스트로 고정했다(BE 변경 0).
- **재결제 실패 8종 매핑**(보완): 404 `ORDER_NOT_FOUND` / 422 `ORDER_NOT_PAYABLE`(detail `PRODUCT_NOT_ON_SALE`·`OUT_OF_STOCK`) / 422 `ORDER_NOT_PENDING_PAYMENT` / 422 `PAYMENT_ALREADY_COMPLETED` / 409 `PAYMENT_IN_PROGRESS` / 401 / 400. 보완 전에는 401 외 전부를 422·그 외 두 갈래로 뭉개 상품이 판매중지된 경우에도 "이미 결제되었거나 종료된 주문"이라고 잘못 안내했다.
- 검증(로컬·2026-09-23): typecheck 0 · vitest **724**(기준선 713 → +11·신규 `test/unit/track-102-first-time-operator.spec.ts`) · Playwright **109 passed / 0 failed / 2 skipped**(기준선 106 + `/help` 3) · 워크스루 18/18 2회 연속 **86·31·28** 일치(라운드 2 기준선과 동일) · 픽셀 `track102` vs `track101b` 12장 **전부 diff 0**.

### §8 이월
- **구매자 "판매자" ↔ 운영자 "셀러"**는 표준화하지 않았다. `.vue` 기준 admin 122·seller 98회에 메뉴·통계 축까지 걸려 이번 범위(8쌍 + 화면 내 혼용 4건 + 동사)를 넘어선다 — 다음 라운드 판단 대상.
- **구매자 화면의 "클레임" 노출**(`claims/new.vue:154` 등)도 같은 이유로 남겼다. 진입 버튼·탭은 "취소/반품/교환"인데 완료 문구만 "클레임"이다.
- 픽셀 도구 대상은 구매자 6페이지뿐이라 **위험 조작 시각 규약(관리자·셀러 버튼)은 픽셀로 측정되지 않았다** — 회귀 감지는 e2e 텍스트·워크스루 스크린샷에만 의존한다.
- `/help` 3화면은 껍데기다. 본문·목차·역할별 분기는 매뉴얼 단계 소관.
- **결제 재개 실측 커버리지(외부 검토 지적)는 해소**: 실패 매핑 단위 6케이스 + Location override 1케이스 + e2e 2건(결제대기 → mock 결제 화면 진입 / 만료 주문 버튼 미노출). **잔여**: 409 `PAYMENT_IN_PROGRESS`·422 `ORDER_NOT_PAYABLE`은 실 서버 응답이 아니라 매핑 함수 단위로만 검증했다(재현에 결제·재고 상태 조작이 필요). 보완 후 검증: vitest **731** · Playwright **111 passed / 0 failed / 2 skipped**.
- BE `NotificationService` Javadoc(`:60`·`:439`·`:459`)에 "정산 정상처리" 표현이 남아 있다(주석 전용·동작 무관).
- 필수 입력 표시 규약(`사유 (필수)` vs `판매가(원) *`)은 여전히 두 가지다 — 정찰 STEP 4 지적이지만 이번 결정 범위 밖.

## FE-65: 화면 확인 이슈 — 처리 이력 사람 말 · 토글 · 관리자 주문 목록 · 셀러 간격·통계 (Track 103 D-214) (2026-09-23)

배경: 화면 확인 이슈 7건 정찰(`docs/track-103/recon-report.md`). 처리 이력은 BE diff 원시값(`REQUESTED`·`true`·나노초 ISO)이 그대로 보였고 회수 확인·검수·정산 확정·지급이 모두 "변경"이었으며, 이름 없는 최초 관리자는 역할만 보였다. 처리 이력 토글은 `variant="text"` x-small이라 주변 메타 문구와 섞였다. 관리자 주문 목록은 결제 칸(결제상태)과 주문 칸(주문상태)이 서로 다른 맵인데 PAID가 둘 다 '결제완료'였고, 전 품목 반품 주문은 '결제취소 + 구매확정'으로 보였다. 셀러 품목·클레임 상세는 한 `v-row dense`에 7칸을 줄바꿈시켜 행 간격이 4+4px뿐이었고, 셀러 미판매·재고 회전 표는 전량을 렌더했다.

결정:
- **처리 이력 순수 함수**(`layers/admin/app/lib/admin-audit-view.ts`): `auditValueText`(status는 대상별 클레임/정산/배송 라벨 · 거부 사유·검수 결과·택배사·배송 방향 기존 라벨 재사용 · 재입고함/재입고 안 함 · 구매자 대신 등록 · 시각 `formatDateTime` · 금액 `formatWon`) · `auditActionText`(바뀐 필드로 검수·회수 확인·정산 생성/삭제/확정·지급완료·회수 송장 대행 등록·교환품 발송·송장 등록·송장 정정·배송완료, 추론 불가는 기존 라벨) · `auditActorText` 이름 없으면 이메일. 타입 `AdminAuditTargetType`('CLAIM'|'DELIVERY'|'SETTLEMENT')·`AdminAuditLog.targetType`.
- **토글**: 클레임 행 "처리 이력" 토글 1건을 `variant="outlined"` x-small + `append-icon` chevron + `:aria-expanded`로. 같은 방식을 셀러 통계 "전체 보기"에도 쓴다.
- **관리자 주문 목록**: `adminOrderListStatusLabel(order)` — PAID + 원 발송 없음 + `PREPARE_SHIPMENT` → "발송 대기", 그 외 PAID → "처리 중", 나머지 공용 라벨(공용 맵·필터·상세·구매자 무변경). 결제수단은 상태 칩 옆 같은 줄 보조 표기(`payment-method`). 머리글 "금액" → "주문 금액". `allItemsReturned`면 "전체 반품" 캡션.
- **셀러 간격**: 주문·클레임 상세 바깥 `v-row dense`에 `mb-4`, 라벨/값 `v-row dense`에 `gr-2`(Vuetify 3.13 row-gap 8px) — 클래스만·구조 무변경.
- **셀러 상품 통계**: `STATS_LIST_PREVIEW_LIMIT = 10`·`previewRows(rows, expanded)`(BE 정렬 그대로 slice) · 초과 시 "전체 보기 (N건)"/"접기" · 제목 옆 지표 설명 1줄(D-200 90-E-3 정의표 인용).

### §1-A 갈림길·채택/기각 근거
- **값 변환 거처 = FE 【채택: BE는 원문 전달 설계(`admin-audit-view.ts` 머리 주석)이고 라벨 단일 소스가 이미 FE constants에 있다】** / BE parseChanges 【기각: 화면 라벨이 API에 굳는다】.
- **값만 표기하는 필드(검수 결과·재입고·대행 등록) 【채택: "검수 결과 검수 합격"·"재입고 재입고함"처럼 라벨이 겹친다】** / 전부 "라벨 값" 【기각: 위】.
- **결제수단 = 칩 옆 같은 줄 【채택: 칩 아래 줄 캡션은 칩 안쪽 여백만큼 어긋나 보였다. flex-wrap이라 좁으면 자연히 줄바꿈되고 1440 가로 스크롤 0 e2e가 유지된다】** / 칩 아래 줄 + 왼쪽 여백 보정 【기각: 칩 패딩 값에 결합】.
- **목록 PAID 판정 = 기존 응답(deliveryStatus·actions) 【채택: BE 필드 추가 없이 가능·액션은 BE `AdminOrderQueryService.actions`와 1:1】** / BE에 표시 상태 필드 추가 【기각: 표시 규칙이 API에 굳는다】.
- **셀러 간격 = Vuetify 유틸 클래스 【채택: `seller-vuetify.css:9` 셀러 컴포넌트 Tailwind 금지 규약 · 구조 변경 금지 지시】** / `slr-*` CSS 신설 【기각: 단일 사용】 / 한 줄 row + divider 구조 변경 【기각: 지시 범위 밖】.

### §2 확정 구현 규칙·트랩
- 기존 테스트 기대값 갱신: `track-101a-recovery.spec`(원시값 → 라벨·행위명·이메일 대체) · e2e `admin-orders`(주문 칩 "발송 대기"·`payment-method` "카드"·`all-returned-note` 0) · e2e `seller-stats-products`(행 수 = min(응답, 10) · 초과 시 토글 → aria-expanded true → 전량).
- e2e는 앱 코드를 import하지 않는 관례라 미리보기 한도 10을 `PREVIEW_LIMIT` 상수로 재현한다(`depletion()` 선례).
- 검증(분리 후·2026-09-23): typecheck 0 · vitest **744**(FE-64 보완 후 731 → +13) · Playwright **111 passed/0 failed/2 skipped** · 워크스루 18/18 2회 **86·31·28** 일치.

### §8 이월
- 목록 "경과 N일" 칩이 "처리 중" PAID 행에도 붙는다(기존 `pendingElapsed`) — D-214 §8.
- 처리 이력 행위명 추론은 BE 적재 diff 키에 결합돼 있다 — 감사 적재 형식을 바꾸면 `auditActionText`와 vitest를 같이 고친다.
- 구매자 화면에는 이번 표시 규칙("발송 대기"·"전체 반품")을 적용하지 않았다(지시 범위: 관리자 목록 전용). 같은 목록 API를 쓰는 관리자 회원 상세 "주문" 탭(`members/[id].vue`)도 공용 라벨 그대로다 — 결제 칸이 없어 '결제완료' 중복은 없지만 전 품목 반품 주문은 "구매확정"으로만 보인다.
- 외부 검토: B(생략) · 셀프 리뷰 지적 5건 중 수용 4건

## FE-66: 불일치 화면 — 목록·해결 다이얼로그·주문 상세 섹션·대시보드 9칸 (Track 104-2 D-216) (2026-09-23)

배경: BE가 PG 충돌·환불 완료 시점·정기 점검에서 발견한 불일치를 `reconciliation_issue`로 남기게 됐다(D-216). 운영자가 그것을 찾고, 확인한 뒤 해결 처리할 화면이 필요했다. 주문 상세의 C-12 경고 배지("환불 전액 완료·취소 미반영")는 응답 값으로 계산한 표식이라 저장된 불일치와 기준이 둘이 된다.

결정:
- **상수 단일 소스** `layers/admin/app/lib/constants/reconciliation.ts`: 유형 11·상태 2(4층위 (4)) · 세부 사유(detail.reason) 라벨 — 사유는 DB 컬럼이 아니라 세부 JSON 값이라 모르는 코드는 원문을 보인다.
- **표시 순수 함수** `lib/admin-reconciliation-view.ts`: URL ↔ 조회 조건(기본 = 확인 필요·"전체"는 `status=ALL` 명시) · 요약(사유 라벨 → 원문 → 유형 라벨) · 세부 사실(정해진 키만 라벨·금액 변환) · 기록 주체(정기 점검/처리 중 기록) · 해결 확인 문구.
- **공용 행 목록** `AdminReconciliationIssueList`: 목록 화면과 주문 상세 섹션이 함께 쓴다(주문 링크는 목록에서만). 해결된 행은 처리자·메모 — 시스템 자동 해소(재전송 매칭)는 "시스템".
- **해결 다이얼로그** `AdminReconciliationResolveDialog`: 메모 필수 · `riskConfirmMessage(…, IRREVERSIBLE)` · 확정 버튼 `.op-risk-action`(FE-64) · 이미 해결 422 → warning 후 재조회.
- **목록 화면** `/admin/orders/reconciliation`(주문 관리 메뉴 "불일치") · 주문 상세 back 허용.
- **대시보드 9번째 칸** `reconciliationOpen` → 목록 `status=OPEN`(BE 카운트와 같은 조건이라 근사 칸 아님) · 톤 danger(재고 임박과 함께).
- **주문 상세**: C-12 계산형 배지 제거 → 불일치 섹션(해결 포함·없으면 섹션 없음). 수동 결제 취소 버튼은 같은 계산 조건(`isPaymentCancelLost`) 그대로.
- **처리 이력**: `AdminAuditTargetType`에 `RECONCILIATION_ISSUE` · 행위명 "불일치 해결" · 상태 라벨 · `memo` 라벨.

### §1-A 갈림길·채택/기각 근거
- **배지만 제거·버튼 유지 【채택: 경고는 저장된 불일치(스케줄러 U6)가 대신하고, 버튼은 BE 전액 환불 가드와 같은 조건이라 보정 수단으로 남아야 한다】** / 배지·버튼 모두 섹션으로 이동 【기각: 섹션은 불일치 기록이고 보정 조작은 결제 행에 있어야 대상이 분명하다】 / 둘 다 유지 【기각: 같은 사실을 계산형·저장형 두 기준으로 보여 준다】.
- **목록 기본 = 확인 필요 【채택: 대시보드 칸과 같은 조건으로 들어오고, 해결된 건은 필요할 때 필터로 본다】** / 전체 【기각: 처리할 것이 해결된 건 사이에 묻힌다】.
- **행 목록 공용 컴포넌트 【채택: 목록과 주문 상세가 같은 요약·세부·해결 버튼을 보여야 한다 — 두 벌이면 문구가 갈린다】** / 목록은 표·상세는 별도 마크업 【기각: 위】.
- **해결 버튼 = 위험 조작 규약 【채택: 해결된 행은 다시 열리지 않는다(D-216) — 되돌릴 수 없는 조작】** / 일상 버튼 【기각: FE-64 규약 위반】.

### §2 확정 구현 규칙·트랩
- 주문 상세 응답에 `reconciliationIssues`가 늘 온다(빈 배열도 직렬화) — 주문 상세를 mock하는 e2e 3파일(admin-claims·admin-members·admin-settlements)에 `[]`를 넣지 않으면 상세 화면이 `length` 접근에서 깨진다.
- 새 페이지 라우트는 프런트 컨테이너 재시작 후 등록된다(FE-64 §2와 같은 계열) — 검증 전 `docker restart zslab_mall_frontend`.
- 기존 테스트 갱신: vitest `admin-dashboard-helpers`(9칸·링크·danger) · `track-102-first-time-operator`(9칸 hint) · e2e `admin-dashboard`(9칸·불일치 링크) · `admin-orders` ⑨(배지 0 → 섹션·해결 다이얼로그 노출까지).
- 신규: vitest `admin-reconciliation-view.spec`(9) · e2e `admin-reconciliation.spec`(2 — 목록·유형 필터·해결 POST body·422 재조회, API mock).
- 검증(2026-09-23): typecheck 0 · vitest **753**(106 파일·744 → +9) · Playwright **113/0/2 실질**(112 passed + 콜드 트랩 admin-categories ① 단독 통과·기준선 111 + 신규 2).

### §8 이월
- 불일치 행의 결제·환불·클레임·배송 id는 응답에 싣지 않았다(주문 번호·PG 번호·세부 사실로 추적) — 대상 행으로 바로 가는 링크가 필요해지면 BE에 public id를 더한다.
- 목록 페이지 크기 선택 UI는 두지 않았다(URL `size`로만·기본 20).
- 외부 검토: A(PR 등급 적용·FE 범위 지적 없음)

## FE-67: 구매자 스킨 구조 — 페이지=로직 / 스킨=화면 (Track 105-1a) (2026-09-24)

배경: 구매자 화면의 디자인을 바꿔 끼울 수 있게 하되, 기본 스킨은 env로 정하고 운영 중에도 쿠키로 다른 스킨을 미리 볼 수 있어야 한다. 정찰(recon-report-track105) 결과 구매자 페이지 21개는 로직과 마크업이 한 파일에 있고, 관리자·셀러는 별도 레이어(Vuetify)라 경계가 이미 나뉘어 있다.

결정:
- **구조** `app/skins/registry.ts`(스킨 표·대체 규칙) · `app/skins/contracts/`(페이지↔뷰 타입) · `app/skins/classic/`(index.ts + views/). `app/components` 밖이라 컴포넌트 자동 import 대상이 아니다.
- **레지스트리** 스킨명 → `{ parent?, views: Partial<SkinViews> }`. 스킨 목록 SoT = `SKIN_NAMES`. classic은 전 뷰를 정적 import하고 `SkinViews`(전부 필수)로 선언 — 뷰 누락은 typecheck에서 막힌다. 이후 스킨은 바꾸는 뷰만 `defineAsyncComponent`로 채운다(별도 청크).
- **스킨 결정** `plugins/skin.ts`: 쿠키 `zslab_skin` → `runtimeConfig.public.skin`(env `NUXT_PUBLIC_SKIN`, 기본 classic) → classic. 미등록 값은 무시. `useState('skin')`로 요청 단위 1회 결정(SSR 결정값을 페이로드로 CSR이 그대로 씀).
- **미리보기** `?skin=<이름>` → 쿠키 설정(세션 쿠키) · `?skin=reset` → 삭제 · 미등록 이름은 쿠키도 건드리지 않음 · 구매자 경로에서만(`/admin`·`/seller` 제외, `useRequestURL` 기준).
- **적용** 구매자 레이아웃(`layouts/default.vue`)이 `<html data-skin>`을 붙이고 `useSkinView('LayoutShell')`로 셸을 렌더한다(slot 전달). 관리자·셀러 화면에는 data-skin이 없다.
- **조회** `useSkinView(name)`: 현재 스킨 → parent 체인(순환 방지) → classic.
- **페이지** script 로직(조회·조작·이동·에러·useSeoMeta·definePageMeta)은 그대로 두고, 템플릿은 `<component :is="useSkinView('XxxView')" :vm="vm" />`만. `vm = reactive({...})`에 템플릿이 쓰던 스크립트 바인딩을 전부(상수 포함) 담고 `app/skins/contracts/<페이지>.ts` 인터페이스로 받는다(캐스팅 없이 대입 — ref·computed는 reactive가 풀어 준다). 바인딩이 없는 페이지(index·help)는 vm 없이 뷰만.
- **뷰** 기존 템플릿 그대로(클래스·data-testid·문구 불변), 바인딩만 `vm.` 접두. 입력은 `v-model="vm.필드"`(reactive를 거쳐 페이지 ref가 바뀐다).
- **스킨 가드** `test/unit/skin-guard.spec.ts`: app/skins의 .vue·.ts에서 composable·store·API 모듈 import(`/composables`·`/stores`·`#layers/`·`/server/`)와 호출(`useFetch`·`useLazyFetch`·`useAsyncData`·`useLazyAsyncData`·`$fetch`·`navigateTo`·`useRouter` + 구매자·관리자·셀러 composables/stores의 export 이름 전부)을 0건으로 강제. auto-import라 import 없이 호출돼도 잡는다. `registry.ts`·`contracts/`는 제외.
- **예외(스킨 밖 공용 컴포넌트·수정 안 함)**: 뷰가 직접 쓰는 `AppHeader`(auth·cart store·navigateTo·useCategories) · `HomeProductGrid`(useProducts) · `ProductListView`(useProductList·useRouter). `CategoryTabs`(useCategories)는 ProductListView 안에서 간접 사용. 스킨이 이 컴포넌트를 쓰는 것은 허용하고, 스킨마다 다른 모양이 필요해지면 그때 로직/뷰를 나눈다.
- **배치 계획** a = 셸 + 난이도 하 8(index·help·search·products/index·categories/[id]·checkout/complete·mypage/index·mypage/withdraw — 이번) · b = 중 6(login·signup·mypage/password·mypage/profile·orders/index·payment/mock) · c = 상 7(cart·checkout/index·claims/new·claims/[claimPublicId]·mypage/addresses·orders/[orderPublicId]·products/[productPublicId]).

### §1-A 갈림길·채택/기각 근거
- **α Nuxt layer 덮어쓰기(스킨 = 레이어, 같은 경로 파일을 덮음)** 【기각: 빌드 시점에 한 벌만 존재 — 쿠키로 요청마다 다른 스킨을 고르는 미리보기와 공존할 수 없다】
- **β app/skins + 레지스트리 조회** 【채택: 모든 스킨이 한 빌드에 함께 있고 요청마다 고를 수 있다. 없는 뷰는 parent → classic으로 대체돼 스킨을 부분만 만들 수 있다】
- **페이지 로직을 composable로 추출** 【기각: 페이지마다 로직을 옮겨야 해 이관 범위·회귀 위험이 커진다 — vm 객체로 넘기면 script는 그대로이고 템플릿만 이동한다】

### §2 확정 구현 규칙·트랩
- `app/composables`에 새 파일을 추가하면 실행 중인 dev 컨테이너가 auto-import에 등록하지 못한다(SSR 500 "useSkinName is not defined"·템플릿 "useSkinView accessed during render but is not defined") — `docker restart zslab_mall_frontend` 후 정상(FE-66 §2 새 라우트와 같은 계열).
- `docker-compose.mall.yml` 프런트 env가 명시 목록이라 `NUXT_PUBLIC_SKIN`을 추가했다(없으면 .env 값이 컨테이너에 닿지 않는다). 값은 런타임 override라 재빌드 없이 바뀐다.
- SSR HTML(하 8 + 변형 2 = 10화면)을 main과 대조: 차이는 `data-skin` 속성 · `__NUXT__.config.public.skin` · `<main>` 안 슬롯 fragment 주석 1쌍(레이아웃 slot → LayoutShell slot 2중 전달)뿐 — 요소·클래스·문구·testid 차이 0.
- 검증(2026-09-24): typecheck 0 · vitest **756**(107 파일·753 → +3 스킨 가드) · Playwright smoke 1/1 · `?skin=` classic/미등록/reset·관리자·셀러 경로 curl 확인 · 가드 위반 샘플 1회(import 1·호출 5 검출 후 샘플 삭제).

### §8 이월
- 난이도 중(b)·상(c) 페이지 이관.
- 두 번째 스킨이 생기기 전까지 비동기 뷰 경로(`defineAsyncComponent`)는 실제로 실행되지 않는다 — 첫 스킨 추가 시 SSR 렌더·청크 분리를 확인한다.

### 배치 b·c 완료·보완 확인 (Track 105-1b·1c) (2026-09-24)
- **배치 a(1a)**: 셸 + 하 8 · SSR 10화면 차이 = data-skin·public.skin·슬롯 fragment 주석 1쌍 · 관련 e2e 8 spec 29/29(1b 보완 검증·재시작 후).
- **배치 b(1b)**: 중 6(login·signup·mypage/password·mypage/profile·orders/index·payment/mock) · SSR 11화면 diff 0 · 관련 e2e 8 spec 29/29 · vitest 756.
- **배치 c(1c)**: 상 7(cart·checkout/index·claims/[claimPublicId]·claims/new·mypage/addresses·orders/[orderPublicId]·products/[productPublicId]) · SSR 24화면(유형·상태·쿼리·미존재 변형) diff 0 · e2e 전수 35 파일 115 → 112 passed/1 failed/2 skipped · vitest 756 · typecheck 0. 구매자 21페이지 전부 이관 완료(뷰 22 = 셸 1 + 페이지 21).
- 콜드스타트: 전수 e2e 실패 1건은 재시작 직후 첫 테스트 admin-categories ①(관리자 화면·이번 변경 무관) — 단독 재실행 1/1로 LT-22 콜드 트랩 판정.
- **부분 스킨 타입·비동기 경로**: 임시 스킨(parent classic·HelpView만 `defineAsyncComponent`)을 등록해도 typecheck 0 → 레지스트리 타입 수정 불필요("classic = 전부 필수, 그 외 = Partial"이 이미 성립). dev 서버 SSR에서 비동기 뷰 렌더·없는 뷰의 classic 대체·쿠키 전환까지 확인 후 제거 → 위 §8 "SSR 렌더" 항목 해소(청크 분리는 prod 빌드 미확인·아래 이월).
- **규칙 보완(1b·1c에서 확정)**: 뷰는 "기존 템플릿에 vm. 접두만" — 상수·순수 함수(라벨 함수·formatDateTime 등)도 원래 이름 그대로 vm 키로 넣고 계약은 `typeof`로 선언한다. 템플릿 안 대입(`vm.cancelConfirmOpen = true`·`vm.confirmTargetId = null`·`vm.activeImageUrl = …`)과 컴포넌트 v-model(`v-model="vm.attachments"`)도 reactive 경유로 그대로 동작한다. watch는 전부 페이지 script에 남는다. 예외 1건: 1a withdraw는 WITHDRAW_NOTICE를 `vm.notice`로 개명한 채 유지.
- **e2e 미커버 페이지**: signup·mypage/profile·orders/index·cart·checkout/index·mypage/addresses는 e2e 0, products/[id]는 smoke(SSR 렌더)만 → 커밋하지 않는 임시 Playwright 8케이스(목 외 비-GET API 전부 abort로 실데이터 변경 0)로 입력·검증 안내·탭/페이지 이동·수량/선택·배송지 전환·결제수단·수정 모드·요청 취소 패널·옵션/담기·썸네일 전환 확인 → 8/8 후 삭제.
- 임시 스크립트 셀렉터: 1회차 3건 실패는 `getByRole('status')`가 Nuxt 라우트 안내 `<span role="status">`와 앱 문구 `<p role="status">`에 동시에 걸린 strict 위반(앱 결함 아님) → 기대 문구 `getByText`로 바꿔 재실행 8/8.
- 트랩 재발: 1b 보완 검증 1회차 24건 실패는 1a typecheck(nuxt prepare) 뒤 컨테이너 재시작 없이 e2e를 돌려 `#app-manifest` 미해석(FE-16 계열) → 재시작 후 29/29. 이후 검증 순서 = typecheck → 재시작·healthy → vitest·e2e.

### §8 이월(추가)
- prod 빌드에서 비동기 스킨 뷰의 청크 분리 확인(첫 실제 스킨 추가 시).
- 스킨 밖 공용 컴포넌트(AppHeader·HomeProductGrid·ProductListView·CategoryTabs + 페이지 하위 컴포넌트 CheckoutOrderItemList·CheckoutPaymentSummary·OrderSummaryCard·ClaimSummaryCard·OrderItemDeliveryInfo·ClaimAttachmentInput·CommonErrorState·CommonEmptyState)는 모든 스킨이 공유한다 — 스킨별 모양이 필요해질 때 로직/뷰를 나눈다.
- e2e 미커버 6페이지는 정규 spec이 없다(이번 확인은 임시 스크립트) — 해당 흐름을 바꿀 때 spec을 추가한다.

## FE-68: renew 스킨 준비 — 범위·토큰 덮어쓰기·서체·testid 전환 (Track 105-2a) (2026-09-24)

배경: FE-67 구조 위에 두 번째 스킨 renew를 만든다. 정찰(recon-report-track105-2) 결과 구매자 상품 API는 `GET /api/v1/products` 하나(최신순·카테고리별만 가능)이고, 카테고리 API는 루트만 주며 slug가 없고, 운영자 테마·기획전 엔티티는 없다.

결정:
- **105-2 범위(β)** 메인 큐레이션을 위해 구매자 상품 목록에 셀러 필터·가격 상한·판매량 정렬을 BE에 추가한다. 운영자 테마·기획전은 만들지 않고 메인에 자리만 둔다.
- **카테고리 색 키 = 카테고리 이름**(displayName). 매핑표에 없는 이름은 기본값. id는 AUTO_INCREMENT이고 시드가 id를 지정하지 않아 환경마다 달라진다.
- **목록 하위 카테고리 탭 미도입** — 카테고리 API가 루트만 주고 자식 데이터가 없다.
- **스킨별 데이터 선언**(스킨이 필요한 조회를 어떻게 선언할지)은 105-2c에서 정한다.
- **토큰** 브랜드 색 17·모서리 3을 shadcn 토큰과 같은 `:root` 변수 + `@theme inline` 배선으로 옮겼다(classic 값 동일). renew는 `:root[data-skin="renew"]`에서 변수 값만 덮어쓰므로 renew 뷰가 없는 화면도 classic 뷰가 renew 톤으로 렌더된다. 가격은 본문색, 버튼·강조·활성(ring)은 포인트색. 파스텔 5쌍·이미지 대기 배경·큰 면 모서리(32px)는 변수만 정의(사용은 2c).
- **서체** 나눔고딕 자체 호스팅 유지(보유 굵기 400·700 — 800 없음). IBM Plex Mono 500·600 woff2를 같은 방식으로 자체 호스팅(OFL 동봉 `public/fonts/IBMPlexMono-OFL.txt`). `--font-mono`는 renew 블록에서만 덮어써 classic의 `font-mono`는 그대로다.
- **testid 전환 범위** smoke·mock-payment·password-change·claims에서 구매자 뷰 문구·DOM에 기대던 locator를 classic 뷰 `data-testid`로 바꿨다. 문구 자체가 검증 대상인 단정(에러 안내·교환 안내·사유 라벨·타임라인 단계명·송장 라벨)은 testid로 찾고 문구 단정을 유지한다.

### §1-A 갈림길·채택/기각 근거
- **α 기존 API만** 【기각: 최신순·카테고리별 두 가지뿐이라 메인이 빈약하다】
- **β 구매자 목록 파라미터 3개 BE 추가** 【채택: 기존 엔드포인트 확장으로 메인 섹션을 구성할 수 있다】
- **γ 테마·기획전 엔티티까지** 【기각: 운영자가 등록하는 구조는 D-220 운영 구조와 함께 설계한다】

### §2 확정 구현 규칙·트랩
- 전환 전에도 일반 `@theme` 유틸은 `var(--color-X)`를 참조해 셀렉터 덮어쓰기 자체는 가능했다(컴파일 CSS 실측). 전환은 두 토큰 계열을 같은 `:root` 구조로 통일한 것이다. 컴파일 CSS 전후 diff = 유틸 22곳 `var(--color-X)`→`var(--X)` 1:1 · 출력되던 값 15개 동일.
- classic 뷰 밖 공용 컴포넌트는 이번에 건드리지 않아 locator를 유지했다: smoke 카드 링크·상품명(ProductCard) · claims 첨부 목록 `li`(AttachmentInput). order-resume-payment의 heading '모의 결제'도 범위 밖이라 유지.
- Git Bash에서 `docker exec ... sh -s /tmp/x`처럼 컨테이너 경로를 인자로 넘기면 MSYS가 `C:/Users/.../Temp/x`로 바꿔 bind-mount된 `/app` 아래 `C:` 디렉터리를 만든다 → `MSYS_NO_PATHCONV=1`. 같은 원인으로 보이는 이전 잔재(`frontend/C:/Program Files/Git/app/...` 빈 디렉터리)도 이번에 함께 지워졌다.
- 검증(2026-09-24): typecheck 0 · vitest 756(107 파일) · e2e 전수 115 → 111 passed/2 failed/2 skipped, 실패 2(admin-categories ①·admin-product-form ① 각 파일 첫 테스트)는 단독 재실행 2/2로 LT-22 콜드 트랩 판정 · classic SSR 16화면 data-testid 외 차이 0 · `?skin=renew` 메인·목록·상세 바탕 #F8F6FC·본문 #221F2B·포인트 #5B3FA8·카드 모서리 24px · 흰 글자/포인트 대비 7.72:1.

### §8 이월
- renew `--primary-hover` 값 미지정 → 포인트와 같은 값으로 둠(hover 시 색 변화 없음). 값 확정 시 교체.
- 뱃지 new/soldout 배경은 면(#EFEBF8)·new 글자는 포인트로 매핑, sale 뱃지·success·warning·destructive·control/badge 모서리는 classic 값 그대로.
- classic 뷰·공용 컴포넌트에 하드코딩된 Tailwind 팔레트(`bg-white`·`bg-gray-*`·`text-gray-*`·`border-gray-*` 124곳·31파일)는 토큰이 아니라 renew에서도 바뀌지 않는다 — renew 뷰(2c)에서 처리.
- renew에 비동기 뷰가 없어 prod 청크 분리 확인(FE-67 §8)은 여전히 미실행.

## FE-69: renew 스킨 1차 — 스킨별 데이터 선언·메인 큐레이션·상품 목록·상품 카드 (Track 105-2c) (2026-09-24)

배경: renew는 classic과 다른 데이터(메인 큐레이션·번호 페이지 목록·헤더 상태)를 화면에 쓴다. 스킨 뷰는 조회·이동을 할 수 없으므로(FE-67 skin-guard) 데이터를 누가·언제 조회할지 정해야 한다.

결정:
- **스킨별 데이터 선언** 레지스트리 스킨 정의에 `needs`(SKIN_NEEDS 중: layoutHeader·homeCuration·productList)를 두고 parent 체인까지 합산한다. 페이지·레이아웃은 `useSkinNeeds(키)`가 true일 때만 조회 composable을 호출해 vm으로 넘긴다 — classic은 선언이 없어 추가 조회 0건(메인 SSR 백엔드 요청 수·페이로드 asyncData 키 모두 전과 동일).
  - layoutHeader: 레이아웃이 `useAppHeader()`(AppHeader에서 추출·마크업 불변) 결과를 LayoutShell vm으로.
  - homeCuration: pages/index가 `useHomeCuration()`.
  - productList: products/index·categories/[id]가 `useProductPage()`(번호 페이지·URL ?sort·?page가 상태 기준). 잘못된 카테고리 id는 조회하지 않는다(classic과 같음).
- **메인 큐레이션** 섹션 = 새로 들어온(LATEST 5) · 많이 찾는(SALES 5) · 카테고리별 추천(첫 카테고리 LATEST 5·탭 전환 시 해당 카테고리 재조회) · 셀러 픽(최신 20개에서 서로 다른 셀러 3곳 → 셀러별 3개·신규 API 없음) · 2만원 이하(maxPrice 20000·6). 섹션마다 별도 useAsyncData라 SSR에서 병렬이고, 조회 실패·빈 결과는 그 섹션만 숨긴다(페이지 에러 아님). 카테고리 목록은 헤더와 같은 `categories` 키를 공유한다.
- **테마·기획전 섹션** 자리만 예약(데이터 없음·렌더 안 함) — D-220 운영 구조와 함께 설계한다.
- **카테고리 매핑표** `skins/renew/category-theme.ts`: 의류=라벤더·셔츠 / 리빙=페리윙클·머그 / 뷰티=핑크·병 / 푸드=민트·그릇 / 잡화=버터·가방. 전체·미매핑 = 면(옅은 라벤더)·그리드. 가운뎃점으로 묶인 이름은 첫 단어로 찾는다("리빙·주방" → 리빙·시드 실측명). 일러스트는 인라인 선 SVG(stroke 1.6·currentColor·aria-hidden).
- **반응형 상품 열** ≥1280 5 · ≥1024 4 · ≥768 3 · 그 미만 2. 좌우 여백 데스크톱(≥1024) 64px · 태블릿 40px · 모바일 20px. 모바일 헤더 검색은 아이콘으로 접고 누르면 헤더 아래 한 줄로 펼친다.
- **호버** 상품 카드는 바깥 12px까지 흰 면·그림자·−6px·이미지 1.04, 그 12px를 음수 마진으로 상쇄해 호버 전 정렬 불변. 카테고리 카드 떠오름, 목록 배너 색 전환 0.45s. 이동·확대는 `motion-safe`에서만. SSR 첫 화면을 숨겼다 보이는 연출 없음.
- **목록 정렬 선택지**는 classic과 같은 4종(SALES는 메인 전용 — PRODUCT_SORT_OPTIONS에 넣으면 classic 셀렉트가 바뀐다).
- **testid** renew 뷰는 classic 셸·목록의 testid(search-form·search-input·account-menu-*·footer-help-link·category-tabs)와 공용 카드 testid(product-card·product-card-name — 이번에 공용 ProductCard에도 추가·smoke가 사용)를 그대로 가진다. renew 헤더는 드롭다운이 아닌 인라인 카테고리 메뉴라 `category-menu-content`만 있고 `category-menu-trigger`는 없다.

### §1-A 갈림길·채택/기각 근거
- **큐레이션 조회 위치 = 페이지 로직 + 스킨 선언** 【채택: 스킨은 화면만(FE-67) 유지·필요한 스킨만 조회】
- **스킨 뷰 내부 조회** 【기각: skin-guard 원칙 위반(스킨이 조회·이동을 갖게 됨)】
- **항상 조회** 【기각: classic에 쓰지 않는 호출이 생긴다】

### §2 확정 구현 규칙·트랩
- BE 변경 뒤 백엔드 dev 컨테이너(bootRun·소스 마운트)가 이전 코드로 떠 있으면 새 파라미터가 400(`sort=SALES`)이거나 무시되고 새 필드가 없다 → `docker restart zslab_mall_backend` 후 healthy에서 확인(D-214 §2와 같은 계열).
- 전체 길이 스크린샷은 `loading="lazy"` 이미지가 빈 칸으로 찍힌다 → 끝까지 스크롤한 뒤 캡처.
- renew e2e는 커밋하지 않는 임시 설정(기존 playwright.config + storageState 쿠키 `zslab_skin=renew`)으로 돌린다.
- 검증(2026-09-24): typecheck 0 · vitest 756(107 파일·skin-guard가 renew 포함 스캔) · classic SSR 8화면 data-testid 외 차이 0·페이로드 키 동일 · e2e classic 전수 112 passed/1 failed(admin-categories ① LT-22·단독 1/1)/2 skipped · renew smoke·help·admin-shell·seller-shell 15/16(help ① 1건은 단독 1/1·재현 불가 → 콜드 트랩 판정) · 스크린샷 8장 docs/frontend/screens-track105-2c/(gitignore 대상 아님).

### §8 이월
- 2만원 이하 6개는 ≥1280(5열)·≥1024(4열)에서 마지막 줄에 1·2개가 남는다 — 개수·배치 확정 필요.
- 히어로 "지금 둘러보기"는 classic HomeHero처럼 동작 없는 버튼 그대로.
- renew에 이제 비동기 뷰가 있으나 prod 빌드 청크 분리 확인(FE-67 §8)은 미실행.
- 섹션은 클라이언트 이동 시 조회가 끝나면 나타난다(로딩 자리 표시 없음).
- renew help ① 첫 실행 실패의 원인 미확정(재시작 뒤 첫 실행에서만 1회) — 재발 시 조사.
- renew 뷰가 없는 나머지 화면(상세·장바구니·주문 등)은 classic 뷰에 renew 토큰이 적용된 상태.

### 보완 (Track 105-2c 보완) (2026-09-24)
- **로컬 기본 스킨 renew** 로컬 `.env`에 `NUXT_PUBLIC_SKIN=renew`(비커밋). CI·운영 기본값은 classic 유지(`.env.example`·compose 기본값 불변) — 운영 전환은 서버 `.env` 값만 바꾼다. env는 컨테이너 생성 때 들어가므로 값 변경 뒤에는 restart가 아니라 dev 오버레이로 재생성(`compose -f docker-compose.mall.yml -f docker-compose.dev.yml up -d zslab_mall_frontend`·LT-25). classic 확인은 `?skin=classic` 또는 쿠키 `zslab_skin=classic`.
- **정렬 선택 상자** 네이티브 select 유지, 겉모습만(appearance none·알약·흰 바탕·border-line·높이 44px·오른쪽 선 화살표는 pointer-events 없음·포커스 시 포인트색 테두리·aria-label 유지).
- **가로 스크롤 규칙** 한 줄 고정·overflow-x auto·스크롤바 숨김(`scrollbar-none`)·scroll-snap·오른쪽 끝 흐림(`fade-right` 마스크)·scroll-padding = 줄 안쪽 여백(없으면 snap이 첫 항목을 여백만큼 왼쪽으로 민다). 현재 항목(aria-current="page")은 진입 시와 aria-current가 바뀔 때 가운데로 옮긴다(`skins/renew/scroll-active.ts`). 대상: <768의 목록 카테고리 탭·메인 카테고리 카드 줄·헤더 카테고리 메뉴(≥768은 기존 배치) + 전 폭의 2만원 이하.
- **카테고리 배정표**(실측 표시명·탭 순서): 데모=기본(면·그리드) · 리빙·주방=페리윙클·머그 · 의류=라벤더·셔츠 · 잡화=버터·가방 · 디지털=민트·모니터 · 문구=버터·연필. 파스텔 5색이라 버터를 재사용하되 탭에서 이웃하지 않게(잡화와 문구 사이에 디지털). 뷰티=핑크·병·푸드=민트·그릇은 데이터가 없지만 유지.
- **2만원 이하** 10개 조회 → 전 폭 한 줄 가로 스크롤(카드 폭 고정 160/208px). 열 수 규칙과의 어긋남(§8)을 해소.
- **히어로** "지금 둘러보기"를 /products 링크(`<a>`)로, 버튼 모양 유지.
- **help ①** 이번 전수(기본 renew)에서 재발 없음 — 105-2c 1회는 재시작 뒤 첫 실행 콜드로 본다.
- 검증(2026-09-24): typecheck 0 · vitest 756 · e2e 전수(기본 renew) 112 passed/1 failed(admin-categories ① LT-22·단독 1/1)/2 skipped · classic 회귀(임시 설정 쿠키 classic) smoke·help·admin-shell·seller-shell 16/16 · 스크린샷 8장 재촬영(DevTools 오버레이 숨김) · `docs/frontend/screens-*/` gitignore 추가.

### 보완 2 (Track 105-2c 보완 2) (2026-09-24)
- **정렬 목록 커스텀** renew 목록·카테고리의 정렬을 네이티브 select에서 기존 shadcn dropdown-menu(RadioGroup) 기반 `skins/renew/components/RenewSortMenu.vue`로 바꿨다. 네이티브 select의 펼침 목록은 OS가 그려 스타일을 입힐 수 없기 때문이다. 트리거는 보완 1의 알약 외형(선택값·화살표 열림 시 180°)이고, 패널은 흰 바탕·16px·border-line·부드러운 그림자·트리거 폭 이상·항목 44px·하이라이트 옅은 라벤더·선택 항목 포인트 글자 + 체크, 열림/닫힘 페이드 + 4px(180/150ms). 키보드·포커스 복귀·aria는 reka-ui 기본 그대로. 값 변경 동작(쿼리·1페이지·재조회)은 기존 setSort. classic SortSelect는 그대로다.
  - tw-animate가 없어 shadcn 기본 `animate-in` 클래스는 동작하지 않는다 → 패널 모션은 main.css keyframes(`renew-menu-in/out`)로 직접 정의(reka Presence가 닫힘 애니메이션 끝까지 기다림).
  - 메뉴가 열리면 reka가 바깥 영역에 aria-hidden을 건다(모달 메뉴) — 자동화에서 열린 상태의 트리거는 role이 아니라 CSS로 찾는다.
- **2만원 이하 좌우 이동** ≥1024: 제목 줄 오른쪽 이전·다음 원형 버튼(44px·aria-label·선 화살표)으로 보이는 폭만큼 부드럽게 이동, 양끝에서 해당 버튼 비활성(흐림·클릭 불가), 가장자리 흐림 없음. <1024: 버튼 숨김·스와이프 + 오른쪽 흐림, 끝까지 가면 흐림 제거. 카드 폭 고정·scroll-padding·스냅 유지. `SectionHeading`에 제목 줄 오른쪽 slot을 열었다.
- **흐림 색 토큰화** 2만원 이하 흐림은 마스크 대신 바탕 토큰(`to-surface-page`)으로 끝나는 그라디언트 오버레이 — 끝 도달 시 제거하려고 요소로 둔다. 탭·카드 줄의 `fade-right`(마스크)는 색이 없어 바탕이 그대로 비친다.
- 검증(2026-09-24): typecheck 0 · vitest 756 · 임시 스크립트 18항목(정렬 마우스·키보드·포커스 복귀·패널 치수·가격/이름 순서 · 버튼 양끝 비활성·끝까지 이동/복귀 · 모바일 흐림 색·제거) · e2e 전수(기본 renew) 112 passed/1 failed(admin-categories ① LT-22·단독 1/1)/2 skipped · 스크린샷 5장(정렬 열림 1440/390 · 2만원 이하 1440 처음/끝 · 390).

## FE-70: renew 상품 상세 — 구매 영역·옵션 품절·총액·셀러의 다른 상품·모바일 고정 바 (Track 105-2e) (2026-09-24)

배경: renew 상세는 classic 뷰에 토큰만 입힌 상태였다. 구매 판단에 필요한 옵션 품절·총액·같은 셀러 상품을 화면에 두되, 페이지 로직은 classic과 공유하고 classic 동작·조회는 바꾸지 않는다.

결정:
- **구성** `skins/renew/views/ProductDetailView.vue`. ≥1024 2열: 왼쪽 갤러리(대표 1:1·32px·이미지 없으면 `--image-placeholder` / 썸네일 88px·18px·선택 = 본문색 2px 테두리), 오른쪽 구매 영역 sticky. 순서 = 카테고리 배지(category-theme 색·해당 카테고리 목록 링크) → 상품명 32px → 셀러 칩(이니셜·링크 없음) → 가격(mono) → 구분선 → 옵션 알약 → 수량 알약 → 총 상품 금액 박스("배송비 무료") → 담기 56px → 성공 카드(민트·"장바구니 보기" /cart) / 실패 문구. 설명은 아래 전체 폭 흰 카드(28px·본문 최대 880px). testid(product-detail-name·product-detail-price)는 classic과 같다. 모션: 썸네일 전환 시 대표 이미지 교차 페이드 0.2s·성공 카드 페이드·motion-reduce 시 없음.
- **sticky 기준** 헤더 높이 토큰 `--header-height: 69px`(main.css renew 블록) 한 곳 — LayoutShell 헤더(≥1024 `lg:h-(--header-height)`, 자연 높이와 같은 값)와 구매 영역(`top: calc(var(--header-height) + 24px)`)이 함께 쓴다. 스펙의 "top 24px"를 그대로 두면 구매 영역 윗부분이 sticky 헤더 밑에 가려진다.
- **옵션 품절 규칙** 표시(흐림·취소선) = 현재 선택 조합 기준 / 비활성 = 이 값으로 구매 가능한 variant가 전혀 없을 때만. 판정은 `lib/utils/product-option-availability.ts` 하나(이 값 + 다른 그룹 선택값을 포함하는 variant 중 구매 가능 0 → 품절·미선택 그룹은 조건에서 뺌·매칭 variant 없음도 품절)를 선택 조합/빈 선택으로 두 번 쓴다. 조합 기준 품절 값을 누르면 기존 "선택하신 옵션은 품절입니다."·담기 비활성(canAddToCart)이 그대로 동작한다.
  - 교착 사례: 블랙-L·화이트-M 품절(대각)에서 블랙→M을 고르면 조합 기준으로 화이트·L이 모두 품절이라, 비활성까지 조합 기준이면 구매 가능한 화이트-L로 옮겨갈 수 없다(선택 해제 수단 없음).
- **총 상품 금액** = 기존 표시 단가(currentPrice = 확정 variant salePrice = basePrice + additionalPrice) × 수량 — 장바구니 단가(CartService displayPrice)와 같은 규칙을 재사용하고 새 계산식을 만들지 않는다. variant 확정 전에는 null → 금액 박스·고정 바 모두 "옵션을 선택해 주세요"(판매 불가면 그 사유 문구). 담기 비활성은 기존 canAddToCart 그대로.
- **셀러의 다른 상품** 스킨 데이터 선언 `productDetailMore`(SKIN_NEEDS 추가·renew needs). 페이지가 선언 시에만 `useProductDetailMore()` 호출: 상세 응답 sellerPublicId(D-222)로 LATEST 6개 조회 → 현재 상품 제외 → 최대 5개(목록 API에 제외 파라미터가 없어서). 실패·0개는 섹션 숨김. SSR은 상세 요청을 기다린 뒤 조회하고, 브라우저는 상세가 아직 없으면 첫 조회를 건너뛰고 sellerPublicId가 정해질 때 1회 조회(중복 요청 방지). 제목 "[셀러명]의 다른 상품"·renew 상품 카드·메인과 같은 열 규칙. classic은 추가 조회 0.
- **모바일 고정 바(<768)** 하단 fixed(흰 바탕·상단 테두리·아래 여백 max(12px, safe-area))에 총액 + 담기(본문과 같은 handleAddToCart·같은 비활성 규칙). 바가 페이지 끝(푸터)을 가리지 않도록 뷰가 `useHead` bodyAttrs로 body 하단 여백(바 높이 73px = 테두리 1 + 12 + 48 + max(12, safe-area))을 준다 — 페이지 이탈 시 자동 제거.
- **vm 추가 필드** isOptionValueSoldOut·isOptionValueUnavailable·totalPrice·sellerProducts(classic 뷰는 쓰지 않음·classic 뷰 무변경).
- **제외** 바로 구매·셀러 목록 링크(셀러 칩은 링크 없음)·등급 표시 — 등급 혜택율(grade_policy discount_rate·point_rate)은 결제·적립에 적용하는 코드가 없어 화면에 보이면 실제와 어긋난다. 배송 정보는 "배송비 무료" 문구만(배송비 0 고정 D-61).

### §1-A 갈림길·채택/기각 근거
- **비활성 = 이 값으로 살 variant가 없을 때만**(D5 A) 【채택: 교착 없음·페이지 로직 불변·classic 무영향】
- **조합 기준 비활성 + 선택 초기화 버튼** 【기각: 요청에 없는 요소 추가】
- **품절 값 클릭 시 양립 불가 선택 자동 해제** 【기각: selectOption 동작이 바뀌어 classic까지 영향·renew 전용 함수 필요】
- **확정 전 총액 = 최저가 × 수량** 【기각: 옵션에 따라 달라지는 합계를 확정처럼 보인다】

### §2 확정 구현 규칙·트랩
- **컨테이너 vitest가 로컬 스킨 env를 물려받음 → 테스트 환경 classic 고정** @nuxt/test-utils가 설정 로드 시 process.env의 NUXT_*를 runtimeConfig에 적용한다(test-utils config.mjs applyEnv·`.env.test`는 기존 env를 덮지 않음). 컨테이너 `NUXT_PUBLIC_SKIN=renew`가 새면 classic 전제 테스트(CategoryPage.spec)가 실패 → `frontend/vitest.config.ts` 최상단에서 `process.env.NUXT_PUBLIC_SKIN = 'classic'`(test.env는 워커 전용이라 늦음).
- **준비 단계(setupNuxt 훅) 시간 상향** 컨테이너 실행 시 import가 거의 없는 가벼운 파일이 워커 첫 파일이 되면 Nuxt 전체 변환을 준비 단계에서 떠안아 10s 기본 제한을 넘는다(측정 transform 10.02·10.35s — 다른 무거운 프로세스 없이 재현, 페이지를 import하는 파일은 import 단계에서 선변환돼 통과) → `hookTimeout: 30000`(testTimeout 불변). 상향 후 단독 transform 12.5s로 통과.
- 페이지 내 이동은 URL이 새 페이지 부착보다 먼저 바뀐다 — 이전 페이지의 head(body class) 제거를 확인할 때는 이전 뷰 요소가 사라진 뒤 측정한다.
- 상품명 32px/800은 NanumGothic ExtraBold 파일이 없어 700으로 렌더된다(`font-extrabold` 지정).
- 검증(2026-09-24): typecheck 0 · vitest 108 files / 762 passed(신규 unit 6케이스 포함·skin-guard) · e2e 전수(기본 renew) 112 passed/1 failed(admin-categories ① LT-22·단독 1/1)/2 skipped · classic 회귀(임시 설정 쿠키 classic) smoke·claims·order-resume-payment 9/9 · classic SSR 3종(단일 옵션·다중 옵션·판매중지 — 로컬 DB 1행 임시 STOPPED 후 복원) main 대비 차이 = devtools 타임스탬프 1개·페이로드 키 동일(추가 조회 0) · 임시 스크립트 26항목(품절 비활성·교착 탈출·총액·sticky 93px·담기 성공 카드/장바구니 이동·390 고정 바 담기·body 여백·셀러 조회 renew 1회/classic 0회) · 스크린샷 4장 docs/frontend/screens-track105-2e/.

### §8 이월
- `viewport-fit=cover` 미적용 — nuxt.config에 head viewport 설정이 없어 iOS에서 `env(safe-area-inset-bottom)`이 0이다(고정 바 아래 여백은 최소 12px로 동작). 적용하면 전 페이지 영향 검증이 필요하다.
- 768~1023은 1열 쌓임이고 고정 바가 없다(스펙 범위 그대로).

## FE-71: renew 구매 흐름 — 장바구니·주문서·모의 결제·주문 완료 · 모바일 고정 바 도킹 (Track 105-2f) (2026-09-24)

배경: 상세(FE-70) 다음 구매 흐름 4화면이 classic 뷰에 토큰만 입힌 상태였다. 페이지 script(결제·주문·장바구니 로직)는 그대로 두고 뷰만 renew로 만든다.

결정:
- **구성** renew 뷰 4개(CartView·CheckoutView·PaymentMockView·CheckoutCompleteView)를 renew/index에 등록. 장바구니·주문서는 ≥1024 [목록 | 400px 금액 카드] 2열, 금액 카드는 sticky(top = `--header-height` + 24px·FE-70 토큰). 흰 카드 28px·금액 mono(값 변경 시 0.2s 페이드 `FadeAmount`). 공용 renew 컴포넌트: `RenewCheckbox`(실제 checkbox를 appearance-none으로 꾸밈·라벨 연결) · `FadeAmount` · `MobileActionBar`.
  - 장바구니: 전체 선택 · 품목 행(체크·썸네일 96px 18px/없으면 placeholder 면·셀러명(있을 때)·상품명·옵션·구매 불가 핑크 배지·수량 알약·합계·삭제 아이콘) · 금액 카드(선택 상품 금액·배송비 무료·결제 예정 금액·주문하기 56px) · 빈 상태(아이콘·"장바구니가 비어 있어요"·쇼핑 계속하기 /products). busy 잠금·구매 불가 비활성·checkoutEnabled 규칙은 classic 그대로.
  - 주문서: 번호 섹션 ① 배송지 ② 결제 수단(PAYMENT_METHODS 카드형 실제 radio 3열 — 4값이라 3+1 배치) ③ 주문 상품 + 결제 금액 카드(오류·장바구니 링크는 버튼 바로 위·submitting 스피너).
  - 모의 결제: 결제 금액 카드 형태만 바꾸고 제목(h1 "모의 결제")·문구·버튼·동작·testid 그대로. 주문 완료: 민트 원형 체크·"주문이 완료됐어요"·주문번호 mono·주문 상세 보기(id 없으면 classic처럼 주문 내역 /orders)·쇼핑 계속하기(/products).
- **저장 배송지 요약 카드**(역제안 β 채택 — 재구매 사용자는 입력보다 확인이 주 동작) 저장 배송지를 고르면 입력칸 대신 요약(받는 분·연락처·주소) + "이 주소 수정"으로 펼친다. 새 주소 입력·저장 배송지 없음은 처음부터 펼침. 알약 선택은 기존 `selectedKey` + `onSelectAddress` 그대로이고 펼침 여부만 뷰 로컬 상태(다른 알약을 고르면 다시 접힘) — 로직 불변. 입력칸은 접혀도 DOM에 남아 v-model·required·maxlength가 classic과 같고, 접힘은 grid-rows 높이 전환 + `inert`(초점·보조기기 제외).
- **모바일 고정 바 도킹(<768)** 상세·장바구니·주문서가 같은 `MobileActionBar` 하나를 쓴다. 본문의 실제 주 버튼(장바구니 담기·주문하기·결제하기)이 화면에 없을 때만 바가 나타난다 — `skins/renew/dock-visibility.ts`(IntersectionObserver·rootMargin 하단 = 바 높이라 실제 버튼이 바 뒤에 있으면 "안 보임"·DOM만 사용·skin-guard 준수). 표시/숨김은 아래로 슬라이드 + 페이드 0.2s(reduced-motion 즉시), 첫 측정 전(SSR 포함)에는 숨김이라 처음부터 버튼이 보이는 화면에서 깜빡이지 않는다. 바 버튼은 본문 버튼과 같은 함수·같은 비활성 규칙(주문서는 같은 form submit).
  - FE-70의 "항상 표시 + body 하단 여백(useHead)" 방식을 대체한다: 실제 버튼이 화면에 보일 때 바 버튼과 두 개가 동시에 보였고, 페이지 끝에서 body 여백이 푸터 아래 빈 영역으로 남았다. 도킹이면 실제 버튼이 보이는 페이지 끝에서 바가 없어 여백이 필요 없다(상세처럼 버튼이 위쪽에 있는 화면은 페이지 끝에서도 바가 떠 있어 푸터 아래쪽을 덮는다).
- **계약 확인 결과** 디자인 전부 기존 vm으로 가능. 없어서 쓰지 않은 필드: 장바구니 품목 **구매 불가 사유**(CartItemView는 purchasable boolean만 → 배지는 classic 문구 "구매 불가 (품절 또는 판매 중지)"). 장바구니 vm에는 배송비가 없어 "무료" 표기(SHIPPING_FEE 0·FE-70과 같음), 주문서는 summary.shippingFee(0이면 무료).
- **testid** classic 대응 뷰의 testid 전부 유지(CartView item-option-label · 주문서 공용 목록과 같은 item-option-label · payment-mock-title/success/cancel/error · checkout-complete-order-id).

### §1-A 갈림길·채택/기각 근거
- **저장 배송지 = 요약 카드 + 수정 펼침(β)** 【채택: 재구매는 확인 위주·로직 불변】
- **저장 배송지도 입력칸 항상 펼침(classic 배치)** 【기각: 채워진 입력칸 7개가 확인을 어렵게 한다】
- **고정 바 도킹** 【채택: 버튼 중복 노출·푸터 아래 여백 해소】
- **고정 바 항상 표시 + body 여백(FE-70 방식)** 【기각: 위 두 문제】

### §2 확정 구현 규칙·트랩
- Playwright `waitForLoadState('networkidle')`는 이미 도달한 상태면 새 요청을 기다리지 않고 즉시 반환한다 — 조작(PATCH·재조회·금액 페이드) 뒤 값 확인은 값이 바뀔 때까지 폴링한다.
- renew 뷰는 비동기 청크라 컨테이너 재시작 뒤 첫 이동에서 늦게 붙는다 — 즉시 판정(`isVisible`) 전에 대상 요소를 기다린다.
- 높이 0·overflow로 접은 영역 안 요소는 Playwright `isVisible`이 참이다(요소 자체 박스는 남음) — 접힘은 래퍼 높이·`inert`로 판정한다.
- 좁은 폭(390) 장바구니 행은 수량 알약과 금액이 한 줄에 안 들어간다 → 금액 줄바꿈 금지 + 하단 줄 flex-wrap(금액이 다음 줄 오른쪽).
- 검증(2026-09-24): typecheck 0 · vitest 108 files / 762 passed(skin-guard) · e2e 전수(기본 renew) 112 passed/1 failed(admin-categories ① LT-22·단독 1/1)/2 skipped(도킹 전·후 각 1회) · classic 회귀 smoke·mock-payment·order-resume-payment·claims 11/11 · 임시 스크립트(실 API·로컬 DB 주문 생성·데모 구매자 기존 장바구니 선택 해제 후 복원·새 주소 저장 안 함) 2차 39/41 + 측정 시점 결함 2건 부분 재확인 5/5 — 구매 흐름(담기 → 선택·수량 → 주문서 요약·수정 펼침·새 주소·결제 수단 → 모의 결제 성공 → 완료 · 실패 경로 · 390 고정 바 주문·결제) · 구매 불가 비활성·빈 상태(장바구니 응답 mock) · 390 도킹 3화면 ①~④·동시 노출 0 · 스크린샷 12장 docs/frontend/screens-track105-2f/.

### §8 이월
- 구매 불가 사유 필드(품절/판매중지/셀러 비활성 구분)가 생기면 배지 문구를 사유별로 바꿀 수 있다(BE 계약 추가 필요).
- 상세 화면은 담기 버튼이 페이지 위쪽이라 페이지 끝에서도 고정 바가 떠 푸터 아래쪽을 덮는다(도킹 규칙상 정상) — 필요 시 푸터 관찰을 더하는 방식 검토.
- 도킹 판정용 바 높이는 마운트 시 한 번 잰다 — <768↔≥768을 오가는 창 크기 변경에서는 다시 재지 않는다.
- `viewport-fit=cover` 미적용(FE-70 §8)은 그대로다.

### 보완 (Track 105-2f 보완) (2026-09-24)
- **고정 바 표시 중 하단 여백** 바가 보이는 동안에만 body 하단 여백 = 바 높이(`calc(61px + max(12px, safe-area))`·<768), 숨겨지면 0 — `MobileActionBar`가 표시 상태로 useHead bodyAttrs를 바꾼다. 상세처럼 페이지 끝에서도 바가 떠 있는 화면은 푸터 마지막 줄까지 바 위로 보이고(§8 "푸터 아래쪽을 덮는다" 해소), 장바구니·주문서는 페이지 끝에서 바가 숨어 여백 0이다. 여백은 문서 끝에만 붙어 바 표시·숨김이 바뀌어도 보이는 콘텐츠 위치는 그대로다.
- **결제 수단 배치** 주문서 결제 수단 카드 ≥768 4열 · <768 2열(4값이 한 줄 또는 2×2로 맞아떨어짐 — 3열의 3+1 배치 해소).
- 검증(2026-09-24): typecheck 0 · vitest 108 files / 762 passed(skin-guard) · e2e smoke·mock-payment·order-resume-payment·claims(기본 renew) 11/11 · 임시 스크립트(390·주문 생성·장바구니 조작 없음) 9/9 — 상세 끝 바 표시·여백 73 = 바 73·푸터 마지막 줄 안 가려짐 · 장바구니·주문서 끝 바 숨김·여백 0·푸터 아래 빈 영역 0 · 3화면 바 표시↔숨김 전환 전후 제목 문서 위치·스크롤 불변 · 결제 수단 1440 4열·390 2열 · 스크린샷 detail-390-end·checkout-method-1440·checkout-method-390.

## FE-72: renew 마이페이지 — 공통 틀(사이드 메뉴)·홈·회원 정보·비밀번호·배송지·탈퇴 · 메뉴 정의 통합 (Track 105-2d-FE1) (2026-09-24)

배경: 마이페이지 5화면이 classic 뷰에 토큰만 입힌 상태였고, 메뉴가 헤더(useAppHeader)와 classic 허브(pages/mypage/index.vue)에 따로 정의돼 라벨·순서가 달랐다. 홈은 D-223 요약 API·주문 목록 items[]로 구성한다.

결정:
- **메뉴 정의 1곳** `lib/constants/mypage-menu.ts` MYPAGE_MENU_ITEMS = 홈(/mypage)·주문 내역(/orders)·회원 정보·배송지 관리·비밀번호 변경·회원 탈퇴(label·description). 취소·반품·교환 항목 없음(FE-63).
  - 파생: 헤더 ACCOUNT_MENU_ITEMS = 회원 탈퇴 제외(FE-19 유지)·홈은 "마이페이지"로 표기 / classic 허브 = 허브 자신인 홈 제외·description 사용 / renew 사이드 메뉴 = 전체.
  - 통합으로 바뀐 표시: 헤더 계정 메뉴 라벨·순서(주문내역 → 주문 내역 · 회원정보 수정 → 회원 정보 · 배송지 관리가 비밀번호 변경 앞) · classic 허브 "프로필" → "회원 정보"(순서도 정의 기준).
- **사이드 메뉴** renew 공통 `skins/renew/components/MypageFrame.vue`(메뉴 + 제목 h1 + slot)를 5화면 모두 쓴다. 정의를 데이터로 렌더(항목 추가는 정의 한 줄). 현재 위치 = NuxtLink 정확 일치 aria-current(홈은 하위 화면에서 비활성)이고 활성 스타일도 aria-current 기준. ≥768 왼쪽 세로 메뉴(≥1024 sticky) · <768 상단 가로 스크롤 탭 + `followActiveItem`(scroll-active 재사용). 본문은 진입 시 짧게 떠오른다(@starting-style·움직임 줄이기면 없음).
- **홈 구성** 인사 카드("{회원명}님, 반가워요" + 회원 정보 수정·등급/혜택 없음) → 주문 현황(최근 {periodMonths}개월·결제 완료·상품 준비·배송 중·배송 완료·구매 확정 5단계·단계 사이 구분선·0건 흐림·표시 전용) → 구매 확정 대기(stages.delivered > 0일 때만·확정하러 가기 /orders) → 진행 중인 취소·반품·교환(activeClaimCount·/orders?tab=claim) · 기본 배송지(isDefault·없으면 빈 상태 + 배송지 등록) 2카드(<768 세로) → 최근 주문 3건(썸네일 = items[0].thumbnailUrl·없으면 자리 표시 · 주문일·주문번호 · previewTitle · 상태 배지 · 금액 · 행 클릭 = 주문 상세 · 더 보기 /orders · 0건이면 빈 상태 + 쇼핑하러 가기).
- **홈 데이터** need `mypageHome`(renew만 선언·classic 추가 조회 0) → `useMypageHome`: 회원명 = useProfile.fetchProfile · 기본 배송지 = useAddresses.listAddresses의 isDefault · 요약 = useOrderStatusSummary(신규) · 최근 주문 = useRecentOrders(3)(신규). 섹션별 독립 조회·캐시 키 분리(mypage-home:* · recent-orders — useOrderList 키 'order-list'가 고정이라 주문 내역 페이지와 데이터가 섞이지 않게). 섹션별 스켈레톤, 실패한 섹션만 CommonErrorState + 재시도, 어느 섹션이든 401이면 로그인 유도(기존 페이지 관례). 단계 매핑 = `lib/utils/order-summary-stages.ts`.
- **타입** OrderStatusSummary·OrderStatusStages 신설 · OrderSummaryItem(null 키가 빠지는 필드는 선택·D-223 인계) · OrderSummary.items 선택 필드.
- **회원 정보·비밀번호·배송지·탈퇴** renew 뷰만 추가(vm 계약·페이지 script 무변경·기능 추가 없음). testid·e2e 단언 문구 유지. 배송지는 ≥1024 [주소 카드 목록 | 400px 폼 sticky], 체크박스는 RenewCheckbox.
- **계약** MypagePageVm.home(MypageHomeVm | undefined) 추가. renew MypageView는 home을 필수로 받는다(need 선언 스킨 전제·renew HomeView와 같음).

### §1-A 갈림길·채택/기각 근거
- **주문 목록 탭 구성: α 4탭 유지 / β 2탭** → β 채택(사이드 메뉴 통합 결정과 정합·BE type 선택값). 실제 변경은 FE2.
- 그 외 대안 검토 없음.

### §2 확정 구현 규칙·트랩
- bootRun backend 컨테이너는 재생성 전까지 새로 머지된 BE 코드를 싣지 않는다 — 신규 API를 쓰는 FE 검증(스크린샷 포함) 전에 backend를 --force-recreate·healthy 확인한다(이번: /orders/summary가 "summary"를 주문번호로 해석해 404·목록 items[] 누락 → 재생성 후 200). D-214 §2와 같은 계열.
- 검증(2026-09-24): typecheck 0 · vitest 110 files / 769 passed(skin-guard·신규 mypage-menu 4·order-summary-stages 3) · e2e password-change·admin-shell(기본 renew) 9/9 · 스크린샷 13장 docs/frontend/screens-track105-2d/(홈 4장은 backend 재생성 후 재촬영).

### §8 이월
- 홈 클레임 카드 링크 ?tab=claim은 FE2 전까지 기본 탭(주문)으로 열린다.
- 390 폭 최근 주문 행의 주문번호 잘림 → 통합 검토의 모바일 항목에서 처리.
- AppHeader.spec 테스트 이름 "링크 6개" 표기 불일치(실제 링크 5 + 로그아웃·기존 표기).

외부 검토: C / 생략

### 보완 1 (배송지 모달 전환·공용 모달 기반) (2026-09-24)
- **공용 모달** `components/ui/dialog/`(shadcn-vue 구성·reka-ui Dialog·새 패키지 없음): Dialog · DialogContent(Portal + Overlay + Content) · DialogHeader · DialogTitle · DialogDescription · DialogBody(본문 안쪽 스크롤) · DialogFooter · DialogConfirm · DialogClose(reka re-export).
  - 용도 2가지: 폼 = Content 안에 Header + Body + Footer 조립 / 확인 = DialogConfirm(제목 + 설명 + 취소·확인, destructive면 확인 버튼 위험 강조색, 열림은 부모의 open + update:open, 확인은 confirm만 알림).
  - 반응형: ≥768 가운데 모달(최대 85dvh·기본 max-w-lg, 사용처가 class로 폭 지정) / <768 하단 시트(최대 90dvh·하단 safe-area 여백·<768 바닥 버튼은 같은 폭으로 채움). 애니메이션: 배경 페이드 · 모달 페이드 + 0.96 확대(scale 속성 — 가운데 정렬 translate와 분리) · 시트 아래→위, 0.15~0.25s, reduced-motion이면 없음(main.css keyframes — tw-animate 미도입).
  - 포커스 가두기·Esc·aria 제목·설명 연결·배경 스크롤 잠금(Overlay)·닫을 때 열었던 요소로 포커스 복귀는 reka 기본 동작(reka 2.10은 열릴 때 활성 요소를 trigger로 기억 — DialogTrigger 없이 제어형 open도 복귀). 닫기 X는 DOM 끝에 둬 첫 초점이 본문으로 간다.
- **배송지 vm 추가(기존 필드·함수 동작 유지)** formOpen · openCreate(초기화 후 열기) · closeForm(초기화 후 닫기) · startEdit(기존 + 열기) · 저장 성공 시 닫기 / removeTargetId · requestRemove · cancelRemove · confirmRemove(window.confirm 없이 삭제). 삭제 실행부는 내부 removeById 하나를 공유하고, 기존 handleRemove는 window.confirm 후 같은 함수를 부른다 — classic 뷰는 무변경으로 기존 경로(인라인 폼·window.confirm)를 그대로 쓴다.
- **renew 배송지** 제목 줄 "+ 새 배송지 추가"(MypageFrame actions slot) · 카드 그리드(≥1024 2열·받는 사람·기본 배지·배송지 이름·연락처·주소·수정·삭제·기본이 아니면 기본으로 설정) · 0건 빈 상태 + 추가 · 폼 모달(2열·<768 1열·추가 모드만 기본 체크·취소·저장·errorMessage는 모달이 열려 있으면 안, 닫혀 있으면 페이지) · 삭제 확인 모달("이 배송지를 삭제하시겠습니까?" + 대상 요약·취소·삭제) · successMessage 페이지 role=status. 닫힘 애니메이션 동안 closeForm·confirmRemove가 상태를 먼저 비워도 제목·설명이 바뀌어 보이지 않게 열린 시점 값을 뷰가 붙잡아 둔다.

#### §1-A 갈림길·채택/기각 근거
- **A 상하 배치 + 폼 접기 / B 단순 상하 배치 / C 모달** → C 채택(reka-ui 기반이라 비용이 낮고 106 리뷰·문의·재입고 신청 등에서 재사용).

#### §2 검증
- 검증(2026-09-24): typecheck 0 · vitest 110 files / 769 passed(skin-guard) · 임시 스크립트(데모 구매자·임시 배송지만 생성·삭제·잔여 0) 8/8 — 추가 저장 → 닫힘 + 목록 반영 · 수정 모달 값 채움(제목·필드·기본 체크 숨김) → 저장 반영 · 삭제 확인 취소 유지·삭제 제거 · Esc 닫힘 + 추가 버튼·해당 카드 수정 버튼 포커스 복귀 · 열림 동안 body overflow hidden·휠 후 scrollY 불변 · 스크린샷 8장 addresses(목록·추가·수정·삭제 확인) × 1440/390.

#### §8 이월
- 연락처 표시 형식(+82 원문 그대로 표시).
- 구매 확정·탈퇴 확인의 모달 전환 여부(FE2·통합 검토에서 판단).

### 보완 2 (renew 공용 알림·배송지 1열 행) (2026-09-24)
- **renew 공용 알림** `skins/renew/components/RenewNotice.vue`(tone 필수). 톤 규칙 3가지:
  - success = 저장·추가·수정·삭제 완료(민트·체크 아이콘·role=status)
  - info = 안내(라벤더·정보 아이콘·role=status)
  - danger = 실패(핑크·경고 아이콘·role=alert)
  - 색은 기존 파스텔 토큰만 쓰고(새 색 값 없음) 아이콘은 @lucide/vue(CircleCheck·Info·TriangleAlert·aria-hidden). 본문은 단어 단위 줄바꿈(break-keep).
- **적용 범위** FE1 renew 뷰 5개의 성공·안내·에러 메시지 전부(모달 안 에러 포함): 회원 정보 저장 success·에러 danger / 비밀번호 임시 비밀번호 안내 info·에러 danger / 배송지 페이지 완료 success·페이지 에러 danger·폼 모달 에러 danger / 탈퇴 안내 info·에러 danger / 홈 구매 확정 대기 info. 문구·testid(password-temporary-notice·withdraw-notice·mypage-confirm-waiting)는 유지. 2f 이전 renew 뷰는 대상 아님.
- **배송지 목록 1열** 모든 폭 1열 — ≥768 가로형 행(왼쪽 이름·기본 배지·배송지 이름 / 연락처 / 주소, 오른쪽 기본으로 설정(기본이 아닐 때)·수정·삭제), <768 버튼은 아래 줄. 이유: 배송지가 1건일 때 2열 그리드는 오른쪽이 빈 영역으로 남는다.
- **공용 모달 설명** DialogDescription에 break-keep(한국어 단어 단위 줄바꿈 — 삭제 확인의 "없습니/다" 끊김 해소).
- 대안 검토 없음.
- 검증(2026-09-24): 신규 vitest RenewNotice 3(톤별 role·아이콘·내용) · typecheck 0 · vitest 전체 exit 0(skin-guard 포함) · e2e password-change(renew) 3/3 · 임시 스크립트(데모 구매자·임시 배송지 추가 후 삭제·DB 잔여 0) — success 알림 role=status "배송지가 추가되었습니다" · 삭제 확인 설명 word-break keep-all · 스크린샷 addresses-1440·addresses-390·addresses-success-1440·addresses-delete-390.

#### §8 이월
- 2f 이전 renew 뷰(장바구니·주문서 등)의 메시지를 공용 알림으로 교체(통합 검토).
- 알림 톤 4번째 warning(butter, role=status): 되돌릴 수 없는 동작 안내용, 현재 탈퇴 안내만 사용

## FE-73: renew 주문 목록·주문 상세·클레임 신청·클레임 상세 — 탭 2개 · 품목 행 · 구매 확정 확인 모달 (Track 105-2d-FE2) (2026-09-24)

배경: 주문·클레임 4화면이 classic 뷰에 토큰만 입힌 상태였고, 주문 목록은 유형별 4탭(FE-63)이었다. FE-72에서 사이드 메뉴 통합과 함께 탭 2개를 예고했고, 주문 목록 품목 요약(D-223 items[])으로 카드 안에서 품목 단위 동작이 가능해졌다.

결정:
- **탭 2개** `order-tabs.ts` = 전체 주문(order·GET /orders) · 취소·반품·교환(claim·GET /claims를 type 없이). 탭 상수 변경은 classic OrdersView에도 적용된다(허용). 번호 페이지 유지. `useClaimList(page, enabled)` — 클레임 탭일 때만 조회(자동 watch 끔·[enabled, page] 변화 시 refresh), 전환 직후 idle은 로딩으로 표시. 클레임 상세 복귀·진행 클레임 배지는 claim 탭.
- **전체 주문 탭 = 주문 카드** 헤더(주문일·주문번호·"주문 상세" 링크·결제대기면 결제하기) + 진행 클레임 배지(→ claim 탭) + items[] 품목 행(썸네일·품목 상태 배지·셀러명·상품명·옵션·수량·금액). 행 버튼: 구매 확정(DELIVERED) · 클레임 요청(상세와 같은 claimableTypes 규칙·같은 /claims/new 경로·교환은 상품·변형·단가 전달). 배송 조회·요청 상세 버튼은 두지 않는다(D-223). items가 없는 응답은 previewTitle 한 줄.
- **취소·반품·교환 탭 = 클레임 카드** 유형 배지·상태·품목명·요청일·거부 사유·환불 상태, 누르면 클레임 상세.
- **구매 확정 확인 모달(목록·상세 공통)** DialogConfirm·설명 = 기존 경고 문구(ITEM_CONFIRM_WARNING). testid는 기존 확인 패널과 같다(content item-confirm-panel·description item-confirm-warning·cancel item-confirm-cancel·확정 item-confirm-submit). 성공 시 재조회 + success 알림, 실패(422 등)는 서버 detail 우선 danger 알림 — 상세와 같은 규칙. 상세의 인라인 확인 패널은 이 모달로 대체(페이지 확정 상태 confirmTargetId를 모달 열림으로 연결·script 무변경). 목록은 페이지에 confirmTarget·openConfirm·cancelConfirm·submitConfirm·goClaim을 추가.
- **주문 상세** 섹션 순서 유지(헤더 → 결제하기 → 셀러 그룹별 품목 → 총 결제금액 → 배송지 → 목록 링크) + 품목 썸네일(OrderItem.thumbnailUrl·D-223). 배송 정보·송장 복사(공용 OrderItemDeliveryInfo)·자동확정 안내·클레임 버튼 유지.
- **클레임 신청·상세** 기존 vm 계약·화면 상태 그대로 renew 뷰(잘못된 접근/폼/접수 완료 · 타임라인(<768 세로·≥768 가로) · 요청 취소 인라인 · 회수 송장 등록). 4화면 모두 MypageFrame 사용, 사이드 "주문 내역"은 /orders·/orders/*·/claims/*에서 활성.
- **메시지 톤** 완료 success · 안내 info · 되돌릴 수 없는 동작 안내 warning(요청 취소 확인 패널) · 실패 danger.
- **확정 범위 밖 변경 3건**
  - claims/[claimPublicId].vue 복귀 탭 기본값 'cancel' → 'claim'(탭 타입 변경에 따른 필수 수정 1줄).
  - DialogConfirm 선택 prop(pending·content/description/cancel/confirm testid·설명 줄바꿈 표시) — 기본값에서는 동작 불변.
  - MypageFrame activeTo(하위 화면이 소속 메뉴를 지정하면 aria-current).

### §1-A 갈림길·채택/기각 근거
- **탭: α 4탭(FE-63 유지) / β 2탭** → β 채택(FE-72 사이드 메뉴 통합과 정합·BE type 선택값).
- **구매 확정 확인: α 인라인 패널 / β 확인 모달** → β 채택(목록 품목 행 크기 증가 방지·공용 모달 재사용).
- 그 외 대안 검토 없음.

### §2 검증
- 1차(2026-09-24): typecheck 0 · vitest 111 files / 772 passed · e2e claims·order-resume-payment·smoke(renew) 9/9 · 임시 스크립트 7/9 — 실패 2건은 판정 방식 오류로 통과 처리(① 구매 확정 모달 취소 후 배지 불변·확정 POST 0건 확인 ② 클레임 신청 화면 "주문 내역" 활성은 렌더 후 스크린샷에서 확인).
- 재검증(보완 1 반영 후·2026-09-24): typecheck 0(1회 실패 → LocationQuery 타입 명시 후 통과) · vitest 111 files / 777 passed(order-tabs 15·skin-guard 3) · e2e claims·order-resume-payment·smoke(renew) 9/9.

### §8 이월
- FE-72 이월 종결: 구매 확정 모달 전환(목록·상세 공통 확인 모달) · ?tab=claim(claim 탭으로 열림).
- 요청 취소·탈퇴 확인의 모달 전환 여부(통합 검토).
- 클레임 접수 완료 "주문 내역으로" 링크의 탭(현재 기본 탭·통합 검토).
- 목록 페이지당 20건일 때 renew 카드 페이지 길이(통합 검토).
- 모바일 주문 카드의 버튼 밀도(통합 검토).

외부 검토: C / 생략

### 보완 1 (클레임 유형 색·필터 칩) (2026-09-24)
- **유형 색 규칙** renew 전용 상수 1곳 `skins/renew/claim-type-tone.ts` — 유형 배지 = 기존 파스텔 토큰(취소 butter · 반품 pink · 교환 periwinkle·새 색 값 없음), 상태 칩·환불 상태·거부 사유 = 흰 바탕 + 테두리 중립 칩(문구 그대로). 적용 4곳: 클레임 탭 카드 · 주문 카드 진행 클레임 배지 · 클레임 상세 헤더 · 클레임 신청 화면 대상 카드.
- **유형 필터 칩** 취소·반품·교환 탭 안 전체 · 취소 · 반품 · 교환. URL ?tab=claim&type=cancel|return|exchange(type 없음 = 전체·칩 변경 시 page 0·허용값 밖은 전체). 조회는 기존 GET /claims의 type 파라미터(BE 변경 없음). 필터 결과 0건 문구 "해당 유형의 요청이 없습니다". 옛 ?tab=cancel|return|exchange는 claim 탭 + 해당 유형으로 해석한다(parseClaimTypeFilter·?type 우선).
- **지시 없이 추가해 수용한 2건** ① 옛 탭 링크로 들어오면 주소를 tab=claim&type=으로 정리(onMounted — 이후 페이지·탭 이동이 쿼리를 이어받을 때 유형이 사라지지 않도록) ② 진행 클레임 배지 링크에 해당 유형(type)을 붙임(ActiveClaimBadge.claimType 추가·classic 카드는 미사용).

#### §1-A 갈림길·채택/기각 근거
- **α 유형별 페이지 분리 / β 탭 안 필터 칩** → β 채택(탭 통합 결정 유지·BE type 파라미터 재사용·옛 링크 의미 보존).

#### §2 검증
- 위 재검증과 같음 · 스크린샷 orders-claim 1440/390 · orders-claim-filter-return 1440(반품 칩 선택·반품 3건만) · claim-detail 1440(유형 색 배지·상태 중립 칩) — docs/frontend/screens-track105-2d/.

#### §8 이월
- 클레임 목록 썸네일 부재(BE 응답에 없음).
- classic에서 옛 링크로 필터가 적용될 때 칩 UI 없음(105-3에서 해소).

## FE-74: renew 로그인·회원가입·검색 결과·도움말·에러 화면 — 모든 구매자 화면 renew 뷰 보유 (Track 105-2g) (2026-09-24)

배경: renew 뷰가 없어 classic으로 대체되던 마지막 4화면(LoginView·SignupView·SearchView·HelpView)과, error.vue 부재로 Nuxt 기본 영문 에러 페이지(레이아웃·스킨 없음)가 뜨던 에러 화면을 renew로 만든다(recon-report-track105-2g). 이 트랙으로 모든 구매자 화면이 renew 뷰를 갖는다.

결정:
- **공통** 기존 renew 토큰·컴포넌트(RenewNotice·파스텔 토큰) 사용 · 메시지 톤 완료 success · 안내 info · 되돌릴 수 없는 동작 warning · 실패 danger · classic 뷰 무수정(페이지 script·계약은 추가만) · renew 전용 동작은 skin needs(renew만 선언) · 기존 testid·e2e 단언 문구 유지(password-change.spec /login testid 5·문구 1 · help.spec "준비 중입니다." · pixel.mjs 데모 버튼 문구).
- **로그인** 데모 로그인 버튼(FE-43)·기존 동작·에러 문구 유지. "회원가입" 링크(/signup) 추가 — 받은 redirect를 그대로 넘긴다(계약 signupLink). ≥1024는 연보라 안내 면 + 폼 카드(RenewAuthFrame·회원가입 공용), <1024는 폼 카드만.
- **회원가입** 비밀번호 확인 칸(need signupPasswordConfirm·renew만 선언) — 불일치면 요청 전에 "비밀번호가 일치하지 않습니다"(danger·비밀번호 변경 화면과 같은 방식), BE 요청 본문 불변(확인 값 미전송). classic은 확인 칸·검사 없는 현행. "로그인" 링크(/login·redirect 그대로 되돌림·계약 loginLink). 이메일 중복(409) 안내 등 기존 동작 유지. 비밀번호 찾기·약관 동의 없음.
- **검색 결과** renew 상품 목록과 같은 형태(번호 페이지·정렬 칩·상품 카드·카테고리 탭 없음). useProductPage(categoryId, keyword) — 검색 모드 캐시 키 `product-search:${keyword}:${sort}:${page}`로 목록 키와 분리(둘 다 categoryId null이라 충돌), 빈 검색어는 조회하지 않음, BE 목록 API keyword 사용. 제목 면에 검색어·결과 건수. 빈 결과 = 안내 문구 + "전체 상품 보기"(/products). 빈 검색어·50자 초과 등은 기존 동작(안내 문구·400 → CommonErrorState). 헤더 검색창 무변경. classic은 기존 무한스크롤.
- **도움말** renew 준비 중 화면(FE-64 자리 그대로·"준비 중입니다." 유지). 비밀번호 변경 가드 허용 경로 무변경.
- **에러 화면** app/error.vue 신규 — 구매자 경로는 기본 레이아웃(헤더·푸터·data-skin) 안에서 renew ErrorView 렌더(Nuxt는 에러 상태에서 app.vue 대신 error.vue를 렌더하므로 NuxtLayout을 직접 감싼다). 404 "페이지를 찾을 수 없어요" / 그 외 "일시적인 오류가 발생했어요" + 한국어 설명 1줄. 버튼 홈으로(clearError + 이동) · 이전 페이지. 에러 화면에서 API 직접 호출 없음. classic 레지스트리는 renew ErrorView를 가리키는 1줄. 상세 화면의 페이지 안 404(CommonErrorState)는 무변경.
- **error.vue 경로 분기** /admin·/seller 경로는 레이아웃 없이 ErrorView 본문만 렌더하고 "홈으로"는 /admin·/seller — 앱 전역 error.vue 1개라 관리자·셀러 404에 구매자 헤더가 노출되는 회귀를 막기 위해.
- **지시 외 수용 3건**
  - 이전 페이지 대체 이동: 이전 기록이 없으면(주소로 바로 진입) 홈으로 이동.
  - RenewPagination 분리: RenewProductListing의 번호 페이지 블록을 컴포넌트로 옮겨 목록·검색 공용(마크업 동일).
  - productList need 공유: 검색 결과는 새 need 없이 기존 productList를 선언한 스킨에서 번호 페이지로 조회.

### §1-A 갈림길·채택/기각 근거
- **비밀번호 확인 칸: α 추가 / β 현행** → α 채택(비밀번호 찾기 부재로 오타가 곧 영구 잠금).
- **검색 결과: α 무한스크롤 유지 / β renew 목록과 같은 번호 페이지** → β 채택(목록과 일관).
- **에러 화면**: 대안 검토 없음.

### §2 검증
- typecheck 0 · vitest 113 files / 783 passed(신규 useProductPage 3·SignupPage 3·skin-guard 3 — 1차는 SearchView 주석의 "useProductPage(" 표기가 소스 스캔에 걸려 1 failed → 주석 수정 후 통과) · e2e password-change·help·smoke(renew) 7 passed.
- 임시 스크립트(1440·390): 로그인↔회원가입 redirect 유지 · 불일치 danger · 검색 제목·건수 · 정렬 칩·페이지 이동 keyword 유지 · 빈 결과 링크 · 도움말 문구 · 구매자 404(HTTP 404·헤더 포함) · /admin 404(구매자 헤더 없음·홈으로 → /admin) · 회원가입 POST 0.
  - ① "페이지 이동 → API keyword/page" 판정 실패는 판정 시점 오류로 통과 처리 — 이미 idle이라 networkidle 대기가 즉시 반환돼 page=2 요청 전에 읽었고, 2페이지 스크린샷에 page=2 응답(0건)이 렌더됨.
  - ② "404 홈으로 → 홈 렌더" 단독 재확인 통과 — url / · 상품 카드 23 · 에러 화면 0.
  - 2페이지 스크린샷은 totalCount 모의값(45)으로 촬영 — 실데이터에 20건을 넘는 검색어가 없음(항목은 실응답).
- 스크린샷 docs/frontend/screens-track105-2g/ — login · signup · signup-mismatch · search · search-page2 · search-empty · help · error-404(각 1440·390) · error-404-admin-1440 · error-404-home-after-1440.

### §8 이월
- 비밀번호 찾기(BE 기능).
- 약관 동의(약관 본문 없음).
- 500 계열 에러 화면 확인.
- CommonErrorState·CommonEmptyState renew 톤(105-3).
- helpers/login.ts 주석 불일치(smoke 구매자 데모 케이스 언급 — 실제는 password-change.spec ③).
- skin-guard 정규식이 주석까지 검사함.
- classic 스킨에서 ErrorView 파스텔 값 미정의(105-3에서 해소) — /admin·/seller 404도 data-skin이 없어 같은 상태.
- 로그인 1440 스크린샷의 왼쪽 안내 면이 흐리게 찍힘(계산 opacity 1·촬영 시점 무관 — 원인 미확정).

외부 검토: C / 생략

## FE-75: classic 스킨 제거 — renew 기준 스킨 · 토큰 선택자 [data-skin] · classic 전용 컴포넌트 정리 (Track 105-3) (2026-09-24)

배경: FE-74로 모든 구매자 화면이 renew 뷰를 가져 classic은 대체 대상으로만 남았다(recon-report-track105-2g STEP 7). classic을 제거하고 renew를 기준 스킨으로 삼되, 스킨 구조(registry·parent 체인·needs·data-skin)는 계절 스킨 용도로 유지한다.

결정:
- **기준 스킨** `skins/renew/index.ts` parent 제거 · 23뷰 정적 import · `renewViews: SkinViews`(전체 Record — 누락은 typecheck 차단). registry `SKIN_NAMES = ['renew']` · `DEFAULT_SKIN = 'renew'` · resolveSkinView 최종 대체 = renewViews. `SkinDefinition.views`는 Partial 유지(이후 스킨은 바꾸는 뷰만 비동기) · needs·parent 체인·순환 방지 유지. `skins/classic/` 23파일 삭제.
- **기본값 5곳 renew** nuxt.config.ts `public.skin` · docker-compose.mall.yml fallback · .env.example · vitest.config.ts 강제 덮어쓰기(줄 유지·값만) · registry.ts DEFAULT_SKIN. 각 설명 주석도 수정.
- **토큰 분기 (b)** layers/admin·seller가 main.css 토큰 유틸을 21파일 22건 사용(text-primary 18·text-warning 3·text-success 1) → `:root` 기본값은 data-skin이 없는 관리자·셀러용으로 유지하고, 선택자 `:root[data-skin="renew"]` → `:root[data-skin]`(값 무변경)으로 구매자 기준 토큰을 삼는다. 이후 스킨은 `[data-skin="이름"]`으로 바꿀 값만 덮어쓴다. FE-07·FE-68 주석의 classic 설명 수정.
- **classic 전용 컴포넌트 13 삭제** AppHeader·AppFooter·HomeHero·HomeProductGrid·ProductListView·ProductCard·CategoryTabs·SortSelect·LoadingSkeleton·OrderItemList·PaymentSummary·OrderSummaryCard·ClaimSummaryCard(classic 외 코드 참조 0 — 남긴 것 없음) + 이들만 쓰던 컴포저블 useProducts·useProductList. 유지 AttachmentInput·EmptyState·ErrorState·ItemDeliveryInfo·useAppHeader.
- **CommonErrorState·CommonEmptyState** 고정 회색만 토큰으로(아이콘 gray-300 → text-line · 버튼 border-line·text-ink·hover:bg-surface-card·ring-ring). 레이아웃·문구·props 무변경.
- **제거된 스킨 값** 쿠키 zslab_skin=classic·?skin=classic은 isSkinName에서 걸러져 env → 기본 스킨(renew)으로 떨어진다(plugins/skin.ts 코드 확인). 남은 쿠키는 무시되므로 지우지 않는다.
- **대체되는 결정** FE-67(기본 스킨 classic · classic = 전 뷰 정적 기준 스킨 · 최종 대체 classic) · FE-68(토큰 :root = classic 값 · renew 블록 [data-skin="renew"]) · FE-69 보완(CI·운영 기본값 classic 유지) · FE-70(vitest 스킨 classic 고정 → renew 고정) · FE-72 classic 허브 파생(소비처 소멸).
- **테스트** 삭제한 컴포넌트·컴포저블 spec 7 삭제 · AppHeader.spec 13케이스를 test/component/LayoutShell.spec.ts(기본 레이아웃 마운트 → renew LayoutShell + useAppHeader)로 전부 이전(지시 3종 외 로그아웃·카테고리·ADMIN도 의도 유지로 이전)·"링크 6개" → "링크 5개" · 페이지 mount spec 단언 교체(SearchPage·CategoryPage → useProductPage mock · OrderDetailPage 확인 패널 Portal → document 조회 · SignupPage renew 뷰 import) · skin-guard 금지 목록 useProductList → useProductPage.

### §1-A 갈림길·채택/기각 근거
- **기준 스킨 뷰 로딩: α 정적 import / β 비동기 유지** → α 채택. β 기각: 기준 스킨은 항상 쓰여 청크 분리 이득이 없다. α는 SkinViews 전체 Record로 누락을 typecheck에서 막는다.
- **토큰: (a) renew 값을 :root로 통합 / (b) :root 유지 + [data-skin]** → 정찰 실측(관리자·셀러 22건)으로 (b).
- **AppHeader.spec 이전 범위: 지시 3종만 / 전부** → 전부(케이스 의도 유지·커버리지 소실 방지).

### §2 검증
- typecheck 0 · vitest 107 files / 762 passed(113 − 삭제 7 + LayoutShell 1 · skin-guard 3 · LayoutShell 13) · e2e smoke·help·password-change(renew) 7 passed.
- 개발 중 영향 spec: OrderDetailPage 구매확정 3건이 wrapper 기준 조회라 실패(renew 확인 패널은 DialogConfirm Portal) → document 기준 조회로 교체 후 통과.
- hookTimeout 30000 유지 — 워커 첫 파일(가벼운 파일 6개) 파일 소요 17.0~18.5s(테스트 시간 0.5s 내외 → 대부분 준비 단계). FE-70 측정값(transform 10.0~10.4s)과 측정 기준이 다르다.
- 'classic' 잔여(docs 제외) 50줄 — 전부 주석·JSDoc·테스트 이름·설정 설명, 실행 코드 참조 0.

### §3 공용 컴포넌트 하드코딩 색 집계(수정 없음 — 105-4)
- app/components 4파일 7건: order/ItemDeliveryInfo 3(bg-gray-50·bg-white·hover:bg-gray-100) · ui/dialog/DialogContent 2(bg-white·rgba(0,0,0,0.35)) · ui/dialog/DialogConfirm 1(bg-white) · claim/AttachmentInput 1(bg-white/90). CommonErrorState·CommonEmptyState 0.

### §8 이월
- 'classic' 언급 주석 정리(페이지·renew 뷰·contracts의 "classic과 같은" 등 50줄).
- 소비처 없어진 vm 잔존: mypage/index.vue menus(MYPAGE_HUB_MENU_ITEMS) · addresses.vue handleRemove(window.confirm 경로).
- /admin·/seller 404의 ErrorView 파스텔 값 미정의 — 분기 (b)로 data-skin 없는 경로는 그대로(FE-74 이월 중 구매자 쪽만 해소).
- FE-73 이월 "classic 옛 링크 필터 칩 UI 없음"은 classic 제거로 종결.
- 로컬 .env 주석 "운영·CI 기본값은 classic"(비커밋 파일).
- e2e 잔여 의존 정리 · needs 구조 단순화 · 디자인 변경(105-4).
- FE-74 이월 '로그인 안내 면 흐림' — 오탐(브라우저 확인 정상), 종결

외부 검토: C / 생략

## FE-76: 105-4 디자인 기준 · 토큰·서체·공용 기반 (Track 105-4a) (2026-09-24)

배경: renew가 기준 스킨이 된 뒤(FE-75) 남은 과제는 디자인 폴리시와 사용성 보완이다. 정찰(recon-report-track105-4) 결과, 카드 계열 토큰이 모두 #EFEBF8 한 값이라 면 구분이 약했다. 공용 Button은 renew에서 0건이고 인라인 버튼이 84곳이며, 같은 의미의 배지가 9묶음으로 따로 구현돼 있었다. 105-4a는 모든 화면이 쓸 기준과 기반만 만든다. 화면별 재디자인은 105-4c~f에서 한다.

결정:
- **목표** 사이트 전체 6축(디자인·일관성·직관성·편의성·접근성·성능)의 동일 가중 평균 95 이상. 기준선은 이전 측정 디자인 82 · 일관성 84 · 직관성 74 · 편의성 66 · 접근성 76 · 성능 미측정. 상품 이미지 품질은 채점에서 제외한다.
- **범위** 디자인 폴리시 + 사용성 보완.
- **준비 중 처리 기준** 새 BE 도메인 · 외부 발송·결제 연동 · 검토 등급 A 중 하나에 해당하면 "준비 중입니다."로 둔다. 비밀번호 찾기가 여기에 해당한다.
- **면 4단계** 바탕 #F7F5FB / 카드 #FFFFFF + 그림자 1 / 띠 면 파스텔 / 떠 있는 면 그림자 2·3.
- **그림자 3단계** e1 `0 1px 2px rgba(34,31,43,.04), 0 4px 12px rgba(91,63,168,.06)` · e2 `0 8px 24px rgba(91,63,168,.10)` · e3 `0 16px 48px rgba(34,31,43,.16)`.
- **서체** Pretendard Variable(본문) · IBM Plex Mono(식별자). 모노는 주문번호 같은 식별자 단독에만 쓰고, 금액·숫자는 tabular-nums로 맞춘다.
- **서체 스케일**(768 미만 / 이상) display 30/38 · 44/52 800 −0.02em · h1 24/32 · 30/38 800 −0.02em · h2 20/28 · 22/30 700 −0.02em · h3 16/24 · 17/24 700 · body 15/24 400 · small 13/20 500 · caption 12/16 600. 메인 큐레이션 섹션 제목은 H1 스케일, 보조 섹션은 H2.
- **버튼** 3단계(주 채움 · 보조 틴트 · 3차 글자) + 위험(핑크 틴트). 영역당 주 버튼 1개. 높이 52/44/36. 알약형. 띠 면 위의 보조 버튼은 흰 바탕.
- **카드 4종** 상품 카드(면 없음) · 콘텐츠 카드(흰색 24px, 여백 24/20, 그림자 1) · 목록 행 카드(768 이상에서 좌 정보 / 우 요약·액션) · 띠 면 패널(파스텔 32px).
- **간격** 4px 척도(4·8·12·16·20·24·32·40·56·80). 섹션 간격 80/56. 컨테이너 1320, 좌우 32/20.
- **모션** 150/240/360ms · cubic-bezier(.2,.8,.2,1) · 순차 40ms·최대 6개 · 첫 화면(SSR) 등장 모션 없음 · reduced-motion 전역 대응.
- **배지·칩** 배지 22px 12/600, 의미별 파스텔. 칩 36(390 폭에서는 44), 선택 시 보라 채움.
- **접근성** 터치 영역 44 · 본문 대비 4.5:1.
- **시안** 캔버스 "105-4 폴리시" 페이지(메인·주문 내역·배송지·배송지 추가, 1440·390).

구현(105-4a — 기반만, 화면 재디자인·기존 버튼/배지/모노 교체 없음):
- **서체** npm pretendard@1.3.9의 `dist/web/variable/woff2-dynamic-subset/` woff2 92개(2,957,724B)를 `public/fonts/pretendard-1.3.9/`에 두고 OFL.txt(dist/LICENSE.txt)를 함께 뒀다. 의존성 추가 없이 파일만 복사했다. `pretendardvariable-dynamic-subset.css`는 `app/assets/css/pretendard.css`로 복사해 url만 `/fonts/pretendard-1.3.9/`로 바꾸고, main.css가 `@import`한다. `--font-sans` 첫 값은 'Pretendard Variable'이다. 나눔고딕 @font-face 2개·woff2 2개는 참조 0건을 확인하고 삭제했다(e2e/tools/pixel.mjs 폰트 대기 대상도 교체). IBM Plex Mono는 유지한다. main.css는 전역이라 관리자·셀러도 Pretendard로 바뀐다(`var(--font-sans)` 참조).
- **폰트 버전 폴더·캐시** 모든 폰트는 버전 폴더에 둔다(`pretendard-1.3.9` · `ibm-plex-mono-2.005` — Plex 버전은 name 테이블 "Version 2.005"로 확인). 파일명에 버전이 없으므로 폰트를 교체할 때는 폴더명 버전을 올려 캐시를 깬다. nuxt.config routeRules `'/fonts/**'`에 `cache-control: public, max-age=31536000, immutable`을 둔다.
- **토큰**(`:root[data-skin]`) background·surface-page #F7F5FB · card·popover·surface-card #FFFFFF · 신규 `--surface-muted` #EFEBF8(`bg-surface-muted`) · secondary·muted·accent·surface-section·배지 bg는 #EFEBF8 유지. `:root`(관리자·셀러) 값은 바꾸지 않았다.
- **그림자·모션**(@theme) `shadow-e1·e2·e3` · `ease-soft` · 기존 커스텀 유틸 `duration-fast/normal/slow`(사용처 0) 값을 150/180/250 → 150/240/360으로 바꿨다. 전역 `@media (prefers-reduced-motion: reduce)`는 animation·transition을 0.01ms로 줄인다. 0이 아닌 값인 이유: animationend를 기다리는 닫힘 처리(reka Presence)가 끝나야 해서다.
- **서체 스케일 유틸** text-display·h1·h2·h3·body·small·caption(768 미만/이상). 관리자·셀러 Vuetify가 `text-caption` 클래스를 229곳에서 쓰므로(text-h* 계열도 Vuetify 이름) `:where([data-skin]) &`로 구매자 화면에만 적용한다. 이름은 유지했고 우선순위 가산은 없다. tailwind-merge(cn)는 이 이름을 글자색으로 보고 합칠 수 있어 cn에는 함께 넘기지 않는다.
- **버튼·칩 유틸** `btn` + `btn-primary|secondary|tertiary|danger` + `btn-lg|md|sm`(52/44/36 · 글자 16/15/14 · 좌우 28/20/16px). `chip`은 768 미만 44·이상 36, 흰 바탕 + border-line이다. `aria-pressed`·`aria-selected`·`data-state=on`이면 보라 채움이고, 선택된 칩은 hover 테두리에서 뺐다. 기존 클래스 사용처가 없어(chip은 주석 단어뿐) 전역으로 둔다.
- **RenewBadge**(skins/renew/components) tone info=lavender · success=mint · warning=butter · danger=pink · neutral=periwinkle. 22px · 12/600(text-xs font-semibold) · 알약형 · role 없음 · data-tone.
- **error.vue** `useHead({ htmlAttrs: { 'data-skin' } })`를 추가했다. /admin·/seller 에러(레이아웃 없이 ErrorView만)에서도 파스텔·panel-radius 변수가 정의된다(FE-75 §8 이월 해소). 에러가 풀리면 속성도 함께 빠진다.

### §1-A 갈림길·채택/기각 근거
- **버튼: α CSS 유틸리티 / β 공용 Button 컴포넌트로 이전** → α 채택. NuxtLink·a·button에 공통으로 붙고 마크업 변경이 최소다. β 기각: 84곳 마크업 교체로 회귀 위험이 크다.
- **배지: 컴포넌트** 채택. 9개 묶음의 마크업이 같아 컴포넌트로 묶어도 교체 비용이 작다.
- **폰트: 자체 호스팅 다이나믹 서브셋 / CDN** → 자체 호스팅 채택. CDN 기각: 외부 장애 지점이 생긴다.

### 부록 A surface-card 분류(토큰 흰색 전환에 따른 복구) — 64곳 중 변경 60 · 유지 4
기준: 흰 카드 위에 놓이거나 옅은 면으로 구분돼야 하는 요소(스켈레톤·칩·hover·선택 상태·입력 배경·빈 상태 아이콘 원·안내 박스)는 `surface-muted`(#EFEBF8, 이전 값과 같음)로 바꾼다. 페이지 위의 카드·패널 면 자체는 그대로 둔다(이제 흰색). 카드 그림자·레이아웃은 화면 그룹 단계에서 추가한다.
- **변경 60** (줄은 변경 전 기준)
  - 스켈레톤 20: AddressesView:58·59·60 · CartView:33·34·35 · OrdersView:74·78·79 · ProfileView:21·22 · MypageView:20(SKELETON 상수) · SearchView:44·45 · RenewProductListing:92·93 · ProductDetailView:48·49·50·51
  - 빈 상태 아이콘 원 5: AddressesView:108 · CartView:47 · OrdersView:90 · MypageView:157 · SearchView:54
  - 상태 칩·라벨 7: AddressesView:79 · OrdersView:159 · OrderDetailView:52·103 · MypageView:187 · PaymentMockView:35 · ProductDetailView:111(셀러 알약)
  - hover 배경 19: AddressesView:91·100 · CartView:14·101 · OrdersView:125 · MypageView:26 · PaymentMockView:65 · ProductDetailView:155·165 · LayoutShell:21·23 · SectionHeading:19 · RenewPagination:20·33·42 · HomeView:21 · ErrorView:41 · LoginView:72 · components/common/ErrorState:15
  - 선택 상태 3: ClaimNewView:120 · CheckoutView:145 · ClaimDetailView:108
  - 흰 카드 안 안내 박스·링크 알약 3: CheckoutView:70 · CheckoutCompleteView:21 · PaymentMockView:8
  - 미선택 알약 탭 2: HomeView:133 · RenewProductListing:26
  - 입력 배경 1: LayoutShell:66(헤더 검색)
- **유지 4**(페이지 위 면 → 흰색): HomeView:58(히어로 패널) · HomeView:106(많이 찾는 띠) · ProductDetailView:174(총 상품 금액 박스) · category-theme.ts:25(전체·미매핑 카테고리 배경 — 배너·카드 면). HomeView:104와 category-theme.ts:23 주석의 "옅은 라벤더" 설명만 흰색 기준으로 바꿨다.
- 참고: 유지 4곳 중 HomeView:106 띠 안의 RenewProductCard는 hover 시 `bg-white`라, 흰 띠 위에서는 hover 면이 보이지 않고 그림자·이동만 남는다. 띠 면 파스텔 전환(105-4c)에서 함께 정리한다.

### §2 검증
- typecheck(컨테이너) 통과. vue-tsc 오류 출력 0이다. 종료 코드는 셸 인용 실수로 받지 못했고 재실행하지 않았다. → vitest 108 files / 767 passed(+RenewBadge 5) → e2e smoke(renew) 1 passed.
- 임시 캡처(데모 구매자·관리자, 커밋 제외): / · /orders · /mypage/addresses · /login × 1440·390 전부 200 · data-skin renew · 흰 면 겹침 스캔(불투명 흰 요소 + 흰 조상 + 테두리·그림자 없음) 0건 · 육안 확인도 겹침 없음. 관리자 대시보드는 body font Pretendard이며 한글이 Pretendard로 렌더됐다. /admin 404는 data-skin renew · 파스텔 변수가 정의돼 라벤더 패널이 표시된다.
- 개발 중 확인: @tailwindcss/node 컴파일로 신규 유틸 출력과 `:where([data-skin])` 중첩 유지를 확인했다.

### §8 이월
- 화면 그룹(105-4c~f): 카드 그림자 e1 적용 · 84개 인라인 버튼 → btn 유틸 · 배지 9묶음 → RenewBadge · 모노 51곳 → tabular-nums · 서체 스케일 적용 · 히어로·띠 패널 파스텔 전환(띠 안 카드 hover 면 포함).
- `--shadow-card-hover`(FE-07)는 사용처 0 — 정리 대상(이번 변경 없음).
- 캡처 관찰(재실행 없음): login-1440에서 한글 일부가 대체 글꼴로 찍혔고(같은 화면 390은 Pretendard), 안내 면이 옅게 찍혔다. 다이나믹 서브셋 로딩·진입 모션 시점과 촬영 시점이 겹친 것으로 보이며 브라우저 확인이 필요하다. 관리자 화면의 `document.fonts.check('16px "Pretendard Variable"')`는 false였다(렌더는 Pretendard).

외부 검토: C / 생략

## FE-77: 탐색 그룹 디자인 적용 — 헤더·메인·목록·검색·상품 상세 (Track 105-4c) (2026-09-25)

배경: FE-76 기준(면·그림자·서체 스케일·버튼·칩·배지·모션)을 화면 그룹 단위로 적용하는 첫 단계다. 대상은 LayoutShell, HomeView, ProductsView·CategoryView·RenewProductListing·RenewSortMenu·RenewPagination, SearchView, ProductDetailView와 이 화면들이 쓰는 RenewProductCard·SectionHeading·MobileActionBar다. 기능·BE 계약·vm 계약은 바꾸지 않았다.

결정:
- **공통 규칙** 글자 버튼은 `btn` + 단계 + 크기로 바꾸고, 영역당 주 버튼은 1개다. 띠 면 위 보조 버튼은 흰 바탕(`btn` + `bg-white text-primary`)이다. 상태 배지는 RenewBadge로 바꿨다. font-mono는 식별자 단독에만 쓰는데 대상 파일에는 해당이 0건이라 전부 Pretendard + `tabular-nums`로 바꿨다. 크기 클래스는 모두 서체 스케일 유틸로 바꿨다. 페이지 위 콘텐츠 카드는 흰색 + `shadow-e1`이고, 임의 그림자는 e1~e3로 바꿨다. hover·전환은 150ms `ease-soft`, 카드 hover는 `-translate-y-1` + `shadow-e2`다. 첫 화면 등장 모션은 두지 않는다. 탭·필터 알약은 `chip`이다.
- **굵기 우선순위** 컴파일된 CSS에서 스케일 유틸(`:where([data-skin]) .text-*`, 우선순위 0,1,0)이 `font-*`보다 앞에 출력된다. 따라서 같은 요소에 `font-*`를 함께 쓰면 `font-*` 굵기가 이긴다. 캡처에서도 `text-caption font-normal`의 계산 굵기가 400이었다. 기존 굵기를 유지할 곳에만 `font-*`를 남겼다.
- **히어로 리드 문구 17/28 예외** 히어로 리드 문구 1종만 스케일 밖의 `text-[1.0625rem] leading-7`을 쓴다. 대상 파일에 남은 임의 크기는 이것 하나다.
- **많이 찾는 1위 확대** 띠 안 그리드는 1024px 이상에서 4열로 고정한다(1280px 이상 5열 규칙에서 제외). 1위 카드는 `lg:col-span-2 lg:row-span-2`로 키운다. 규칙은 상품 개수와 무관하고, 지금 5개일 때는 2행이 꽉 찬다. 5열로 두면 둘째 줄에 빈 칸 2개가 생겨 채택하지 않았다.
- **상품 상세 주 버튼** "장바구니 담기"가 영역의 주 버튼이다. 바로 구매는 만들지 않는다. vm 계약과 구매 흐름이 바뀌기 때문이며, 105-4d에서도 범위 밖이고 필요하면 준비 중 기준을 적용한다.

화면별 변경:
- **헤더·푸터** 헤더는 흰 바탕 + 하단 line 1px이다(블러 제거). 로고는 `text-h2`다. 검색 입력은 `rounded-control` · `bg-surface-page` · `border-line` · `text-body`다. 카테고리 링크와 로그인은 `btn btn-tertiary btn-md`다. 장바구니 수는 `text-caption tabular-nums`다. 푸터는 `bg-surface-muted`이고 768px 미만에서 링크 높이가 44px이다. testid·aria는 그대로다.
- **메인 히어로** 패널 전체가 라벤더 띠 면(`--panel-radius`)이다. 눈썹은 caption·대문자·0.12em, 제목은 `text-display`다. 버튼은 주 "지금 둘러보기"(/products)와 흰 보조 "카테고리 보기"(#home-categories — 카테고리 섹션이 있을 때만 보이고, 섹션에 헤더 높이만큼 `scroll-mt`를 둔다)다. 콜라주는 `skins/renew/hero-collage.ts`의 `buildHeroCollage`가 vm.newArrivals 앞 3개로 만든다.
  - 배치: 1024px 이상은 2열이고 첫 칸이 2행을 차지한다. 768~1023px은 3칸 한 줄, 768px 미만은 3칸 한 줄에 높이 132px이다.
  - 칸: `rounded-card` · `shadow-e1` · 링크 aria-label은 상품명이다.
  - 대체 규칙: 이미지 없는 상품 칸은 파스텔 블록이지만 링크는 유지한다. 상품이 없는 칸은 파스텔이고 링크 없이 aria-hidden이다. 칸 순서대로 pink·periwinkle·mint다.
  - 이미지 로딩: 첫 칸은 기본값, 나머지 2칸은 lazy다.
- **메인 섹션** SectionHeading에 `size`('h1'|'h2', 기본 h2)를 두었다. 새로 들어온·많이 찾는·카테고리별 추천은 h1, 카테고리·셀러 픽·2만원 이하는 h2다. 눈썹은 caption이고 "전체 보기"는 `btn-tertiary btn-sm`이다. 카테고리 타일은 `shadow-e2`다. "많이 찾는" 띠는 `surface-muted`로 바꿨고, 순위 숫자는 Pretendard 800 · primary · tabular-nums다. 추천 탭은 `chip`(aria-selected)이다. 셀러 픽 카드는 흰색 + `shadow-e1`(테두리 제거)이다.
- **상품 카드** hover는 `shadow-e2` · `-translate-y-1` · 150ms다. 품절은 RenewBadge neutral이다(기존 흰 바탕·본문색이라 의미색이 없던 것을 기준으로 했다). 가격은 `text-h3 tabular-nums`다.
- **목록·카테고리·검색**
  - 배너·검색 제목 면: 눈썹 caption · 제목 `text-h1` · 건수 `text-small tabular-nums`. 배너 색 전환은 450ms → 150ms다.
  - 카테고리 탭: `chip`. 탭이 링크라 aria-selected를 붙일 수 없어, `aria-current`는 유지하고 `data-state=on`으로 선택 상태(보라 채움)를 표시한다.
  - 정렬 트리거: `btn btn-secondary btn-md`. 패널 그림자는 `shadow-e2`, 열림 애니메이션은 150ms ease-soft다.
  - 페이지 번호: `tabular-nums`. 현재 페이지는 primary 채움이다.
  - 검색 빈 결과: 흰 카드 + `shadow-e1`. 진입 모션은 제거했다(첫 화면 SSR 등장 모션 없음). "전체 상품 보기"는 `btn-primary btn-md`다.
  - cn(tailwind-merge) 트랩: `DropdownMenuRadioItem`은 class를 cn으로 합치는데, 이때 스케일 유틸이 글자색으로 판정돼 `text-ink`와 합쳐진다. 그래서 스케일 유틸은 라벨 span에 두었다.
- **상품 상세**
  - 금액 박스: 흰색 + `shadow-e1`. 가격·합계·수량은 `tabular-nums`, 상품명 `text-[32px]`은 `text-h1`로 바꿨다.
  - 버튼: 담기는 `btn-primary btn-lg`(56 → 52px). 성공 카드의 "장바구니 보기"는 흰 보조 버튼이다.
  - 배지·칩: 판매 불가 오버레이 라벨은 RenewBadge neutral이다. 옵션 값 버튼은 `chip`(aria-pressed)이며, 선택 색이 검정 채움 → 보라 채움으로 바뀌었다.
  - 기타: 상품 설명 카드는 `rounded-card` + `shadow-e1`이다.
- **MobileActionBar(공용)** 버튼은 `btn btn-primary btn-md h-12`로 바꿨다(높이 48 유지 · 글자 14/700 → 15/600). 금액은 `text-h3 tabular-nums`, 슬라이드 전환은 150ms ease-soft다. 장바구니·주문서(105-4d 대상) 고정 바에도 같은 외형 변화가 적용된다.
- **가로 스크롤 흐림 끝 처리** 기존에는 흐림(`fade-right`)이 스크롤 위치와 무관하게 항상 보였다. `skins/renew/scroll-edges.ts`에 `readScrollEdges`(순수 판정 · 오차 1px)와 `trackScrollEdges`(요소 ref → 스크롤·resize 때 갱신)를 두었다. app composables는 import하지 않는다(skin-guard). 흐림은 끝에 닿지 않았을 때만 보인다. 흐림은 mask라서 드러나는 색은 바탕 면(메인은 `surface-page`, 헤더는 흰색)이다. 2만원 이하 오버레이 흐림은 `to-surface-page`를 유지한다.
  - 교체한 곳: HomeView 2만원 이하(기존 판정 로직 이전) · HomeView 카테고리 줄 · LayoutShell 모바일 카테고리 메뉴 · RenewProductListing 카테고리 탭.
- **홈 SSR 카테고리 요청 1회화** `useCategories`에 `getCachedData`를 두었다. hydration과 cause 'initial'이면 `payload.data[key]`를 쓰고, refresh는 캐시를 쓰지 않는다.

### §1-A 갈림길·채택/기각 근거
- **카테고리 2회 원인 정정** 정찰(recon-report-track105-4 §7-2)은 dedupe 'cancel'이 진행 중인 요청을 취소한다고 판독했다. 실제 원인은 순차 재조회다. Vue SSR이 레이아웃 serverPrefetch(카테고리 조회)를 기다린 뒤 페이지 setup을 실행하므로, 페이지 쪽 호출 시점에는 진행 중인 promise가 없다. 그런데 기본 getCachedData는 서버에서 `static.data`만 보므로 handler가 다시 실행된다. 처음 적용한 `dedupe: 'defer'`는 개발 중 측정에서 2회가 그대로여서 기각했다.
- **α getCachedData(채택) / β 페이지 쪽이 useNuxtData로 공유 데이터만 읽기** β는 레이아웃이 먼저 조회한다는 순서에 기대고, 홈만 해결한다. α는 /products(레이아웃 + useProductPage)도 함께 1회가 되고, 실패는 payload에 남지 않아 기존처럼 재조회한다. 부수 변화로, 클라이언트 이동 때 새 페이지의 useCategories가 이미 받은 결과를 재사용한다(이전에는 이동마다 1회 재조회).

### §2 검증
- typecheck(컨테이너) EXIT 0 → vitest 110 files / 769 passed(+hero-collage 1 · scroll-edges 1) → e2e smoke(renew) 1 passed. 깨진 단언은 0건이었다(대상 뷰에 클래스 단언이 없다).
- 홈 SSR 카테고리 요청 수(백엔드 SQL 로그에서 루트 카테고리 쿼리의 요청 ID 고유 수, 비로그인 / 1회): 변경 전 2 → 변경 후 **1**. 같은 요청의 BE 요청 ID는 9개(카테고리 1 + 상품 섹션 4 + 셀러 픽 1+3)다. 개발 중 확인한 /products도 1이다.
- 임시 캡처(커밋 제외): / · /products · /categories/1 · /search?keyword=T75 · /products/{첫 상품} × 1440·390 + 390 메인 카테고리 줄 끝 상태 1장. 판정 36/36 PASS.
  - 히어로·헤더: 히어로 3칸 loading [기본·lazy·lazy] · 헤더 흰색.
  - 그리드·흐림: 1440 1위 span 2 / span 2 · 390 카테고리 줄 흐림이 시작에는 있고 끝에서 사라짐.
  - 목록 탭: 현재 탭 보라 채움.
  - 상세: 담기 btn-primary 52px · 가격 tabular-nums.
- 캡처 관찰: 첫 상품(T75 실측용)은 상세 API `images`가 빈 배열이라 대표 이미지 칸이 비어 있다(데이터 원인, 갤러리 코드 변경 없음).

### §3 작업 전후 집계(대상 12파일)
- 인라인 버튼 27 → 10. 남은 글자 버튼은 0이다. 남은 10은 아이콘 버튼 3 · 원형 화살표 2 · 수량 스테퍼 2 · 페이지 번호 1 + 화살표 2로, btn 유틸(글자·좌우 여백) 대상이 아니다. 교체 후 btn 10곳 · chip 4곳이다.
- 상태 배지 2 → RenewBadge 2 · font-mono 17 → 0 · 임의 그림자 3 → 0 · 임의 크기 1 → 1(히어로 리드 예외) · 표준 크기 클래스 74 → 0 · 150ms가 아닌 duration 34 → 0.

### §8 이월
- 구매 흐름·마이페이지·인증 화면(105-4d~f): 같은 규칙 적용. 장바구니·주문서 고정 바는 이번 MobileActionBar 변경이 먼저 반영돼 있다.
- 이미지 srcset·sizes(105-4g).
- 원형 아이콘·페이지 번호 컨트롤의 공용 유틸 여부는 화면 그룹이 끝난 뒤 판단한다.
- 상세 썸네일 `rounded-[18px]` 임의 모서리는 이번 규칙 밖이라 유지했다.
- `--shadow-card-hover`(FE-07) 사용처는 여전히 0이다(FE-76 §8).

외부 검토: C / 생략

## FE-78: 구매 흐름 디자인·사용성 — 장바구니·주문서·결제·완료 + 주소 검색 (Track 105-4d) (2026-09-25)

배경: FE-76 기준을 구매 흐름에 적용하는 단계다. 대상은 CartView·CheckoutView·PaymentMockView·CheckoutCompleteView와 이 화면들이 쓰는 MobileActionBar·FadeAmount·RenewCheckbox다. 사용성 3건(주문서 주소 검색·태블릿 고정 바·안전 영역)을 함께 처리했다. BE 계약과 vm 계약은 바꾸지 않았다.

결정:
- **공통 규칙** FE-77과 같다. 글자 버튼은 `btn`으로 바꾸고 영역당 주 버튼은 1개다. 띠 면 위 보조 버튼은 흰 바탕이다. 상태 배지는 RenewBadge, font-mono는 식별자(주문번호)에만 쓴다. 크기는 서체 스케일 유틸, 콘텐츠 카드는 흰색 + `shadow-e1`, 전환은 150ms `ease-soft`, 768 미만 터치 영역은 44, 선택 알약은 `chip`이다. 이 화면들의 인라인 성공·오류 메시지 7곳은 RenewNotice로 바꿨다.
- **구매 불가 배지·사유 분리** "구매 불가 (품절 또는 판매 중지)" 알약을 RenewBadge danger "구매 불가" + 사유 caption으로 나눴다. RenewBadge는 줄바꿈이 없어 390 폭 품목 행에서 넘친다.
- **장바구니 삭제** X 아이콘 버튼을 "삭제" 3차 버튼(`btn-tertiary` + `text-destructive`)으로 바꿨다. 컴파일 CSS에서 `.btn-tertiary`가 `.text-destructive`보다 앞이라 위험 글자색이 적용된다.
- **고정 바 1024 미만** MobileActionBar `md:hidden` → `lg:hidden`. 768~1023에는 sticky 요약 카드(lg부터)도 없어 고정 바도 sticky 카드도 없었다(정찰 5e). body 하단 여백도 `max-lg`로 맞췄고, 태블릿 좌우 여백은 `md:px-10`이다. 상품 상세·장바구니·주문서 공통이다.
- **안전 영역** nuxt.config `app.head.viewport`에 `viewport-fit=cover`를 명시했다(전역 — 관리자·셀러 포함). 없으면 iOS에서 `env(safe-area-inset-*)`가 0이다. 고정 바 아래 여백은 `12px + inset`, body 여백은 `73px + inset`이다. 헤더 sticky `top`은 상단 inset만큼 내린다.
- **주소 미선택 안내** 주소가 비면 canSubmit이 false라 결제하기(고정 바 포함)가 비활성이고, 그래서 제출 때 뜨는 기존 필수 문구는 없다. 받는 사람·연락처를 채우고 주소만 비었을 때 주소 영역 아래에 "주소를 검색해 선택해 주세요."(`text-small` · 위험색 · `aria-live="polite"`)를 보인다. 알림 영역은 항상 두고 문구만 바꾼다. 처음 진입과 직접 입력 중에는 보이지 않고, canSubmit은 바꾸지 않았다.
- **e2e postcode 모의 불필요** 지정 e2e 3종은 주문서 주소 검색에 닿지 않고, 스크립트는 "주소 검색"을 누를 때만 넣으므로 모의할 요청이 없다.
- **RenewNotice 자체 스타일** 공용 알림(`text-sm` · `rounded-[20px]`)은 마이페이지에도 쓰여 105-4e에서 정리한다.

주소 검색(카카오 우편번호):
- **로더** `skins/renew/postcode-loader.ts`. 처음 쓸 때 script를 1회 동적 삽입하고 로드 Promise를 공유한다. 클라이언트 전용이고 타임아웃은 8초다. 실패하면 script를 지우고 캐시를 비워 "다시 시도"가 새로 넣는다. 생성자는 `kakao?.Postcode ?? daum?.Postcode`다. 스크립트(VERSION 260702)는 로드 즉시 `window.kakao = window.daum`을 공유하고 Postcode를 정의한다. document.write는 팝업 문서용이라 동적 삽입이 가능했다. app composables는 import하지 않는다(skin-guard).
- **표시** RenewAddressSearch가 공용 Dialog를 연다. 1024 미만은 전체 화면 시트(상·하단 안전 영역), 1024 이상은 가운데 520×600이다. 헤더는 "주소 검색"(`text-h3`) + 닫기 아이콘 44이고, 본문은 임베드가 채운다. 열림 자동 포커스는 막고 임베드 `focusInput`으로 검색창에 포커스한다. 테마는 배경 흰색 · 테두리 line · 강조 primary의 토큰 hex다(iframe 안이라 CSS 변수를 읽지 못한다).
- **폼** 주소 영역은 하나다. 선택 전에는 "주소 검색" `btn-secondary btn-lg` 전폭 버튼이고, 선택 후에는 요약 카드(surface-muted · 우편번호 tabular-nums · 도로명 body · 지번 small·sub · "변경" `btn-tertiary`)다. 아래에 상세 주소 칸 1개를 둔다. 주소를 고르면 닫힘 자동 포커스를 막고 상세 주소로 직접 포커스한다.
- **값 연결** zonecode → zonecode · (roadAddress || autoRoadAddress) + 참고항목 → addressRoad · (jibunAddress || autoJibunAddress) → addressJibun. 참고항목은 끝 글자가 동·로·가인 법정동과, 공동주택일 때의 건물명이다. 도로명이 둘 다 비면 선택을 받지 않고 모달 안에 "도로명 주소가 있는 결과를 선택해 주세요"를 띄운다.
- **실패 대비** 로드 실패·타임아웃이면 모달 본문에 RenewNotice warning + "다시 시도"(`btn-secondary`) + "직접 입력"(`btn-tertiary`)을 둔다. 직접 입력을 고르면 모달을 닫고 폼에 우편번호·도로명·지번 입력칸을 펼친다. 수기 입력칸은 이때만 보인다.
- **공용 모달 덮어쓰기 트랩** cn(tailwind-merge)은 `rounded-*-card`·`max-h-none`을 기본값과 충돌로 보지 않아 둘 다 남긴다. 같은 속성 유틸은 컴파일 CSS에서 이름순으로 뒤에 오는 쪽이 이긴다(`md:rounded-none` > `md:rounded-card` · `max-h-none` > `max-h-[90dvh]`). 다만 `rounded-none`은 `rounded-t-card`보다 앞이라 base에는 `rounded-t-none`을 쓴다.
- **105-4e** 마이페이지 배송지 모달도 같은 컴포넌트를 쓴다(모달 위 모달).

화면별 변경:
- **장바구니** 품목은 목록 행 카드다(흰색 + `shadow-e1` · 768 이상에서 왼쪽 선택·이미지·정보 / 오른쪽 수량·금액·삭제). 스테퍼는 768 미만 44 · 이상 36이고, 체크박스는 label로 둘레를 44로 넓혔다. 요약은 흰 카드(1024 이상 sticky)이고 주문하기는 `btn-primary btn-lg`(56 → 52)다. 빈 상태 문구는 유지했다. 알림 2곳은 RenewNotice danger다.
- **주문서** 섹션 4개(배송지·결제 수단·주문 상품·결제 금액)는 흰 카드 + `shadow-e1`이다. 저장 배송지 알약은 `chip`(data-state=on) + "기본" RenewBadge info다. 저장 배송지 요약 카드는 유지했고 "이 주소 수정"은 흰 바탕 보조 버튼이다. 결제하기는 `btn-primary btn-lg`다. 알림 4곳은 RenewNotice(배송지 로드 실패 warning · 선택 상품 없음 info · 구매 불가 danger · 오류 danger)이고, "장바구니로 이동" 글자 링크 3곳은 흰 바탕 `btn-sm`이다.
- **모의 결제** 모의 안내는 RenewNotice info다. 결제 수단·금액은 흰 카드이고 금액은 `text-h2 tabular-nums`다. 오류는 RenewNotice danger(testid 유지)이고, 성공·실패·취소 버튼은 주·보조·3차다.
- **완료** 결과 띠 면(민트 · `--panel-radius`)이다. 주문번호만 모노이고, 주문 상세는 주 버튼, 쇼핑 계속은 흰 바탕 보조 버튼이다.
- **공용** FadeAmount는 font-mono → tabular-nums, "원" `text-[0.7em]` → `text-small`, 150ms `ease-soft`다. RenewCheckbox 전환도 150ms `ease-soft`다(Withdraw·Addresses 공용).

### §1-A 갈림길·채택/기각 근거
- **주소 검색 표시: 모달 + 요약 카드(채택) / 폼 안 임베드(기각)** 폼 안 임베드는 1차 구현·캡처 뒤 기각했다. 열면 아래 입력칸을 밀어내고, 카카오 기본 테두리가 톤과 맞지 않으며, 우편번호·도로명·지번 칸이 많아 어색했다(zslab 확인).
- **고정 바 아래 여백: 12px + inset(채택) / max(12px, inset)(기각)** max는 버튼이 홈 인디케이터 영역 경계에 바로 붙는다.

### §2 검증
- 1차: typecheck EXIT 0 → vitest 111 files / 776 passed → e2e smoke·mock-payment·order-resume-payment 5 passed → 캡처 판정 35/36 PASS. FAIL 1건은 390 품목 행의 display 기대값을 block으로 정의한 판정 오류다(li 기본값 list-item · 결함 아님 · 통과 처리).
- 표시 방식 변경 뒤: typecheck EXIT 0 → vitest 112 files / 781 passed(RenewAddressSearch 11 · CheckoutView 1) → 주소 검색 캡처 교체 6/6 PASS. e2e는 재실행하지 않았다(주소 검색 경로 미접촉).
- 캡처 판정 요지:
  - 고정 바: 1440 숨김 / 820·390 표시 · body 73px · 바 아래 12px. 820 맨 아래에서 푸터를 가리지 않는다. 요약 sticky는 1440에서만이다.
  - 모달: 1440 가운데 520×600 · 24px / 390 0,0 · 390×844 · 0px. 검색창 포커스는 region_name이다.
  - 선택: "테헤란로 152" → 06236 · "서울 강남구 테헤란로 152 (역삼동)" · "서울 강남구 역삼동 737" · 상세 주소 포커스 · 안내 표시 후 사라짐.

### §3 작업 전후 집계(대상 7파일)
- 인라인 글자 버튼 11 → 0(남은 인라인은 스테퍼 기호 버튼 2) · 밑줄 글자 링크 3 → 0 · 선택 알약 2 → chip.
- 상태 배지 2 → RenewBadge 2(+ "기본" 1) · font-mono 12 → 1(주문번호) · 표준 크기 클래스 66 → 0 · 임의 크기 1 → 0 · 임의 그림자 0 → 0.
- 150ms가 아닌 전환 16 → 0 · 인라인 메시지 7 → 0(RenewNotice 8) · 임의 모서리 11 → 3(썸네일 — FE-77과 같이 유지).

### §8 이월
- 105-4e: 마이페이지 배송지 모달 주소 검색(같은 컴포넌트) · RenewNotice 자체 스타일. 105-4f: 인증 화면.
- 카카오 결과 상자 테두리가 진하게 보인다(캡처 관찰). 테마 outlineColor가 닿지 않는 카카오 내부 스타일이다.
- 원형 아이콘·스테퍼 컨트롤의 공용 유틸 여부는 화면 그룹이 끝난 뒤 판단한다(FE-77 §8).

외부 검토: C / 생략

## FE-79: 마이페이지 디자인·사용성 — 틀·홈·회원 정보·비밀번호·배송지·탈퇴 + 공용 보완 (Track 105-4e-1) (2026-09-25)

배경: FE-76 기준을 마이페이지에 적용하는 단계다. 대상은 MypageFrame·MypageView·ProfileView·PasswordView·AddressesView·WithdrawView다. 여기에 공용 보완 4건(카테고리 대체 테마·RenewNotice action 슬롯·연락처 표시 형식·DialogConfirm 처리 중 상태)과 배송지 모달 주소 검색을 함께 처리했다. BE 계약은 바꾸지 않았다. vm 계약은 배송지 1건만 바꿨다(아래). 주문 목록·상세·클레임, 그리고 홈 주문 현황 숫자 링크는 105-4e-2에서 한다.

결정:
- **공통 규칙** FE-77·FE-78과 같다. 글자 버튼은 `btn`, 영역당 주 버튼 1개, 띠 면 위 보조 버튼은 흰 바탕이다. 상태 알약은 RenewBadge를 쓰고, font-mono는 주문번호에만 쓴다. 크기는 서체 스케일 유틸이다. 콘텐츠 카드는 흰색 + `shadow-e1`, 전환은 150ms `ease-soft`다. 첫 화면 등장 모션은 두지 않는다. 768 미만 터치 영역은 44다.
- **A. 카테고리 대체 테마** `categoryTheme(categoryId, displayName)`. id가 null이면 "전체"(흰 면 + 격자)다. "전체" 타일은 카테고리 목록에 없는 가상 항목으로, /products 배너에서 categoryId가 null일 때 쓴다. 이름 매핑이 있으면 기존과 같다. 매핑이 없는 실제 카테고리(이름이 null인 경우 포함)는 `|id| % 5`로 파스텔(라벤더·페리윙클·핑크·민트·버터)을 고르고 태그 선 일러스트를 쓴다. 호출처는 3곳이다(HomeView 카테고리 타일 · RenewProductListing 배너 · ProductDetailView 카테고리 링크). 운영 "데모"가 "전체"와 같은 흰 면·격자로 보이던 문제가 해소됐다.
- **B. RenewNotice action 슬롯** 버튼이 있으면 768 이상에서 아이콘·문구·버튼을 세로 가운데로 맞추고, 768 미만에서는 버튼을 아래 줄(문구 시작선 들여쓰기)로 내린다. 버튼이 없으면 아이콘을 문구 첫 줄(24px) 가운데에 맞춘다. 자체 스타일도 정리했다(FE-78 이월): `rounded-[20px]` → `rounded-card`, `text-sm font-bold` → `text-body font-semibold`.
  - 이전한 곳(알림 안 버튼 전수 6곳): MypageView 구매 확정 대기 "확정하러 가기" · CheckoutView 3곳(선택 상품 없음·구매 불가·오류 — "장바구니로 이동") · ClaimNewView 교환 옵션 오류 "다시 시도" · ClaimDetailView 취소 패널(요청 취소 = `btn-danger`, 닫기 = 흰 바탕 보조).
  - 대상 밖 화면(주문서·클레임)은 슬롯 이전과 버튼 유틸 교체만 했다.
- **C. 연락처 표시 형식** `app/lib/format/phone.ts` `formatPhone`. +82는 0으로 바꾼 뒤 판단한다. 규칙은 01X 11자리 3-4-4 / 10자리 3-3-4, 02 2-3/4-4, 그 밖의 지역번호 3-3/4-4다. 숫자·공백·하이픈·괄호·점 밖의 글자(마스킹 `*` 등)가 있거나 자릿수가 맞지 않으면 원문을 돌려준다. 표시 문자열만 바꾸고 입력칸 값(BE로 보내는 값)은 바꾸지 않는다.
  - 구매자 4곳: AddressesView 목록 · MypageView 기본 배송지 · OrderDetailView 배송지 · CheckoutView 저장 배송지 요약.
  - 관리자·셀러 12곳(표시 문자열만 바꾸고 Vuetify 마크업·스타일은 그대로): AdminDeliveryDetailDialog 수령인 · AdminMemberListView 연락처 열 · AdminOperatorProvisionDialog·AdminSellerProvisionDialog·AdminSellerMemberAddDialog 회원 검색 부제 · members/sellers/[id] 담당자 · members/[id] 연락처·배송지 표·임시 비밀번호 확인 문구 · orders/[id] 수령인 · settlements/[id] 정산 연락처(마스킹이라 원문 유지) · seller/orders/[id] 수령인. 기존 대체 문구(`?? '—'`·`?? '연락처 없음'`)는 판별 불가라 그대로 돌아온다.
  - 정찰 당시 관리자·셀러는 10곳으로 셌으나 재검색에서 12곳으로 정정했다(첫 grep 결과 오계수 1 + 한 줄 조건 밖 확인 문구 1).
- **D. 배송지 모달 주소 검색** 추가·수정 모달의 우편번호·도로명·지번 입력칸을 RenewAddressSearch로 바꿨다(FE-78과 같은 방식). 검색 모달은 폼 모달 위에 겹친다. 수정 모드에서는 기존 주소가 요약 카드로 보이고 "변경"으로 다시 검색한다. 직접 입력을 고를 때만 세 칸을 펼친다.
  - 주소 비움 저장: 입력칸이 없어 브라우저 필수 검사가 닿지 않는다. 그래서 저장을 누를 때 주소가 비어 있으면 요청을 보내지 않고 "주소를 검색해 선택해 주세요."(`aria-live="polite"`, FE-78 문구)를 보인다.
  - 겹침 오버레이: `RenewAddressSearch`의 `nested` prop을 켜면 검색 모달 오버레이가 투명이다(공용 DialogContent `overlayClass` prop). 배경 어둡기는 1단계로 유지된다. 배송지만 켜고, 주문서는 그대로다.
  - 닫힘 뒤 초기화(이월 해소): 페이지는 닫기만 하고, 뷰가 DialogContent `close-auto-focus`(닫힘 애니메이션이 끝나 모달이 사라진 뒤)에서 `resetForm`을 부른다. 페이지 오류 알림은 `!formOpen` 대신 뷰의 `formShown` 기준이다. 닫히는 사이에 폼 오류가 페이지로 번쩍이지 않게 하기 위해서다.
- **E. DialogConfirm 처리 중** 기존 `pending`(FE-73, 두 버튼 잠금)을 확장했다. 확인 버튼에 스피너와 `aria-busy`를 두고, pending 동안 Esc·바깥 누름·닫기 요청을 무시한다. 버튼은 `btn btn-md`다(취소 `btn-secondary`, 확인 `btn-danger`/`btn-primary`).
  - 배송지 삭제에 연결했다.
  - 대상 밖 사용처(목록만): OrderDetailView·OrdersView 구매확정 모달. 이미 `pending`을 쓰고 있고, 공용 변경으로 확인 버튼 외형이 빨강 채움에서 핑크 틴트(`btn-danger`)로 함께 바뀌었다.
- **vm 계약 변경(배송지)** `AddressesPageVm.removing` 추가: `confirmRemove`가 삭제가 끝날 때까지 확인 모달을 열어 두고, 성공·실패 뒤 닫는다. `closeForm`은 닫기만 하고 저장 성공 경로도 닫기만 한다. 초기화는 뷰가 닫힘 뒤 `resetForm`으로 한다(계약 주석 갱신).

화면별 변경:
- **MypageFrame** "My page" 눈썹과 활성 점(기능 없는 장식)을 제거했다. 768 이상 메뉴의 활성 항목은 흰 면 + `shadow-e1` + primary 글자다. 768 미만은 `max-md:chip`(44)이고, 활성 `aria-current`는 보라 채움이다. 흐림은 `trackScrollEdges`로 끝에 닿기 전까지만 보인다. 제목은 `text-h1`이고, 본문 등장 모션을 제거했다. `max-md:chip`처럼 변형이 붙은 커스텀 유틸이 `@media (width < 48rem)` 안에 규칙 전체로 출력되는 것을 컴파일 CSS에서 확인했다.
- **홈**
  - 인사: 라벤더 띠 면(`--panel-radius`)을 유지했다. "회원 정보 수정"은 흰 바탕 보조 버튼이다.
  - 카드: 주문 현황·진행 중 클레임·기본 배송지·최근 주문은 흰 카드다.
  - 숫자: 현황 숫자는 `text-h1 tabular-nums`, 클레임 건수는 `text-display tabular-nums`다.
  - 구매 확정 대기: RenewNotice + action 슬롯이다.
  - 최근 주문: 날짜 줄과 주문번호 줄(모노·줄바꿈 없음)을 나눴다. 640 미만에서는 상태·금액을 정보 아래 줄로 내려 390에서 주문번호가 잘리지 않는다(FE-72 이월 해소). 주문 상태는 RenewBadge info다.
- **회원 정보·비밀번호** 폼은 흰 카드이고, 라벨·입력칸은 주문서 상수와 같다(`rounded-control`·`text-body`). 저장·변경 버튼은 `btn-primary btn-lg`(56 → 52)이고, 메시지는 기존 RenewNotice를 유지했다.
- **배송지**
  - 기본 배송지: 라벤더 띠 면 한 장이다. "기본 배송지" caption으로 표시하는데, 띠 면 위 라벨 배지는 대비가 부족해서다.
  - 나머지 배송지: 흰 카드이고 768 이상에서 2열이다. 배송지 이름 알약은 "· 이름" 글자로 바꿨다(FE-78 라벨 알약 → 글자 선례).
  - 액션: 기본으로 설정 `btn-secondary` · 수정 `btn-tertiary` · 삭제 `btn-tertiary text-destructive`. 전부 `btn-sm`이고 768 미만은 44다.
- **탈퇴** 경고 RenewNotice를 유지했다. 탈퇴는 `btn-danger btn-lg`이고, "취소"(`btn-secondary` → /mypage)를 새로 뒀다. 동의 label은 44다.

### §1-A 갈림길·채택/기각 근거
- **카테고리 대체: 이름 매핑 + id 순환(채택) / id 매핑(기각) / 관리자 지정(이월)** id 매핑은 환경마다 id가 달라 기각했다. id 순환은 매핑이 없는 카테고리에만 적용되고, 이름이 바뀌어도 흰 면으로 떨어지지 않는다. 관리자가 색·일러스트를 지정하는 방식은 운영 구조(D-220)와 함께 설계하므로 이월한다.
- **닫힘 뒤 초기화: 뷰가 close-auto-focus에서 resetForm(채택) / 뷰만 수정(불가)** 입력칸이 `vm.form`에 직접 묶여 있고, 저장 성공 경로는 페이지가 초기화 → 닫기 순으로 처리해 뷰만으로는 막을 수 없다.

### §2 검증
- 1차: backend dev 재생성(D-224 반영) → typecheck EXIT 0 → vitest 116 files / 798 passed(신규 category-theme 4 · phone-format 5 · DialogConfirm 2 · AddressesView 4 · RenewNotice +2) → e2e smoke·password-change 4 passed → 캡처 판정 7 PASS / 1 FAIL로 중단.
  - FAIL 원인: 검색 모달이 카카오 iframe(교차 출처) 안 검색창에 포커스를 둔다. 그래서 Esc keydown이 부모 문서에 닿지 않아 모달이 닫히지 않았다. 겹침 순서 문제는 아니다. 이어서 스크립트가 시간 초과로 끝났다(스크립트 결함).
- 결정 A 반영 뒤: nested 오버레이 + 단위 1 → 변경 관련 vitest 5 files / 28 passed → 캡처 재실행 1회 22/22 PASS. typecheck·e2e는 재실행하지 않았다(PR CI).
  - 카테고리·틀: "데모"는 페리윙클 + 태그다. 390 메뉴 칩은 44다.
  - 확정 대기 정렬: 1440은 아이콘·문구·버튼 중심이 같고, 390은 아이콘이 문구 첫 줄 중심에 있고 버튼은 아래 줄 44다. 390 주문번호는 넘치지 않는다.
  - 겹침: 오버레이는 폼 반투명 + 검색 투명(1단계)이다. 닫기 버튼에 포커스를 두고 Esc를 누르면 검색만 닫히고 포커스가 폼의 "주소 검색"으로 돌아온다.
  - 닫힘 초기화: 취소 직후에는 입력값이 유지되고, 다시 열면 비어 있다.
  - 탈퇴: 위험·보조 버튼이 적용됐다.
  - 연락처: 관리자 회원 17행과 셀러 주문 상세가 형식과 일치한다.

### §3 작업 전후 집계(대상 8파일 — 6화면 + RenewNotice·DialogConfirm)
- 버튼·배지: 인라인 글자 버튼 19 → 0(남은 비-btn은 메뉴 칩 링크 1과 카드 행 링크 2) · btn 51곳. 상태·라벨 알약 3 → RenewBadge 1 + 띠 면 caption 1 + 글자 1.
- 서체: font-mono 8 → 1(주문번호) · tabular-nums 0 → 12 · 표준 크기 클래스 49 → 0.
- 모션: 150ms가 아닌 전환 21 → 0 · 첫 화면 등장 모션 2곳 → 0.
- 면·모서리: 임의 모서리 14 → 2(썸네일, FE-77 유지 선례) · 임의 그림자 0 → 0 · shadow-e1 0 → 8.

### §8 이월
- **iframe 안 포커스 중 Esc 무반응** 카카오 우편번호 iframe은 교차 출처라 검색창에 포커스가 있을 때 Esc가 모달에 닿지 않는다(주문서 FE-78도 같은 구조). 대체 수단: Shift+Tab으로 닫기 버튼에 도달한 뒤 Esc · 닫기 버튼 · 바깥 누름.
- **105-4e-2** 주문 목록·상세·클레임 · 홈 주문 현황 숫자 → 필터 목록 연결(D-224).
- 주문서 결제 금액 aside(1024 이상 좁은 열)의 action 슬롯 알림은 768 이상 가로 배치 규칙이라 문구 열이 좁아질 수 있다(이번 캡처 대상 밖 · 105-4e-2 이후 화면 점검 때 확인).
- 캡처 관찰: mypage-1440 풀페이지에서 한글이 대체 글꼴로 찍혔다(FE-76 §8과 같은 촬영 시점 현상으로 추정). 데모 구매자 배송지가 1건이라 흰 카드 2열은 컴포넌트 테스트·클래스로만 확인했다.
- 관리자가 카테고리 테마를 지정하는 방식은 D-220 운영 구조와 함께 설계한다.

### 보완 — DialogConfirm tone 분리 · 결과 안내 · 동작명 버튼(커밋 전 같은 브랜치)
- DialogConfirm tone 분리(기본 primary, 삭제·취소·철회만 danger) — 구매확정 위험색 결함 수정. 위 E의 공용 변경으로 구매확정 확인 버튼이 `btn-danger`(핑크 틴트)가 됐는데, 구매확정은 긍정 동작이라 맞지 않았다. `destructive` boolean을 `tone: 'primary' | 'danger'`로 바꿨고 그 밖의 색은 추가하지 않았다.
- **구조 강조** 설명 칸에는 대상만 적는다. 되돌릴 수 없는 결과는 notice 슬롯(RenewNotice warning, 슬롯이 비면 렌더하지 않음)에 둔다. 확인 버튼 이름은 동작을 그대로 적는다. testid `descriptionTestId`는 `noticeTestId`로 바꿨다.
- **구매확정 결과 문구** 규약 문구 `ITEM_CONFIRM_WARNING`(FE-64 단일 소스, 2줄 가역성 형식)을 그대로 notice에 옮겼다. 처음 지정했던 "확정하면 반품·교환을 요청할 수 없어요."는 쓰지 않았다(단일 소스 유지). testid `item-confirm-warning`을 notice로 옮겼고 unit·e2e 단언은 바꾸지 않았다(둘 다 testid로 찾아 문구를 비교한다).
- 호출처(DialogConfirm 전수 3곳 — 클레임 요청 취소·탈퇴는 DialogConfirm 미사용이라 변경 없음):
  - 배송지 삭제(AddressesView): tone danger · "삭제하기"(처리 중 "삭제 중…") · 설명 "{받는 분} · {도로명}" · notice "삭제하면 되돌릴 수 없어요." — 근거 UserAddressService.delete soft delete(markDeleted) + UserAddressController 복구 경로 없음.
  - 구매확정(OrderDetailView·OrdersView): tone primary · "구매 확정하기"(처리 중 "확정 중…") · 설명 `itemConfirmDescription` = "{상품명} · {옵션}을 구매 확정합니다."(옵션 없으면 상품명만, 둘 다 없으면 "이 품목") · notice `ITEM_CONFIRM_WARNING` — 근거 ClaimService.validateReturnRequest(반품·교환은 DELIVERED 품목만). 설명은 닫히는 동안 바뀌어 보이지 않게 열려 있을 때의 값을 붙잡아 둔다.
- §1-A: **α 구조 강조(결과 안내 + 동작명 버튼)** 채택 / **β 별도 강조색** 기각(포인트 1색 기준(FE-76) 위반, 경고와 의미 중복) / **γ 크기·아이콘만 강조** 기각(결과가 전달되지 않음).

외부 검토: C / 생략

## FE-80: 주문·클레임 디자인·사용성 — 주문 목록·주문 상세·클레임 + 품목 상태 필터 연결 (Track 105-4e-2) (2026-09-25)

배경: FE-76 기준을 주문·클레임 화면에 적용하는 단계다. 대상은 OrdersView(주문·클레임 탭)·OrderDetailView·ClaimNewView·ClaimDetailView다. 여기에 105-4b(D-224) BE 변경을 FE에 연결했다(홈 주문 현황 → 품목 상태 필터 목록, 클레임 목록 썸네일). BE는 바꾸지 않았다. vm 계약 추가는 주문 목록 2건(아래)뿐이다.

결정:
- **공통 규칙** FE-77~FE-79와 같다. 글자 버튼은 `btn`, 영역당 주 버튼 1개, 띠 면 위 보조 버튼은 흰 바탕이다. 상태 알약은 RenewBadge를 쓰고, font-mono는 주문번호 식별자 단독에만 쓴다. 숫자는 `tabular-nums`, 크기는 서체 스케일 유틸이다. 콘텐츠 카드는 흰색 + `shadow-e1`, 전환은 150ms `ease-soft`다. 첫 화면 등장 모션은 두지 않는다. 768 미만 터치 영역은 44이고, 선택 알약은 `chip`, 인라인 메시지는 RenewNotice다.
- **A. 품목별 배지** 주문 카드와 주문 상세 모두 상태를 품목 행마다 RenewBadge로 보인다. 라벨은 `ORDER_ITEM_STATUS_LABELS` 그대로다. 주문 상세 머리의 주문 상태 배지는 없앴다. 주문 상태는 품목 상태에서 파생되므로, 한 줄로 요약하면 상태가 섞인 주문을 잘못 말한다(D-224 γ 기각 근거와 같다). 결제대기·미결제 종료는 머리 안내(RenewNotice)가 이미 말한다. 주문 카드 머리에는 원래 주문 상태 배지가 없었다.
- **B. 품목 상태 필터(D-224)**
  - 홈 링크: 마이페이지 홈 주문 현황 5단계 숫자가 `/orders?itemStatus=PAID|PREPARING|SHIPPING|DELIVERED|CONFIRMED` 링크다(`orderSummaryStageLink`). 0건 단계도 링크를 유지하고 흐림도 그대로다. 높이는 44 이상이고 aria-label은 "{라벨} {N}건 주문 보기"다. tab 쿼리는 붙이지 않는다(기본 = 전체 주문).
  - 쿼리 검증: `parseItemStatusFilter`는 허용 5값(대문자)만 받는다. 단계 밖 품목 상태·소문자·빈 값·배열은 null(필터 없음)로 무시해 BE 400을 부르지 않는다. `useOrderList(page, itemStatus)`는 null을 undefined로 바꿔 쿼리에서 뺀다. ufo는 null 값을 키만 붙은 `?itemStatus`로 출력한다.
  - 탭·페이지: 필터는 전체 주문 탭에만 걸린다. `moveTo`로 클레임 탭에 가거나 `moveToClaimType`을 쓰면 itemStatus를 지운다. 클레임 탭 URL에 itemStatus가 남아 있어도 걸지 않는다. 같은 탭 안 페이지 이동은 쿼리를 이어받아 필터가 유지된다.
  - 표시: 목록 위 선택 칩 1개 "{단계 라벨} 품목 · 최근 3개월" + 칩 안 해제 아이콘 버튼(aria-label "필터 해제" · 768 미만 44 · 이상 32)이다. 해제는 itemStatus 제거 + 첫 페이지다. 단계 라벨은 홈 현황과 같은 라벨(`orderSummaryStageLabel`)이고, 기간 3은 FE 상수 `ITEM_STATUS_FILTER_PERIOD_MONTHS`(BE `SUMMARY_PERIOD_MONTHS`와 같은 값)다.
  - 빈 결과: "최근 3개월 동안 {단계 라벨} 품목이 있는 주문이 없어요" + "필터 해제"(`btn-secondary`)다. 이때는 "쇼핑하러 가기"를 두지 않는다.
- **C. 페이지 크기** `useOrderList` 기본 size 20 → 10. BE 기본 20·최대 100 범위 안이다. 주문 카드가 품목 행을 모두 펼쳐 20은 길다. 홈 최근 주문(`useRecentOrders` 3)은 그대로다.
- **D. 주문 카드**
  - 768 이상 머리 한 줄: 날짜(`text-small` tabular-nums) · 주문번호(모노 caption + `surface-muted` 식별자 칩) · "상품 N개 · 총 {금액}원" · 결제하기(결제대기만 `btn-primary btn-sm`) · "주문 상세"(`btn-tertiary btn-sm`).
  - 768 미만 머리: 1줄은 날짜 + "상세" 링크(44), 2줄은 주문번호 + 개수·총액이다. 한 grid에서 배치만 바꿔 요소 중복이 없다.
  - N은 품목 행 수(items.length)다. 품목 요약이 없는 옛 응답이면 개수를 빼고 총액만 쓴다.
  - 품목 행(768 이상): [썸네일+정보] [금액 열 `w-28` · `text-h3` tabular-nums] [액션 세로 열 `w-36`]. 썸네일+정보 영역은 상품 상세 링크다. 링크는 상품명에 걸고 `after:inset-0`로 영역 전체를 덮는다(카드 안 다른 버튼과 중첩 없음). productId가 없으면(삭제 상품) 링크 없이 글자다. 액션이 없는 행도 열 폭을 남겨 행마다 금액 위치를 맞춘다.
  - 품목 행(768 미만): 금액은 정보 아래(썸네일 폭만큼 들여쓰기)에 둔다. 버튼은 2열 grid(균등 폭 · 44)이고 3개면 2+1이다.
  - 주 버튼은 구매확정(배송 완료) 하나다. 반품·교환·취소 요청은 보조(`btn-secondary`)이고, 취소 요청만 있는 품목도 보조다.
  - 진행 중 클레임 배지는 링크 안 RenewBadge(유형 tone · 768 미만 44), "외 N"은 중립 칩이다.
- **E. 클레임**
  - 목록 카드: 썸네일 64(`thumbnailUrl` · 없으면 이미지 대기 면 · lazy). `ClaimSummary` 타입에 `thumbnailUrl?`를 더했다(BE 응답 필드 · vm 계약 변경 아님). hover는 임의 그림자 → `shadow-e2` + `-translate-y-1`이다.
  - 유형 배지: RenewBadge `CLAIM_TYPE_BADGE_TONE`(취소 warning · 반품 danger · 교환 neutral). 기존 파스텔(butter·pink·periwinkle)과 같은 색이다. `CLAIM_TYPE_BADGE_CLASS`는 사용처가 0이 돼 tone 맵으로 바꿨다.
  - 상태·거부 사유·환불 상태: 흰 바탕 + 테두리 중립 칩을 유지하고 크기만 RenewBadge(22px · 12/600)에 맞췄다(`CLAIM_NEUTRAL_CHIP_CLASS`). RenewBadge neutral로 바꾸면 교환 유형 배지와 같은 페리윙클이 돼 "색은 요청 유형에만"(FE-73 보완 1)이 깨진다.
  - 신청 완료 "주문 내역으로" → `/orders?tab=claim`(클레임 상세 복귀 `tabOfClaimType`과 같은 값)이다. 방금 낸 요청이 유형과 무관하게 보인다. 잘못된 접근 안내의 링크는 `/orders` 그대로다.
  - 클레임 상세 요청 취소 패널: 구조를 유지한다(action 슬롯 FE-79 · 모달 전환 안 함). 여는 버튼만 `btn-secondary btn-sm`이다.
- **F. 확인창 설명 조사** 구매확정 확인창 설명을 두 줄로 바꿨다. 1줄은 "구매 확정할 품목"(`text-small` · sub), 2줄은 "{상품명} · {옵션}"(없으면 "이 품목" · `text-body` semibold · testid `item-confirm-target`)이다. 조사를 붙이지 않는다. FE-79 캡처에서 "사이즈: M을"로 읽혔기 때문이다. `itemConfirmDescription` → `itemConfirmTarget`(대상만) + `ITEM_CONFIRM_TARGET_CAPTION`. 제목·notice(`ITEM_CONFIRM_WARNING`)·버튼 이름은 그대로다.
  - 공용 DialogConfirm에 `description` 슬롯을 더했다. 슬롯이 있으면 prop보다 우선하고, prop `description`은 선택(기본 '')이 됐다. 배송지 삭제 확인창은 prop 그대로다.
- **vm 계약 추가(주문 목록)** `OrdersPageVm.itemStatusFilter`(null = 없음 · 클레임 탭에서는 항상 null) · `clearItemStatusFilter()`. 이름은 `claimTypeFilter`·`moveToClaimType` 관례를 따랐다.

배지 tone 매핑(`skins/renew/order-item-tone.ts` ORDER_ITEM_STATUS_TONE · 미지 값 neutral):

| 품목 상태 | 라벨 | tone | 근거 |
|---|---|---|---|
| ORDERED | 주문접수 | info | 관리자·셀러 품목 색 의미(결제 완료와 같은 계열) |
| PAID | 결제완료 | info | 지시 |
| PREPARING | 상품준비중 | neutral | 지시 |
| SHIPPING | 배송중 | neutral | 지시 |
| DELIVERED | 배송완료 | success | 지시(구매 확정을 기다림) |
| CONFIRMED | 구매확정 | neutral | 지시(끝난 상태) |
| CANCEL_REQUESTED · RETURN_REQUESTED · EXCHANGE_REQUESTED | 취소·반품·교환 요청 | warning | 관리자·셀러 `*_REQUESTED=warning` |
| CANCELLED · RETURNED | 취소완료·반품완료 | danger | 관리자·셀러 취소·반품 완료 = danger |
| EXCHANGED | 교환완료 | info | 관리자·셀러 EXCHANGED = info |

화면별 변경:
- **주문 목록** 탭 2개는 세그먼트(흰 바탕 + `shadow-e1` · 선택 primary 채움 · aria-current 유지)다. 클레임 유형 필터는 `chip`(aria-pressed · 768 미만 44)이다. 빈 목록은 흰 카드 + `text-h3`이고 "쇼핑하러 가기"는 `btn-primary btn-md`다. 페이저 이전·다음은 `btn-secondary btn-md`, 번호는 tabular-nums다. 등장 모션(ENTER)을 없앴다.
- **주문 상세** 머리·결제 재개·판매자 그룹·결제 금액·배송지는 흰 카드 + `shadow-e1`이다. 결제수단 알약은 `chip`(data-state=on · 숨긴 radio의 focus-visible 링)이고, 결제하기는 `btn-primary btn-lg`(56 → 52)다. 품목 금액은 `text-h3`, 총 결제금액은 `text-h2`이며 둘 다 tabular-nums다. 구매확정은 `btn-primary`, 클레임 요청은 `btn-secondary`(`btn-sm` · 768 미만 44)다. 배송지 연락처는 formatPhone 적용 완료(FE-79)를 확인했고, font-mono → tabular-nums로 바꿨다. 배송 정보는 공용 OrderItemDeliveryInfo(판매자 흰 카드 안)이며 변경하지 않았다. "주문 내역으로"는 `btn-secondary btn-lg`다.
- **클레임 신청** 대상·폼은 흰 카드이고 라벨·입력칸은 FE-79 상수(`rounded-control` · `text-body`)다. 글자수는 tabular-nums다. 교환 옵션 로딩 문구는 RenewNotice info(testid 유지)다. 제출은 영역 주 버튼 `btn-primary btn-lg`(56 → 52)다. 완료 화면 등장 모션을 없앴고, "주문 내역으로"는 `btn-primary btn-md`다.
- **클레임 상세** 섹션은 흰 카드, 제목은 `text-h3`다. 단계 번호·시각·송장번호는 font-mono → tabular-nums로 바꿨다. 첨부 확대 전환은 500 → 150ms다. 회수 송장 등록은 `btn-primary btn-lg`, "주문 내역으로"는 `btn-secondary btn-lg`다. 이 화면에는 금액 표시가 없다.

### §1-A 갈림길·채택/기각 근거
- **신청 완료 이동: α `?tab=claim`(채택) / β `?tab=cancel`(routeRules /claims 리다이렉트 값 · 기각) / γ `?tab=claim&type={유형}`(기각)** β는 클레임 탭을 취소 유형으로 걸러 열어, 반품·교환을 막 신청한 사용자에게 새 요청이 보이지 않는다. γ는 필요 이상으로 좁고, 클레임 상세 복귀와 값이 달라진다(zslab 결정 ①).
- **클레임 상태 칩: 중립 칩 유지(채택) / RenewBadge neutral(기각)** 위 E의 페리윙클 충돌 때문이다(zslab 결정 ⑤).
- **상품 링크: 이름 링크 + 영역 확장(채택) / 영역 전체를 NuxtLink로 감싸기(기각)** 삭제 상품(productId 없음)일 때 같은 마크업을 링크/비링크 두 벌로 나눠야 한다.

### §2 검증
- typecheck(컨테이너) EXIT 0 → vitest 118 files / 820 passed → e2e smoke·claims·mock-payment·order-resume-payment 11 passed.
- 테스트 변경: 깨진 단언은 1건이다(OrderDetailPage "…블랙을 구매 확정합니다." → 설명 캡션 + 대상 "배송완료 상품 · 색상: 블랙"). 추가한 테스트:
  - order-tabs +3(필터 허용 5값·무시·기간)
  - order-summary-stages +2(홈 링크 URL·단계 라벨)
  - order-item-tone 4(tone 매핑·12값 전부·클레임 유형)
  - OrderDetailPage +1(품목 배지 tone·머리 주문 상태 없음)
  - OrdersPage 8(필터 전달·칩 · 5값 밖 무시 · 칩 해제 · 페이지 유지·탭 전환 제거 · 빈 결과 · 클레임 탭 미적용 · 카드 요약·배지·상품 링크 · 요약 없음)
- 테스트 트랩: `useRouter`를 mockNuxtImport로 통째 mock하면 Nuxt 플러그인이 `afterEach`·`beforeResolve`에서 TypeError를 낸다 → 실제 `$router.replace`만 spy로 바꿨다.
- 임시 캡처(데모 구매자 · 읽기만 · 확정·제출 클릭 없음): /mypage · /orders · ?itemStatus=DELIVERED · ?itemStatus=CONFIRMED · 클레임 탭 · 주문 상세 · 구매확정 확인창 · 클레임 신청 · 클레임 상세 × 1440·390 = 18장, 18/18 OK. 확인 결과:
  - 홈 링크: 5단계 href · 링크 높이 70/60(390).
  - 목록: 카드 10 · 요약 "상품 1개 · 총 45,000원" · 상세 링크 36/44 · 필터 칩 문구 · 배지 배송완료 success · 품목 버튼 36/44 · 클레임 탭 전환 URL `tab=claim&page=0`(itemStatus 제거) · 유형 칩 36/44.
  - 상세: 머리는 주문번호만이다.
  - 확인창: 설명 "구매 확정할 품목 / T75 옵션 티셔츠 · 색상: 화이트 / 사이즈: M".
  - 클레임: 신청 반품 danger · 제출 52 / 상세 취소 warning.
  - CONFIRMED는 데모 데이터에 결과가 2건이라 빈 결과 화면은 컴포넌트 테스트로만 확인했다.
- 캡처 결함 1(재캡처 안 함): 클레임 탭 2장은 스켈레톤 상태로 찍혔다. 탭 클릭 뒤 `waitForLoadState('networkidle')`가 이미 충족된 상태라 즉시 반환해, 클라이언트 조회 전에 촬영됐다. 진단 1회에서 전환 +500ms에 카드 9, SSR `/orders?tab=claim`도 9로 코드 결함이 아님을 확인했다. 클레임 카드 썸네일의 실제 렌더는 이번 캡처로 확인하지 못했다. API에서 첫 클레임 thumbnailUrl이 있음은 확인했다.
  - 재캡처 PASS(썸네일 렌더 확인): 대기 조건을 "클레임 카드 1개 이상(최대 10초)"으로 바꿔 1440·390 2장만 다시 찍었다. 카드 9 · 썸네일 img 9/9(API thumbnailUrl 9) 로드 완료 naturalWidth 600 · 썸네일 없는 카드는 이번 데이터에 0건이라 대기 면은 코드·클래스로만 확인했다.

### §3 작업 전후 집계(대상 4파일)
- 버튼·선택:
  - 인라인 글자 버튼 17 → 0. 남은 비-btn은 탭 세그먼트 1 · 필터 해제 아이콘 1 · 배지 링크 1 · 상품명 링크 1 · 클레임 카드 링크 1이다.
  - btn 3 → 23 · 선택 알약 3 → chip 3(유형 필터·결제수단·필터 칩) + 세그먼트 1.
- 배지: 상태·라벨 알약 13 → RenewBadge 6 + 중립 칩 6 + 주문 상태 1 제거.
- 서체: font-mono 18 → 2(주문번호) · tabular-nums 0 → 25 · 표준 크기 클래스 82 → 0.
- 면·모션: 임의 그림자 1 → 0 · shadow-e 0 → 8 · 150ms가 아닌 전환 26 → 0 · 첫 화면 등장 모션 2 → 0 · 임의 모서리 11 → 5(썸네일, FE-77 유지 선례).
- 메시지: 인라인 메시지 1 → 0 · RenewNotice 17 → 18.

### §8 이월
- 105-4g: 카테고리 타일 대체 아이콘 중복(기타·데모 모두 태그) · id 순환 색이 이웃 타일과 겹침(기타·리빙·주방 페리윙클) · 태블릿 가로 폭에서 타일이 지나치게 큼 · routeRules `/claims` → `/orders?tab=cancel` 옛 값(FE-63) — `tab=claim`으로 정정 검토.
- 클레임 목록 조회는 유형 전체일 때 `?type`(키만)을 보낸다(기존 `useClaimList` · null → ufo 키만 출력). BE는 빈 값으로 받아 동작하며 이번 변경은 없다.
- 마이페이지 홈 "확정하러 가기"는 `/orders` 그대로다. `?itemStatus=DELIVERED` 연결은 지시 범위 밖이라 하지 않았다.
- 캡처 대기 방식: 클라이언트 전환 뒤에는 networkidle 대신 대상 요소 대기를 쓴다(이번 클레임 탭 결함).

외부 검토: C / 생략

## FE-81: 인증·기타 디자인 — 로그인·회원가입·도움말·에러 화면 + 비밀번호 찾기 준비 중 (Track 105-4f) (2026-09-25)

배경: FE-76 기준을 마지막 화면 그룹에 적용하는 단계다. 대상은 LoginView·SignupView·HelpView·ErrorView와 이 화면들이 쓰는 RenewAuthFrame, 그리고 app/error.vue다. 비밀번호 찾기 진입점을 "준비 중"으로 둔다. BE 계약과 vm 계약은 바꾸지 않았다.

결정:
- **공통 규칙** FE-77~FE-80과 같다. 글자 버튼은 `btn`이고 영역당 주 버튼은 1개다. font-mono는 식별자 단독에만 쓰는데 대상 파일에는 해당이 0건이다(눈썹 문구는 `text-caption`). 크기는 서체 스케일 유틸, 콘텐츠 카드는 흰색 + `shadow-e1`, 전환은 150ms `ease-soft`다. 첫 화면 등장 모션은 두지 않는다. 768 미만 터치 영역은 44이고, 인라인 메시지는 RenewNotice다(대상 화면은 이미 RenewNotice였다).
- **비밀번호 찾기 준비 중** 준비 중 처리 기준(FE-76: 새 BE 도메인 · 외부 발송·결제 연동 · 검토 등급 A 중 하나)에 해당한다. 인증 영역이고 검토 등급 A다. 로그인 비밀번호 칸 아래 오른쪽에 3차 버튼 "비밀번호를 잊으셨나요?"(testid `login-forgot-password` · type=button · 768 미만 44)를 두고, 누르면 공용 알림 RenewNotice info "비밀번호 찾기는 준비 중입니다."(testid `login-forgot-password-notice`)를 폼 안에 띄운다. 페이지 이동과 요청은 없다.
  - 표시 상태는 뷰의 로컬 `ref` 하나다. skin-guard는 데이터 조회·조작·이동 호출만 막으므로 뷰에서 공용 알림을 쓸 수 있어, 지시에서 허용한 vm 동작 추가는 하지 않았다.
- **1024 미만 안내 면** 현행 유지(`hidden lg:flex`). 폼 카드만 보인다.

로그인 안내 면(RenewAuthFrame · ≥1024 왼쪽):
- 장식 원 2개와 진입 모션(ENTER)을 없앴다. 라벤더 띠 면(`--panel-radius`) 한 장에 위에서 아래로 놓는다.
  - 눈썹: "zslab-mall" · `text-caption` · 대문자 · 0.12em · `--pastel-lavender-ink`(메인 히어로와 같다).
  - 제목: 기존 headline "다시 만나서 반가워요" · `text-h1` · ink.
  - 리드: 기존 description "로그인하고 장바구니와 주문 내역을 이어서 확인하세요." · `text-body` · sub.
  - 일러스트: CategoryIllustration shirt·mug·bottle·bag을 흰 원형 칩(64 · `shadow-e1` · lavender-ink 선) 2×2로 둔다.
  - 보조 문구: `note` prop(선택). 로그인은 데모 로그인이 켜져 있을 때만 "데모 계정으로 바로 둘러볼 수 있어요"를 넘긴다(`text-small` · 면 하단 `mt-auto` · testid `auth-panel-note`).
- **새 문구 목록** 1개 — "데모 계정으로 바로 둘러볼 수 있어요". 리드는 기존 description으로 1줄이 되어(1440 기준) 재사용했다. 눈썹 "zslab-mall"은 지시 값이고 메인 히어로 눈썹과 같다. 이 밖의 새 문구는 지시로 정한 "비밀번호를 잊으셨나요?" · "비밀번호 찾기는 준비 중입니다." · 도움말 "홈으로"다.
- 안내 면은 기존대로 `aria-hidden`이다. 데모 버튼은 폼에 있어 보조 문구를 스크린리더에서 읽지 않아도 기능 손실이 없다.
- 회원가입도 같은 틀이라 장식 원 제거·일러스트 칩이 함께 적용된다. note는 넘기지 않고 눈썹 "Join"은 유지했다.

화면별 변경:
- **로그인 폼** 폼 카드는 흰색 + `shadow-e1` + `rounded-card`(28px → 24px)이고 제목은 `text-h1`이다(틀 공용). 라벨·입력칸은 FE-79 상수(`text-small` · `rounded-control` · `text-body`)다. 로그인은 `btn-primary btn-lg`(56 → 52), 데모는 `btn-secondary btn-lg`(문구·testid 유지), 회원가입 링크는 `btn-tertiary btn-sm text-primary`(768 미만 44)다. 비밀번호 찾기 버튼은 `-mr-4`로 3차 버튼 좌우 여백만큼 당겨 글자 끝을 입력칸 오른쪽 끝에 맞추고 글자색은 sub다. 오류는 RenewNotice danger(문구 유지)다.
- **회원가입** 폼은 흰 카드(틀 공용)이고 라벨·입력칸은 FE-79 상수다. 가입은 `btn-primary btn-lg`, 로그인 링크는 `btn-tertiary btn-sm text-primary`(768 미만 44)다. 비밀번호 확인 불일치는 기존 동작(요청 전 판정 · 문구 · RenewNotice danger · testid `signup-error`)을 그대로 둔다.
- **도움말** 라벤더 띠 면 → 흰 카드 1장(`shadow-e1` · 최대 560 가운데)이다. 아이콘 원(lavender) + 제목 `text-h1` "도움말" + "준비 중입니다."(testid `help-placeholder` · 문구 완전 일치 유지) + "홈으로"(`btn-secondary btn-md` · /)다. 모노 눈썹 "Help"와 진입 모션을 없앴다.
- **에러 화면** 라벤더 띠 면 → 흰 카드 1장(`shadow-e1` · 최대 560 가운데)이다. 404는 큰 숫자 "404"(`text-display` 800 · primary · tabular-nums · aria-hidden) + 제목 + 설명, 일시 오류는 숫자 없이 제목 + 설명이다. 아이콘(SearchX·CloudAlert)과 모노 눈썹을 없앴다. 홈으로는 `btn-primary btn-md`, 이전 페이지는 `btn-secondary btn-md`(testid·문구 유지)다.
- **error.vue** /admin·/seller 분기(레이아웃 없이 ErrorView · 홈으로 = 각 영역 첫 화면)는 유지했다. 그 분기에 바탕 div(`min-h-screen bg-surface-page`) 한 겹을 더했다. 레이아웃이 없으면 LayoutShell의 바탕 면도 없어 흰 카드가 흰 body에 묻히기 때문이다(지시 외 · 캡처로 바탕 #F7F5FB 확인).

### §1-A 갈림길·채택/기각 근거
- **비밀번호 찾기 알림: α 뷰 로컬 상태(채택) / β vm 동작 추가(기각)** β는 지시상 공용 알림을 뷰에서 쓸 수 없을 때의 대안이다. skin-guard가 막지 않아 계약 변경 없이 α로 끝난다.
- **에러 화면 면: 흰 카드(채택) / 라벤더 띠 면(기각)** 도움말과 같은 모양으로 맞췄고, primary 숫자 "404"가 흰 면에서 대비가 크다.

### §2 검증
- typecheck(컨테이너) vue-tsc 오류 출력 0. EXIT=0은 `| tail` 파이프의 종료 코드라 pnpm 자체 코드가 아니다(재실행 안 함 · FE-76 §2와 같은 실수).
- vitest 119 files / 822 passed(CODE=0 · 신규 LoginView 2). 깨진 단언은 0건이었다(대상 뷰에 클래스·문구 단언이 없다).
  - LoginView: 비밀번호 찾기 클릭 → info 알림 문구 완전 일치 · 경로 불변 · handleSubmit/handleDemoLogin 미호출.
  - LoginView: 안내 면 데모 문구는 demoEnabled true일 때만 렌더된다.
- e2e smoke·password-change·help 7 passed(EXIT=0 · 데모 로그인 ③ 포함).
- 임시 캡처(커밋 제외 · document.fonts.ready 뒤 촬영): /login(+비밀번호 찾기 알림 상태) · /signup · /help · /없는-경로 · /admin/없는-경로 × 1440·390 = 12장, 판정 10/12 PASS.
  - FAIL 2는 /admin 404의 HTTP 상태 200이다. `/admin/**`는 ssr:false(D-9)라 SPA 셸 200 + 클라이언트 404가 정상이므로 판정 기준 오류로 통과 처리했다. 나머지 값(404 · 800 · 제목 · 구매자 푸터 0 · 카드 흰색 · 바탕 #F7F5FB)은 일치했다.
  - 로그인: 1440 안내 면 · 칩 4 · 데모 문구 표시 / 390 안내 면 숨김 · 로그인·데모 52 · 비밀번호 찾기·회원가입 링크 36(1440)/44(390) · 알림 뒤 URL 불변.
  - 구매자 404: HTTP 404 · 숫자 800 · 헤더·푸터 포함 · 버튼 44.
- 스크린샷 docs/frontend/screens-track105-4f/ — login · login-forgot · signup · help · error-404 · error-404-admin(각 1440·390).
- 캡처 관찰: login-forgot-1440 풀페이지에서 한글이 대체 글꼴로 찍혔다(fonts.ready 뒤 · body 계산 글꼴 Pretendard — FE-76 §8과 같은 촬영 현상).

### §3 작업 전후 집계(대상 6파일 — 4뷰 + RenewAuthFrame · error.vue)
- 버튼: 인라인 글자 버튼 5 → 0 · 밑줄 글자 링크 2 → 0(`btn-tertiary`) · btn 0 → 9.
- 서체: font-mono 3 → 0 · 표준 크기 클래스 21 → 0 · tabular-nums 0 → 1.
- 면·모션: 임의 그림자 0 → 0 · shadow-e 0 → 4 · 임의 모서리 3 → 0 · 150ms가 아닌 전환 8 → 0 · 첫 화면 등장 모션 3 → 0.
- 메시지: 인라인 메시지 0 → 0 · RenewNotice 3 → 4(비밀번호 찾기 알림).

### §8 이월
- 실제 비밀번호 찾기(BE 기능 · 외부 발송) · 약관 동의 · 도움말 본문(매뉴얼 단계).
- 500 계열 에러 화면 확인(FE-74 이월 유지 — 이번 캡처는 404만).
- 안내 면 aria-hidden 유지 — 제목·리드는 폼 제목과 중복이라 두었다. 접근성 채점 때 재검토한다.

외부 검토: C / 생략

## FE-82: 채점 보완 — 접근성·일관성·디자인·직관성 + 성능 1차 (Track 105-4g-2) (2026-09-25)

배경: 105-4g-1에서 운영 사이트를 FE-76 6축으로 재채점했다. 결과는 디자인 91 · 일관성 93 · 직관성 94 · 편의성 93 · 접근성 82 · 성능 64(평균 86.2, 목표 95)였다. 이번 트랙은 그중 FE만으로 고칠 수 있는 감점을 처리하고, 성능은 1차 조치만 한다. 주문번호·주문 일시·결제 수단·클레임 대상 품목은 BE가 함께 바뀌어야 해서 105-4g-3으로 넘긴다. 성능 재측정은 배포 후에 한다.

결정:
- **접근성**
  - 언어: nuxt.config `app.head.htmlAttrs.lang = 'ko'`. 레이아웃·error.vue의 `useHead({ htmlAttrs: { 'data-skin' } })`와 unhead가 합친다(구매자 404에서 `lang=ko data-skin=renew` 확인). 전역 설정이라 관리자·셀러도 lang=ko가 된다.
  - 위험 글자색: 구매자 `[data-skin]`의 `--destructive`를 #EF4444에서 #B0245A로 바꿨다. 대비는 흰 바탕 6.47 · surface-muted 5.52 · 라벤더 4.81 · 핑크 바탕 5.05다. `btn-tertiary text-destructive`와 인라인 위험 문구가 이 토큰 하나를 쓴다. 관리자·셀러(`:root`)는 그대로다.
  - 마이페이지 0건 단계: 링크의 `opacity-40`을 없애고 숫자만 `text-sub`로 낮췄다. 투명도 때문에 라벨 대비가 1.87까지 떨어졌었다.
  - 검색 눈썹 문구: `opacity-80`을 없앴다(lavender-ink 7.17).
  - 터치 44(390 폭): 헤더 로고는 `inline-flex min-h-11`로 모양이 그대로다. 상세 수량 버튼은 h/w-11이다. 송장번호 복사는 `btn btn-sm bg-white text-primary max-md:min-h-11`이다.
  - 본문 바로가기: LayoutShell의 첫 포커스 요소다. `btn btn-primary btn-md`를 쓰고, 고정 위치에 투명하게 두었다가 포커스를 받을 때만 보인다. main은 `id="main-content" tabindex="-1"`이고, 링크를 누르면 해시 이동 대신 main에 직접 포커스한다.
  - 로그인·회원가입 안내 면: 면 전체의 aria-hidden을 없애 문구가 읽히게 했다. 일러스트 묶음에만 aria-hidden을 남겼다.
- **일관성**
  - 공용 DialogContent의 임의 그림자를 `shadow-e3`로 바꿨다.
  - 공용 3개
    - ItemDeliveryInfo: `bg-gray-50`·`text-xs`·`hover:bg-gray-100`을 surface-muted 면과 `text-small`로 바꿨다. 복사 버튼은 흰 바탕 btn이다.
    - ErrorState: "다시 시도"를 `btn btn-secondary btn-md`로 바꿨다.
    - AttachmentInput: 크기를 서체 스케일로 바꿨다. shadcn Button outline은 `btn btn-secondary btn-sm`으로, `shadow`는 `shadow-e1`로 바꿨다.
  - 클레임 타임라인 현재 단계: `ring-4`(box-shadow)를 같은 토큰 색의 `outline-4`로 바꿨다. 그림자는 e1~e3만 쓴다는 규칙을 따른다.
  - 주문 탭: FE-80 세그먼트(흰 바탕 + `shadow-e1` · 선택 primary 채움 · aria-current)와 이미 같아 코드는 바꾸지 않았다. **세그먼트를 btn·chip과 나란한 허용 패턴으로 등재**한다(용도: 화면 전환 탭 2~3개).
  - 상세 카테고리 링크: 버터색 인라인 채움을 `btn-secondary`로 바꿨다. 이 링크만 쓰던 categoryTheme import도 정리했다.
- **디자인**
  - 카테고리 테마 목록 단위 계산: `categoryThemes(categories)`를 추가했다. 결과는 입력 순서와 같다. 메인 타일과 목록 배너가 같은 계산을 쓰고, 목록 밖 id와 전체는 단일 `categoryTheme`을 쓴다.
    - 색: 매핑 없는 카테고리는 id % 5가 기본이다. 바로 앞 타일 색이나 바로 다음 타일 색(매핑 여부 무관 · 매핑이 없으면 그 타일의 기본색)과 같으면 다음 파스텔로 넘긴다. 피할 색은 최대 2개이고 파스텔은 5색이라 항상 고를 수 있다.
    - 초기 규칙의 빈틈: 처음에는 앞 타일만 피했다. 그러자 로컬 순서 데모(id 1 · 기본 페리윙클) → 리빙·주방(매핑 페리윙클)에서 뒤쪽 겹침이 남았다(캡처로 발견). 그래서 다음 타일도 피하도록 넓혔다.
    - 대체 아이콘: tag·box·sparkle 3종이다. 매핑 없는 카테고리를 id 오름차순으로 세운 순번으로 돌린다. id % 3을 쓰면 운영의 기타(1)·데모(7)가 같은 아이콘이 된다. 단일 `categoryTheme`은 목록을 모르므로 id % 3으로 돌린다.
    - 그리드: md 4열 · lg 6열로 바꿨다(이전 md 3 · xl 6). 타일 높이 상한은 `max-h-40`이다.
  - 빈 목록: CommonEmptyState를 검색 빈 결과와 같은 톤으로 바꿨다. 흰 카드 + `shadow-e1` + 아이콘 원을 쓰고, 기존 문구 "등록된 상품이 없습니다"를 `text-h3`로 유지했다. 카테고리 화면에만 "전체 상품 보기" `btn-secondary` 버튼을 둔다.
- **직관성**
  - 푸터 회사소개·이용약관·개인정보처리방침: `<a href="#">`를 button으로 바꿨다. 누르면 RenewNotice info "준비 중입니다."를 띄운다(FE-81 비밀번호 찾기와 같은 방식). 외형은 고객센터 링크와 같은 푸터 링크 모양을 유지했다(btn 아님). 고객센터(/help)는 그대로다.
  - 모바일 카테고리 줄: LayoutShellVm에 `hasListingTabs`를 추가했다. layouts/default.vue가 경로로 판정한다(`/products` · `/categories/*`). 목록·카테고리 화면의 768 미만에서는 헤더 카테고리 줄을 `max-md:hidden`으로 숨기고 목록 탭만 둔다.
  - 상세 셀러 표시: 아바타 + surface-muted 알약을 평문(`text-small text-sub`)으로 바꿨다.
- **기타** `.gitignore`에 `docs/frontend/scorecard-*.md`를 추가했다(채점 보고서는 로컬 전용).

성능 1차:
- **prefetch 원인** Nuxt entry의 dynamicImports에 관리자·셀러 레이어의 레이아웃 4개와 미들웨어 4개가 있었다. Nuxt는 entry의 동적 import를 모든 화면에서 prefetch한다. 이 목록을 따라 Vuetify JS·CSS 청크까지 구매자 화면에서 미리 받고 있었다.
- **조치** nuxt.config `hooks['build:manifest']`에서 isEntry 청크의 dynamicImports 중 `layers/(admin|seller)/` 경로만 뺐다. 매니페스트는 링크 힌트 계산에만 쓰이므로 실제 `import()`와 관리자·셀러 화면 로딩은 그대로다(빌드 산출에서 /admin/login·/seller/login 200 · Vuetify 렌더 · 콘솔 오류 0).
- **전후 수치**(pnpm build 산출 · / · /products · 상세 · /login 모두 같다)
  - prefetch: 51(CSS 11 — Vuetify 10) → 1(CSS 0)
  - modulepreload: 4~5 → 4~5
  - preload: 0 → 0
  - entry CSS: 109,711B → 109,449B
- **렌더 차단 CSS** 구성은 @font-face 94개 49,322B · utilities 48,697B · 나머지 약 11KB다. utilities는 전부 app/ 소스에서 나오고 테스트·e2e에서만 생긴 클래스는 0이라, 안전하게 뺄 전역 CSS가 없다. `features.inlineStyles` 기본값은 .vue 스타일만 인라인하므로 전역 main.css는 대상이 아니다. 조치하지 않았다.
- **LCP 요소**(105-4g-1 운영 Lighthouse 모바일): / = 히어로 h1 텍스트 · /products = 첫 상품 썸네일 img · 상세 = 대표 원본 png · /login = 이메일 입력칸 placeholder.

### §1-A 갈림길·채택/기각 근거
- **본문 바로가기 숨김: 투명도(채택) / sr-only + focus:not-sr-only(기각)** `not-sr-only`가 padding을 0으로 되돌려 btn 좌우 여백이 사라진다.
- **대체 아이콘 순환 기준: id 오름차순 순번(채택) / id % 3(기각)** 운영 id 1·7이 같은 나머지라 "기타·데모가 같은 아이콘이 되지 않게"를 지키지 못한다.
- **렌더 차단 CSS: 조치 없음(채택) / 전역 CSS 인라인·폰트 선언 분리(보류)** 인라인은 HTML마다 108KB가 붙고 캐시를 잃는다. 폰트 선언 분리는 FOUT 동작이 바뀌어 성능 트랙에서 측정과 함께 판단한다.

### §2 검증
- 1차(각 1회)
  - typecheck EXIT 0(error TS 0) · vitest 119 files / 827 passed(+5) · build EXIT 0
  - e2e smoke·help·password-change·claims 13 passed
  - 로컬 axe 390(/ · /mypage · /search?keyword=노트): lang=ko이고 앱 요소의 lang·대비 위반은 0이다. 남은 color-contrast 1·region 1은 dev 전용 nuxt-devtools-frame이다.
  - 390 DOM: 로고 91×44 · 수량 44×44 · 송장번호 복사 107×44
- 규칙 확장 뒤: typecheck EXIT 0 · category-theme vitest 1 file / 8 passed. build·e2e·axe는 순수 함수 규칙만 바뀌어 다시 돌리지 않았다.
- 캡처 docs/frontend/screens-track105-4g-2/(커밋 제외): home-categories 1440·820·390 · footer-notice-1440 · pdp-seller 1440·390 · products-header-390 · category-empty 390·1440
  - 빈 카테고리 캡처: 로컬에는 상품 0개 카테고리가 없다. 클라이언트 이동 때의 상품 조회만 page.route로 빈 목록으로 바꿔 찍었다.
- 테스트 추가·수정
  - categoryThemes 4: 인접 색 겹침 없음 · 다음 매핑 타일 회피 · 아이콘 순환 · 전체 타일 유지
  - LayoutShell 2: 본문 바로가기 · 푸터 준비 중 알림
  - 깨진 단언 2: category-theme 대체 아이콘 기대값 · LayoutShell routeMock에 path 추가

### §3 채점 감점 대응표(105-4g-1 기준)
| 축 | 감점 항목 | 점 | 이번 처리 |
|---|---|---|---|
| 접근성 | html lang 없음 | 3 | 조치 |
| 접근성 | 대비: 주문 현황 · 위험색 · 검색 눈썹 | 2·2·1 | 조치 |
| 접근성 | 44 미만: 로고 · 스테퍼 · 송장 복사 | 2·1·1 | 조치 |
| 접근성 | 스킵 링크 · 안내 면 aria-hidden | 1·1 | 조치 |
| 접근성 | 상품 카드 접근 이름 · 장바구니·주문 단계 이름 · landmark-unique | 2·1·1 | 이월 |
| 일관성 | 모달 임의 그림자 · 공용 3컴포넌트 · 타임라인 링 · 버터 버튼 | 2·2·1·1 | 조치 |
| 일관성 | 주문 탭 세그먼트 | 1 | 허용 패턴 등재 |
| 디자인 | 카테고리 타일 3건 · 빈 상태 톤 · 배송 정보 블록 | 3·2·1 | 조치 |
| 디자인 | 내부 주문 ID 노출 | 2 | 105-4g-3 |
| 디자인 | 상품명 break-keep | 1 | 이월 |
| 직관성 | 푸터 # 링크 · 셀러 알약 | 2·1 | 조치 |
| 직관성 | 모바일 탐색 줄 중복 | 2 | 목록·카테고리 조치(마이페이지 3단은 이월) |
| 직관성 | "클레임" 용어 | 1 | 이월 |
| 편의성 | 빈 카테고리 막다른 길 | 1 | 조치 |
| 편의성 | 클레임 상세 대상 품목 · 주문 일시·결제 수단 · 클레임↔주문 대조 | 2·1·1 | 105-4g-3 |
| 편의성 | 준비 중 2건(비밀번호 찾기·도움말) | 1·1 | 유지(FE-76 준비 중 기준) |
| 성능 | 모바일 LCP | 계산식 | 1차(prefetch) · 배포 후 재측정 |

### §8 이월
- 성능 트랙
  - 렌더 차단 CSS의 절반가량이 @font-face 94개(49,322B)다. 폰트 선언 분리·지연을 검토한다(FOUT 동작과 함께).
  - LCP 요소별 개선 후보: 히어로 h1 텍스트(폰트·렌더 차단) · 목록 첫 썸네일 · 상세 원본 png(srcset·sizes — FE-77 이월) · 로그인 입력칸.
  - 배포 후 운영 Lighthouse 재측정(prefetch 51 → 1의 효과 확인).
- 105-4g-3(BE+FE): 주문번호(orderNo — 주문 목록 응답에 필드 없음) · 주문 일시·결제 수단 · 클레임 대상 품목.
- 접근성: 상품 카드·장바구니 접근 이름 · landmark-unique(헤더 데스크톱 nav와 목록 탭 nav가 같은 "카테고리" 이름).
- 디자인·직관성: 상품명 break-keep · "클레임" 용어 통일 · 마이페이지 모바일 가로줄 3단.
- routeRules `/claims` → `/orders?tab=cancel` 옛 값(105-4g-1 대조 결과 감점 없음).
- 목록 배너 눈썹 "Category"의 `opacity-80`은 검색과 같은 패턴이지만 axe 통과라 유지했다.

외부 검토: C / 생략

## FE-83: 주문번호·주문 상세·클레임 대상 품목 연결 + 채점 이월 보완 5건 (Track 105-4g-3) (2026-09-25)

배경: FE-82 §8에서 넘긴 BE 필요 감점 3건(내부 주문 ID 노출 · 주문 상세 일시·결제 수단 없음 · 클레임 상세 대상 품목 없음)을 D-225 응답 필드로 연결하고, FE 이월 5건(상품 카드 접근 이름 · landmark-unique · 상품명 break-keep · "클레임" 용어 · 마이페이지 모바일 가로줄)을 처리했다.

결정:
- **주문번호 표시** 화면에는 사람이 읽는 `orderNo`만 보인다. 링크·라우팅·API 호출은 `orderId`(public_id)를 그대로 쓴다. `orderNo`가 없는 옛 응답이면 표시를 생략한다(내부 id로 대체하지 않는다).
  - 식별자 칩 한 곳: `skins/renew/order-no-chip.ts` `ORDER_NO_CHIP_CLASS`(기존 주문 목록 칩 클래스 · 모노 + surface-muted). 쓰는 곳: 주문 목록 카드 · 마이페이지 최근 주문 · 주문 상세 머리 · 주문 완료 · 클레임 상세 대상 품목.
  - 주문 완료: 쿼리의 `orderPublicId`로 기존 `useOrderDetail`을 조회해 orderNo를 쓴다. id가 없으면 조회하지 않고, 조회 실패·옛 응답이면 번호 줄만 뺀다(완료 안내·상세 링크는 그대로).
  - 주문 상세 탭 제목: `주문 {orderNo}` · orderNo가 없으면 "주문 상세".
- **주문 상세 머리** dl 3행 — 주문번호(칩) · 주문 일시(`formatDateTime` · tabular-nums) · 결제 수단(`paymentMethodLabel` — `PAYMENT_METHODS` 파생 · 모의 결제도 같은 수단 코드). 값이 없는 행은 생략한다(미결제 = BE가 payment 생략).
- **클레임 상세 대상 품목** 맨 위 흰 카드 — 썸네일(없으면 이미지 대기 면) · 상품명(break-keep) · 옵션·수량 · 주문번호 칩 · "주문 상세 보기" `btn-tertiary`(→ `/orders/{orderId}`). 대상 품목이 없는 옛 응답이면 카드를 생략한다.
- **상품 카드 접근 이름** 링크를 상품명에만 걸고 `after:absolute after:inset-0`으로 카드 전체를 누르게 넓혔다(주문 목록 상품명 링크와 같은 방식). 접근 이름 = 보이는 상품명이고 셀러·가격은 링크 밖 일반 텍스트라 중복 낭독이 없다. 포커스 링은 넓힌 영역(after)에 그려 카드 전체에 보인다. 이미지는 이름과 겹쳐 `alt=""`. testid `product-card`는 카드 div에 그대로 둔다. 적용: RenewProductCard · 홈 셀러 픽.
  - 홈 히어로 타일(`HomeView` 히어로)은 보이는 상품명 없이 이미지만 있는 링크라 `aria-label`=상품명이 유일한 이름이다 — 불변.
- **landmark-unique** 목록 카테고리 탭 nav 이름을 "카테고리" → "목록 카테고리"로 바꿨다. 헤더의 데스크톱·모바일 nav 2개는 lg 경계로 동시에 보이지 않아 같은 이름을 유지한다.
- **상품명 줄바꿈** 상품 카드 이름에 `break-keep`(단어 중간 끊김 방지).
- **"클레임" 용어** 구매자 화면 문구를 "취소·반품·교환"으로 바꿨다. URL·코드·testid·관리자·셀러 화면은 그대로다.
  - 교체 목록(11): 클레임 상세 제목 · 요약 aria-label · "클레임 유형" 라벨 · "클레임 정보" 제목 / 요청 화면 제목 폴백 · 잘못된 접근 안내 · 접수 완료 문구 / 클레임 상세 404·로드 실패·요청 취소 404 문구 · 탭 제목 / 요청 실패 문구 · 요청 탭 제목 / 주문 내역 취소·반품·교환 탭 로드 실패 / 진행 중 요청 중복 안내(claim-request-error `message` — `match`는 BE detail 문자열이라 불변)
  - 불변: `useClaim` 내부 Error 문구(화면에 나오지 않음)
- **마이페이지 모바일 가로줄** `LayoutShellVm.hasListingTabs`를 목적에 맞게 `hasPageTabRow`(화면 자체 탭 줄이 있는 화면)로 이름을 바꾸고 경로를 넓혔다: `/products` · `/categories/*`(FE-82) + `/mypage` · `/mypage/*` · `/orders` · `/orders/*` · `/claims/*`(MypageFrame을 쓰는 9화면). 768 미만에서 헤더 카테고리 줄을 숨겨 390 마이페이지 가로줄이 3단 → 2단(헤더 · 메뉴 칩)이 됐다.
- **FE-82 대비 표기 재계산 일치**: #B0245A / 라벤더 #E3DAF5 = 4.81(흰 6.47 · surface-muted 5.52 · 핑크 5.05도 일치) — 정정 없음.

### §1-A 갈림길·채택/기각 근거
- **상품 카드 접근 이름: 상품명 링크 + after 오버레이(채택) / aria-label 제거 + 이미지 alt=""(기각)** 후자는 구조 변경이 가장 작지만 이름이 "셀러 상품명 가격 원"으로 길게 읽힌다.
- **주문 완료 주문번호: 주문 상세 조회로 orderNo 표시(채택) / 번호 줄 제거(기각)** 완료 화면이 클레임·주문 목록과 같은 번호로 대조할 수 있는 첫 지점이다.

### §2 검증
- typecheck EXIT 0(error TS 0) · vitest 121 files / 836 passed · e2e smoke·claims·mock-payment·order-resume-payment 11 passed
- 테스트 추가·수정
  - 신규: OrdersPage 1(칩 = orderNo · 없으면 생략) · OrderDetailPage 2(머리 3행 / 옛 응답·미결제 생략) · ClaimDetailPage 3(대상 품목 카드 · 썸네일·옵션 없음 · 옛 응답 카드 생략·제목) · payment-method-label 2
  - 깨진 단언 2(의도 유지): smoke 첫 카드 href → 카드 안 링크의 href · mock-payment 완료 화면 주문번호 = orderNo(주문 상세 GET 픽스처 추가)
- 캡처 docs/frontend/screens-track105-4g-3/(커밋 제외): mypage · orders · order-detail · orders-claim · claim-detail · checkout-complete × 1440·390 — 전부 200 · 가로 넘침 0 · 화면 텍스트 `ord_` 0. 1440 6장은 대체 글꼴로 찍혔다(390 정상 · 원인 미확인 · 캡처 도구 쪽).

### §3 채점 감점 대응표(105-4g-1 기준 · FE-82 이월분)
| 축 | 감점 항목 | 점 | 이번 처리 |
|---|---|---|---|
| 디자인 | 내부 주문 ID 노출 | 2 | 조치 |
| 디자인 | 상품명 break-keep | 1 | 조치 |
| 접근성 | 상품 카드 접근 이름 · landmark-unique | 2·1 | 조치 |
| 접근성 | 장바구니·주문 단계 링크 이름(숫자) | 1 | 이월 |
| 직관성 | 마이페이지 모바일 가로줄 3단 · "클레임" 용어 | 2(일부)·1 | 조치 |
| 편의성 | 클레임 상세 대상 품목 · 주문 일시·결제 수단 · 클레임↔주문 대조 | 2·1·1 | 조치 |

### §8 이월
- 접근성: 헤더 장바구니·마이페이지 주문 단계 링크 접근 이름에 숫자 없음(105-4g-1 1점).
- 클레임 목록의 주문번호(`· 주문 {orderNo}`)는 캡션 평문 그대로다(칩 통일은 범위 밖).

외부 검토: B / 생략(D-225와 같은 트랙)

## FE-84: 송장번호 입력 형식 검증·오류 표시 (관리자·셀러·구매자 회수 송장 · D-227) (2026-09-26)

배경: D-227(송장 전역 유니크 제거 · 형식 규칙 · 정정 409 제거)을 입력 화면에 맞춘다. 운영 사건에서 관리자는 500 뒤 원인을 알 수 없었다.

결정:
- **규칙·문구 한 곳**: base `app/lib/constants/delivery.ts` `DELIVERY_TRACKING_NO_PATTERN`(`/^[A-Za-z0-9-]{8,20}$/`)·`DELIVERY_TRACKING_NO_FORMAT_MESSAGE`(BE 필드 메시지와 같은 문구). 레이어끼리 import할 수 없어 base에 둔다. 입력 상한 `DELIVERY_TRACKING_NO_MAX` 100 → 20, 레이어 상수(`ADMIN_ORDER_TRACKING_NO_MAX`·`SELLER_TRACKING_NO_MAX`)는 base 값을 그대로 쓴다(maxlength·counter).
- **제출 전 검증**: 앞뒤 공백 제거 후 빈 값은 기존 "송장번호를 입력하세요."(재발송은 "재발송 송장번호를 입력하세요."), 그 외 위반은 공통 문구. 적용: 관리자 발송 · 교환품 발송 · 회수 송장 대행 · 검수 재발송 · 송장 정정 / 셀러 발송 · 송장 정정 / 구매자 회수 송장(빈 값도 공통 문구 — 입력은 native required).
- **서버 400 표시**: 관리자·셀러 다이얼로그는 기존 `mapFieldErrors`로 fieldErrors 문구를 입력칸 아래에 표시한다(변경 없음 · 다이얼로그 유지 · 재시도 없이 보임). 구매자 회수 송장은 400이면 `trackingNoFieldError`로 서버 문구를 안내 박스에 쓰고, 필드 오류가 없으면 기존 "택배사와 송장번호를 확인하세요." 500 등 예상 밖 오류는 기존 공통 문구 그대로다.
- **409 제거**: 정정 다이얼로그 2종의 `DELIVERY_TRACKING_NO_CONFLICT` 분기와 관리자·셀러 오류 문구 매핑을 지웠다(BE가 더 이상 내지 않음).

### §2 검증
- typecheck EXIT 0(error TS 0) · vitest 121 files / 839 passed(+3) · e2e admin-orders·seller-orders·seller-deliveries·admin-claims·claims 25 passed(첫 실행 22 + 각 spec ① 3건 콜드 트랩 · 단독 재실행 통과)
- 테스트: 신규 seller-write-dialogs 1(클라이언트 형식 문구·API 미호출 / 서버 400 fieldErrors 문구 입력칸 표시·토스트 없음) · claim-return 2(패턴 · `trackingNoFieldError`) · 수정 seller-write-dialogs 정정 409 → 400 fieldErrors · e2e seller-deliveries ② 409 mock → 클라이언트 문구(PATCH 0) → 서버 400 fieldErrors 문구 · 헬퍼 spec 4종 100자 단언 → 형식 단언 · seller-error-message 409 단언 제거. 입력값 교체 목록은 D-227 §2.
- 캡처 docs/frontend/screens-d227/(커밋 제외): 관리자 발송 · 셀러 발송 · 구매자 회수 송장 형식 오류 1440.

외부 검토: B / 생략(D-227과 같은 트랙)
