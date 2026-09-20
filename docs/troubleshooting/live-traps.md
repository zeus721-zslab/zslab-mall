# Live Traps Catalog

> 라이브 발견 트랩 카탈로그·후속 트랙 진입 전 정독 의무 SoT
> 박제 임계: ≥3건 누적 시 신설 (D-82 정합·운영 first → repetition → promote 원칙)
> 출처: CLAUDE.md "라이브 트랩 방지" 룰·D-82

---

## 목적

- CI·단위 테스트로 탐지 불가하고 라이브 실행·후속 테스트 실행 시점에만 표면화하는 트랩 영구 추적
- 후속 트랙 진입 시 동일 트랩 재발 방지 (정독 의무)
- 단건 트랩은 decisions.md D-XX 박제 유지·≥3건 누적 시 본 카탈로그 신설·기존 단건 결정은 [ARCHIVED] 라벨 후 본 문서로 이관 (D-82 정합)

## 정독 의무 시점

- Track 7 Batch-3 진입 전 (LT-03 영향 7 Entity)
- Track 8+ Application Service 트랙 진입 전 (LT-01·LT-02 후속 영향)
- 신규 라이브 트랩 발견 시 즉시 본 문서 갱신

---

## LT-01. CHAR(N) public_id @JdbcTypeCode 미적용 시 Hibernate VARCHAR 매핑 [ACTIVE]

**발견 트랙**: Track 2 Order Aggregate
**원본 결정**: D-26 [ARCHIVED]

### 증상
public_id 컬럼이 DDL에서 `CHAR(30)`으로 정의되어 있으나 Hibernate가 기본적으로 VARCHAR로 매핑·DDL과 부정합·후속 마이그레이션 충돌 가능.

### 재현
```java
// 트랩 (잘못된 매핑)
@Column(name = "public_id", length = 30, nullable = false, updatable = false)
private String publicId;
```
→ Hibernate가 VARCHAR(30)으로 처리·CHAR(30) 우측 공백 패딩 의미 미반영.

### 처치
```java
@JdbcTypeCode(SqlTypes.CHAR)
@Column(name = "public_id", length = 30, nullable = false, updatable = false)
private String publicId;
```

### 후속 영향
- AbstractPublicIdFullAuditableEntity·AbstractPublicIdSoftDeletableEntity 본문에 @JdbcTypeCode 선언 → 상속 Entity 자동 적용
- 신규 public_id 컬럼 추가 시 abstract 미상속 케이스에서 동일 처치 의무

### 관련
- 원본: D-26 (decisions.md [ARCHIVED])
- abstract 적용 Entity: Order·OrderItem·Payment·Delivery·Claim·Refund·User·Seller·Product·ProductVariant·Attachment

---

## LT-02. Testcontainers SET FOREIGN_KEY_CHECKS HikariCP 잔류 [ACTIVE]

**발견 트랙**: Track 6 PR-A OrderTransactionRollbackTest
**원본 결정**: D-79 [ARCHIVED]

### 증상
invalid FK item 시딩을 위해 `SET FOREIGN_KEY_CHECKS=0` 사용 시 HikariCP 커넥션 풀에 세션 변수 잔류·**후속 테스트에서 FK 비활성 오염** 발생.
CI 미탐지 (단독 실행 시 통과)·다중 테스트 순차 실행 시에만 표면화.

### 재현
```java
// 트랩 (복원 누락)
entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS=0").executeUpdate();
entityManager.persist(invalidFkEntity);
// SET FOREIGN_KEY_CHECKS=1 복원 누락 → HikariCP 커넥션 반환 시 변수 잔류
```

### 처치
```java
try {
    entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS=0").executeUpdate();
    // seed·cleanup 작업
} finally {
    entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS=1").executeUpdate();
}
```
`SET FOREIGN_KEY_CHECKS=0` 사용 시 동일 트랜잭션·동일 커넥션 내 1:1 복원 짝 의무 (try-finally 또는 동등 구조).

### 후속 영향
- 전 Testcontainers 기반 통합 테스트
- @DataJpaTest·@SpringBootTest 양쪽 동일 적용

### 관련
- 원본: D-79 (decisions.md [ARCHIVED])

---

## LT-03. @SQLRestriction @MappedSuperclass → @Entity 비전파 (HHH-17453) [ACTIVE]

**발견 트랙**: Track 7 Batch-1 (Category 구현)
**원본 결정**: D-82 본문 §3

### 증상
`@SQLRestriction("deleted_at IS NULL")`이 abstract @MappedSuperclass 클래스에 선언되어 있으나 **Hibernate 6.6에서 @Entity 서브클래스로 전파되지 않음** (HHH-17453 버그).
→ soft-delete된 행이 `findAll()`·`findById()`에 노출·@SQLRestriction 무효.

### 재현
```java
// 트랩 (abstract 클래스만 선언)
@MappedSuperclass
@SQLRestriction("deleted_at IS NULL")
public abstract class AbstractSoftDeletableEntity extends AbstractFullAuditableEntity {
    // deleted_at·deleted_by·delete_reason 필드
}

// @Entity 서브클래스
@Entity
@Table(name = "category")
public class Category extends AbstractSoftDeletableEntity {
    // @SQLRestriction 전파 안됨·soft-delete 무효
}
```

### 처치
@Entity 서브클래스에 **@SQLRestriction 직접 선언**:
```java
@Entity
@Table(name = "category")
@SQLRestriction("deleted_at IS NULL")  // 직접 선언 의무
public class Category extends AbstractSoftDeletableEntity {
    // ...
}
```

### 후속 영향 (Batch-3 필수 처치)
AbstractSoftDeletableEntity·AbstractPublicIdSoftDeletableEntity 상속 Entity 전원 직접 선언 의무:

| Entity | 상속 abstract | 처치 |
|---|---|---|
| Category | AbstractSoftDeletableEntity | ✓ 완료 (Track 7 Batch-1) |
| UserAddress | AbstractSoftDeletableEntity | ✓ 완료 (Track 7 Batch-3a) |
| ProductImage | AbstractSoftDeletableEntity | ✓ 완료 (Track 7 Batch-3c) |
| User | AbstractPublicIdSoftDeletableEntity | ✓ 완료 (Track 7 Batch-3a) |
| Seller | AbstractPublicIdSoftDeletableEntity | ✓ 완료 (Track 7 Batch-3b) |
| Product | AbstractPublicIdSoftDeletableEntity | ✓ 완료 (Track 7 Batch-3c) |
| ProductVariant | AbstractPublicIdSoftDeletableEntity | ✓ 완료 (Track 7 Batch-3c) |
| Attachment | AbstractPublicIdSoftDeletableEntity | ✓ 완료 (Track 7 Batch-3c) |

### 검증 방법
@DataJpaTest 케이스 필수 1건: "soft-delete 후 findById Optional.empty" (Category 기 적용).

### 관련
- 원본: D-82 (decisions.md ACTIVE·본 카탈로그 신설 결정)
- 외부 참조: Hibernate HHH-17453

---

## LT-04. AFTER_COMMIT 핸들러 시점에 동기 upstream 상태 전이 완료로 item_status 기반 skip 가드 데드코드 [ACTIVE]

**발견 트랙**: Track 17 PR-B 구현 진입 전 정찰 (Claude Code 실측)
**원본 결정**: D-101 §6 갱신 (2026-07-01)

### 증상
AFTER_COMMIT 핸들러의 "item_status == <종결값>이면 skip" 1차 가드가 정상 흐름에서 항상 skip 판정·핸들러 본문 영구 미실행.

### 재현
- 발행처 Service가 이벤트 발행 후 동일 트랜잭션 내 동기 소비 핸들러(@EventListener)가 upstream 상태 전이 수행 (예: OrderEventHandler.markPaid → OrderItem PAID 전이)
- 발행 트랜잭션 커밋 후 AFTER_COMMIT 핸들러 실행 시점에 모든 대상 이미 종결 상태 진입
- "item_status == 종결값이면 skip" 가드 → 항상 skip → 핸들러 본문 데드코드

### 처치
1차 가드 제거 + 재전달 방어선 이중화 (upstream UNIQUE 제약 + at-most-once publisher + 도메인 invariant backstop)

### 후속 영향
- 신규 AFTER_COMMIT 핸들러 신설 시 동기 upstream 상태 전이 여부 실측 의무
- 상태 기반 가드 대신 History 기반 또는 도메인 invariant 단독 방어 우선 검토
- 통합 테스트에서 동기 upstream 실행 경로 실증 의무 (라이브 트랩 방지 회귀 안전망)

### 검증 방법
통합 테스트 1건 필수: "동기 upstream 상태 전이 후 AFTER_COMMIT 핸들러 본문 실제 실행 검증" (Track 17 T2 SoT).

### 관련
- 원본: D-101 §6 갱신 (decisions.md ACTIVE)
- 관련 결정: D-75 (AFTER_COMMIT + REQUIRES_NEW)·D-100 Q1 γ (멱등 패턴 카탈로그)·D-100 Q2 γ (인메모리 publisher)

---

## LT-05. AFTER_COMMIT 형제 핸들러 실행 순서 비결정으로 상태 기반 skip 가드 누락 부작용 [ACTIVE]

**발견 트랙**: Track 17 PR-B 구현 진입 전 정찰 (Claude Code 실측)
**원본 결정**: D-101 §6 갱신 (2026-07-01)

### 증상
동일 이벤트를 소비하는 AFTER_COMMIT 형제 핸들러 2건 이상 존재·`@Order` 미부여 시 실행 순서 비결정. 상태 기반 skip 가드는 형제 핸들러가 먼저 실행되어 상태 전이 완료 시 후속 핸들러 부작용 누락.

### 재현
- 이벤트 E 소비 핸들러 A·B 양자 AFTER_COMMIT
- 핸들러 A: OrderItem *_REQUESTED → 종결 상태 전이
- 핸들러 B: "item_status 종결값이면 skip" 가드 보유 (재고 복구 등)
- 순서가 A → B로 잡히면 B skip → 재고 복구 누락
- 순서가 B → A로 잡히면 B 정상 실행 → 정상 복구
- 순서 비결정적·CI 통과 후 라이브에서 산발 실패

### 처치
상태 무관 멱등 가드로 대체:
- History 존재 조회 (`existsByReferenceTypeAndReferenceId`) 등 순서 독립 가드
- 형제 핸들러가 세팅하는 상태 필드에 비의존

### 후속 영향
- 동일 이벤트 소비 AFTER_COMMIT 핸들러 2건 이상 신설 시 순서 종속성 실측 의무
- `@Order` 강제는 D-100 Q9 γ "AFTER_COMMIT 순서 비보장" 박제 위배·비권장
- 통합 테스트에서 재발행 멱등 시나리오 실증 의무 (Track 17 T5 SoT)
- [Track 67·D-151 단서] 동기 소비자를 가진 이벤트에는 "재발행 멱등 실증"이 부적합할 수 있음. PaymentCompleted는 동기 형제 OrderEventHandler.markPaid(@EventListener·동일 TX·ORDERED→PAID 단방향)가 재발행 2회차를 거부→TX 롤백→AFTER_COMMIT 형제 미발화시키므로, AFTER_COMMIT 형제(CartPaymentCompletedHandler 등)의 재발행 멱등은 프로덕션 도달 불가 경로. 이 경우 실 이중발행 통합 테스트는 부적합(동기 형제와 충돌)이며, 멱등 검증은 단위(handle 직접 호출)로 격리하거나 도달 불가면 생략한다. HARD DELETE 상태 무관 멱등 논지 자체는 유지.

### 검증 방법
통합 테스트 1건 필수: "동일 이벤트 재발행 시 형제 핸들러 순서 무관 멱등 검증" (History 기반 skip 실증·Track 17 T5 SoT). 단, 동기 소비자를 가진 이벤트(예: PaymentCompleted·D-151)는 재발행이 동기 형제에서 거부되어 AFTER_COMMIT 형제가 미발화하므로 실 재발행 통합 테스트를 적용하지 않는다(단위 격리 또는 생략).

### 관련
- 원본: D-101 §6 갱신 (decisions.md ACTIVE)
- 관련 결정: D-75 (AFTER_COMMIT + REQUIRES_NEW)·D-100 Q9 γ (핸들러 순서 비보장)·D-100 Q1 γ (멱등 패턴 카탈로그·B-2 History 기반 신설 근거)·D-151 (Track 67·PaymentCompleted 동기 형제 markPaid 얽힘·재발행 도달 불가)

---

## LT-06. permitAll 경로도 무효 Bearer 토큰 동봉 시 401 [ACTIVE]

**발견 트랙**: FE-65 정찰 (/api/v1/products 공개 GET 401 규명·Claude Code HTTP 실측)
**원본 결정**: docs/frontend/recon-report-65.md (로컬)

### 증상
permitAll 공개 GET 경로라도 요청에 만료·타-환경 Bearer 토큰이 실리면 200이 아니라 401(code=UNAUTHENTICATED, RFC7807)로 응답. 무헤더·비-Bearer는 정상 200.

### 재현
JwtAuthenticationFilter가 AuthorizationFilter(permitAll 판정)보다 앞에 위치. Bearer 프리픽스가 있으면 permitAll 경로 예외 없이 tokenProvider.verify() 무조건 호출 → 실패 시 AuthenticationException 전파 → ExceptionTranslationFilter가 SecurityErrorHandler로 401 위임.

### 처치
FE API 클라이언트가 공개 카탈로그 GET에 Authorization 헤더를 부착하지 않도록 조정(인터셉터 공개 엔드포인트 화이트리스트). backend 무변경.

### 후속 영향
- FE 인증 인터셉터·토큰 부착 로직 구축 시 공개 엔드포인트 화이트리스트 필수.
- "permitAll인데 401" 관측 시 소스 인가 결함이 아니라 클라이언트 토큰 동봉부터 의심.

### 관련
- recon-report-65 (로컬)·SecurityConfig(GET /api/v1/products/** permitAll)·JwtAuthenticationFilter(ETF 뒤 배치)

---

## LT-07. 컨테이너명 언더스코어 → 임베디드 Tomcat Host 검증 400 (SSR 직결 시) [RESOLVED]

**발견 트랙**: FE-03 STEP 6 검증 (홈 SSR 최초 실 소비·Claude Code HTTP 실측)
**원본 결정**: FE-02 §1-A 3 'SSR 직결' 전제 결함 → FE-03에서 별칭 우회

### 증상
Nuxt SSR(undici 서버 fetch)이 도커 내부 backend로 직결할 때, 대상 호스트명에 언더스코어(_)가 포함되면(예 zslab_mall_backend:8080) Spring 도달 전 임베디드 Tomcat 커넥터가 HTML 400을 반환. 게이트웨이 경유는 nginx가 언더스코어 없는 Host를 전달해 200이라 CI·게이트웨이 테스트로는 미탐지.

### 재현
- 프론트 컨테이너 → http://zslab_mall_backend:8080/... : 400 (Tomcat HTML, Host 검증 거부)
- 동일 대상에 Host: localhost / 도메인 지정 : 200
- undici는 Host가 Fetch 표준 금지 헤더라 override 불가 → 클라이언트 코드로는 교정 불가

### 처치
backend 서비스에 언더스코어 없는 네트워크 별칭 부여, SSR base를 별칭으로 교체.
- docker-compose.mall.yml: zslab_mall_backend.networks(map) gateway_net.aliases: [mall-backend]
- docker-compose.dev.yml: API_INTERNAL_BASE 기본값 http://mall-backend:8080
- backend 코드·nuxt.config·composable·gateway·nginx 무변경.

### 후속 영향
- 신규 컨테이너가 SSR·서버간 직결 대상이 되면 호스트명 언더스코어 회피(별칭 부여) 의무.
- prod frontend 서비스 추가 시 동일 별칭 경로 사용(근본 조치라 재사용).
- "게이트웨이는 200인데 SSR 직결만 400" 패턴 관측 시 Host 검증(언더스코어)부터 의심.

### 관련
- FE-02 §1-A 3(SSR 직결 결정)·§2(recreate DNS 재해석 트랩)·FE-03 §2

---

## LT-08. Tailwind v4 마이그레이션 (@theme·유틸·Preflight·배선) [ACTIVE]

**발견 트랙**: FE-08 Tailwind v3.4.19 → v4 마이그레이션 (Claude Code 생성 CSS 실측)
**원본 결정**: decisions-fe.md FE-08 §2

### 증상
v3→v4 전환 시 다음 4점이 CI·빌드 성공만으로는 드러나지 않고 생성 CSS 실측·실행 시점에만 표면화.

1. @theme `--duration-*` 변수는 named duration 유틸(duration-fast/normal/slow)을 생성하지 않는다(v4 내장은 duration-<number>만) → FE-07 transition 토큰 무효(전환속도 폴백).
2. outline-none이 v4에서 의미 변경(v3=투명 2px outline / v4=outline-style:none) → forced-colors 대비 동작 상실.
3. v4 Preflight는 button에 cursor:pointer를 부여하지 않는다(v3는 부여) → 인터랙티브 버튼 커서가 default.
4. @nuxtjs/tailwindcss 정식 라인(6.x)은 v4 미지원(tailwindcss ~3.4.x 고정)·v4 지원은 alpha/7.0.0-beta뿐.

### 처치
1. @utility duration-fast/normal/slow로 `--tw-duration` + transition-duration 미러링(내장 duration-<number> 구조 복제) 복원.
2. outline-none → outline-hidden(v3 동작 등가·공식 @tailwindcss/upgrade codemod 대응).
3. @layer base로 `button:not(:disabled), [role="button"]:not(:disabled) { cursor: pointer }` 복원.
4. v4는 @tailwindcss/vite로 배선(Nuxt vite.plugins)·@nuxtjs/tailwindcss 제거·main.css 진입점 @import "tailwindcss".

### 후속 영향
- 후속 v4 작업(FE-09 shadcn-vue 등)·v4 마이너 업데이트 시 위 4점 재점검. 특히 @utility duration 미러링은 내장 duration 구조 변화에 취약.
- named @theme 값이 유틸을 실제 생성하는지 생성 CSS로 실측(dev + prod 빌드 패리티) 의무.

### 관련
- decisions-fe.md FE-08 §1-A/§2·recon-report-72(로컬)·frontend/app/assets/css/main.css(@theme·@utility·@layer base)

---

## LT-09. pnpm store 교차-디바이스 → verifyDepsBeforeRun purge로 컨테이너 Exited [RESOLVED]

**발견 트랙**: FE-09 STEP 1 shadcn-vue 도입 (컨테이너 재기동 실측)
**원본 결정**: decisions-fe.md FE-09 §1-A 6

### 증상
pnpm store가 바인드-마운트(호스트 파일시스템 device)에 위치하면 node_modules(컨테이너 익명 볼륨 device)와 교차-디바이스가 되어 하드링크·무결성 검증이 실패. 재기동 시 pnpm verifyDepsBeforeRun이 node_modules purge를 시도하는데 no-TTY 환경이라 확인 프롬프트에서 컨테이너가 Exited(1). 1회 기동/CI만으로는 미표면화(재기동·deps 변경 시점에만 발현).

### 처치
storeDir을 node_modules 익명 볼륨 내부로 이동(/app/node_modules/.pnpm-store) → store와 node_modules가 동일 device → 하드링크·검증 정합. 빌드타임 install과 런타임이 같은 파일시스템을 봐 purge 트리거 소멸.
- 기각: verifyDepsBeforeRun:false 마스크 — 검증 자체를 끄는 증상 은폐라 근본 아님.

### 후속 영향
- 컨테이너에서 pnpm store 위치 지정 시 익명/named 볼륨 내부에 둘 것. 호스트 바인드-마운트 store 금지.
- "재기동 후 UNEXPECTED_STORE·REMOVE_MODULES·ELIFECYCLE Exited" 패턴 관측 시 store device 경계부터 의심.

### 관련
- decisions-fe.md FE-09 §1-A 6·frontend/pnpm-workspace.yaml(storeDir)

---

## LT-10. shadcn-vue + Nuxt4 "Failed to resolve extends base type" (SFC 타입 리졸버) [RESOLVED]

**발견 트랙**: FE-09 STEP 1 Button 스캐폴드 (SSR 컴파일 500 실측)
**원본 결정**: decisions-fe.md FE-09 §1-A 5

### 증상
공식 shadcn-vue Button.vue의 `interface Props extends PrimitiveProps`(reka-ui)가 SSR 컴파일 시 "Failed to resolve extends base type"으로 홈 500. 에러 메시지가 원인을 직접 가리키지 않아(외부 타입 extends 미해결) 버전 상호작용 문제로 오진하기 쉬움. 실제 원인은 @vue/compiler-sfc 타입 리졸버가 typescript 부재 시 외부 패키지의 extends된 타입을 해석하지 못함.

### 처치
typescript를 devDependency로 설치 → SFC 타입 리졸버가 extends 체인을 해석 → Button.vue 무수정으로 컴파일 정상.
- 기각: reka-ui/vue 버전 도박·build.transpile 우회·Button.vue props 타입 인라인(스캐폴드 수정)·스모크 이연 — 전부 근본 아님.

### 후속 영향
- shadcn-vue(reka-ui) 컴포넌트를 Nuxt4 SFC에서 쓸 때 typescript devDep 필수(외부 타입 extends 해석 전제).
- "Failed to resolve extends base type" 관측 시 버전 조정 전에 typescript 설치 여부부터 확인.

### 관련
- decisions-fe.md FE-09 §1-A 5·frontend/package.json(typescript devDep)·frontend/app/components/ui/button/Button.vue

---

## LT-11. Nuxt auto-import 신규 대상(store/plugin/composable) 미스캔 → "is not defined" [RESOLVED]

**발견 트랙**: FE-09 STEP 3 Header가 store 첫 소비 (SSR 500 실측)
**원본 결정**: decisions-fe.md FE-09 §2

### 증상
신규 store/plugin(및 composable)을 파일만 추가하고 dev 서버를 재기동하지 않으면, 부팅 시점에 해당 디렉토리가 비어(.gitkeep) 있던 경우 런타임 unimport 스캔에 미포함 → 소비 컴포넌트 렌더 시 "useXxxStore is not defined" 500. 타입 레지스트리(.nuxt/imports.d.ts)에는 등록돼 tsc는 통과하므로 tsc·타입검사만으로는 런타임 미스캔을 못 잡음. 소비처가 없던 동안(STEP 2)엔 잠복.

### 처치
auto-import 대상(store·plugin·composable) 추가 후 컨테이너(dev 서버) 재기동으로 unimport 재스캔 → 런타임 주입 정합. 추가 직후 실제 소비 페이지를 HTTP 실측(200·"is not defined" 부재)해 확인.

### 후속 영향
- auto-import 디렉토리에 신규 파일 추가 시 재기동 + 페이지 실측을 완료 게이트에 포함(tsc GREEN만으로 완료 처리 금지).
- 부팅 시 비어있던(.gitkeep) 디렉토리에 첫 파일 추가 시 특히 주의(watcher가 신규 등록을 놓칠 수 있음).

### 관련
- decisions-fe.md FE-09 §2·frontend/app/stores/·frontend/app/plugins/

---

## LT-12. Pinia setup store 접근 시 ref 자동 언랩 → `.value` 접근 시 undefined [RESOLVED]

**발견 트랙**: FE-10b P2 cart.vue useAsyncData SSR 렌더 (에러 상태 오렌더 실측)
**원본 결정**: decisions-fe.md FE-10b §2

### 증상
Pinia setup store가 반환한 ref(예 `items = ref<CartItemView[]>([])`)를 store 인스턴스로 접근(`cart.items`)하면 자동 언랩돼 이미 배열이다. 컴포저블 관습대로 `cart.items.value.length`로 접근하면 `.value`가 undefined → `.length`에서 TypeError. cart.vue의 useAsyncData 핸들러에서 발생 → SSR이 예외를 잡아 에러 상태를 렌더(GET /cart·load 자체는 성공·payload엔 품목 존재). tsc는 통과 → SSR HTML 실측으로만 진단됨.

### 처치
setup store 인스턴스 접근 시 `.value` 금지 — `cart.items.length`(언랩된 배열 직접). store 내부(setup 함수 안)에선 `.value` 필요하나, 외부(컴포넌트·페이지)에서 store.prop 접근은 이미 언랩. storeToRefs 구조분해 시에도 언랩된 ref 반환.

### 후속 영향
- setup store의 ref/computed를 컴포넌트/페이지에서 쓸 때 `store.prop`(언랩)·`store.prop.value`(X) 구분.
- SSR 데이터 핸들러(useAsyncData/useFetch 콜백)에서 store 접근 시 특히 주의 — 예외가 에러 상태 오렌더로 나타나 원인이 가려짐. tsc 미포착 → SSR HTML 실측 필요.

### 관련
- decisions-fe.md FE-10b §2·frontend/app/pages/cart.vue·frontend/app/stores/cart.ts

---

## LT-13. webhook occurredAt(LocalDateTime·무 Z) vs checkout expiresAt(Z 포함) 포맷 비대칭 → Z 붙이면 400 [ACTIVE]

**발견 트랙**: FE-11 STEP 2 모의 PG webhook 배선 (3경로 200 실증)
**원본 결정**: decisions-fe.md FE-11 §1-A 5·§8

### 증상
결제 webhook POST /api/webhooks/payments의 occurredAt은 timezone 없는 LocalDateTime으로 역직렬화된다. FE가 관습적으로 `new Date().toISOString()`(예 "2026-07-09T10:00:00.000Z")로 보내면 트레일링 Z 때문에 LocalDateTime 파싱 실패 → 400. 같은 결제 흐름의 checkout 응답 expiresAt은 Z 포함 포맷("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")이라, 한 흐름 안에서 두 시각 필드의 포맷 규약이 비대칭이다. 이 비대칭을 모르면 expiresAt 포맷을 webhook에 그대로 재사용해 400을 맞는다.

### 처치
webhook occurredAt 전송 시 Z 제거: `new Date().toISOString().slice(0,23)` → "2026-07-09T10:00:00.000"(밀리초까지·Z 없음). SUCCESS/FAILURE/CANCEL 3경로 전건 200 실증. checkout expiresAt(Z 포함)과 혼동 금지 — 두 필드는 서로 다른 포맷 규약.

### 후속 영향
- 결제/webhook 계약에 시각 필드 전송 시 대상 타입 확인: LocalDateTime(무 Z)·Instant/ZonedDateTime(Z 포함) 구분. 한 흐름에 두 규약 공존 가능.
- FE에서 시각 직렬화 시 `.toISOString()` 무비판 사용 금지 — 서버 파싱 타입에 맞춰 Z 유무 결정.

### 관련
- decisions-fe.md FE-11 §1-A 5·§8·frontend/app/composables/useCheckout.ts(sendPaymentCallback occurredAt)·POST /api/webhooks/payments 계약

---

## LT-14. backend stale-class — compileJava UP-TO-DATE 오판 + gradle named volume → 부분 클래스 부팅·NoSuchMethodError [ACTIVE]
**발견 트랙**: Track 68/69 dev 런타임
**원본 결정**: 세션 트랩(decisions.md 미박제·본 카탈로그 직접 등록)
### 증상
소스는 정합·CI 통과인데 dev 런타임만 깨진다. gradle named volume 캐시가 compileJava를 UP-TO-DATE로 오판해 변경 클래스가 재컴파일되지 않고, 구 클래스와 신 클래스가 섞여 부팅되어 NoSuchMethodError 등으로 나타난다. 소스만 보면 원인이 안 보인다.
### 처치
컨테이너 내 backend/build/classes 삭제 후 클린 재컴파일 강제. 재기동 시 전체 재컴파일로 정합 복원.
### 후속 영향
- "소스 정합·dev 런타임 미반영·CI 통과·라이브만 터짐" 패턴. 신규/변경 클래스 미반영 의심 시 build/classes 잔재 우선 확인.
- dev-up.ps1에 clean 컴파일 보장 추가 검토(후보).
### 관련
- Track 68/69 dev 세션·backend/build/classes

---

## LT-15. frontend 신규 라우트·디렉토리 Nuxt watcher 미인식 [ACTIVE]
**발견 트랙**: FE-14 claims/ 신규 디렉토리
**원본 결정**: 세션 트랩(본 카탈로그 직접 등록)
### 증상
신규 라우트 디렉토리(예 claims/)를 만들어도 Nuxt 파일워처가 인식하지 못해 라우트가 404·미반영. 소스는 정합하나 dev 런타임만 신규 경로를 모른다.
### 처치
프론트엔드 컨테이너 restart로 워처 재기동·라우트 재스캔. (docker-compose.dev.yml frontend restart: unless-stopped와 별개로 신규 디렉토리는 수동 restart 필요.)
### 후속 영향
- 신규 페이지/라우트 디렉토리 추가 후 404면 워처 미인식 의심 → 컨테이너 restart 우선.
- "소스 정합·dev 미반영·라이브만 터짐" 패턴(LT-14와 동류).
### 관련
- FE-14 claims/·docker-compose.dev.yml frontend

---

## LT-16. 응답 직렬화만 오프셋 변경·역직렬화 경로 방치 → 멱등 round-trip 500 [ACTIVE]
**발견 트랙**: Track 69 결정2 구현(전체 테스트 게이트에서 발견)
**원본 결정**: decisions.md D-156 §3·§진입점 6
### 증상
응답 시각 필드를 +09:00 오프셋으로 직렬화하도록 바꾸면, 그 응답을 다시 읽는 역직렬화 경로가 깨진다. 체크아웃 멱등 재요청이 캐시된 응답 JSON을 objectMapper.readValue(CheckoutResponse.class)로 되읽는데, 기본 LocalDateTime 역직렬화기가 +09:00 오프셋을 파싱하지 못해 500(CheckoutIntegrationTest.checkout_idempotentReplay). 직렬화만 바꾸고 대칭 역직렬화기를 안 넣으면 round-trip 경로만 터진다.
### 처치
직렬화기(KstOffsetSerializer)와 대칭인 역직렬화기(KstOffsetDeserializer·OffsetDateTime.parse→toLocalDateTime)를 round-trip 대상 필드에 부착. CheckoutResponse.expiresAt만 캐시 역직렬화 경로라 해당 필드에 @JsonDeserialize 부착(타 응답 필드는 역직렬화 경로 없어 불요).
### 후속 영향
- 응답 직렬화 계약 변경 시 그 응답을 되읽는 경로(멱등 캐시·내부 재파싱) 유무 확인. 있으면 직렬화/역직렬화 대칭 필수.
- 순수 응답 필드와 round-trip 필드를 구분해 역직렬화기 부착 범위를 최소화.
### 관련
- decisions.md D-156·CheckoutResponse.PaymentView.expiresAt·CheckoutService 멱등 재요청·KstOffsetDeserializer

---

## LT-17. backend 소스 변경 후 재기동 누락 — 신규 컨트롤러 미등록·매핑 없는 경로 500(NoResourceFoundException) [ACTIVE]
**발견 트랙**: 인수인계 1회차(pull 후) + Track 84(STEP 342 재시작 후 컨트롤러 추가·STEP 359 조사)
**원본 결정**: 세션 트랩(본 카탈로그 직접 등록)·decisions.md D-178 §8 이월(NoResourceFoundException 404 매핑) 관련
### 증상
pull 또는 BE 변경 후 신규 API 호출이 500. 로그는 `NoResourceFoundException: No static resource api/v1/admin/members`이고 스택에 컨트롤러·서비스 프레임이 없다(JwtAuthenticationFilter → DispatcherServlet → ResourceHttpRequestHandler). 통합 테스트는 전부 GREEN. 무인증 GET은 401이라 매핑 유무를 구분하지 못하고(보안 매처 선행), 관리자 토큰으로 호출해야 500이 드러난다.
### 처치
로컬 backend는 `gradle bootRun` + 소스 볼륨 마운트지만 spring-boot-devtools가 없어 핫리로드가 없다. 기동 시점의 컴포넌트 스캔 결과가 고정되므로 이후 추가된 컨트롤러는 등록되지 않는다. 호스트 `gradlew test`가 공유 `build/classes`에 새 .class를 써 놓아도 실행 중 JVM에는 반영되지 않는다. Playwright는 API를 mock하므로 못 잡는다. → BE 변경·pull·Flyway 적용 후 `docker restart zslab_mall_backend`, 기동 로그에서 `Started ZslabMallApplication`·Flyway `up to date`·ERROR 0 확인 후 **관리자 토큰으로** 신규 API 200 확인(무인증 401은 근거 아님).
### 후속 영향
- "통합 테스트 GREEN·로컬만 500·컨트롤러 프레임 없는 스택" 패턴이면 코드가 아니라 실행 프로세스 최신 여부부터 의심(LT-14 stale-class와 동류·원인은 재기동 누락).
- 매핑 없는 경로가 404가 아닌 500으로 새는 것은 별건(GlobalExceptionHandler NoResourceFoundException 미매핑·D-178 §8 이월). 404로 매핑되면 같은 트랩이 "신규 API 404"로 정직하게 보인다.
- 기동 대기 폴링 시 `docker logs --tail N`은 TRACE(SQL 바인딩) 로그에 밀려 `Started` 줄을 놓칠 수 있다 → `--since` 또는 전체 로그 grep.
### 관련
- LT-14·LT-15(dev 미반영 계열)·backend/Dockerfile.dev(bootRun)·docker-compose.mall.yml zslab_mall_backend·D-178 §8

---

## LT-18. Gradle 데몬이 외부 도구(Python·sed)로 쓴 소스 변경을 놓침 — stale class로 테스트 실행·신규 테스트만 실패 [ACTIVE]
**발견 트랙**: Track 85(STEP 376 재개·외부 검토 반영 검증)
**원본 결정**: 세션 트랩(본 카탈로그 직접 등록)·decisions.md D-179 외부 검토 반영 관련
### 증상
`gradlew test --rerun-tasks`가 BUILD FAILED인데 실패는 **방금 추가한 신규 테스트만**이고 메시지는 "Expecting code to raise a throwable"·"collection size was 0"처럼 새 코드가 아예 없는 것처럼 보인다. `build/classes/java/main/.../OrderItem.class` mtime이 소스 수정(09:36)보다 이른 02:01 그대로였고, 같은 빌드에서 다른 변경 클래스(CommissionRateResolver.class)는 09:39로 갱신됐다. `--rerun-tasks`를 붙여도 재현된다. 소스 수정을 Python `write_text`·`sed -i`(파일 교체 방식)로 한 직후 발생.
### 처치
`gradlew --stop`으로 데몬 종료 후 `gradlew clean test`. 데몬의 파일 시스템 감시(VFS) 상태가 외부 도구의 파일 교체를 놓쳐 incremental compile 입력이 stale로 남은 것으로 판단(데몬 재시작 후 전량 재컴파일로 정합). clean 직후 첫 `compileTestJava`가 "cannot find symbol"로 한 번 더 실패할 수 있으나 재실행하면 통과한다(같은 원인).
### 후속 영향
- "신규 테스트만 실패·기존 GREEN·소스는 맞음" 패턴이면 class mtime을 소스 mtime과 대조(`ls --time-style=full-iso build/classes/...`). LT-14(named volume UP-TO-DATE 오판)·LT-17(재기동 누락)과 같은 stale 계열이나 이 건은 **호스트 데몬**이 원인.
- 외부 도구로 다수 파일을 일괄 수정한 뒤 검증 게이트를 돌릴 때는 `gradlew --stop` 후 실행을 기본으로 한다.
### 관련
- LT-14·LT-17·D-179 외부 검토 반영·PROGRESS STEP 376

---

## LT-19. settlement_item.order_public_id CHAR(30) 스냅샷 컬럼 — 엔티티 @JdbcTypeCode(SqlTypes.CHAR) 누락 시 Hibernate validate 실패 [ACTIVE]
**발견 트랙**: Track 85(STEP 367 V29 로컬 적용·재시작)
**원본 결정**: decisions.md D-179 결정 5(settlement_item 스냅샷)·LT-01 동류
### 증상
V29로 `settlement_item.order_public_id CHAR(30)`을 추가하고 엔티티에 `@Column(length = 30)`만 선언하면 `spring.jpa.hibernate.ddl-auto=validate`(application.yml:17) 기동이 schema-validation(컬럼 타입 CHAR≠VARCHAR)으로 실패한다(STEP 367 로컬 재시작에서 발견). LT-01은 abstract 상속 `public_id` 식별자 컬럼에 대한 것이었으나, **다른 테이블의 public_id를 복사한 스냅샷 컬럼**(abstract 미상속·FK 아님·표시용)도 CHAR(N)이면 동일하게 걸린다.
### 처치
엔티티 필드에 `@JdbcTypeCode(SqlTypes.CHAR)`(settlement/entity/SettlementItem.java:56) 추가 후 재시작. 신규 CHAR(N) 컬럼은 용도(식별자·스냅샷·코드값)와 무관하게 전부 대상이다.
### 후속 영향
- 이후 `*_public_id` 스냅샷 컬럼을 추가할 때 원본과 같은 CHAR(30) + `@JdbcTypeCode(SqlTypes.CHAR)` 쌍으로 간다(LT-01 "abstract 미상속 케이스 동일 처치 의무"의 스냅샷 컬럼 사례).
- 컴파일·단위 테스트로는 잡히지 않고 validate 프로파일 기동에서만 드러난다 → 신규 CHAR 컬럼 마이그레이션 후 로컬 `docker restart` 기동 로그 확인(LT-17 절차와 동일).
### 관련
- LT-01·LT-17·D-179 결정 5·V29__settlement_items_and_payout.sql

---

## LT-20. prod 프로파일 테스트가 기본 로그 경로(/app/logs)에 의존 — CI(Linux)에서만 컨텍스트 로드 실패·로컬 Windows는 통과 [ACTIVE]
**발견 트랙**: Track 89-F(STEP 485 CI 실패 조사·PR feat/seller-bank-account Backend CI run 35422012794)
**원본 결정**: 세션 트랩(본 카탈로그 직접 등록)·decisions.md D-188 결정 3(키 fail-fast 테스트)
### 증상
로컬 `gradlew test --rerun-tasks`는 1197/0 GREEN인데 GitHub Actions에서만 `ProdBankAccountKeyFailFastTest` 2건(blank·16바이트)·`ProdSecurityContextSmokeTest` 3건이 실패. 스택은 키 검증이 아니라 `Logback configuration error detected: ERROR in RollingFileAppender[JSON_FILE] - Failed to create parent directories for [/app/logs/zslab-mall.json]`. 같은 파일을 쓰는 첫 케이스(missingKey)는 통과.
### 원인
`logback-spring.xml` prod 프로파일의 `JSON_FILE` appender가 `${LOG_PATH:/app/logs}`를 연다. CI 러너(Linux)는 `/app`을 만들 수 없어 로깅 초기화(EnvironmentPreparedEvent)가 키 검증보다 먼저 컨텍스트를 죽인다. 로컬 Windows는 `/app/logs`가 `C:\app\logs`로 생성돼 통과. **종전 CI에서 스모크가 통과한 것은 우연** — 앞선 비-prod 컨텍스트가 로깅을 먼저 초기화해 `LogbackLoggingSystem`이 "이미 초기화" 상태였고 prod 재설정이 스킵됐다. 실패하는 prod SpringApplication(fail-fast 테스트)이 추가되자 그 cleanUp이 초기화 마커를 제거 → 이후 prod 컨텍스트가 prod 로깅을 실제로 재초기화하며 드러났다(첫 케이스만 마커가 남아 통과 = 5/6 실패).
### 처치
테스트가 로그 경로를 임시 디렉터리로 지정한다. `ProdBankAccountKeyFailFastTest`는 `builder.run("--LOG_PATH=" + ${java.io.tmpdir}/zslab-prod-failfast-logs)`(커맨드라인 인자 = 최고 우선순위·OS env `LOG_PATH`보다 우선), `ProdSecurityContextSmokeTest`는 `@TestPropertySource(properties = "LOG_PATH=${java.io.tmpdir}/zslab-prod-smoke-logs")`. **`@DynamicPropertySource`는 무효** — 로깅 초기화 시점에 아직 환경에 실리지 않는다(실측). `@TempDir`도 부적합 — 실패한 컨텍스트가 Logback 파일 핸들을 닫지 않아 Windows에서 정리 단계가 "Failed to close extension context"로 실패한다. 소스·CI 워크플로 무변경. 로컬 재현: `LOG_PATH='C:\Windows\notepad.exe\logs' gradlew test --no-daemon --tests "*Prod*"`(생성 불가 경로) → 수정 전 6/6 실패·후 6/6 통과·전체 1197/0.
### 후속 영향
- prod 프로파일로 컨텍스트를 띄우는 테스트를 추가할 때는 `LOG_PATH`를 인라인 속성/커맨드라인 인자로 임시 경로에 고정한다(`@DynamicPropertySource` 금지·`@TempDir` 금지). 기존 스모크 테스트가 통과한다고 로깅 경로 문제가 없다고 판단하지 말 것(초기화 순서 의존).
- 로컬 Windows 통과 ≠ CI 통과: 절대경로 기본값(`/app/…`)은 Windows에서 드라이브 루트에 조용히 생성된다(`C:\app\logs` 산물 존재). CI 재현은 생성 불가 경로를 env로 주입해 흉내 낸다.
- 실패하는 SpringApplication을 여러 번 띄우는 테스트는 로깅 시스템 마커를 지워 뒤 컨텍스트의 로깅을 재초기화시킨다 — 다른 prod 테스트의 통과 여부를 바꿀 수 있다.
### 관련
- D-188 결정 3·ProdBankAccountKeyFailFastTest·ProdSecurityContextSmokeTest·logback-spring.xml:15-38·PROGRESS STEP 485·486

---

## LT-21. CSS 주석 안 슬래시 포함 문자열이 주석을 조기 종료 — tailwind 파싱 오류로 화면 전체 파손·typecheck·vitest 미검출 [ACTIVE]
**발견 트랙**: Track 90-C-4(STEP 639·셀러 상품 폼 Playwright 1차 35·2차 27 fail)
**원본 결정**: decisions-fe.md FE-48 §2
### 증상
셀러 레이어 전 페이지가 렌더되지 않고 Playwright만 대량 실패(27~35 fail). `pnpm typecheck` 0·vitest 전부 GREEN이라 코드 결함으로 보이지 않는다. `docker logs zslab_mall_frontend`에 tailwind/postcss 파싱 오류.
### 원인
`seller-vuetify.css`의 블록 주석 본문에 `adm-image-*/adm-variant-*`처럼 `*/`가 포함된 문자열을 써 주석이 그 자리에서 닫혔다. 뒤따르는 텍스트가 CSS로 해석돼 파일 전체가 실패한다. typecheck는 CSS를 보지 않고 vitest는 `mountSuspended`가 스타일시트를 처리하지 않아 둘 다 통과한다.
### 처치
주석 본문의 `*/` 패턴 제거(`adm-image-… · adm-variant-…`로 치환). 규칙: CSS 주석에 글로브·경로 표기(`*/`)를 쓰지 않는다. e2e가 원인 불명으로 대량 실패하면 코드 디버깅 전에 `docker logs zslab_mall_frontend` 먼저 확인한다.
### 후속 영향
- CSS 변경은 typecheck·vitest가 잡지 못하는 영역 — Playwright(또는 dev 서버 로그)가 유일한 검증. CSS만 바꾼 커밋도 e2e 1회는 돈다.
### 관련
- FE-48 §2·PROGRESS STEP 639

---

## LT-22. 로컬 backend 컨테이너는 `gradle bootRun` 상주 — 브랜치 BE 변경이 반영되지 않아 신규 셀러 API가 404 [ACTIVE]
**발견 트랙**: Track 90-B-3(대시보드 "서버 오류"·FE-47 §2)·Track 90-C 매 단계 사전 조치로 고정(STEP 615~)
**원본 결정**: decisions-fe.md FE-47 §2
### 증상
브랜치에서 BE 컨트롤러를 새로 만들고 FE를 붙였는데 로컬 화면이 404(셀러 API)·"서버 오류"를 표시한다. BE IT는 전부 GREEN.
### 원인
`Dockerfile.dev`의 backend 컨테이너는 `gradle bootRun`으로 기동 시점 클래스를 상주시키며 소스 변경을 다시 컴파일하지 않는다. BE+FE를 한 브랜치에 쌓는 전략(FE-47·FE-48)에서 BE 커밋 직후 FE 실측을 하면 항상 걸린다.
### 처치
BE 변경 후 FE 실측·Playwright 전에 `docker restart zslab_mall_backend`(헬스 ~2분). Track 90-C부터 각 단계 지시의 "사전" 항목으로 고정.
### 후속 영향
- Playwright 콜드 로드 트랩(FE-46)과 겹친다 — 재시작 직후 1차 실행은 버리고 2·3차로 판정.
- `pnpm typecheck`(nuxt prepare)는 frontend 컨테이너의 `#app-manifest`를 깨뜨리므로(FE 트랩 후보·962행) typecheck 후에도 frontend 재시작.
### 관련
- FE-47 §2·FE-48 §2·PROGRESS STEP 615·649

---

## LT-23. 커밋 메시지·명령 문자열의 슬래시 경로·대괄호 표기가 PowerShell 안전 훅의 Remove-Item 오인을 유발 [ACTIVE]
**발견 트랙**: Track 90-C(STEP 613·626·650 동형 3회)
**원본 결정**: PROGRESS STEP 650(세션 트랩·본 카탈로그 직접 등록)
### 증상
`git commit -m`·`Remove-Item` 등 도구 호출이 훅에 차단된다. 메시지 내용은 정상이고 파일 변경도 없다. 차단 시점의 명령 텍스트에 `/seller:`·`/{productPublicId}/images`·`91/91`·`0·[]` 같은 표기가 들어 있다.
### 원인
PowerShell 안전 훅이 명령 문자열 전체를 스캔해 슬래시로 시작하는 경로·대괄호 글로브를 파괴적 삭제 인자로 오인한다. 커밋 메시지 본문도 명령 텍스트에 포함되므로 API 경로·분수 표기가 트랩이 된다. 같은 호출에 `Remove-Item`(임시 파일 정리)이 섞이면 확률이 더 오른다.
### 처치
커밋 메시지에서 슬래시 경로는 괄호·단어 표기로 치환(`PUT seller products images`·`91건 중 91`·`0과 빈 배열`), 대괄호는 쓰지 않는다. 커밋과 임시 파일 삭제는 별도 호출로 분리하고 메시지는 `-F` 파일 또는 heredoc으로 넘긴다. 반복 지시 항목: "커밋 메시지에 슬래시 경로 표기 금지".
### 후속 영향
- 훅 차단은 "사용자 거부"와 구분되지 않으므로 같은 명령을 그대로 재시도하지 않고 표기부터 바꾼다.
- 리뷰 패킷·문서에는 슬래시 경로를 그대로 써도 된다(파일 내용은 스캔 대상이 아님) — 명령 텍스트만 해당.
### 관련
- PROGRESS STEP 613·626·650

---

## LT-24. DB `NOW()`와 JVM 벽시계 차이로 `credentials_changed_at` 경계 테스트가 흔들림 — 고정 시각 사용 [ACTIVE]
**발견 트랙**: Track 90-D-1(STEP 683·외부 검토 r1 반영)
**원본 결정**: D-195 §2 트랩
### 증상
`UPDATE user SET credentials_changed_at = DATE_ADD(NOW(6), INTERVAL 1 HOUR)`로 "갱신 이전 발급 토큰 거부"를 만들려 했는데 방금 발급한 토큰이 200으로 통과한다(기대 404). 같은 테스트의 withdrawn_at·deleted_at 케이스는 정상.
### 원인
`AuthenticatedUserStateVerifier`는 저장값을 JVM `ZoneId.systemDefault()`(Asia/Seoul) 벽시계로 epoch 초 변환해 토큰 iat와 비교한다. DB 세션 `NOW()`는 컨테이너 TZ(UTC)라 "+1시간"이 KST 기준으로는 8시간 전이 되어 iat가 더 늦다. 시드가 쓰는 `NOW(6)`는 다른 컬럼(created_at 등)에서는 문제되지 않지만 **JVM 시각과 대소 비교되는 컬럼**에서만 드러난다.
### 처치
경계값을 DB 상대 시각이 아니라 고정 시각으로 둔다 — 거부 케이스 `'2099-01-01 00:00:00'`, 허용 케이스 `'2000-01-01 00:00:00'`. JVM 시각과 비교되는 컬럼(`credentials_changed_at`·만료·기한류)을 시드할 때는 `NOW()` 산술을 쓰지 않는다.
### 후속 영향
- 반품 기한(`ReturnWindowPolicy`)·자동 확정처럼 JVM `LocalDateTime.now()`와 비교하는 시각 시드도 같은 규칙(기존 IT는 고정 문자열 사용 중).
### 관련
- PROGRESS STEP 683 · `ClaimAttachmentServingIntegrationTest.userState_rejectedOnAttachmentPath`

---

## LT-25. 로컬에서 prod compose `up -d`가 같은 container_name의 dev 컨테이너를 교체 — 복원은 dev 오버레이 `up -d --build` [ACTIVE]
**발견 트랙**: FE 빌드 OOM 대응(STEP 698)
**원본 결정**: PROGRESS STEP 698
### 증상
배포 순서 재현(`BACKEND_DOCKERFILE=Dockerfile FRONTEND_DOCKERFILE=Dockerfile` + `compose -f docker-compose.mall.yml build/up -d`) 뒤 로컬 dev 환경(bootRun 볼륨 마운트·frontend-dev)이 사라지고 prod 이미지 컨테이너가 같은 이름(`zslab_mall_backend`·`zslab_mall_frontend`)으로 떠 있다.
### 원인
prod·dev compose가 `container_name`과 이미지 태그(`zslab-mall-zslab_mall_backend` 등)를 공유한다. `up -d`는 같은 이름의 기존 컨테이너를 새 정의로 재생성하므로 dev 오버레이 없이 실행하면 dev 컨테이너가 교체된다.
### 처치
로컬에서 prod 순서를 재현한 뒤에는 반드시 `docker compose -f docker-compose.mall.yml -f docker-compose.dev.yml up -d --build`로 dev 오버레이를 다시 올린다(`--build` 없이는 prod 이미지가 그대로 재사용됨). 검증 중 생긴 이전 이미지는 빌드 캐시 교체라 dangling 정리 대상은 없다.
### 관련
- PROGRESS STEP 698

---

## LT-26. `git push`를 다른 명령과 묶으면 PowerShell 안전 훅에 차단 — 단독 실행 [ACTIVE]
**발견 트랙**: Track 90-D-1(STEP 691)·FE 빌드 OOM(STEP 695·699)
**원본 결정**: PROGRESS STEP 691
### 증상
`python …; git push -u origin <branch>`처럼 다른 명령과 한 줄로 묶은 push가 훅에 차단되어 실행되지 않는다. 같은 push를 단독으로 다시 실행하면 통과한다.
### 원인
안전 훅이 명령 문자열 전체를 검사하며 push가 다른 명령(경로·스크립트 실행)과 결합된 형태를 위험 조합으로 판정한다(LT-23의 슬래시·대괄호 오인과 같은 계열).
### 처치
`git push`(및 `-u origin <branch>`)는 항상 단독 명령으로 실행한다. 사전 확인(status·diff·테스트)은 별도 호출로 끝낸 뒤 push만 따로 보낸다.
### 관련
- LT-23 · PROGRESS STEP 691·695·699

---

## LT-27. 제거된 경로의 `NoResourceFoundException`이 GEH `Exception` catch-all에 잡혀 404가 아닌 500 — 경로 제거 시 403 단언 사용 [ACTIVE]
**발견 트랙**: Track 92(STEP 700 예측·STEP 703 실측)
**원본 결정**: D-196 §1-A 2
### 증상
셀러 처리 endpoint를 제거한 뒤 BUYER 토큰으로 `POST /api/v1/claims/{id}/inspect`를 보내면 404가 아니라 **500 INTERNAL_ERROR**가 온다(`ClaimReturnIntegrationTest` T7 기존 "BUYER 검수 403" 단언이 500으로 RED).
### 원인
`/api/v1/claims/**`는 hasRole(BUYER)라 BUYER 토큰은 필터를 통과해 DispatcherServlet까지 간다. 매핑이 없으면 Spring 6.1+ `ResourceHttpRequestHandler`가 `NoResourceFoundException`을 던지는데, `GlobalExceptionHandler`는 `ResponseEntityExceptionHandler`를 상속하지 않고 `@ExceptionHandler(Exception.class)` catch-all이 이를 먼저 잡아 500으로 매핑한다. 인가에 걸리는 액터(SELLER·ADMIN)는 필터 단계 403이라 영향이 없다.
### 처치
경로를 제거한 뒤의 "부재" 단언은 404가 아니라 **인가 필터 단계에서 결정되는 403**(해당 경로 규칙에 없는 역할의 토큰)으로 둔다. 필터를 통과하는 역할의 토큰으로 부재를 단언하지 않는다. catch-all에 `NoResourceFoundException` 404 매핑을 추가하는 GEH 수정은 별건으로 이월(D-196 §8).
### 관련
- D-196 §1-A 2·§8 · PROGRESS STEP 700·703 · `SellerClaimIntegrationTest` R1~R4 · `SellerDeliveryIntegrationTest` R5

---

## LT-28. 회수(RETURN) Delivery가 먼저 DELIVERED가 되면 관리자 confirm-pickup이 `IllegalStateException` → GEH catch-all 500·검수 영구 차단 [ACTIVE]
**발견 트랙**: Track 92(STEP 711 부수 관찰)·Track 92-a(정찰 C-10 실측)
**원본 결정**: D-197 §8
### 증상
구매자 회수 송장 등록으로 생긴 RETURN·SHIPPING Delivery를 confirm-pickup 이전에 누군가 DELIVERED로 마감하면(가드 도입 전 셀러·관리자 mark-delivered·또는 데이터 보정), 이후 관리자 `POST /api/v1/admin/claims/{id}/confirm-pickup`은 **500 INTERNAL_ERROR**를 돌려주고 `picked_up_at`은 NULL로 남는다. inspect는 "회수 확인 전에는 검수할 수 없습니다" 422로 영구 차단된다.
### 원인
`ClaimService.confirmPickup` → `DeliveryService.completeReturnShipment` → `Delivery.markDelivered`가 DELIVERED→DELIVERED 불법 전이로 `IllegalStateException`을 던지고, GEH에 전용 매핑이 없어 `Exception` catch-all 500으로 샌다(TX 롤백). 회수 Delivery 재등록은 RETURN 중복 가드 422, Delivery 상태를 되돌리는 API는 없어 운영 데이터 보정(delivery.status·delivered_at 원복)만 가능하다.
### 처치
D-197로 셀러 claim 연결 마감·관리자 RETURN 마감을 422로 막아 정상 API로는 도달하지 않는다. confirm-pickup 측의 `IllegalStateException` 흡수(422)·GEH 매핑은 범위 밖으로 이월(D-197 §8). 과거 데이터에서 재현되면 delivery 행 원복 후 confirm-pickup 재시도.
### 관련
- D-197 배경·§8 · docs/track-92a/recon-report-delivery.md C-10 · PROGRESS STEP 711·719 · `SellerDeliveryCompletionControllerIntegrationTest` T6 · `AdminDeliveryControllerIntegrationTest` T7

---

## 부록. 트랩 추가 절차

1. 라이브 발견 시 즉시 decisions.md D-XX 박제 (단건 처리)
2. 누적 ≥3건 도달 시점에 본 카탈로그 신설 (D-82 패턴)
3. 기 신설 후: 신규 트랩은 본 카탈로그 LT-XX 직접 추가·decisions.md 중복 박제 금지
4. 트랩 해소·무효화 시 [RESOLVED] 라벨 부착·항목 보존 (이력 추적)

