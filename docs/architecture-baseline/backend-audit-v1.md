# zslab-mall 백엔드 전수 검사 결과 v1 (Track 56)

> Phase B(기능 갭)+B'(품질/잔존물) 통합. 기준선: backend-standard-baseline-v1(기능)·backend-quality-baseline-v1(품질).
> 정찰 원본: docs/track-56/recon-report.md(983줄·gitignored). 모든 판정 file:line 실측·MCP 교차검증 병행.
> 판정: ✅구현 ⚠️부분 ❌미구현 ⊘의도적제외. 신뢰도: 파일+MCP 직접확인은 [MCP], 정찰 기반은 [recon].

## 1. 종합 판정
- **"완료·운영 가능": 조건부 가능.** 치명 결함 BL-8(BuyerProfile 미생성) 1건 수정 시 거래 핵심(주문~정산) 운영 가능.
- 거래 핵심거래군(order·payment·delivery·claim·refund·settlement): 상태전이·이벤트·멱등·불변식 견고. 품질 양호.
- 갭은 회원/셀러 생명주기·리뷰 부재에 집중(대부분 의도적 이연·단일운영자 MVP 범위 밖).

## 2. 기능 갭 (Phase B·A 기능표준 대조)

### 핵심거래군 (6도메인)
- 주문 생성·조회·부분취소·구매확정: ✅ [MCP] BuyerOrderConfirmService.java:53
- 결제 승인·취소·부분환불: ✅ RefundService.java:85(명시 amount·과환불 차단)
- 배송 송장·추적·완료: ✅ DeliveryService(markShipping/markDelivered)
- 클레임 접수·승인·거절(취소/반품/교환): ✅ ClaimService·ClaimType 3종
- 정산 수수료·순액: ✅ [MCP] net=gross−fee−refund·fee=gross×rate/10000 (SettlementCreationService.java:75)
- 에스크로: ⊘ 전용필드 없음·confirmed_at 정산보류로 패턴 충족
- 이행상태 별도축: ⊘ 멀티벤더 OrderItem 분할이 대체
- 구매확정→정산 이벤트: ⊘ 의도적 미배선·월배치 풀집계(BuyerOrderConfirmService.java:17 박제)
- 클레임 귀책별 배송비: ⊘ D-94 Q7 박제(ClaimApprovedHandler.java:26) → BL-1

### 잔여 도메인
- 회원 가입·역할분리·권한제어: ✅ UserRole N:M·SecurityConfig·DbRoleAuthorization
- 회원 프로필 조회/수정·주소록·탈퇴/휴면: ❌ 엔티티/스키마만·실행 미구현(User.java:22 "Track 8+ 이연") → BL-3·4·5
- 셀러 입점: ✅ 관리자 provision(SellerProvisioningService.java:71)
- 셀러 승인 워크플로우·KYC/KYB: ❌ 상태전이·승인 API 없음(Seller.java Javadoc "후속 트랙 이연")·SellerStatus 미사용값 동반 → BL-2
- 상품 등록·옵션/변형·승인: ✅ ProductRegistrationService·approve/reject(PENDING→SALE)
- 상품 이미지 등록 write: ❌ 조회만 → BL-6
- 재고 예약-확정 2단계: ✅ 주문시 reserve·결제완료시 commit
- 장바구니·체크아웃·카테고리·알림·감사: ✅
- 등급(grade): ⚠️ 구현됐으나 BuyerProfile 미생성으로 불변식 깨짐 → BL-8
- 리뷰(review): ❌ 도메인 완전 부재(패키지·엔티티·마이그레이션 전무) → BL-7

## 3. 실결함·부채

### BL-8 BuyerProfile 미생성 (실결함·유일 치명) [MCP]
- UserService.register가 User+UserRole만 생성·BuyerProfile 미생성.
- GradeService.recalculate:74가 findById().orElseThrow(IllegalStateException) — 프로필 없으면 단건 500, 배치(findAll)는 순회 누락.
- GradeService.java:66 "모든 buyer는 가입 시 프로필 보유" 불변식이 코드로 미보장.
- 권고: 트랙화(우선). 가입 시 BuyerProfile 생성 배선 or grade 방어 로직.

### BL-9 순환의존 (부채·심각도 낮음) [MCP]
- refund↔claim: RefundService↔ClaimRepository / ClaimService↔RefundRepository 상호 직접 주입.
- payment↔refund: PaymentService↔RefundRepository / RefundService↔PaymentRepository 상호 주입.
- Service→Repository 직접 주입(강결합)이나 도메인상 정당한 교차 aggregate 조회. 리팩터 후보.

## 4. 잔존물 (Phase B'·Negative Checklist)
- 미사용 Enum 6값: SellerStatus.SUSPENDED·TERMINATED / ProductStatus.DRAFT·APPROVED·HIDDEN·STOPPED (참조 0)
- 고립 Aggregate: code(CodeGroup·Code·2 Repository·프로덕션 참조 0·테스트만)
- 미사용 Repository 10종: Code·CodeGroup·Permission·RolePermission·BuyerGrade·BuyerPurchaseAggregate·UserAddress·WithdrawnUser·WithdrawnSeller·SellerSalesDaily (주입 0)
- 미사용 테이블 3종: buyer_purchase_aggregate·seller_sales_daily·withdrawn_seller (매핑만·런타임 0)
- 미구현 이벤트: InventoryAdjusted(클래스·발행·소비 0)
- 잔존물 아님(정상): Mock 2종(의도적 seam)·소비처0 이벤트 없음·TODO/FIXME 0·BuyerProfileRepository(사용중)

## 5. 품질 12축 (Phase B'·A' 품질표준 대조)
- 충족(8): API설계(2)·DB설계(3)·운영성MDC(5부분)·보안BOLA/JWT(6)·이벤트일관성(7)·데이터라이프사이클(9)·코드품질(10·BL-9 제외)·구성관리(11)
- 부분(3): 축4 테스트유형(API/Auth 3셀 공백·대체커버)·축5 graceful shutdown 미설정·축12 Clock 미주입 34건
- ⊘(현단계): 분산트레이싱·Outbox/DLQ·CSRF·RateLimit·실어댑터 장애대응
- 배포단계 점검: CORS(프론트 착수 시)

## 6. 처리 권고 백로그
| ID | 항목 | 권고 | 등급 |
|---|---|---|---|
| BL-8 | BuyerProfile 미생성 | 트랙화(우선) | A |
| BL-7 | review 도메인 | 트랙화 or ⊘(판단 필요) | 미정 |
| BL-5 | 탈퇴/휴면(개인정보) | 트랙화 | B |
| BL-3·4 | 프로필/주소록 | 트랙화 | B |
| BL-6 | 상품 이미지 등록 | 트랙화 | B |
| BL-2 | 셀러 승인+미사용 enum | ⊘ or 트랙화 | B |
| BL-9 | 순환의존 | 부채·리팩터 후보 | 낮음 |
| BL-1 | 귀책별 배송비 | ⊘(D-94 Q7) | — |
| 잔존물 | enum·테이블·Repository·code | 정리 트랙(선택) | B |
| 품질 | Clock·graceful shutdown | 개선(배포 전 shutdown 권장) | — |

## 7. 검사 방법론 (재현성)
- Phase A/A': 웹 표준 리서치 기준선 → 외부 검토 반영.
- Phase B/B': Claude Code read-only 정찰(workflow 독립 skeptic 재검증) → Claude.ai MCP 교차확인.
- MCP 직접 재검증 항목: 구매확정 이벤트 미배선·정산 트리거·귀책배송비(D-94 Q7)·BuyerProfile 미생성·순환의존 import — 전부 자가보고 승격 전 실코드 확인.
