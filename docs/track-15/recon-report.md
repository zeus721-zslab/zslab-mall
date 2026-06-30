# Track 15 Seller Service — 정찰 보고서

> **정찰일**: 2026-06-30  
> **핵심 의제**: D-98 Q3 §후속 `DeliveryService.registerExchangeShipment` Controller·endpoint 결손 해소  
> **정찰 방식**: read-only·코드 수정·파일 생성·git 명령 전건 금지  
> **기조 5 선언**: 모든 주장은 실측 라인 인용 근거. 추측·추정 금지.

---

## §1 개요

### §1.1 트랙 번호·핵심 의제

- **트랙 번호 미부여** (진입 선언 후 Q0 결정 의제로 확정 예정 — D-94 Q0 β 선례)
- **핵심 의제**: D-98 §후속 명시 "Seller Service 트랙: Q3 Seller registerExchangeShipment Controller·endpoint·DTO @ValidEnum 3·4층"
- **보조 의제**: D-97 Q3 §후속 "판매자 운송장 등록 API·운영자 강제 전이" (markShipping/markDelivered Seller endpoint)

### §1.2 읽은 SoT 목록

| 문서 | 읽은 범위 | 핵심 확인 |
|---|---|---|
| decisions.md | D-92~D-98 전문 (lines 3422~4847) | D-98 Q3·§후속 Seller Service 트랙 명시 |
| aggregate-boundary.md §2.5 | lines 67~81 | Delivery 외부 ID에 Claim.id 추가 (D-98 Q13) |
| state-machine.md §6.1 | lines 177~193 | READY 의미·단방향 직진·DLV-3 |
| invariants.md §2.12 | lines 122~129 | DLV-1~3 불변식 3건 |

---

## §2 Seller 도메인 현황 실측

### §2.1 패키지 트리 (전수)

```
backend/src/main/java/com/zslab/mall/seller/
├── entity/
│   ├── Seller.java                  60줄  (Track 4 read-only·D-59)
│   ├── SellerUser.java              61줄  (Seller 종속·HARD)
│   ├── SellerBankAccount.java       87줄  (Seller 종속·ARCHIVE)
│   ├── SellerSalesDaily.java        74줄  (Read Model·ARCHIVE·복합 PK)
│   └── WithdrawnSeller.java         67줄  (ARCHIVE·SLR-6·D-23)
├── enums/
│   ├── SellerStatus.java            15줄  (4값: PENDING/ACTIVE/SUSPENDED/TERMINATED)
│   └── SellerBankAccountStatus.java (미읽·추정 소형)
└── repository/
    ├── SellerRepository.java        18줄  (findByPublicId·findByIdIn)
    ├── SellerUserRepository.java     8줄  (기본 JpaRepository)
    └── SellerSalesDailyRepository.java    (미읽·추정 소형)

Controller·Service·Handler·DTO: 전무 (Track 7 이연·D-59)
```

**실측 확인**: Seller 도메인에 Controller·Service·Handler·비즈니스 로직 **0건**.  
`Seller.java:19` Javadoc — `"Track 4 read-only(D-59) — 응답 enrich 조회 전용으로 최소 신설. 입점·정지·해지 등 쓰기 책임은 Track 7 이연."`

### §2.2 기존 Seller endpoint 전건 열거

| Path | Method | Controller 파일 | Actor | DTO |
|---|---|---|---|---|
| /api/v1/claims/{publicId}/approve | POST | SellerClaimController.java:49 | Seller | — (ClaimResponse 반환) |
| /api/v1/claims/{publicId}/reject | POST | SellerClaimController.java:59 | Seller | — (ClaimResponse 반환) |

**Seller 전용 endpoint: 2건 (ClaimService.approveBySeller·rejectBySeller 위임)**  
Delivery 관련 Seller endpoint: **0건**

---

## §3 D-98 Q3 §후속 결손 명세

### §3.1 DeliveryService.registerExchangeShipment 시그니처 (실측)

파일: `backend/src/main/java/com/zslab/mall/delivery/service/DeliveryService.java`

```java
// L89-107
public Delivery registerExchangeShipment(Long claimId, DeliveryCarrier carrier, String trackingNo) {
    Claim claim = claimRepository.findById(claimId)
            .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));

    Delivery delivery = Delivery.create(claim.getOrderItemId(), carrier);
    deliveryRepository.save(delivery); // delivery.id 발급(attachExchangeDelivery 인자 요건)

    // Aggregate 불변식 검증(Claim 측·D-98 Q13·외부 검토 1차 Q3 흡수)
    claim.attachExchangeDelivery(delivery.getId(), delivery.getOrderItemId());

    delivery.attachExchangeClaim(claimId);
    delivery.markShipping(trackingNo, LocalDateTime.now());
    deliveryRepository.save(delivery);

    eventPublisher.publishEvent(new DeliveryStarted(
            delivery.getId(), delivery.getOrderItemId(), delivery.getCarrier(),
            delivery.getTrackingNo(), LocalDateTime.now()));
    return delivery;
}
```

**Javadoc (L74-88)**: `"교환품 출고 등록(D-98 Q3·Seller Service 트랙 endpoint 진입점 예정)"`

### §3.2 Controller 호출처 실측

| 패턴 | 실측 결과 |
|---|---|
| Delivery controller 패키지 존재 여부 | **부재** (`backend/src/main/java/.../delivery/controller/**` glob → No files found) |
| registerExchangeShipment 호출처 (production) | **0건** |
| registerExchangeShipment 호출처 (test) | 1건 — `ClaimExchangeIntegrationTest.java:166` (`deliveryService.registerExchangeShipment(claimId, DeliveryCarrier.CJ, "CJ-EXC-0001")`) |

**결론**: D-98 Q3 §후속 결손 명세 실측 확인 — `registerExchangeShipment` Service 메서드 완전 구현·Controller endpoint **0건**.

### §3.3 markShipping/markDelivered Seller endpoint 결손

| 메서드 | Service 구현 | Controller 구현 |
|---|---|---|
| DeliveryService.markShipping (L48-56) | 완전 구현 | **0건** |
| DeliveryService.markDelivered (L64-71) | 완전 구현 | **0건** |

D-97 Q3 §후속 — `"Seller Service 트랙 (또는 Admin Service 트랙): Delivery Controller·endpoint 신설·판매자 운송장 등록 API·운영자 강제 전이·DTO @ValidEnum 3·4층 동반"` 명시.

---

## §4 동반 후보 endpoint 평가

### §4.1 자연 묶음 후보 (D-98 Q3 기준)

| 후보 endpoint | Service 메서드 | 근거 | 우선도 |
|---|---|---|---|
| POST `.../register-shipment` | `registerExchangeShipment(claimId, carrier, trackingNo)` | D-98 Q3 §후속 명시·핵심 결손 | **P0** |
| POST `.../mark-shipping` | `markShipping(deliveryId, trackingNo)` | D-97 Q3 §후속·판매자 운송장 등록 | P1 |
| POST `.../mark-delivered` | `markDelivered(deliveryId)` | D-97 Q3 §후속·운영자 강제 전이 | P2 |

### §4.2 URL 패턴 후보

D-40 본문 — `"/api/buyer/...·/api/seller/... prefix 금지"` (buyer·seller 2건 한정).  
D-93 Q6 §실측 — `/admin` prefix는 D-40 본문 미명시 → γ′ 채택.  
D-98 §후속 — `"Seller Service 트랙: Q3 registerExchangeShipment Controller·endpoint"` URL 패턴 미박제.

**Q1 의제 예비 평가**:
- α: `/api/v1/claims/{claimPublicId}/register-shipment` (액터 중립 base path 공존·D-40 정합·D-92 Q4 β 패턴)
- β: `/api/v1/seller/deliveries/{claimPublicId}/register-shipment` (D-40 명시 `/api/seller` prefix 금지 가능성·실측 필요)
- γ: `/api/v1/deliveries/{claimPublicId}/register-shipment` (새 base path·Delivery Aggregate 기준)

D-40 본문 원문 실측이 필요하나 D-93 Q6 §"buyer·seller 2건 한정" 인용으로 β가 D-40 위배 가능성 있음.  
**사전 추천**: α 또는 γ — 결정 라운드 Q1 의제로 도출.

### §4.3 DTO @ValidEnum 3·4층 최초 적용 대상

`DeliveryCarrier` enum (4값: CJ/HANJIN/POST/LOGEN) — Controller endpoint 신설 시 DTO 필요.  
현재 상태: Controller OUT-OF-SCOPE이므로 DTO 미존재 → **전 프로젝트 @ValidEnum 실사용 0건**.

D-97 Q3 §기각 이유 인용 — `"Controller 동반: 외부 노출 DTO @ValidEnum 3·4층 동반 의무 발생"` — 본 트랙에서 최초 활성화 의무.

---

## §5 결정 라운드 의제 사전 도출

### §5.1 Q0: 트랙 식별자

D-94 Q0 β·D-95 Q1 α·D-97 Q0 β·D-98 Q0 α 선례 5회차.  
**의제**: Track 15 = Seller Service로 확정 여부 또는 타 라벨.

### §5.2 Q1: endpoint URL 패턴

**옵션**:
- α: `/api/v1/claims/{claimPublicId}/register-shipment` (클레임 중심·D-92 Q4 β 패턴·SellerClaimController base path 공존)
- β: `/api/v1/seller/deliveries/{claimPublicId}/register-shipment` (D-40 `/seller` prefix 금지 가능성)
- γ: `/api/v1/deliveries/{claimPublicId}/register-shipment` (Delivery base path 신설)

**실측 필요**: D-40 본문 원문의 `/seller` prefix 금지 범위 재확인.

### §5.3 Q2: markShipping/markDelivered Seller endpoint 동반 여부

- α: registerExchangeShipment 단독 (D-98 §후속만 해소)
- β: registerExchangeShipment + markShipping + markDelivered 3건 (D-97 §후속 동반)
- γ: 상위 포함 + Admin override endpoint 동반

**추천**: β — D-97 §후속 "판매자 운송장 등록 API" 명시·markShipping/markDelivered Service 완전 구현·단일 PR 범위 내 자연 묶음.

### §5.4 Q3: DTO 설계 — registerExchangeShipment 입력

현재 Service 시그니처: `registerExchangeShipment(Long claimId, DeliveryCarrier carrier, String trackingNo)`  
claimId는 path variable (`{claimPublicId}` → id 해소), carrier·trackingNo는 request body DTO.

**옵션**:
- α: `RegisterExchangeShipmentRequest(DeliveryCarrier carrier, String trackingNo)` — carrier enum 바인딩(@ValidEnum 3층·D-98 §후속 "DTO @ValidEnum 3·4층" 최초 적용)
- β: carrier를 String으로 받고 Service에서 변환 (@ValidEnum 불요·기존 Jackson 역직렬화 패턴·D-92 Q3 패턴)

**추천**: α — D-98 §후속 "DTO @ValidEnum 3·4층" 명시 의무이므로 본 트랙에서 최초 적용 진입.

### §5.5 Q4: @ValidEnum 어노테이션 신설 여부

CLAUDE.md §"신규 type/status 컬럼 — 4층위 enum 잠금 의무" — `"(3) DTO: @Pattern 또는 커스텀 @ValidEnum 검증 (매직 문자열 금지)"`.  
전 프로젝트 @ValidEnum 실사용 0건 → 본 트랙 최초 신설 필요.

**옵션**:
- α: @ValidEnum 커스텀 어노테이션 신설 (javax.validation.Constraint 기반)
- β: Jackson enum 역직렬화 실패 → 400 패턴 재사용 (ClaimRequestRequest 현행 패턴·@ValidEnum 미구현)

**추천**: β 우선 검토 — D-97 Q3 §기각 때 "외부 노출 DTO @ValidEnum 3·4층 동반 의무 발생"은 경고였고 실제 의무화는 D-98 §후속이 처음. Jackson 역직렬화 실패 400 패턴이 기존 3 Controller에서 확인된 실동작 패턴이므로 β로 충분할 가능성 있음. 결정 라운드에서 최종 확인 필요.

### §5.6 Q5: SellerActorResolver 재사용 패턴

현행: SellerClaimController.java L51 — `Long sellerId = sellerActorResolver.resolve(request);`  
신규 Delivery Controller도 동일 패턴 1:1 재사용 가능.

**의제**: SellerActorResolver를 Delivery Controller에도 그대로 주입할 것인가 (α·D-92 seam 재사용) vs. 별도 resolver 신설 (β·불요).  
**추천**: α — 패턴 1:1 재사용·별도 resolver 신설 이유 없음.

### §5.7 Q6: PR 분할 전략

- α: 단일 PR (registerExchangeShipment + markShipping + markDelivered + DTO + @ValidEnum)
- β: PR-1 registerExchangeShipment + PR-2 markShipping/markDelivered 분할

**추천**: α 검토 — 범위가 Controller 신설 1개 수준이면 단일 PR·D-87~D-98 1-PR 단독 패턴 8회차.

### §5.8 Q7: Admin override endpoint 병행 여부

D-93 Admin 패턴 (AdminClaimController) — AdminActorResolver 재사용.  
markShipping/markDelivered Admin override: 별도 Admin Service 트랙 이연 권장.

---

## §6 WARN 매트릭스

| WARN | Priority | 내용 | 해소 방향 |
|---|---|---|---|
| WARN-1 | P0 | `DeliveryService.registerExchangeShipment` Controller 전무 — D-98 §후속 미이행 | 본 트랙 핵심 해소 대상 |
| WARN-2 | P0 | `DeliveryService.markShipping/markDelivered` Seller endpoint 전무 — D-97 §후속 미이행 | 동반 해소 권장 |
| WARN-3 | P1 | @ValidEnum 전 프로젝트 0건 — 4층위 잠금 의무(CLAUDE.md) 대비 DeliveryCarrier DTO 미검증 | Q4 결정 필요 |
| WARN-4 | P1 | D-40 `/seller` prefix 금지 범위 불명확 — URL 패턴 결정 선행 필요 | Q1 결정 라운드·D-40 원문 재실측 |
| WARN-5 | P1 | Delivery Controller 신설 시 `findByPublicId` 또는 `findById` 진입점 선택 | Service 시그니처는 `Long deliveryId`·publicId→id 해소 패턴 신규 결정 필요 |
| WARN-6 | P2 | DeliveryRepository에 `findByPublicId` 부재 — Controller publicId 진입 시 추가 필요 가능성 | Delivery entity에 publicId 필드 존재 확인 필요 |
| WARN-7 | P2 | TemplateCode 누적 8건 (D-98 기준)·임계 ≥10 미도달·D-95 WARN-10-α 유지 | 본 트랙 추가 없으면 문제 없음 |

---

## §7 외부 검토 라벨 사전 평가

### §7.1 A급 vs S급 판단 기준

| 항목 | 실측 결과 | 등급 영향 |
|---|---|---|
| 새 패턴 유무 | @ValidEnum 최초 신설 가능 (Q4 β 채택 시 기존 패턴) | S급 요인 |
| Controller 신설 규모 | 1개 예상·SellerClaimController 1:1 패턴 재사용 | A급 요인 |
| D-92·D-93 횡단 원칙 재사용 | SellerActorResolver 패턴 재사용 (3회차) | A급 요인 |
| Service 완전 구현 | registerExchangeShipment L89-107 기완비 | A급 요인 |
| D-40 URL 패턴 모호성 | `/seller` prefix 금지 범위 불명확 | S급 요인 |
| DDL 무변경 | V6·V7 이미 완료·신규 마이그레이션 불요 | A급 요인 |

**사전 평가**: **A급 권장** — 단, Q4(@ValidEnum 신설 여부)·Q1(URL 패턴 D-40 실측)에 따라 S급 상향 가능.

---

## §8 영향 범위 사전 추정

### §8.1 신규 파일 (예상)

| 파일 유형 | 예상 수 | 내용 |
|---|---|---|
| Controller | 1 | SellerDeliveryController.java (가칭) |
| Request DTO | 1~2 | RegisterExchangeShipmentRequest.java + (markShipping Request 동반 시 1건) |
| ValidEnum 어노테이션 | 0~1 | Q4 α 선택 시 신설·β 선택 시 0건 |
| 단위 테스트 | 1 | SellerDeliveryControllerTest.java (@WebMvcTest) |
| 통합 테스트 | 1 | SellerDeliveryIntegrationTest.java (D-91·LT-02) |

### §8.2 수정 파일 (예상)

| 파일 유형 | 예상 수 | 내용 |
|---|---|---|
| DeliveryRepository.java | 0~1 | findByPublicId 추가 필요 시 (WARN-6) |
| decisions.md | 1 | D-99 (또는 D-100) 박제 |

### §8.3 무변경 예상

DeliveryService.java·Delivery.java·DeliveryStatus.java·Claim.java·ClaimService.java·DDL·Flyway·기존 테스트 전건·invariants.md·state-machine.md

### §8.4 테스트 baseline

현재 424 tests (D-98 PR-2 기준·PROGRESS.md Track 14 PR-2 STEP 9 실측).  
신규: Controller 단위 ≥4건 + 통합 ≥3건 = ≥7건 → **431+ tests** 예상.

---

## §9 PR 분할 사전 평가

### §9.1 단일 PR (α) 권장

- 범위: Delivery Controller 1건 + DTO 1~2건 + 테스트 2건
- 패턴: SellerClaimController 1:1 재사용·Service 완전 구현·회귀 위험 저
- D-87~D-98 1-PR 단독 패턴 선례 8회차 정합

### §9.2 분할 PR (β) 검토

- PR-1: registerExchangeShipment endpoint
- PR-2: markShipping·markDelivered endpoint

registerExchangeShipment만 D-98 §후속 명시 의무·markShipping/markDelivered는 D-97 §후속 권장 수준. β 분할은 과분할 가능성 있음.

**추천**: α 단일 PR — 단 Q2에서 markShipping/markDelivered 포함(β) 결정 시 β 분할 재평가.

---

## §10 정찰 한계·미해소 의문점

### §10.1 미실측 항목

| 항목 | 이유 | 해소 방법 |
|---|---|---|
| D-40 원문 `/seller` prefix 금지 범위 | 본 정찰에서 decisions.md D-40 원문 직접 실측 미수행 (D-93 Q6 §인용만 확인) | 결정 라운드 전 D-40 원문 실측 필수 (WARN-4) |
| DeliveryRepository.findByPublicId 유무 | SellerRepository 패턴에는 존재하나 DeliveryRepository 전체 미읽 | 구현 단계 실측 |
| SellerBankAccountStatus 값집합 | 파일 미읽 | 본 트랙 무관 — 필요 시 구현 단계 실측 |
| SellerSalesDailyRepository 메서드 | 파일 미읽 | 본 트랙 무관 |

### §10.2 미해소 의문점

1. **URL 패턴**: D-40 원문 `/seller` prefix 금지 범위가 정확히 어디까지인가. D-93 Q6 §인용("buyer·seller 2건 한정")만으로는 `/api/v1/seller/...` 또는 `/api/seller/...` 중 어느 형태가 금지인지 불분명.

2. **Controller 위치**: Delivery 전용 Controller 신설 vs. SellerClaimController에 endpoint 추가 vs. 신규 SellerDeliveryController. D-40 §책임 분리 원칙 정합 필요.

3. **Delivery publicId → id 해소**: registerExchangeShipment 시그니처가 `Long claimId`를 받음. Controller 진입점에서 `{claimPublicId}` path variable을 `ClaimRepository.findByPublicId`로 해소하면 되나, Delivery Controller에 ClaimRepository 주입이 적절한가 vs. DeliveryService 시그니처에 publicId 오버로드 신설 여부.

4. **markShipping deliveryId 진입점**: `DeliveryService.markShipping(Long deliveryId, ...)` — deliveryId가 외부 노출 식별자가 아님. Controller에서 `{deliveryPublicId}` path variable로 받고 DeliveryRepository.findByPublicId 해소 필요. findByPublicId 현재 미존재 (WARN-6).

5. **@ValidEnum 신설 비용**: 커스텀 Constraint 어노테이션 신설은 처음이며 패턴 없음. Jackson 역직렬화 실패 → 400 패턴(β)이 실동작 검증된 패턴이므로 @ValidEnum α 신설 필요 최소화 가능성 검토 필요.

---

## §11 기조 5 자체 감사

| 항목 | 실측 파일:라인 |
|---|---|
| registerExchangeShipment 메서드 본문 | DeliveryService.java:89-107 |
| Delivery Controller 부재 | glob `delivery/controller/**` → No files found |
| registerExchangeShipment 테스트 호출처 | ClaimExchangeIntegrationTest.java:166 |
| SellerClaimController 기존 endpoint 2건 | SellerClaimController.java:49·59 |
| X-Seller-Id HeaderSellerActorResolver | HeaderSellerActorResolver.java:17 |
| SellerActorResolver resolve 패턴 | SellerClaimController.java:51 |
| @ValidEnum 전 프로젝트 미구현 | ClaimRequestRequest.java:15 (Javadoc 인용) |
| DeliveryCarrier enum 4값 | DeliveryCarrier.java:7-10 |
| D-98 §후속 Seller Service 트랙 명시 | decisions.md:4829 |
| D-97 §후속 Delivery Controller 이연 명시 | decisions.md:4451 |
| Seller 도메인 Controller·Service 부재 | glob `seller/**` 13파일·entity/enum/repo만 존재 |
| aggregate-boundary.md §2.5 Delivery Claim.id | aggregate-boundary.md:73 |
| state-machine.md §6.1 READY 의미 | state-machine.md:186 |
| invariants.md §2.12 DLV-1~3 | invariants.md:125-127 |
| 테스트 baseline 424 | PROGRESS.md Track 14 PR-2 STEP 9 |

---

## §12 D-40 원문 실측 (Claude.ai MCP 정찰 보강·2026-06-30)

### §12.1 D-40 본문 핵심 인용

decisions.md D-40 결정안 원문 (Track 4·CR-02 흡수 후 확정):

> 1. **URL은 액터 중립** — 리소스 중심 URL 유지(`/api/orders`·`/api/order-items`). `/api/buyer/...`·`/api/seller/...` prefix 금지.
> 2. **Controller는 액터별 분리** — `BuyerOrderController` (Track 4)·`SellerOrderController` (#7 결정에 따라 본 또는 후속 트랙)·`AdminOrderController` (후속).
> 3. **Security·DTO·Application Service도 액터 분리** — `@PreAuthorize` 컨트롤러 클래스 단위·DTO 액터별 분리(D-41)·향후 BuyerOrderQueryService·SellerOrderQueryService 자연 분리.

옵션 비교 — γ (URL prefix 분리 `/api/buyer/...`·`/api/seller/...`) **기각**·사유: "Seller 1년 내 계획 부재·prefix 선점 무의미".

근거 인용:
> "Order Aggregate는 buyer/seller 구분 없는 단일 도메인. 변하는 건 인증·조회 범위(RLS)·응답 DTO·권한 — 모두 애플리케이션 계층 경계. URL이 아닌 Controller + Application Service 조합에서 분리하는 것이 DDD 정합."

### §12.2 정찰 §10.2-1 미해소 의문 해소

| 의문 | 해소 결과 |
|---|---|
| D-40 원문 `/seller` prefix 금지 범위 | **액터 prefix 자체 금지** (`/buyer/`·`/seller/`)·버전 prefix(`/api/v1/`)는 별개 |
| `/api/v1/seller/...` 형태 허용 여부 | **금지** — D-40 위배 (액터 prefix 포함) |
| `/api/seller/...` 형태 허용 여부 | **금지** — 동일 사유 |
| 실 운영 URL 버전 prefix 사용 정황 | D-43·D-44b·D-45 모두 `/api/v1/orders`·`/api/v1/orders/{publicId}/payments` 사용 (실측 확인) |

### §12.3 Q1 URL 패턴 사전 결론

| 옵션 | URL | D-40 정합 | 평가 |
|---|---|---|---|
| α | `/api/v1/claims/{claimPublicId}/register-shipment` | 정합 | **강력 추천** — 액터 중립·Claim Aggregate 중심 리소스·D-98 Q13 attachExchangeDelivery Aggregate 진입점 정합 |
| β | `/api/v1/seller/deliveries/{claimPublicId}/register-shipment` | **위배** | 기각 — 액터 prefix 포함 |
| γ | `/api/v1/deliveries/{claimPublicId}/register-shipment` | 정합 | 차선 — D-40 위배 없으나 path variable이 claimPublicId라 base path와 의미 불일치·Claim Aggregate가 attach 진입점 보유 |

**결정 라운드 Q1 사전 권장**: α — Claim publicId path variable과 base path 의미 일치·D-98 Q13 `Claim.attachExchangeDelivery` Aggregate 진입점 정합.

### §12.4 보조 의제 Q2 URL 패턴 (markShipping/markDelivered)

Q2 β 채택 시 markShipping/markDelivered Seller endpoint 동반. URL 패턴 사전 평가:

| 옵션 | URL | D-40 정합 | 비고 |
|---|---|---|---|
| α-2 | `/api/v1/deliveries/{deliveryPublicId}/mark-shipping`·`.../mark-delivered` | 정합 | Delivery 리소스 중심·publicId path variable 의미 일치 |
| β-2 | `/api/v1/seller/deliveries/{...}/...` | 위배 | 기각 |

**Delivery publicId 진입 의제** (정찰 §10.2-4): `DeliveryService.markShipping(Long deliveryId, ...)` 시그니처는 내부 id 수신·Controller에서 `{deliveryPublicId}` 해소 필요. `DeliveryRepository.findByPublicId` 부재 (WARN-6) → 결정 라운드 Q5 (가칭) 신규 의제·Repository 메서드 추가 또는 Service publicId 오버로드 분기 결정.

### §12.5 정찰 후 추가 결정 라운드 의제

본 보강으로 결정 라운드 의제 1건 추가 도출:

- **Q5 (신규·임시 번호)**: Delivery publicId 진입 패턴
  - α: `DeliveryRepository.findByPublicId` 메서드 추가·Controller에서 해소 후 Long deliveryId Service 호출
  - β: `DeliveryService.markShippingByPublicId(String publicId, ...)` 오버로드 신설·Service 내부 해소
  - γ: registerExchangeShipment는 claimPublicId 기반이므로 markShipping/markDelivered만 Q5 적용·Q2 α 채택 시 Q5 자체 회피

Q1·Q2 결정 후 자연 도출·Q5는 Q2 β 채택 조건부 의제.

---

## §13 정찰 보강 종료 선언

정찰 §10.2-1 D-40 원문 실측 미해소 의문 **해소 완료**. 결정 라운드 Q0~Q7 + Q5(임시 번호·조건부)·8 의제 진입 가능.

남은 정찰 한계:
- §10.1 항목 1 (D-40 원문) — 해소
- §10.1 항목 2 (DeliveryRepository.findByPublicId 유무) — Q5 결정 라운드 의제로 흡수 (구현 단계 실측 가능)
- §10.1 항목 3·4 (SellerBankAccountStatus·SellerSalesDailyRepository) — 본 트랙 무관 유지

본 보강 author: Claude.ai (MCP read-only)·실측 위치: decisions.md D-40 본문·D-43·D-44b·D-45 URL 패턴 grep 인용.

---

## §14 정찰 보강 2회차 — D-99 박제 본문 작성 전 실측 (2026-06-30)

### §14.1 배경

D-99 박제 본문 초안 작성 중 Claude.ai 미실측 인용 4건 식별. 기조 5 (추측·추정 절대 금지·실측 우선) 위배 회피 위해 Claude Code Sonnet 4.6 read-only 정찰로 정정. §1~§13은 본 정찰 시점에 이미 완료된 1차 정찰 산출물·본 §14는 D-99 박제 직전 보강.

### §14.2 §1 DeliveryService.java 주입 전건 실측

파일: `backend/src/main/java/com/zslab/mall/delivery/service/DeliveryService.java`

```java
// L31-33
private final DeliveryRepository deliveryRepository;
private final ClaimRepository claimRepository;
private final ApplicationEventPublisher eventPublisher;

// L35-40 생성자
public DeliveryService(DeliveryRepository deliveryRepository,
    ClaimRepository claimRepository, ApplicationEventPublisher eventPublisher)
```

- **OrderItemRepository 주입**: **없음** (3개 의존성만 존재)
- **wrapper/primitive 분리 패턴**: 없음 (단일 공개 메서드군)
- **registerExchangeShipment**: L89-107 (§3.1 인용 실측 일치)

### §14.3 §2 V1__init.sql delivery 테이블 실측

파일: `backend/src/main/resources/db/migration/V1__init.sql` L642-654

```sql
CREATE TABLE delivery (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  public_id      CHAR(30)     NOT NULL COMMENT 'ULID+prefix dlv_',
  order_item_id  BIGINT       NOT NULL COMMENT 'FK→order_item(N:1·DLV-2)',
  carrier        ENUM('CJ','HANJIN','POST','LOGEN') NOT NULL COMMENT '택배사·A#11',
  tracking_no    VARCHAR(100) NULL     COMMENT '운송장번호·UK(DLV-1)·자연키',
  status         ENUM('READY','SHIPPING','DELIVERED') NOT NULL COMMENT '배송 상태·A#12',
  shipped_at     DATETIME(6)  NULL     COMMENT '발송 시각',
  delivered_at   DATETIME(6)  NULL     COMMENT '배송 완료 시각(DLV-3)',
  ...
)
```

- **tracking_no**: `VARCHAR(100) NULL` → D-99 Q3 `@Size(max=100)` 박제 근거 확정
- **carrier ENUM**: CJ·HANJIN·POST·LOGEN (4종)
- **NULL 허용**: tracking_no·shipped_at·delivered_at (markShipping 진입 시 앱 레벨 검증·D-98 Q3 박제)

### §14.4 §3 Claim.java attachExchangeDelivery 실측

파일: `backend/src/main/java/com/zslab/mall/claim/entity/Claim.java`

```java
// L202-215
public void attachExchangeDelivery(Long deliveryId, Long deliveryOrderItemId) {
    if (deliveryId == null || deliveryOrderItemId == null) {
        throw new IllegalArgumentException(...);
    }
    if (this.type != ClaimType.EXCHANGE) {
        throw new ClaimInvalidStateException(
            "교환 배송 연결은 EXCHANGE 클레임에서만 가능합니다: type=" + this.type);
    }
    if (!this.orderItemId.equals(deliveryOrderItemId)) {
        throw new ClaimInvalidStateException(
            "Delivery-OrderItem 불일치: claim.orderItemId=" + this.orderItemId
                  + ", delivery.orderItemId=" + deliveryOrderItemId);
    }
}  // void — 필드 변경 없음, 검증만 수행
```

#### 핵심 발견 (D-99 본문 정정 영향)

- **exchange Delivery 참조 필드 부재**: Claim 엔티티에 `exchangeDeliveryId` 필드 **없음**
- Javadoc L195: "본 메서드 자체는 검증만 수행한다(반환 void·필드 변경 없음)"
- 연결은 `Delivery.attachExchangeClaim(claimId)`이 단독 수행 (D-01 Aggregate 외부 ID·D-98 Q13 박제)
- **이중 호출 멱등 가드 부재**: `attachExchangeDelivery`는 type·orderItemId 불일치만 차단·이중 호출 시 검증 재통과 가능
- 관련 멱등 가드 위치:
  - `ClaimService.confirmPickup` L304: `claim.getPickedUpAt() != null` → no-op
  - `ClaimService.markCompleted` L279: `claim.getStatus() == COMPLETED` → no-op

### §14.5 §4 SellerClaimController Javadoc 라인 실측

파일: `backend/src/main/java/com/zslab/mall/claim/controller/SellerClaimController.java`

```java
// L19-27
/**
 * Seller 액터용 Claim REST 컨트롤러(D-92). 승인·거부 2 endpoint를 노출한다(D-92 Q6 α).
 * ...
 * <p>HTTP 책임만 가진다(D-40 β′): 액터 해소·publicId→id 해소·Service 위임·응답 조립만
 * 수행하며 권한/상태 판단은 {@link ClaimService} 책임이다. approve/reject primitive는
 * void이므로 전이 후 재조회로 응답을 조립한다.
 */
```

- D-99 Q5 인용 정확 라인: **L24** "HTTP 책임만 가진다(D-40 β′)"

### §14.6 §5 ClaimService.authorizeSellerAccess 실측

파일: `backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java`

```java
// L353-360
private void authorizeSellerAccess(Claim claim, Long sellerId) {
    OrderItem orderItem = orderItemRepository.findById(claim.getOrderItemId())
        .orElseThrow(() -> new IllegalStateException(
            "OrderItem 무결성 위반: orderItemId=" + claim.getOrderItemId()));
    if (!orderItem.getSellerId().equals(sellerId)) {
        throw new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claim.getId());
    }
}
```

- **메서드 가시성**: `private` (1차 인용 L468-475는 라인 번호 오기·실제 L353-360)
- 검증 경로: `orderItem.getSellerId().equals(sellerId)` (§5 1차 인용 일치)
- **ClaimService 주입 전건** (L50-53):
  - `ClaimRepository`
  - `OrderItemRepository` ← **주입됨**
  - `OrderRepository`
  - `ApplicationEventPublisher`

---

## §15 D-99 박제 본문 정정 매트릭스

| 항목 | 박제 초안 (추정) | 실측 결과 | 정정 방향 |
|---|---|---|---|
| Q3 | `@Size(max=N)` N값 미확정 | `tracking_no VARCHAR(100) NULL` (V1 L647) | `@Size(max=100)` 확정·NULL 허용 |
| Q5 | Javadoc 인용 라인 미확인 | L24 "HTTP 책임만 가진다(D-40 β′)" | 인용 라인 L24 확정 |
| Q9 | DeliveryService에 `OrderItemRepository` 주입 + 자체 authorize 신설 가정 | DeliveryService 주입 3건 (OrderItemRepository 부재)·ClaimService에만 주입·`authorizeSellerAccess` private | **Q9 옵션 재정의 의무**·γ ClaimService wrapper 채택 시 DeliveryService 주입 무변경 |
| Q10 | `Claim.exchangeDeliveryId` 필드 자연 멱등 가정 | Claim 엔티티에 해당 필드 **부재**·`attachExchangeDelivery`는 void 검증 전용 | 이중 연결 차단 근거 재정의 의무·Q11 신규 의제 도출 |

### §15.1 ClaimService 라인 번호 정정

- 1차 §11 기조 5 자체 감사 항목 "ClaimService.java:468-475 authorizeSellerAccess" 인용 → **실측 L353-360**으로 정정 박제
- D-99 본문 작성 시 L353-360 사용 의무

---

## §16 Q9 재결정 결과·Q11 신규 의제 도출

### §16.1 Q9 옵션 재정의 (실측 후)

기존 1차·2차 검토 권장 α "1:1 재사용"은 `ClaimService.authorizeSellerAccess` private 가시성·`DeliveryService` 주입 차이로 직접 호출 불가. 옵션 4건 재정의:

| 옵션 | 내용 | 평가 |
|---|---|---|
| α | `DeliveryService`에 `OrderItemRepository` 신규 주입·자체 authorize 8라인 복제 | 차선·규칙 복제 |
| β | `ClaimService.authorizeSellerAccess` private → package-private·`DeliveryService`에서 호출 | Service 내부 계약 누수·반대 |
| **γ** | `ClaimService.registerExchangeShipmentBySeller` wrapper 신설·내부 authorize → `DeliveryService.registerExchangeShipment` 위임 | **권장·D-92·D-98 정합** |
| δ | `ExchangeShipmentApplicationService` orchestration 신설 | 과잉개발·기각 |

### §16.2 외부 검토 3차 회신 결과 (Q9 단독 회신)

검토자 결론 (전건 인용):

> "이번 자료 기준에서는 γ가 단순히 DRY 때문이 아니라, 이미 프로젝트가 채택한 '권한 wrapper → actor 없는 primitive' 패턴과 가장 일관적입니다."

권장 우선순위: γ > α >>> β >>> δ

근거 요약:
- D-92 정합: 권한 검증 = Service 진입점·primitive 메서드는 actor 비의존
- D-98 흐름 업무 주체는 Claim (ClaimApproved → ClaimPickedUp → registerExchangeShipment → attachExchangeDelivery)
- 권한 규칙 중복 제거: `approveBySeller`·`confirmPickupBySeller`·`registerExchangeShipmentBySeller` 일관성
- β 반대: private helper 공개는 Service 내부 구현이 사실상 공유 API화·테스트 경계 흐려짐

### §16.3 Q9 추가 확인 포인트 실측 해소

| 확인 항목 | 실측 결과 |
|---|---|
| 순환 의존 가능성 | `DeliveryService` → `ClaimRepository` 의존 (§14.2 L32)·`ClaimService` → `DeliveryService` 신규 주입은 Repository 대 Service 분리·**순환 없음** |
| 트랜잭션 경계 | `ClaimService` 클래스 단위 `@Transactional` 기보유 (§14.6 L48 인용·정찰 1차 §5)·wrapper 동일 트랜잭션·primitive 호출은 `Propagation.REQUIRED` 자연 전파 |
| 이벤트 발행 위치 | `DeliveryStarted` 발행은 `DeliveryService.registerExchangeShipment` L103 유지·wrapper 통과해도 위치 무변경 |

### §16.4 Q9 γ 확정 박제

- ClaimService 신규 메서드: `registerExchangeShipmentBySeller(Long claimId, Long sellerId, DeliveryCarrier carrier, String trackingNo): Delivery`
- 내부 흐름: 조회 → `authorizeSellerAccess(claim, sellerId)` → `deliveryService.registerExchangeShipment(claimId, carrier, trackingNo)` 위임 → Delivery 반환
- ClaimService 신규 주입: `DeliveryService` (생성자 추가)
- DeliveryService primitive 무변경 (actor 비의존)
- D-92 횡단 원칙 재사용 3회차 박제 정합 (`approveBySeller`·`confirmPickupBySeller`·`registerExchangeShipmentBySeller`)

### §16.5 Q11 신규 의제 도출 (Q10 정정 영향)

§14.4 실측 결과: `Claim.attachExchangeDelivery`는 type·orderItemId 불일치만 차단·이중 호출 시 검증 재통과 가능. D-99 Q10 "자연 흡수" 근거 재정의 의무.

#### Q11 옵션

| 옵션 | 내용 | DDL | 비고 |
|---|---|---|---|
| α | `DeliveryService.registerExchangeShipment` 진입부 가드 신설 (`deliveryRepository.findByClaimId(claimId).ifPresent → ClaimInvalidStateException throw`) | 무변경 | 런타임 가드·DeliveryRepository.findByClaimId 신규 메서드 |
| β | `Delivery.claim_id` UNIQUE 제약 추가 | V8 마이그레이션 동반 | D-98 Q13 "UNIQUE 금지·재출고 가능성 차단 회피" 박제와 직접 충돌·기각 |
| γ | 통합 테스트 회귀 1건 추가만·런타임 가드 무방어 | 무변경 | D-98 Q13 박제 정합·운영 데이터 무결성 분류 |

#### Claude.ai 사전 권장: α

근거:
- 런타임 가드로 즉시 차단·운영 데이터 무결성 위험 사전 회피
- D-98 Q13 "UNIQUE 금지·재출고 가능성 차단 회피" 박제 유지 (DDL 무변경)
- `DeliveryRepository.findByClaimId` 신규 메서드 1건 추가·범위 최소
- 위치는 Service primitive 진입부 (actor 비의존 유지·D-92 정합)

#### 미해소 의제

- 재출고 시나리오 정확한 정의 (D-98 Q13 박제 "향후 재출고 가능성"의 구체 흐름·EXCHANGE 1회차 실패 → 새 Claim 또는 동일 Claim 재시도 여부)
- α 채택 시 재출고 진입점은 별도 Service 메서드 신설 필요 (현 primitive에서 이중 차단 시 재출고 불가)

→ Q11 결정 라운드에서 사용자 확정·필요 시 외부 검토 추가 회신.

---

## §17 §11 기조 5 자체 감사 정정

§11 1차 자체 감사 항목 중 다음 1건 정정:

| 1차 인용 | 정정 | 사유 |
|---|---|---|
| `ClaimService.java:468-475 authorizeSellerAccess` | `ClaimService.java:353-360 authorizeSellerAccess` | 1차 인용은 ClaimService 다른 위치 (markCompleted 영역 추정)·실측 본 메서드 L353-360 |

§11 매트릭스 다른 항목은 실측 일치 확인.

---

## §18 D-99 박제 진입 조건

본 §14·§15·§16·§17 정정 흡수 후 D-99 박제 본문 작성 가능 상태. Q11 결정 라운드 후 박제 본문 최종화·사용자 직접 박제 (D-94·D-95·D-96·D-97·D-98 패턴 6회차).

본 보강 author: Claude Code Sonnet 4.6 (정찰)·Claude.ai (옵션 평가)·외부 검토 (γ 합의)·실측 위치: DeliveryService.java L31-107·V1__init.sql L642-654·Claim.java L202-215·SellerClaimController.java L19-27·ClaimService.java L50-53·L353-360.

---

## §19 정찰 보강 3회차 — D-99 Q10·Q11 가정 검증 및 차단 위험 실측

### §19.1 배경

D-99 박제 Q10·Q11 본문이 "ClaimInvalidStateException → 422·ClaimNotFoundException → 404 프로젝트 전체 통일" 가정에 의존하나, §1~§18 정찰에서 GlobalExceptionHandler 실측이 수행되지 않았다. 기조 5("모든 주장은 실측 라인 인용 근거·추측 금지") 위배 회피를 위해 예외 매핑 메커니즘 전건을 재실측한다. 부수적으로 Q11 구현 대상(`DeliveryRepository.findByClaimId`)·Q3·Q6 재사용 패턴 근거(ClaimRequestRequest·HeaderSellerActorResolver)를 동반 실측한다.

### §19.2 예외 매핑 메커니즘 실측

**파일**: `backend/src/main/java/com/zslab/mall/common/web/GlobalExceptionHandler.java`

```java
// L38-40
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
```

- `@ResponseStatus` 어노테이션 없음·`@RestControllerAdvice`로 일원화 (L39)
- RFC 7807 `ProblemDetail` + 커스텀 `code`·`traceId` 속성 (L182-192)

**ClaimNotFoundException → 404 매핑** (L106-111):

```java
@ExceptionHandler(ClaimNotFoundException.class)
public ResponseEntity<ProblemDetail> handleClaimNotFound(
        ClaimNotFoundException exception, HttpServletRequest request) {
    // Track 9 PR-B: 클레임 미존재·타인 소유(정보 노출 회피·Q8)·주문 품목 미매칭(404).
    return build(HttpStatus.NOT_FOUND, CODE_CLAIM_NOT_FOUND, exception.getMessage(), request);
}
```

**ClaimInvalidStateException → 422 매핑** (L167-173):

```java
@ExceptionHandler(ClaimInvalidStateException.class)
public ResponseEntity<ProblemDetail> handleClaimInvalidState(
        ClaimInvalidStateException exception, HttpServletRequest request) {
    // Track 9 PR-B(D-89 Q3): 클레임 상태·정책 위반(CANCEL 한정·CLM-5·canTransitionTo). 500 fallback 차단·422 매핑(D-50).
    log.warn("[Claim] 상태 위반(422): {}", exception.getMessage());
    return build(HttpStatus.UNPROCESSABLE_ENTITY, CODE_CLAIM_STATE_INVALID, exception.getMessage(), request);
}
```

**두 예외 클래스 정의 실측**:

- `ClaimInvalidStateException` (`claim/exception/ClaimInvalidStateException.java`): `@ResponseStatus` 없음·`extends RuntimeException`·생성자 1건 (String message) (L16-21)
- `ClaimNotFoundException` (`claim/exception/ClaimNotFoundException.java`): `@ResponseStatus` 없음·`extends RuntimeException`·생성자 1건 (String message) (L6-11)

두 예외 모두 GlobalExceptionHandler L6-7에서 import됨·`@ResponseStatus` 미사용·Handler에서 명시적 HTTP 상태 할당 패턴 통일 확인.

**코드 상수** (L58-59):
- `CODE_CLAIM_NOT_FOUND = "CLAIM_NOT_FOUND"` → 404
- `CODE_CLAIM_STATE_INVALID = "CLAIM_STATE_INVALID"` → 422

### §19.3 DeliveryRepository 실측

**파일**: `backend/src/main/java/com/zslab/mall/delivery/repository/DeliveryRepository.java`

```java
// L10-13
public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByPublicId(String publicId);
}
```

- 현재 메서드: `findByPublicId(String publicId)` 1건 (L12)·`Optional<Delivery>` 반환
- `findByClaimId` **미존재** (D-99 Q11 박제 대상 신규 추가 필요)
- `@Query` 어노테이션 없음·Spring Data JPA 쿼리 메서드 네이밍 방식 사용
- `findByOrderItemId` 등 유사 메서드 부재·기존 패턴: `Optional` 반환·단순 쿼리 메서드

Q11 신규 메서드 예상 시그니처: `Optional<Delivery> findByClaimId(Long claimId)` — 기존 `findByPublicId` Optional 반환 패턴 일치.

### §19.4 ClaimRequestRequest 실측

**파일**: `backend/src/main/java/com/zslab/mall/claim/controller/request/ClaimRequestRequest.java`

```java
// L18-25
public record ClaimRequestRequest(
        @NotBlank
        @Pattern(regexp = "^oit_[0-9A-Z]{26}$",
                message = "orderItemPublicId 형식이 올바르지 않습니다(oit_ + ULID 26자).")
        String orderItemPublicId,
        @NotNull ClaimType claimType,
        @NotNull ClaimReasonCode reasonCode,
        @Size(max = 500, message = "reasonDetail은 500자 이하여야 합니다.") String reasonDetail) {
```

- **record 정의** (L18)
- **enum 직접 바인딩**: `ClaimType claimType`·`ClaimReasonCode reasonCode` — `@ValidEnum` 없음·Jackson 역직렬화 실패 → 자동 400 (GlobalExceptionHandler L73-77 `HttpMessageNotReadableException` 핸들러)
- **Bean Validation 패턴**: `@NotBlank` + `@Pattern` (형식)·`@NotNull` (enum)·`@Size` (옵션 문자열)
- `toCommand` 변환 메서드 (L28-30): `buyerId·requestedAt` 외부 주입 후 `ClaimRequestCommand`로 변환

D-99 Q3 채택 `RegisterExchangeShipmentRequest` 패턴 근거: record·enum 직접 바인딩·`@Size(max=100)` (tracking_no VARCHAR(100) 정합)·`toCommand` 패턴 생략(Service 직접 호출로 단순화 가능).

### §19.5 HeaderSellerActorResolver 실측

**파일**: `backend/src/main/java/com/zslab/mall/common/auth/HeaderSellerActorResolver.java`

```java
// L15-31
@Component
public class HeaderSellerActorResolver implements SellerActorResolver {

    private static final String SELLER_ID_HEADER = "X-Seller-Id";

    @Override
    public Long resolve(HttpServletRequest request) {
        String raw = request.getHeader(SELLER_ID_HEADER);
        if (raw == null || raw.isBlank()) {
            throw new UnauthenticatedException("X-Seller-Id 헤더 누락");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new MalformedRequestException("X-Seller-Id 형식 오류");
        }
    }
}
```

- `resolve(HttpServletRequest request): Long` 시그니처 (L20)
- X-Seller-Id 헤더 누락 (`null || isBlank`) → `UnauthenticatedException` → **401** (GlobalExceptionHandler L80-84)
- 형식 오류 (NumberFormatException) → `MalformedRequestException` → **400** (GlobalExceptionHandler L73-77)
- `@Component` 등록·`SellerActorResolver` 인터페이스 구현 (D-93 seam)
- D-99 Q6 재사용 판단: `SellerDeliveryController`에서 `@Autowired SellerActorResolver sellerActorResolver`·`sellerActorResolver.resolve(request)` 호출 1:1 재사용 가능·별도 신설 없음

### §19.6 D-99 본문 정정 필요 항목 매트릭스

| D-99 항목 | D-99 본문 가정 | 실측 결과 | 정정 방향 |
|---|---|---|---|
| Q10 ClaimInvalidStateException → 422 | "프로젝트 전체 통일 (ClaimService 패턴 일관)" | GlobalExceptionHandler.java L167-173 실측 확인·Handler 신설 불필요 | **정정 불필요** — 이미 구현됨 |
| Q10 ClaimNotFoundException → 404 | D-99 Q9 L4996 "ClaimNotFoundException throw (404·cross-tenant 정보 노출 회피)" | GlobalExceptionHandler.java L106-111 실측 확인 | **정정 불필요** — 이미 구현됨 |
| Q10 @ResponseStatus 유무 | 명시 가정 없음 (GlobalExceptionHandler 경유 암묵적 전제) | 두 예외 모두 `@ResponseStatus` 없음·Handler 명시적 매핑 확인 | **정정 불필요** — 패턴 정합 |
| Q11 DeliveryRepository.findByClaimId 신규 | "DeliveryRepository.findByClaimId 신규 메서드 1건" | 현재 `findByPublicId(String)` 1건만 존재·`findByClaimId` 부재 | **정정 불필요** — 신규 추가 박제 정합 |
| Q11 이중 호출 가드 throw → 422 | "ClaimInvalidStateException throw → Q10 일관 매핑 (HTTP 422)" | Q10 실측 확인 (L167-173) | **정정 불필요** — 매핑 확인됨 |

**결론**: D-99 Q10·Q11 본문 가정 5건 전건 실측 정합. 정정 필요 항목 없음.

### §19.7 구현 단계 차단 위험 평가

| 위험 항목 | 평가 | 근거 |
|---|---|---|
| GlobalExceptionHandler 부재 → 신규 파일 의무 | **차단 없음** | `GlobalExceptionHandler.java` 기존재·`ClaimInvalidStateException`·`ClaimNotFoundException` 이미 import·핸들러 등록 완료 |
| `findByClaimId` 미존재 → 신규 메서드 추가 필요 | **경미 (1라인)** | DeliveryRepository.java L12 패턴 동일·`Optional<Delivery> findByClaimId(Long claimId)` 1줄 추가·`@Query` 불필요 |
| `ClaimService` 신규 주입 (`DeliveryService`) | **확인 필요** | §14.6 실측 `ClaimService` 생성자 파라미터 목록 미인용·순환 의존 D-99 Q9 본문에서 "부재 확인" 명시 (DeliveryService → ClaimRepository·ClaimService → DeliveryService 방향 비교·순환 없음) |
| `SellerDeliveryController` 신규 파일 | **신규 파일 필수** | 현재 `SellerClaimController`만 존재 (§2.2 실측)·D-99 Q5 채택 신설 파일 |
| `RegisterExchangeShipmentRequest/Response` DTO 신규 | **신규 파일 필수** | `ClaimRequestRequest` 패턴 재사용·record 2건 신설 |
| `ClaimService.registerExchangeShipmentBySeller` wrapper 신설 | **신규 메서드** | `authorizeSellerAccess` (L353-360) 재사용·`DeliveryService` 위임 |

**구현 차단 요인 없음.** 모든 신규 항목은 기존 패턴의 1:1 재사용 또는 최소 추가(1라인~소규모 파일) 범위 내.

### §19.8 본 보강 author·실측 위치

정찰 보강 3회차 author: Claude Code Sonnet 4.6·실측 위치: GlobalExceptionHandler.java L6-7·L38-40·L58-59·L106-111·L167-173·ClaimInvalidStateException.java L16-21·ClaimNotFoundException.java L6-11·DeliveryRepository.java L10-13·ClaimRequestRequest.java L18-30·HeaderSellerActorResolver.java L15-31·decisions.md D-99 L4998-5028.

---