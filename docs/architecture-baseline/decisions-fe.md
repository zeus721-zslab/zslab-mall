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
- FE-11 체크아웃
- FE-12 주문
- FE-13 계정
- FE-14 클레임
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

