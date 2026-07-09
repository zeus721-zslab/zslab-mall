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

### 검증 방법
통합 테스트 1건 필수: "동일 이벤트 재발행 시 형제 핸들러 순서 무관 멱등 검증" (History 기반 skip 실증·Track 17 T5 SoT).

### 관련
- 원본: D-101 §6 갱신 (decisions.md ACTIVE)
- 관련 결정: D-75 (AFTER_COMMIT + REQUIRES_NEW)·D-100 Q9 γ (핸들러 순서 비보장)·D-100 Q1 γ (멱등 패턴 카탈로그·B-2 History 기반 신설 근거)

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
## 부록. 트랩 추가 절차

1. 라이브 발견 시 즉시 decisions.md D-XX 박제 (단건 처리)
2. 누적 ≥3건 도달 시점에 본 카탈로그 신설 (D-82 패턴)
3. 기 신설 후: 신규 트랩은 본 카탈로그 LT-XX 직접 추가·decisions.md 중복 박제 금지
4. 트랩 해소·무효화 시 [RESOLVED] 라벨 부착·항목 보존 (이력 추적)

