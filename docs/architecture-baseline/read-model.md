# Read Model (PR-03)

> 소스: decisions.md D-10 [확정 2026-06-24]
> 범위: Read Model 카탈로그·갱신 트리거·Write↔Read 동기화 패턴. 실제 VIEW SQL·인덱스·CQRS Read DB 분리는 PR-04/구현 이연.
> 갱신 트리거는 domain-events.md 이벤트와 정합(aggregate-boundary.md §3 Read Model 후보 = BuyerPurchaseAggregate·SellerSalesDaily).

---

## 1. 분리 원칙

- **Write Model**: Aggregate 트랜잭션 일관성을 보장하는 원천 데이터(17개 Aggregate). 도메인 불변식의 단일 출처.
- **Read Model**: 조회 최적화·집계·검색용 **파생 데이터**. 원천(Write Model)으로부터 재생성 가능(손실 허용).
- **분리 이유**: 집계·조회 부하를 Write 경로의 잠금 범위 밖으로 분리해 결합도·경합을 낮춘다. Read Model 손상 시 원천에서 재집계로 복구한다.
- **동기화 방식 선택**: db-schema §1.11 3패턴에 따라 케이스별로 이벤트 핸들러 / 배치 / VIEW 중 택1.

---

## 2. Read Model 카탈로그

> 발행 이벤트의 (E#)은 domain-events.md 이벤트 번호.

### 2.1 BuyerPurchaseAggregate (집계 테이블 — 이벤트 핸들러 갱신)

| 항목 | 내용 |
|---|---|
| 컬럼(db-schema §2.2) | buyer_id PK · lifetime_purchase_amount · last_ordered_at · updated_at |
| 원천(Write Model) | Order/OrderItem (item_status = CONFIRMED 항목의 total_price) |
| 갱신 트리거 | **E6 PurchaseConfirmed** (OrderItem → CONFIRMED · 발행 주체 Order) |
| 갱신 동작 | lifetime_purchase_amount += 확정 금액 · last_ordered_at 갱신 |
| 집계 키 | buyer_id |
| 집계 단위 | 구매확정 누적(lifetime) |
| 재계산 가능 | 가능 — Order/OrderItem(CONFIRMED) total_price SUM 재집계(이벤트 유실 시 배치 보정) |
| 후속 사용 | GradeEvaluator → BuyerProfile.grade_id 산정(db-schema §2.2) |

> **정합 노트**: db-schema §2.2의 "Order COMPLETED 이벤트" 표기는 PR-01에서 Order.status에 COMPLETED 값이 없고 CONFIRMED로 확정(D-02)되어 **E6 PurchaseConfirmed**로 정합. lifetime 원천은 Aggregate 단독 보유(BuyerProfile 미보유).

### 2.2 SellerSalesDaily (집계 테이블 — 배치 갱신)

| 항목 | 내용 |
|---|---|
| 컬럼(db-schema §2.8) | seller_id · sale_date 복합 PK · order_count · gross_amount · refund_amount · net_amount |
| 원천(Write Model) | OrderItem(seller_id별 매출) · Refund(환불 차감) |
| 갱신 방식 | **배치(일 1회 마감)** — db-schema §1.11·§2.8 "집계 테이블(배치 갱신)" |
| 집계 키 | (seller_id, sale_date) |
| 집계 단위 | 판매자 × 일자 |
| 재계산 가능 | 가능 — 특정 일자 재배치(idempotent upsert) |

> **배치 채택 이유(M-16)**: refund_amount·net_amount는 당일 확정값 마감이 필요하다. 이벤트 즉시 집계는 같은 날 매출·환불이 섞여 중간 상태가 노출될 수 있어 일 마감 배치가 적합하다.

### 2.3 조회 VIEW (파생 — 테이블 아님)

| 뷰명 | 용도 | 방식(§1.11) |
|---|---|---|
| vw_seller_sales_monthly | 월간 매출(SellerSalesDaily 30개 GROUP BY) | 즉시 집계 VIEW (SellerSalesMonthly 테이블 폐기 대체) |
| vw_order_admin | 주문 관리 화면(단순 JOIN·조건·페이징) | MERGE VIEW |
| vw_seller_dashboard | 판매자 대시보드 | MERGE VIEW |
| vw_buyer_grade_history | 등급 변경 이력(grade_changed_reason 제거 대체·db-schema §2.1) | VIEW (AuditLog 기반) |

> VIEW는 테이블이 아닌 조회 파생. 본 트랙은 뷰명·용도·방식만 정의한다. 실제 SQL·ALGORITHM·인덱스는 PR-04/구현 이연(baseline-plan §5).

---

## 3. Write↔Read 동기화 패턴

db-schema §1.11 조회 최적화 3패턴을 Read Model별로 매핑한다.

| 케이스 | 방식 | 본 도메인 매핑 |
|---|---|---|
| 이벤트 기반 실시간 집계 | Aggregate 테이블(이벤트 핸들러) | BuyerPurchaseAggregate ← E6 PurchaseConfirmed |
| GROUP BY·대량 집계 | 집계 테이블(배치) 또는 즉시 집계 VIEW | SellerSalesDaily(배치) · vw_seller_sales_monthly(VIEW) |
| 단순 JOIN·조건·페이징 | MERGE VIEW | vw_order_admin · vw_seller_dashboard |

- **이벤트 즉시 동기화**: E6 PurchaseConfirmed 수신 → BuyerPurchaseAggregate 누적(등급 산정 실시간성 확보).
- **배치 동기화**: SellerSalesDaily 일 1회 마감(환불 확정 정확성).
- **즉시 집계 VIEW**: vw_seller_sales_monthly는 Daily 30개 GROUP BY(저부하·별도 테이블 불필요).

---

## 4. 멱등성·재시도

- **이벤트 핸들러(BuyerPurchaseAggregate)**: event_id + 집계 키(buyer_id) 조합으로 중복 수신 가드. E6 중복 적재 방지(domain-events.md E6 멱등성 = order_item_id 기준 1회).
- **배치(SellerSalesDaily)**: idempotent upsert로 재실행 안전. 동일 일자 재배치 시 덮어쓰기(중복 누적 금지).
- **복구 경로**: Read Model 손상·이벤트 유실 시 원천(Write Model)에서 재집계. 재계산 가능성(§2)이 멱등 복구를 보장한다.

---

## 5. 외부 이연

- **CQRS 본격 도입(Read DB 물리 분리)**: 트래픽 증가 시점 재검토(db-schema §1.11). 본 PR은 동일 DB 내 Read Model까지만 정의.
- **VIEW 실제 SQL·ALGORITHM·인덱스 전략**: PR-04(ddl-ready-checklist·index-strategy).
- **이벤트 핸들러·배치 잡 구현(스케줄러·워커)**: 구현 단계(baseline-plan §10 배치/워커 이연).
