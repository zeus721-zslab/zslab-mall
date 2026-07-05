# Invariants (불변조건) — PR-05

> 소스: decisions.md D-21 [확정 2026-06-24]·aggregate-boundary.md §2·state-machine.md·db-schema-decisions.md §1/§2·deletion-policy.md·inventory-policy.md · D-31 [PAY-3 분리: PAY-3a Order×PAID 유일·PAY-3b (pg_provider,pg_tid) UNIQUE]
> 도메인 규칙 중 절대 깨지면 안 되는 조건. DDL 제약·Entity 검증·Domain Service 가드의 단일 레퍼런스.
> Enforcement Point = 해당 invariant를 강제하는 위치(DB CHECK·UK·FK / Service / Domain / Batch).

---

## 1. 원칙

- **invariant는 상태(state) 상위 개념**이다. 상태 전이가 합법이어도 invariant를 깨면 무효다.
- 위반 시 시스템 정합성이 붕괴한다(과판매·과환불·중복 계정 등).
- **가장 낮은 레이어(DB)에서 강제**하고, DB로 불가능한 경우에만 Service/Domain으로 올린다(D-09·D-12 정합).
- 본 문서는 **불변조건 카탈로그**다 — State Machine 전이 정의가 아니다(전이는 [state-machine.md](./state-machine.md) Order·OrderItem·Payment·Claim·Seller·Refund 6건 한정·Seller=D-23 §7·Refund=D-24 §8).
- 실제 DB CHECK·UK 제약 명세 / Entity 단위 검증 코드 / Service 가드 구현은 **외부 이연**(§5).

---

## 2. Aggregate별 Invariant

> 핵심 Aggregate(Inventory·Order·Payment·Claim·Product·Delivery)는 상세, 보조 Aggregate는 1~3건(D-21 §e).

### 2.1 User
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| USR-1 | User.email UNIQUE | 계정 중복 차단 | DB UK | 가입 시 중복 거부 | — |
| USR-2 | 탈퇴(withdrawn_at) 후 로그인 차단 | 보안 | Service(로그인 게이트) | 탈퇴 계정 접근 차단 | — |
| USR-3 | 비식별화(anonymized_at) 후 식별자 유지·민감정보 NULL/HASH | 법정 보관 + 개인정보 보호 | Batch + Domain(db-schema §2.1) | ARCHIVE 데이터 정합 보존 | — |
| USR-4 | legal_retention_until 경과 전 비식별화 금지 | 법정 보관 의무 | Batch | 보관 기간 강제 | — |

> 재가입 정책: 비식별화 완료 이후 허용 (db-schema §2.1) — D-22
> email은 비식별화(NULL 처리) 완료 후에만 재사용 허용

### 2.2 Auth
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| AUTH-1 | UserRole (user_id, role_id) 중복 금지 | 권한 중복 차단 | DB UK | 동일 역할 중복 부여 차단 | — |
| AUTH-2 | RolePermission (role_id, permission_id) 중복 금지 | 권한 매핑 중복 차단 | DB UK | 동일 권한 중복 차단 | — |
| AUTH-3 | Role.code 값집합 잠금(A분류) | 권한 무결성 | DB ENUM + enum | 역할 코드 임의 추가 차단 | — |
| AUTH-4 | 권한 회수(HARD delete) 시 AuditLog 기록 | 감사(M-19·D-12) | Service | 회수 이력 보존 | — |

### 2.3 BuyerGrade
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| GRD-1 | 활성 GradePolicy는 (grade_id·기간) 단일 | 등급 산정 모호 차단 | Service(effective 기간·is_active·version DESC LIMIT 1) | 등급 평가 결정성 보장 | — |
| GRD-2 | BuyerGrade.code 값집합 잠금(SILVER/GOLD/PLATINUM) | 등급 무결성 | DB ENUM | 등급 코드 임의 추가 차단 | — |
| GRD-3 | GradePolicy.min_amount ≤ max_amount | 구간 정합 | DB CHECK / Service | 역전 구간 차단 | — |

### 2.4 Seller
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| SLR-1 | Seller.business_no UNIQUE | 사업자번호 중복 차단 | DB UK | 동일 사업자 중복 입점 차단 | — |
| SLR-2 | SellerBankAccount.account_number 암호화 저장 | 금융정보 보호 | Domain(AES·db-schema §2.3) | 평문 저장 금지 | — |
| SLR-3 | SellerBankAccount is_primary 단일 | 정산 계좌 모호 차단 | Service(변경 시 기존 false) | 정산 대상 계좌 결정성 | — |
| SLR-4 | Seller.status 전이 = state-machine §7(B분류 SELLER_STATUS) | 판매자 상태 정합 | Domain(enum canTransition·D-23) | 비합법 상태 전이 차단 | — |
| SLR-5 | SellerUser (seller_id, user_id) 중복 금지 | 소속 중복 차단 | DB UK | 동일 소속 중복 차단 | — |
| SLR-6 | Seller TERMINATED 진입 시 WithdrawnSeller 행 생성 | 법정 보관·비식별화 추적(D-23) | Service | 종료 판매자 아카이브 누락 차단 | — |
| SLR-7 | Seller·WithdrawnSeller 동시 수정 금지·WithdrawnSeller는 TERMINATED 진입 시 INSERT 1회 + anonymized_at 갱신만 허용 | Seller=SoT·WithdrawnSeller=Snapshot Metadata(D-23·외부 검토 2차) | Service (Domain) | 종료 메타 이중 수정 차단·SoT 단일화 | — |

> 사업자 재등록 정책: 비식별화 완료 이후 허용 — D-22
> business_no는 비식별화(NULL 처리) 완료 후에만 재등록 허용
> 비식별화 흐름: WithdrawnSeller 신설·account_number 암호화 키 폐기·company_name/ceo_name/contact_email/contact_phone/business_no NULL — D-23 (전이는 state-machine §7·deletion-policy §3 대칭)

### 2.5 Settlement
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| STL-1 | net_amount = gross_amount − fee_amount − refund_amount | 정산 금액 정합 | Domain | 정산액 계산 일관 | — |
| STL-2 | Settlement.status 전이(PENDING→CONFIRMED→PAID·A분류) | 정산 흐름 정합 | Domain(enum canTransition) | 미확정 정산 지급 차단 | — |
| STL-3 | bank_account_id = 정산 시점 스냅샷 | 계좌 변경 무관 정합 | Domain | 사후 계좌 변경 영향 차단 | — |
| STL-4 | 상태·금액 변경 AuditLog 기록 | 감사(D-11) | Service | 정산 분쟁 대응 | — |
| STL-5 | status=PAID ⟺ paid_at≠null | 회계 상태 일관성(지급 시각 없는 지급 완료 차단) | Domain(markPaid) | 지급 상태·시각 정합 | — |

### 2.6 Category
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| CAT-1 | parent_id self-FK 순환 금지 | 계층 무결성 | Service(사이클 검출) | 무한 루프 차단 | DB 재귀 제약 불가(기각) |
| CAT-2 | depth = parent.depth + 1 일관 | 표시 정합 | Service | 계층 깊이 일관 | — |

### 2.7 Product
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| PRD-1 | Seller 없는 Product 금지 | 멀티벤더 무결성 | DB FK + NOT NULL | 소유자 없는 상품 차단 | — |
| PRD-2 | ProductVariant (product_id, option1~3_value_id) UNIQUE | 옵션 조합 중복 방지 | DB UK(db-schema §2.4) | 동일 옵션 조합 SKU 차단 | — |
| PRD-3 | ProductVariant는 Product 없이 생성 불가 | Aggregate 경계 | DB FK + Domain | Root 경유 강제 | — |
| PRD-4 | ProductOptionGroup 상품당 최대 3개 | 한국 쇼핑몰 표준 | Service(애플리케이션 제약) | 옵션 그룹 폭증 차단 | DB CHECK(동적 불가·기각) |
| PRD-5 | option1_value_id NOT NULL·option2/3 nullable | 옵션 구조 | DB NOT NULL | 필수 옵션 보장 | — |
| PRD-6 | Product.status 전이 = PENDING→SALE(승인)·PENDING→REJECTED(거부)만·REJECTED 종료 | 상품 공개 통제(운영자 승인 게이트) | Domain(ProductStatus.canTransitionTo) + Service(운영자·Track 50) | PENDING만 승인/거부 대상·SALE=카탈로그 노출·REJECTED=미노출 | — |

### 2.8 Inventory
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| INV-1 | quantity_available ≥ 0 | oversell 방지 | DB CHECK | 결제 차감 시 음수 차단 | Service 검증(DB 강제 누락 위험) |
| INV-2 | quantity_available = quantity_on_hand − quantity_reserved | 캐시 정합 | Application(D-09) | 변경 지점마다 재계산 의무 | DB 트리거(기각·D-09) |
| INV-3 | quantity_reserved ≥ 0 | 예약 음수 차단 | DB CHECK | 예약 해제 과다 차단 | — |
| INV-4 | quantity_on_hand ≥ 0 | 실물 음수 불가 | DB CHECK | 차감 과다 차단 | — |
| INV-5 | InventoryHistory append-only(on_hand 변동만 기록) | 이력 무결성(M-11) | Domain | 예약/해제 미기록·이력 추적 | reserved 이력화(enum 확장·기각) |
| INV-6 | Inventory : ProductVariant = 1:1 | 재고 SoT 단일(D-07) | DB UK(variant_id) + FK | 재고 분산 차단 | — |

### 2.9 CartItem
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| CRT-1 | (user_id, variant_id) UNIQUE | 중복 장바구니 차단 | DB UK(db-schema §2.5) | 동일 SKU 중복 행 차단 | — |
| CRT-2 | quantity ≥ 1 | 0개 담기 차단 | DB CHECK / Service | 빈 항목 차단 | — |

### 2.10 Order
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| ORD-1 | Order는 OrderItem 최소 1개 | 빈 주문 불가 | Service | 주문 생성 가드 | Trigger(기각·복잡) |
| ORD-2 | Order.status = OrderStatusResolver 결과 | 캐시 정합(D-04·ADR-003·D-16) | Domain Service | OrderItem 변경 후 재계산 | — |
| ORD-3 | OrderItem.seller_id 멀티벤더 혼재 허용 | 멀티벤더 정산 | Domain | OrderItem 단위 정산 분리 | 단일벤더 강제(기각) |
| ORD-4 | order_no UNIQUE | 주문번호 중복 차단 | DB UK | 표시용 주문번호 유일 | — |
| ORD-5 | OrderItem.total_price = unit_price × quantity | 금액 정합 | Service/Domain | 합계 일관 | — |

### 2.11 Payment
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| PAY-1 | Refund 총액 ≤ Payment.amount | 과환불 차단 | Domain — **Claim/Refund Domain에서 Payment.amount 누적 검증**(교차 Aggregate) | 환불 누적 초과 차단 | — |
| PAY-2 | Payment.status 전이 = state-machine §1 | 결제 흐름 정합 | Domain(enum canTransition) | 비합법 전이 차단 | — |
| PAY-3a | 한 주문에 PAID 상태 결제 행 최대 1건 (Order × PAID 유일성) | 중복 결제 차단 | Service 사전 가드 (MariaDB partial index 미지원으로 Service 단독 강제) | 과결제 방어 | — |
| PAY-3b | (pg_provider, pg_tid) 복합 UNIQUE 제약 | 동일 PG 거래 식별자 중복 INSERT 차단·콜백 멱등 방어선 | DB UNIQUE KEY + Service 사전 가드 + Entity canTransitionTo | 콜백 중복 수신 방어 | — |

> PAY-1은 **교차 Aggregate invariant**다. Refund는 Claim Aggregate(D-01 #13) 소속이나 규칙은 Payment.amount를 참조한다 → 강제 위치는 Claim/Refund Domain(Payment.amount 누적 대조). Payment 절에 기재해 "환불 총액 한도"를 결제 관점에서 단일 노출한다(§a 확정). <!-- D-31 PAY-3 분리 반영 -->

### 2.12 Delivery
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| DLV-1 | tracking_no UNIQUE | 송장 중복 차단 | DB UK | 동일 송장번호 중복 차단 | — |
| DLV-2 | Delivery는 OrderItem 없이 생성 불가(order_item_id) | 부분 배송 지원·OrderItem 1:N Delivery | DB FK + NOT NULL | OrderItem 단위 배송 추적 | — |
| DLV-3 | shipped_at ≤ delivered_at | 시간 순서 정합 | Service/Domain | 발송 전 배송완료 차단 | — |

> Delivery 상태(READY/SHIPPING/DELIVERED) **전이 규칙은 본 문서 범위 외**다 — state-machine.md §6 이연 유지. 위 invariant는 전이가 아닌 구조·시간 불변식이다.

### 2.13 Claim
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| CLM-1 | COMPLETED 후 상태 변경 금지 | 클레임 종결 보호 | Domain(state-machine §2) | 종결 클레임 재오픈 차단 | — |
| CLM-2 | REJECTED 재요청 = 새 Claim 행 | 이력 보존(D-05) | Service | 거절 이력 추적·재요청 가능 상태 복구는 Track 14·D-98 Q7 스냅샷 기반 원복으로 보장 (claim.previous_order_item_status 컬럼 기반 복원·claim-lock release 단어 의미 부재·D-90 Q3 의미 변경) | 기존 행 복귀(기각) |
| CLM-3 | Refund는 Claim 승인 후에만 생성 | 생명주기 공유(D-01) | Domain | 미승인 환불 차단 | — |
| CLM-4 | Claim.status 전이 = state-machine §2 | 클레임 흐름 정합 | Domain(enum canTransition) | 비합법 전이 차단 | — |
| CLM-5 | 동일 OrderItem 활성 Claim 최대 1개 (활성 = REQUESTED 또는 APPROVED) | 중복 클레임 차단·운영 일관성 | Service (ClaimRepository.existsActiveByOrderItemId 사전 가드) | 동일 OrderItem 활성 Claim 중복 차단 | DB partial UK (MariaDB 미지원·기각) |

> **CLM-5 비고 (D-98 Q12)**: 활성 정의(REQUESTED·APPROVED)는 picked_up_at 설정 여부와 무관하다. picked_up_at은 milestone 데이터로 활성성 판단에 영향 없음.

#### 2.13.1 Refund (RFN — 3건·Claim Aggregate 내·D-01 #13)

> Refund는 Claim Aggregate 소속(CLM-3). 별도 Aggregate 아님 — 상태 전이는 state-machine §8(D-24).

| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| RFN-1 | Refund.status COMPLETED 전이는 pg_refund_id 필수 | PG 콜백 전용 멱등성(D-24 A-d2) | Service(pg_refund_id NULL 상태 COMPLETED 전이 차단) | 멱등성 키 없는 환불 완료 차단 | — |
| RFN-2 | Refund.status FAILED·COMPLETED 불가역·재시도는 새 Refund 행 | 감사 추적성·시도별 row 독립(D-24 A-d1) | Domain(canTransition·FAILED/COMPLETED→전이 차단) | 종료 상태 재오픈 차단 | 기존 행 복귀(기각) |
| RFN-3 | 동일 pg_refund_id 중복 콜백 시 멱등 처리(no-op) | PG 콜백 멱등성(D-24 A-d2) | Service(PG 콜백 핸들러 멱등 가드) | 콜백 중복 수신 방어 | — |

### 2.14 Code
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| COD-1 | is_system=TRUE 코드 삭제·비활성 금지 | 시스템 코드 보호 | Service + Domain | 시스템 의존 코드 보존 | — |
| COD-2 | Code (group_id, code) UNIQUE | 코드 중복 차단 | DB UK | 그룹 내 코드 유일 | — |
| COD-3 | B분류 ENUM 값 ↔ Code 시드 일치 | 코드-enum 정합(db-schema §1.13) | 시드 단계 | enum↔Code 드리프트 차단 | — |

### 2.15 Attachment
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| ATT-1 | (target_type, target_id) 논리 참조·FK 없음 | polymorphic(D분류) | Domain(화이트리스트 검증) | 동적 대상 확장 허용 | DB FK(동적 확장 불가·기각) |
| ATT-2 | public_id(att_) UNIQUE | 식별자 무결성(ADR-001) | DB UK | API/URL 조회 키 유일 | — |

### 2.16 AuditLog
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| AUD-1 | append-only(수정·삭제 금지) | 감사 무결성(D-11) | Domain | 위변조 차단 | — |
| AUD-2 | 민감정보(비밀번호·결제 토큰·계좌번호·주민번호) 마스킹/제외 | 개인정보 보호(D-11·M-17) | Domain | 감사 로그 민감정보 노출 차단 | — |
| AUD-3 | diff_json = JSON·changed_fields 한정 | 변경 추적(D-11) | DB JSON(CHECK JSON_VALID) + Domain | 변경 필드 질의 가능 | LONGTEXT(질의 불가·기각) |
| AUD-4 | public_id(aud_) UNIQUE | 식별자 무결성(M-22) | DB UK | 감사 항목 조회 키 유일 | — |

---

## 3. Infra/Event Processing

> Aggregate가 아닌 이벤트 소비 기록(aggregate-boundary.md §2.7·D-18).

### 3.1 NotificationLog
| # | Rule | Why | Enforcement Point | Impact | Alternative |
|---|---|---|---|---|---|
| NOT-1 | append-only 발송 이력 | 이력 보존(ARCHIVE·D-12) | Domain | 발송 기록 위변조 차단 | — |
| NOT-2 | channel/status 값집합 잠금(A분류) | 발송 무결성 | DB ENUM + enum | 채널·상태 임의값 차단 | — |
| NOT-3 | (target_type, target_id) 논리 참조·FK 없음 | polymorphic(D분류) | Domain(화이트리스트 검증) | 동적 대상 확장 허용 | — |

---

## 4. 공통 Invariant (전 Aggregate 횡단)

| # | Rule | Why | Enforcement Point |
|---|---|---|---|
| COM-1 | public_id 부여 대상(12개) UNIQUE | API/URL 조회 키 유일(ADR-001) | DB UK |
| COM-2 | SOFT 대상 조회는 deleted_at IS NULL 가드 | 삭제분 노출 차단(D-12) | Service |
| COM-3 | 시간은 UTC·DATETIME(6) | 타임존 혼선 차단 | 글로벌 정책 §1.2 |
| COM-4 | 금액은 BIGINT(KRW 정수·DECIMAL 금지) | 통화 정밀도·오차 차단 | 글로벌 정책 §1.3 |

> public_id 부여 12개(ADR-001): usr_·slr_·prd_·var_·ord_·oit_·pay_·dlv_·clm_·rfn_·att_·aud_.
> **부여 기준** (외부 검토 2차): 외부 직접 참조 가능성(URL·API·외부 시스템 ID 노출) 기준. 조회 성능·내부 결합도와 무관. 내부 PK(BIGINT)는 별도 유지.

---

## 5. 외부 이연

- **DB CHECK·UK·FK 실제 제약 명세** → DDL 트랙(Flyway). 본 문서는 Enforcement Point 분류까지.
- **Entity 단위 검증 코드**(Bean Validation·@Enumerated 등) → Entity 트랙.
- **Domain/Service 가드 구현**(canTransition·누적 검증 등) → API/구현 트랙.
- **Read Model(참고 — Aggregate 아님·D-10)**: 불변조건 카탈로그 본문에서 제외. Write Model로부터 재생성 가능한 파생 데이터다.
  - `BuyerPurchaseAggregate`: buyer_id PK·재계산 가능(Order CONFIRMED SUM 재집계).
  - `SellerSalesDaily`: (seller_id, sale_date) 복합 PK·idempotent upsert(재배치 안전).

---

> **커버리지**: 16 Aggregate(§2.1~2.16·Refund는 Claim Aggregate 내 §2.13.1) + Infra/Event 1(§3) + 공통(§4). 도메인별 invariant ≈67건(16 Aggregate 64 + Infra/Event 3) + 공통 4 (PAY-3→PAY-3a·PAY-3b 분리·D-31). State Machine 전이는 state-machine.md(Order·OrderItem·Payment·Claim·Seller 5건 + Refund §8) 한정·본 문서와 구분.
