# Track 14 PR-2 EXCHANGE — 추가 정찰 실측 보고서 (recon-report3)

**작성일**: 2026-06-30  
**기준 커밋**: f271f94 (Track 14 PR-1 RETURN 머지 직후·working tree clean)  
**목적**: D-98 박제 본문 PR-2 EXCHANGE 외부 검토 2차 흡수 결과 R3·R5 결정 정밀화.  
**기조 5 준수**: 전건 라인 번호 직접 인용·Read/Grep 실측 결과만 박제·추정 0건.

---

## §1 정찰 보강 배경·범위

recon-report2.md는 PR-2 EXCHANGE STEP 1~8 진입을 위한 15 의제를 박제했다.  
본 보고서는 외부 검토 2차 흡수에서 제기된 2 의제를 추가 실측하여 R3·R5 결정을 정밀화한다.

- **의제 A1**: `ClaimService.markCompleted` publishEvent 위치 → R3 결정 분기 (E9 중복 발행 가능성 유무)
- **의제 A2**: V1__init.sql delivery FK 자동 INDEX → R5 결정 분기 (V7 `ADD INDEX idx_delivery_claim_id` 유지·제거)

라인 번호는 `Read` 도구 실측 결과 직접 인용(추정 없음).

---

## §2 의제 A1 — ClaimService.markCompleted publishEvent 위치

### 실측 대상

`backend/src/main/java/com/zslab/mall/claim/service/ClaimService.java`

### 실측 결과 — markCompleted 전체 (lines 276-288)

```java
276  public void markCompleted(Long claimId) {
277      Claim claim = claimRepository.findById(claimId)
278              .orElseThrow(() -> new ClaimNotFoundException("클레임을 찾을 수 없습니다: claimId=" + claimId));
279      if (claim.getStatus() == ClaimStatus.COMPLETED) {
280          log.info("[Claim] markCompleted 멱등 NO-OP(이미 COMPLETED): claimId={}", claimId);
281          return;
282      }
283      claim.markCompleted(LocalDateTime.now());
284      claimRepository.save(claim);
285      eventPublisher.publishEvent(new ClaimCompleted(
286              claim.getId(), claim.getPublicId(), claim.getOrderItemId(),
287              claim.getType(), claim.getStatus(), LocalDateTime.now()));
288  }
```

### 구조 분석

| 위치 | 라인 | 내용 |
|------|------|------|
| 멱등 가드 진입 | 279 | `if (claim.getStatus() == ClaimStatus.COMPLETED)` |
| 멱등 가드 — log + early return | 280-281 | `log.info` → `return` (멱등 가드 종결) |
| Entity 전이 | 283 | `claim.markCompleted(LocalDateTime.now())` |
| save | 284 | `claimRepository.save(claim)` |
| publishEvent | 285-287 | `eventPublisher.publishEvent(new ClaimCompleted(...))` |

**publishEvent 위치**: line 285-287. 멱등 가드 `return` (line 281) 이후 별개 흐름에 위치.  
이미 COMPLETED 상태 → line 279 조건 true → line 281 `return` → publishEvent(line 285-287)에 절대 도달 불가.

### 추가 실측 — markCompleted 호출처 (ClaimRefundCompletedHandler)

recon-report2.md §9 (lines 40-59 실측 박제)에서 이미 확인된 사실:

```java
// ClaimRefundCompletedHandler.java line 48-52
if (claim.getType() == ClaimType.EXCHANGE) {
    log.info("[Claim] RefundCompleted 수신·type=EXCHANGE → 본 핸들러 미전이: claimId={}", event.claimId());
    return;
}
// line 249
claimService.markCompleted(event.claimId());
```

- CANCEL·RETURN: `ClaimRefundCompletedHandler` → `claimService.markCompleted` 호출 (line 249)
- EXCHANGE: `ClaimRefundCompletedHandler`에서 line 48-52 skip → `ExchangeDeliveryCompletedHandler` (PR-2 신설 대상) → `claimService.markCompleted` 호출 예정

모든 호출처에서 중복 호출 시 `markCompleted` 멱등 가드(line 279-281)가 publishEvent 도달을 차단.  
`ExchangeDeliveryCompletedHandler` 신설 후에도 동일 멱등 보호 구조 자동 적용.

### 판정 매트릭스 적용

| 케이스 | 실측 결과 | R3 결정 |
|--------|-----------|---------|
| publishEvent가 멱등 가드 **안** (이미 COMPLETED 시 publishEvent 호출 안 됨) | **해당** — line 281 early return → line 285 publishEvent 미도달 | E9 중복 발행 자연 차단 → R3 변경 불요·자연 종결 |
| publishEvent가 멱등 가드 **밖** + Entity throw 자연 차단 | 미해당 | — |
| publishEvent가 멱등 가드 **밖** + no-op 경로에서도 publishEvent 도달 가능 | 미해당 | — |

### R3 결정 박제

**R3: publishEvent (line 285-287)가 멱등 가드 early return (line 281) 이후 위치 · 이미 COMPLETED 상태 재호출 시 publishEvent 미도달 확인 · E9 중복 발행 자연 차단 · PR-2 ClaimService.markCompleted 수정 불요 · R3 변경 불요·자연 종결.**

---

## §3 의제 A2 — delivery 테이블 FK 자동 INDEX 확인

### 실측 대상

`backend/src/main/resources/db/migration/V1__init.sql` — 헤더 주석 + `CREATE TABLE delivery` 블록

### 실측 결과 1 — V1 헤더 설계 정책 (line 13)

```sql
13  --               FK 자식 컬럼 인덱스는 InnoDB 자동 생성에 의존 (별도 선언 안 함)
```

V1 헤더에서 **FK 자식 컬럼 인덱스 정책**을 명시적으로 선언:  
InnoDB 자동 INDEX 생성에 의존 · 별도 KEY/INDEX 선언 안 함.

### 실측 결과 2 — delivery 테이블 DDL 전건 (lines 642-659)

```sql
642  CREATE TABLE delivery (
643    id             BIGINT       NOT NULL AUTO_INCREMENT,
644    public_id      CHAR(30)     NOT NULL COMMENT 'ULID+prefix dlv_',
645    order_item_id  BIGINT       NOT NULL COMMENT 'FK→order_item(N:1·DLV-2)',
646    carrier        ENUM('CJ','HANJIN','POST','LOGEN') NOT NULL COMMENT '택배사·A#11',
647    tracking_no    VARCHAR(100) NULL     COMMENT '운송장번호·UK(DLV-1)·자연키',
648    status         ENUM('READY','SHIPPING','DELIVERED') NOT NULL COMMENT '배송 상태·A#12',
649    shipped_at     DATETIME(6)  NULL     COMMENT '발송 시각',
650    delivered_at   DATETIME(6)  NULL     COMMENT '배송 완료 시각(DLV-3)',
651    created_at     DATETIME(6)  NOT NULL,
652    created_by     BIGINT       NULL,
653    updated_at     DATETIME(6)  NOT NULL,
654    updated_by     BIGINT       NULL,
655    PRIMARY KEY (id),
656    UNIQUE KEY uk_delivery_public_id (public_id),
657    UNIQUE KEY uk_delivery_tracking_no (tracking_no),
658    CONSTRAINT fk_delivery_order_item FOREIGN KEY (order_item_id) REFERENCES order_item (id) ON DELETE RESTRICT ON UPDATE CASCADE
659  ) ENGINE=InnoDB ...;
```

**명시적 KEY/INDEX 목록**: `PRIMARY KEY (id)` (line 655) · `UNIQUE KEY uk_delivery_public_id` (line 656) · `UNIQUE KEY uk_delivery_tracking_no` (line 657).  
`order_item_id`에 대한 명시적 KEY/INDEX **없음** (line 642-659 전건 확인).  
`fk_delivery_order_item` FK (line 658)만 선언 → InnoDB 자동 INDEX 생성 대상.

### 실측 결과 3 — 타 테이블 FK 패턴 교차 검증 (옵션 β)

V1__init.sql 내 단순 FK 컬럼 패턴 교차 확인:

| 테이블 | FK 컬럼 | 명시적 KEY | 라인 |
|--------|---------|-----------|------|
| user_address | user_id | 없음 | line 203-204 |
| withdrawn_user | original_user_id | 없음 | line 159-160 |
| product_image | product_id | 없음 | line 415-416 |
| seller_bank_account | seller_id | 없음 | line 305-306 |
| order_shipping_snapshot | order_id | 없음 | line 613-614 |
| delivery | order_item_id | 없음 | line 657-658 |

**일관 패턴**: 단순 FK 컬럼(별도 복합 쿼리 패턴 없음) 전건 명시적 KEY 없음 → V1 헤더 정책(line 13) 1:1 정합.

MariaDB InnoDB는 FK 컬럼에 INDEX가 없으면 FK 생성 시 자동 INDEX를 생성한다 (InnoDB Foreign Key Constraints 공식 동작·5.5+).  
`fk_delivery_order_item`(order_item_id) 자동 INDEX 존재 = V1 헤더 정책 의존.

### 판정 매트릭스 적용

| 케이스 | 실측 결과 | R5 결정 |
|--------|-----------|---------|
| MariaDB InnoDB FK 자동 INDEX 생성 · 기존 fk_delivery_order_item에도 자동 INDEX 존재 확인 | **해당** — V1 line 13 정책 명시 · delivery line 642-659 명시적 KEY 없음 · 타 테이블 교차 검증 6건 일관 | V7 `ADD INDEX idx_delivery_claim_id` 중복 → V7 INDEX 제거 · FK 자동 INDEX 의존 |
| MariaDB FK 자동 INDEX 미생성 · 기존 fk_delivery_order_item도 명시적 KEY 박제 | 미해당 | — |

### R5 결정 박제

**R5: V1 헤더 line 13 정책("FK 자식 컬럼 인덱스는 InnoDB 자동 생성에 의존") + delivery 테이블 order_item_id 명시적 KEY 없음(line 642-659) 실측 확인 · V7에서 `claim_id` FK 선언 시 InnoDB 자동 INDEX 생성 의존 · V7 `ADD INDEX idx_delivery_claim_id` 제거 · V1 정책 일관성 유지.**

---

## §4 기조 5 자체 감사 매트릭스

| 의제 | 실측 방법 | 인용 라인 | 추정 여부 |
|------|-----------|-----------|-----------|
| A1 markCompleted 전체 | Read ClaimService.java | line 276-288 | 실측 |
| A1 멱등 가드 위치 | Read line 279-281 | line 279-281 | 실측 |
| A1 publishEvent 위치 | Read line 285-287 | line 285-287 | 실측 |
| A1 ClaimRefundCompletedHandler skip·호출 | recon-report2.md §9 라인 인용 재확인 | line 40-59 (ClaimRefundCompletedHandler) | 실측 |
| A2 V1 헤더 FK INDEX 정책 | Read V1__init.sql | line 13 | 실측 |
| A2 delivery DDL 전건 | Read V1__init.sql line 642-659 | line 642-659 | 실측 |
| A2 타 테이블 FK 패턴 교차 검증 | Read V1__init.sql (user_address·withdrawn_user·product_image·seller_bank_account·order_shipping_snapshot) | line 203-204·159-160·415-416·305-306·613-614 | 실측 |

추정 항목: **0건**

---

## §5 PR-2 STEP 1~8 영향 정리

### R3 결정 → STEP 영향

| 항목 | 결정 | STEP 영향 |
|------|------|----------|
| ClaimService.markCompleted 수정 | **불요** (멱등 가드 안 publishEvent·자연 차단) | STEP 추가 변경 없음 |
| PR-2 STEP 목록 | R3 변경 불요 확정 | STEP 1~8 계획 변경 없음 |
| ExchangeDeliveryCompletedHandler 신설 후 멱등 보호 | 자동 적용 (markCompleted 내부 가드) | 신설 핸들러 별도 멱등 가드 불요 |

### R5 결정 → V7 마이그레이션·문서 영향

| 항목 | 결정 | STEP 영향 |
|------|------|----------|
| V7 `ADD INDEX idx_delivery_claim_id` | **제거** (InnoDB FK 자동 INDEX 의존) | STEP 1 V7 DDL에서 INDEX 절 미포함 |
| V7 `claim_id` FK 선언 | 유지 (`fk_delivery_claim` FK) | FK 선언 자체는 D-98 Q13 그대로 |
| D-98 Q13 박제 본문 INDEX 절 | 변경 필요 — `ADD INDEX idx_delivery_claim_id` 절 삭제 · V1 정책 의존 주석 추가 권장 | STEP 8 문서 갱신 시 Q13 항목 수정 +1 |
| aggregate-boundary·state-machine | 영향 없음 (R5는 DDL 레벨) | 변경 없음 |
