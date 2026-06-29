# Track 10 정찰 보고서 (Claim Admin API — Seller Scope)

> 트랙: Track 10 / Claim Admin API (S급 후보·외부 검토 권장·결정 라운드 미진입)
> 대상: Claim 승인·거절 Seller endpoint (ClaimService.approve/reject Service layer 기 완성·endpoint 신설)
> 후속 자연 진입: D-87 Q3 (Admin 별도 트랙)·D-88 Q2 (Seller/Admin endpoint Track 10 이연)·D-90 백로그 (ClaimApproved Javadoc 보강)
> 정찰일: 2026-06-29
> 정찰 방식: MCP read-only (Claude Code)·코드/테스트/DDL 변경 0건
> 정찰 commit: main HEAD 9ff8882 (Track 9 PR-C 머지 직후)·작업 디렉토리 read·신규 브랜치 미생성
> 정찰 룰 적용: 파일 전체 read 의무·메서드 목록 전체 확인 의무·실측 1줄 인용 의무·§6 영향 범위 예비 목록만(최종 확정 금지)

---

## 1. 트랙 개요

Track 10 = **Claim Admin API (Seller scope 우선)**. Track 9 PR-B에서 `ClaimService.approve/reject`가 Service layer로 이미 완성되었으나 **승인·거절 endpoint는 미신설**(D-88 Q2 "Seller/Admin endpoint Track 10 이연"). 본 트랙은 Buyer가 요청(REQUESTED)한 Claim을 **Seller가 승인/거절하는 endpoint**를 신설한다.

**후속 자연 진입 식별자 (정찰 룰 #4)**:
- **D-87 Q3**: "Admin API = 별도 트랙 (Track 9+)·메모리 '~70% Service 재사용' 정합."
- **D-88 Q2·후속**: "Seller/Admin 승인·거절 endpoint는 Track 10 (Admin API) 이연·D-87 Q3 정합" / "**Track 10**: Claim Admin API (Seller/Admin 승인·거절 endpoint·권한 매트릭스·**Spring Security 진입 후보**)."
- **D-90 후속·백로그**: "**Track 10**: Claim Admin API (… ClaimApproved Javadoc 보강 동반)" / "Refund 자동 트리거는 D-87 Q3 (Admin 별도 트랙·Track 10) 이연."

**가드 2 라벨**: **S급 후보**. D-88/D-90 PR-B·PR-C가 S급(외부 검토 권장)이었고, Track 10은 권한 인프라 결정(Spring Security 진입 후보)·인증 경계 신설을 동반하므로 **외부 검토 권장**. 단 정찰 후 PR 분할(Q2) 결과에 따라 라벨 재평가.

---

## 2. SoT 정독 결과 (정독 완료 박제)

| SoT | § / 항목 | 정독 결과 핵심 |
|---|---|---|
| decisions.md D-87 | Q1·Q3 | Q1 "인증 인프라(옵션 D) 선구축은 기조 4 위배"·Q3 "Admin API = 별도 트랙·~70% Service 재사용" |
| decisions.md D-88 | Q2·후속 | Q2 "Buyer endpoint만 + ClaimService 전체·Seller/Admin endpoint Track 10 이연"·후속 "Track 10 = Spring Security 진입 후보" |
| decisions.md D-89 | Q3·Q7·Q8·Q10 | Q3 ClaimInvalidStateException 422 재활용·Q7 requestedBy 미노출·Q8 소유권 Service 2단계 조회·404 정보 노출 회피·Q10 ClaimSummaryResponse 신설 |
| decisions.md D-90 | Q2·후속·백로그 | "Refund 자동 트리거 Track 10 이연"·"ClaimApproved 발행 유지(Track 10 Admin endpoint·NotificationLog 미래 소비자용)"·백로그 "ClaimApproved Javadoc 보강 Track 10 동반" |
| decisions.md D-91 | 전건 | Hibernate 전체 컬럼 UPDATE FK 재검증 트랩·통합 테스트 seed FK 부모 그래프 신설 의무(후속 핸들러 트랙 재발 차단) |
| decisions.md D-39 | 전건 | X-Buyer-Id stub·"범위 외(후속): Spring Security·Seller/Admin 인증"·미주입 401 |
| decisions.md D-40 | 전건 | **URL 액터 중립·`/api/seller/...` prefix 금지**·Controller 액터별 분리(SellerXxxController)·@PreAuthorize는 Security 정식 도입 시점 |
| invariants.md | §2.13 CLM-1~5 | CLM-1 COMPLETED 후 변경 금지·CLM-2 REJECTED 재요청=새 행·**CLM-3 Refund는 Claim 승인 후 생성**·CLM-4 전이=state-machine §2·CLM-5 활성 Claim 최대 1개 |
| state-machine.md | §2·§3 | §2 Claim: REQUESTED→APPROVED→COMPLETED / REQUESTED→REJECTED("관리자/판매자 승인·거절")·§3 OrderItem 12값·CANCEL_REQUESTED↔CANCELLED |
| aggregate-boundary.md | §2.5 | Claim Aggregate Root·Refund는 Claim 승인 후 생성·생명주기 공유(Claim 포함)·OrderItem.id·Payment.id 외부 참조 |
| live-traps.md | LT-01~03 | LT-01 CHAR public_id @JdbcTypeCode·LT-02 FK_CHECKS try-finally·LT-03 @SQLRestriction @Entity 직접 선언 |

---

## 3. 실측 결과 (STEP 1 11~14 파일군·메서드 목록·1줄 인용)

### 3.1 ClaimService.java (전체 read·219줄)

| 메서드 | 시그니처 | 상태·Seller scope |
|---|---|---|
| `request` | `Claim request(ClaimRequestCommand)` | 기 구현(PR-B)·소유권 2단계 조회(orderItem→order→buyerId)·CLM-5·CANCEL 한정 |
| `approve` | `void approve(Long claimId, LocalDateTime processedAt)` | **기 구현(PR-B)·endpoint 없음·seller_id 인자/권한 검증 부재** |
| `reject` | `void reject(Long claimId, LocalDateTime processedAt)` | **기 구현(PR-B)·endpoint 없음·seller_id 인자/권한 검증 부재** |
| `getClaim` | `ClaimResponse getClaim(String claimPublicId, Long buyerId)` | 기 구현·requested_by 소유권 판정·404 정보 노출 회피·`@Transactional(readOnly=true)` |
| `listClaims` | `PagedResponse<ClaimSummaryResponse> listClaims(Long buyerId, int page, int size)` | 기 구현·requested_by 기준·size 1~100 클램프 |
| `markCompleted` | `void markCompleted(Long claimId)` | 기 구현·APPROVED→COMPLETED·멱등 no-op·ClaimCompleted 발행 |

> 실측: `approve`·`reject`는 `claimId + processedAt`만 받으며 **Seller 권한 검증(어느 Seller가 이 Claim을 승인할 자격이 있는가)은 0건**. 트랜잭션 경계는 클래스 레벨 `@Transactional`(QB-1). save→publish 순서(D-29) 준수. **Track 10 핵심 = 이 두 메서드에 Seller 권한 검증 진입부 추가 + endpoint 신설.**

### 3.2 BuyerClaimController.java (전체 read·86줄)

> 실측: `@RequestMapping("/api/v1/claims")`·3 endpoint(POST·GET `/{claimPublicId}`·GET 목록)·`resolveBuyerId(X-Buyer-Id)`·누락 401(UnauthenticatedException)·형식 400(MalformedRequestException)·D-40 URL 액터 중립. **Seller endpoint 0건**. Track 10 `SellerClaimController`의 X-Seller-Id resolve·HTTP 변환 패턴 1:1 참조 원본.

### 3.3 BuyerOrderController.java (전체 read·125줄)

> 실측: `resolveBuyerId(String)` — `header==null||isBlank()` → 401·`Long.parseLong(trim())` 실패 → 400. 4 endpoint(생성·단건·목록·재결제). **X-Seller-Id stub은 이 패턴(`resolveSellerId`)을 1:1 복제하면 됨**(Q1 α 시).

### 3.4 ClaimRequestRequest.java + ClaimRequestCommand.java (전체 read)

> 실측: Request = record·`@Pattern("^oit_[0-9A-Z]{26}$")`·`@NotNull ClaimType`·`@NotNull ClaimReasonCode`·`@Size(max=500) reasonDetail`·`toCommand(buyerId, requestedAt)`. 승인·거절은 body 없이 path 변수(claimPublicId)만 받을 가능성이 높아 **신규 Request DTO 불요 가능**(거절 사유 입력 필요 시에만 SellerRejectRequest 신설 — Q5/Q6 연계).

### 3.5 ClaimResponse.java + ClaimSummaryResponse.java (전체 read)

| DTO | 필드 | requestedBy(buyer) 노출 |
|---|---|---|
| ClaimResponse | publicId·orderItemPublicId·claimType·status·reasonCode·reasonDetail·requestedAt·processedAt | **미노출(Q7 보안·Buyer 관점)** |
| ClaimSummaryResponse | publicId·claimType·status·reasonCode·requestedAt | 미노출 |

> 실측: 두 DTO 모두 **requestedBy(내부 buyerId) 미노출**. Seller는 "어느 구매자가 요청했는지" 식별이 업무상 필요할 수 있어 재사용 시 정보 부족 가능 → Q5 의제. 승인·거절 응답(상태 전이 결과)은 ClaimResponse 재사용 가능.

### 3.6 ClaimRepository.java (전체 read·32줄)

> 실측: `findByPublicId`·`existsActiveByOrderItemId(@Query)`·`findAllByRequestedBy(requestedBy, Pageable)`. **Seller scope 쿼리 0건** — `findAllBySellerId`·`existsByClaimIdAndSellerId` 등 부재. Seller 목록 endpoint(Q6 β/γ) 채택 시 신규 메서드 후보.

### 3.7 GlobalExceptionHandler.java (전체 read·205줄)

> 실측: ClaimNotFoundException→404(CLAIM_NOT_FOUND)·ClaimInvalidStateException→422(CLAIM_STATE_INVALID)·UnauthenticatedException→401·MalformedRequestException/IllegalArgumentException→400. **클래스 주석 line 35 "403(Spring Security 후속 트랙)·… 제외" — 403 Forbidden 매핑 부재**. Seller cross-tenant(타 Seller Claim 접근) 처리 시 404 재사용(정보 노출 회피·D-39/Q8 정합) vs 403 신설 결정 필요 → Q3 연계.

### 3.8 Claim.java (전체 read·160줄)

> 실측: `create`·`markCompleted(processedAt)`·`approve(processedAt)`·`reject(processedAt)`·`transitionTo(private)`. approve/reject는 `processedAt`만 받고 **seller 무관**(canTransitionTo 검증·ClaimInvalidStateException throw·CLM-3 책임·422 매핑). 엔티티에 seller 식별 필드 없음(OrderItem.id 외부 참조만).

### 3.9 OrderItem.java (전체 read·133줄)

> 실측: **`@Column(name="seller_id", nullable=false) private Long sellerId`** 존재·class-level `@Getter`로 **`getSellerId()` 접근 가능**(`order` 필드만 `@Getter(AccessLevel.NONE)` 억제). → Seller 권한 검증은 **Claim.orderItemId → OrderItemRepository.findById → OrderItem.getSellerId() 비교** 단일 경로로 가능(D-89 Q8 Buyer 2단계 조회보다 단순).

### 3.10 OrderItemRepository.java (전체 read·32줄)

> 실측: `findByOrderId`·`findByOrderIdIn`·`findByPublicId`·`findOrderIdById(@Query 경량 projection)`. **`findSellerIdById` 부재**. Q3 α(엔티티 getSellerId) 채택 시 신규 메서드 불요·Q3 β(projection 쿼리) 채택 시 `findSellerIdById` 신규 후보.

### 3.11 ClaimIntegrationTest.java (전체 read·600줄) + ClaimEventIntegrationTest.java (전체 read·247줄)

> 실측 (ClaimIntegrationTest): `@SpringBootTest + @AutoConfigureMockMvc + @Transactional`·Testcontainers MariaDB 11.4·MockMvc·**T7·T8 승인/거절은 `claimService.approve(claimId, now())` Service 직접 호출·MockMvc endpoint 미경유**(D-88 Q2)·seed()는 LT-02 try-finally(`FOREIGN_KEY_CHECKS` 0/1). → Track 10에서 T7·T8을 **X-Seller-Id MockMvc POST로 승격**하는 것이 자연.

> 실측 (ClaimEventIntegrationTest): `@SpringBootTest` **NO @Transactional**·`TransactionTemplate + JdbcTemplate`·AFTER_COMMIT 핸들러 검증(D-90 Q5 β)·LT-02 try-finally·**D-91 FK 부모 그래프(user·seller·product·variant) 시드**. → 승인/거절이 OrderItem 전이 핸들러를 발화하는 E2E를 검증하려면 이 패턴 준용.

### 3.12 application.yml / application-local.yml / application-prod.yml (read)

> 실측: `spring.datasource`·`jpa(ddl-auto: validate·open-in-view: false)`·`flyway`·`jackson(non_null)`·`management(actuator health,info)`·`logging(traceId)`만. **spring.security 블록·보안 설정 진입 흔적 0건**.

### 3.13 build.gradle.kts (전체 read·41줄)

> 실측: `spring-boot-starter-web·data-jpa·validation·actuator`·mariadb·flyway·ulid·lombok·test·testcontainers. **`spring-boot-starter-security` 미선언 확정**.

### 3.14 com/zslab/mall 디렉토리 (list·191 java 파일)

> 실측: **SecurityConfig·SecurityFilterChain·인증 Filter 부재**(`TraceIdFilter` MDC만·`common/config`는 AuditingConfig만). **컨트롤러 4개만**: BuyerOrderController·PaymentWebhookController·RefundWebhookController·BuyerClaimController — **Seller/Admin 컨트롤러 0건·Track 10이 최초 Seller-facing 컨트롤러**.

> 실측 (auth·seller 인프라 현황): **`auth` 패키지에 RBAC 데이터 모델 존재** — Role·Permission·RolePermission·UserRole 엔티티 + Repository + `RoleCode`(SUPER_ADMIN·ADMIN_OPERATOR·BUYER·SELLER_OWNER·SELLER_MANAGER·SELLER_STAFF). **`seller` 패키지에 `SellerUser`(userId+roleId→seller 매핑)·SellerUserRepository(JpaRepository 기본만)** 존재. **그러나 enforcement(Spring Security·@PreAuthorize·인증 Filter·SellerUser 조회 Service) 미연결 — 순수 데이터 모델만**. X-Buyer-Id stub 외 인증 인프라 부재 확정.

---

## 4. 결정 라운드 의제 후보 (Q1~Q7·옵션 α·β·γ + 추천안 + 기조 4 정합)

> 기조: 1 운영 용이성·2 객관 판단·3 과잉문서 회피·4 과잉개발 회피. 추천안은 정찰 데이터 기반 잠정안이며 **확정은 Claude.ai 결정 라운드 소관**(외부 검토 권장·S급 후보).

### Q1. 권한 인프라

| 옵션 | 내용 |
|---|---|
| α | **X-Seller-Id stub** (D-39 X-Buyer-Id 패턴 1:1·`resolveSellerId` 헤더 BIGINT) |
| β | Spring Security 본격 진입 (SecurityFilterChain·UserDetailsService·auth RBAC 연결·전 컨트롤러 영향) |
| γ | 혼합 (Buyer stub 유지·Seller만 Security) |

**추천: α (X-Seller-Id stub).** 근거: D-87 Q1 "인증 인프라 선구축은 기조 4 위배"·D-39 "Spring Security는 Seller/Admin 인증과 묶어 후속 트랙 일괄 처리"·auth RBAC 데이터 모델은 존재하나 enforcement 미연결(실측 3.14). β는 SecurityFilterChain·세션/토큰 정책·4 기존 컨트롤러 회귀 = S급 초과·별도 대형 트랙. γ는 인증 모델 이원화로 일관성 저하(기조 1·2 약화). **단 D-88 후속 "Spring Security 진입 후보" 라벨과 직접 긴장 → 본 의제가 외부 검토 1순위.**
**기조 정합**: 기조 4(과잉개발 회피) 강력 정합·기조 1(stub 즉시 운영)·기조 2(D-39 선례).

### Q2. PR 분할

| 옵션 | 내용 |
|---|---|
| α | Seller 단독 1 PR |
| β | Seller + Admin 통합 1 PR |
| γ | Seller·Admin 분리 2 PR |

**추천: α (Seller 단독 1 PR).** 근거: D-88 Q2/D-87 Q3는 "Seller/Admin endpoint"를 함께 언급하나 Admin 권한(SUPER_ADMIN·ADMIN_OPERATOR)은 RoleCode만 존재·권한 매트릭스/Service 재사용 범위 정찰 선행 필요. Seller scope만 우선 자연 응집(approve/reject Service 기 완성·OrderItem.seller_id 실측 가능). β는 PR 비대·결정 중첩(기조 1 위배). γ는 Admin 정찰 비용 선행. **Admin은 Track 10-B 또는 후속 트랙 자연 이연.**
**기조 정합**: 기조 1(리뷰 단위 적정)·기조 4(Admin 권한 매트릭스 선반영 회피).

### Q3. Seller 권한 검증 위치 및 경로

| 옵션 | 내용 |
|---|---|
| α | Service 진입부 2단계 조회 (Claim → OrderItem.getSellerId() → X-Seller-Id 비교) |
| β | Repository 쿼리 (`existsByClaimIdAndSellerId` / `findSellerIdById` projection) |
| γ | 혼합 |

**추천: α (Service 진입부).** 근거: D-89 Q8 Buyer 소유권 Service 2단계 조회 패턴 1:1·D-40 β′(Controller HTTP만·도메인 판단 Service)·**OrderItem.getSellerId() 직접 노출 실측(3.9)으로 Claim→findById(orderItemId)→getSellerId() 단일 경로 가능**(Buyer보다 단순·신규 repo 메서드 불요). β는 쿼리 응집은 좋으나 검증 책임 Service 집중 약화. **인증 실패 HTTP**: 타 Seller Claim 접근 → **404 CLAIM_NOT_FOUND 재사용**(정보 노출 회피·D-39/Q8 정합·GlobalExceptionHandler 무변경) 추천 vs 403 신설(미매핑·Spring Security 후속·실측 3.7). **approve/reject 시그니처 변경 형태**(기존 `approve(claimId, processedAt)`에 sellerId 인자 추가 vs 신규 SellerClaimService) 도 본 의제 하위 결정.
**기조 정합**: 기조 2(D-89 Q8 선례)·기조 4(신규 repo 쿼리·403 인프라 회피).

### Q4. endpoint URL 패턴

| 옵션 | 내용 |
|---|---|
| α | `/api/v1/seller/claims/{claimPublicId}/approve\|reject` (Seller scope prefix 명시) |
| β | `/api/v1/claims/{claimPublicId}/approve\|reject` (URL 액터 중립·헤더로 scope 판정) |
| γ | 혼합 |

**추천: β (URL 액터 중립).** 근거: **D-40 결정 1 "URL은 액터 중립·`/api/seller/...` prefix 금지"로 α는 명시적 위배**. D-40 결정 2 "Controller 액터별 분리" → `SellerClaimController @RequestMapping("/api/v1/claims")` + `/{claimPublicId}/approve`·`/{claimPublicId}/reject` sub-resource·X-Seller-Id 헤더로 scope 판정. BuyerClaimController와 동일 base path 공존(Spring 라우팅 정상·sub-path 상이). γ는 D-40 단일 정책에 불필요.
**기조 정합**: 기조 2(D-40 명시 결정 직접 적용)·기조 4(prefix 선점 회피·D-40 결정 5).

### Q5. DTO 재사용

| 옵션 | 내용 |
|---|---|
| α | ClaimResponse·ClaimSummaryResponse 재사용 |
| β | Seller 전용 DTO 신설 (SellerClaimResponse·SellerClaimSummaryResponse) |
| γ | 부분 재사용 (Response 재사용·SummaryResponse 신설) |

**추천: α (재사용) — Q6 종속.** 근거: 승인·거절 응답은 **상태 전이 결과(publicId·status·processedAt)** 로 ClaimResponse 재사용 충분(Q6 α=approve/reject 2개만이면 목록 DTO 불요). **단 Seller 목록 endpoint(Q6 β/γ) 채택 시** Seller는 requestedBy(어느 buyer 요청) 식별 필요 → ClaimSummaryResponse(requestedBy 미노출·Q7)로는 부족 → 그 경우 γ(SellerClaimSummaryResponse 신설·requestedBy 또는 buyer 식별자 포함). **Q6 결정 후 확정.**
**기조 정합**: 기조 4(승인/거절만이면 DTO 신설 0건)·기조 3(중복 DTO 회피).

### Q6. endpoint 개수

| 옵션 | 내용 |
|---|---|
| α | 2개 (approve·reject) |
| β | 3개 (approve·reject·목록) |
| γ | 4개 (approve·reject·단건·목록) |

**추천: α (2개·approve·reject).** 근거: D-88 Q2 "Seller/Admin **승인·거절** endpoint"가 명시 범위·최소 책임. Seller "본인 판매 Claim 목록/단건 조회"는 업무상 가치 있으나 Seller scope 쿼리(findAllBySellerId·실측 3.6 부재)·DTO(Q5) 신규 동반 = 범위 확장. β/γ는 Seller 워크플로우(목록에서 건별 승인) UX상 합리적이나 정찰 데이터상 Service/Repository 신규 비중 증가. **목록 필요성은 결정 라운드 업무 정책 항목.**
**기조 정합**: 기조 4(D-88 명시 범위 한정)·단 기조 1(Seller 실사용 UX)과 트레이드오프 → 외부 검토 의제.

### Q7. ClaimApproved Javadoc 보강 + D-50 매트릭스 정정 동반 여부

| 옵션 | 내용 |
|---|---|
| α | 본 PR 동반 (둘 다) |
| β | 별도 트랙 (둘 다) |
| γ | 부분 동반 (Javadoc만 동반·D-50 별도) |

**추천: γ (부분 동반).** 근거: **ClaimApproved Javadoc 보강은 D-90 백로그 "Track 10 동반" 명시·실측 4(WARN-1) ClaimApproved Javadoc이 "소비측 핸들러 Track 9 PR-C 소관"이라 적혀 있으나 PR-C에 ClaimApprovedHandler 부재·이벤트 미소비 — Track 10 approve endpoint 신설 시 자연 정합 동반(α 부분).** D-50 매트릭스 정정은 D-90 후속 "**별도 트랙**: D-50 매트릭스 본문 정정" 으로 이미 이연 명시 → 본 PR 동반은 scope 혼입(기조 3·4 위배) → 별도 유지(β 부분).
**기조 정합**: 기조 2(D-90 백로그/별도 트랙 명시 직접 적용)·기조 4(D-50 scope 혼입 회피).

### 의제 요약·외부 검토 권장

| Q | 추천(잠정) | 외부 검토 권장도 |
|---|---|---|
| Q1 권한 인프라 | α X-Seller-Id stub | **상** (Spring Security 진입 후보 긴장) |
| Q2 PR 분할 | α Seller 단독 | 중 |
| Q3 권한 검증 위치·HTTP | α Service 2단계·404 재사용 | **상** (403 vs 404·시그니처 변경) |
| Q4 URL 패턴 | β 액터 중립 | 하 (D-40 명시 결정) |
| Q5 DTO 재사용 | α(Q6 종속) | 중 |
| Q6 endpoint 개수 | α 2개 | 중 (Seller UX 트레이드오프) |
| Q7 Javadoc/D-50 동반 | γ 부분 | 하 (D-90 명시) |

### 4.8 결정 라운드 확정 결과 (D-92·2026-06-30·2차 라운드 갱신)

Track 10 결정 라운드(1·2차 외부 검토 수렴) 완료. 본문 위임 = decisions.md D-92. 정찰 추천안 대비 확정:

| Q | 추천(정찰) | 확정(D-92) |
|---|---|---|
| Q1 권한 인프라 | α | α′ X-Seller-Id stub + SellerActorResolver seam(common.auth) |
| Q2 PR 분할 | α | α Seller 단독 1 PR |
| Q3 권한 검증·HTTP | α | α Service 2단계 조회·404 재사용 + sub a‴ wrapper(approveBySeller·rejectBySeller)·primitive 보존 |
| Q4 URL | β | β 액터 중립(/api/v1/claims/{claimPublicId}/approve·/reject) |
| Q5 DTO | α | α ClaimResponse 재사용·Controller 재조회 |
| Q6 endpoint | α | α 2개(approve·reject) |
| Q7 Javadoc/D-50 | γ | γ 부분(ClaimApproved Javadoc 본 PR·D-50 별도 트랙) |
| Q8(WARN-4) | — | β Refund 자동 트리거 본 PR 미포함·후속 Refund Service 트랙 |
| Q9 | — | α publicId 외부 한정·내부 BIGINT Service 경계 이내 |

횡단 원칙: 권한 검증 = Service 진입부 책임·도메인 전이 메서드는 actor 비의존 시그니처 우선·액터별 권한은 wrapper 캡슐화(Track 10-B·Track 11 재사용 의무). 외부 검토 흡수: 1차 Service 조회 a→b 권고 → 2차 b 철회·a‴ wrapper 최종(primitive 시그니처 보존·테스트 가독성·후속 액터 재사용성).

1차 라운드 실측(R1~R10): Claim·ClaimRepository·ClaimNotFoundException·ClaimInvalidStateException·ClaimResponse·OrderItem·OrderItemRepository·mall 디렉토리·common/exception·ClaimEventIntegrationTest 전건 §3 가정과 일치·불일치 0건.

---

## 5. WARN

### WARN-1. ClaimApproved 이벤트 Javadoc 부정합 [해소·Q7 연계]
**내용**: `claim/event/ClaimApproved.java` Javadoc — "소비측 핸들러(Refund 생성 트리거 등)는 Track 9 PR-C 소관이며 본 PR은 발행만 한다." 그러나 **PR-C에 ClaimApprovedHandler 부재(claim/handler 실측: Requested·Rejected·Completed·RefundCompleted 핸들러만)·ClaimApproved 이벤트는 현재 어떤 핸들러도 소비하지 않음**. D-90이 Refund 자동 트리거를 Track 10 이연했으므로 Javadoc은 stale.
**해소**: D-90 백로그 "ClaimApproved Javadoc 보강(Track 10 동반)" → Q7 γ로 본 PR 동반 보강(발행 의도=Track 10 Admin endpoint·NotificationLog 미래 소비자·현재 미소비 명시).

### WARN-2. Seller 인증 인프라 부재 [Q1 결정 연계]
**내용**: X-Seller-Id stub 외 실제 인증 부재. auth RBAC 데이터 모델(Role·Permission·UserRole·SellerUser)은 존재하나 enforcement(Spring Security·@PreAuthorize·SellerUser→seller_id 조회 Service) 미연결. D-88 후속이 "Spring Security 진입 후보"로 라벨링.
**해소**: Q1 결정(α stub 추천)으로 해소. β 선택 시 별도 대형 트랙으로 분리 권장.

### WARN-3. approve/reject 인증 실패 HTTP 코드 미정 [Q3 연계]
**내용**: GlobalExceptionHandler에 403 Forbidden 매핑 부재(line 35 "Spring Security 후속 트랙·제외"). 타 Seller가 남의 Claim을 승인 시도 시 403 vs 404(정보 노출 회피) 미정.
**해소**: Q3 추천 = 404 CLAIM_NOT_FOUND 재사용(D-39/Q8 정보 노출 회피 정합·GlobalExceptionHandler 무변경). 403 신설 시 핸들러 + Spring Security 동반 → Q1 β 종속.

### WARN-4. Refund 자동 트리거 scope 경계 [결정 라운드 추가 의제 후보]
**내용**: CLM-3 "Refund는 Claim 승인 후에만 생성"·aggregate-boundary §2.5 "Refund는 Claim 승인 후 생성·Claim 포함". D-90 Q2가 "Refund 자동 트리거는 Track 10 이연" 명시. **즉 Track 10 approve endpoint가 Refund 생성을 트리거할지(ClaimApproved 핸들러 신설)가 미결 — Track 10 scope의 핵심 경계.**
**해소 방안**: 본 정찰은 "Seller 승인·거절 endpoint" 한정으로 가정(추천 Q6 α). Refund 자동 트리거 포함 여부는 **결정 라운드 추가 의제(Q8 후보)** 로 표면화 — 포함 시 Track 10 = S급 확정·RefundService 연동·외부 검토 필수.

### WARN-5. Seller 승인 E2E 패턴 선택 [구현 시 해소]
**내용**: 승인이 OrderItem 전이 핸들러(ClaimApproved → ?) 를 발화하지 않으면(현재 ClaimApproved 미소비) ClaimIntegrationTest 패턴(@Transactional+MockMvc) 충분. 만약 WARN-4로 Refund/OrderItem 동기화 핸들러를 동반하면 ClaimEventIntegrationTest 패턴(NO @Transactional·TransactionTemplate·D-91 FK 부모 그래프) 필요.
**해소**: WARN-4 결정 종속·LT-02 try-finally는 신규 통합 테스트 신설 시 의무.

### 5.6 2차 라운드 WARN 갱신 (2026-06-30)

- WARN-1(ClaimApproved Javadoc): Q7 γ 해소 — Javadoc 보강 완료. 단 stale 절("소비측 핸들러 PR-C 소관")은 append가 아닌 정정으로 교체(자가모순 회피). 이벤트 소비자는 여전히 부재(carry-over)·발행만 보장(D-92 Q8 β Refund 자동 변환 후속 트랙).
- WARN-2(Seller 인증 인프라)·WARN-3(403 vs 404): Q1 α′·Q3 α 해소 — X-Seller-Id stub·SellerActorResolver seam·404 재사용·403 미신설(GlobalExceptionHandler 무변경).
- WARN-4(Refund 자동 트리거): D-92 Q8 β로 본 PR 미포함 확정·후속 Refund Service 트랙.
- WARN-5(E2E 패턴): decisions.md D-92 WARN-5 테스트 책임 경계로 위임. SellerClaimIntegrationTest는 β 패턴(@Transactional + @RecordApplicationEvents) 채택·핸들러 발화/이벤트 소비는 ClaimEventIntegrationTest 책임.
- double/triple lookup(endpoint findByPublicId → wrapper findById → primitive findClaim): 설계 의도 — 정합·정보 노출 회피 > 성능. 미해소 유지.

---

## 6. 영향 범위 예비 목록 (WARN 해소·결정 라운드 확정 시점에 §최종 확정 — 현재는 예비)

> 정찰 룰 #3: 본 절은 **예비 목록**이며 최종 확정 표는 WARN 전건 해소·Q1~Q7 결정 확정 후 결정 라운드에서 박제한다.

### 6.1 신규 파일 후보
| 후보 | 책임 | 종속 의제 |
|---|---|---|
| claim/controller/SellerClaimController.java | approve·reject(·목록) endpoint·X-Seller-Id resolve | Q2·Q4·Q6 |
| claim/controller/request/SellerRejectRequest.java | 거절 사유 입력 DTO(필요 시) | Q5·Q6 |
| claim/controller/response/SellerClaimSummaryResponse.java | Seller 목록·requestedBy 포함(필요 시) | Q5·Q6 |
| test/.../claim/controller/SellerClaimControllerTest.java | @WebMvcTest·HTTP 매트릭스 | (필수) |
| test/.../claim/integration/SellerClaimIntegrationTest.java | E2E·X-Seller-Id·권한 격리·LT-02 | (필수) |

### 6.2 수정 파일 후보
| 후보 | 변경 | 종속 의제 |
|---|---|---|
| claim/service/ClaimService.java | approve/reject에 Seller 권한 검증 진입부(sellerId 인자 추가 또는 신규 메서드) | Q3 |
| claim/repository/ClaimRepository.java | Seller scope 쿼리(findAllBySellerId 등·목록 채택 시) | Q3 β·Q6 β/γ |
| order/repository/OrderItemRepository.java | findSellerIdById projection(Q3 β 채택 시) | Q3 β |
| common/web/GlobalExceptionHandler.java | 403 매핑 추가(Q3에서 403 채택 시·404 재사용 시 무변경) | Q3 |
| claim/event/ClaimApproved.java | Javadoc 보강(발행 의도·미소비 명시) | Q7 γ |
| claim/controller/BuyerOrderController.java 패턴 | (참조만·변경 없음) | — |

### 6.3 문서 갱신 후보
| 후보 | 변경 |
|---|---|
| decisions.md | D-92(가칭) Track 10 진입 결정 박제(Q1~Q7 + WARN-4 결정) |
| docs/track-10/recon-report.md | §4~§8 갱신(결정 흡수·WARN 해소·영향 범위 최종 확정) |
| invariants.md / state-machine.md | (필요 시·Seller 권한 invariant 또는 전이 무변경 예상) |

### 6.4 영향 0건 예상 (현재 가정)
Claim.java(approve/reject 메서드 기 존재)·ClaimStatus.java(전이 매트릭스 완성)·OrderItem.java(seller_id 기 존재)·Flyway/DDL(신규 컬럼 0건·신규 Entity 0건 예상). → **LT-01·LT-03 처치 의무 없음**(신규 Entity 0건)·**LT-02 의무 있음**(신규 통합 테스트 FK_CHECKS try-finally)·**D-91 의무 있음**(E2E가 핸들러 발화 시 FK 부모 그래프 시드).

### 6.5 영향 범위 최종 확정 (2026-06-30·2차 라운드 구현 완료)

정찰 §6 예비 목록 대비 최종 확정:

- **신규 5**: SellerActorResolver·HeaderSellerActorResolver(common/auth)·SellerClaimController·SellerClaimControllerTest(@WebMvcTest 8건)·SellerClaimIntegrationTest(β 4건)
- **수정 2**: ClaimService(approveBySeller·rejectBySeller·authorizeSellerAccess 신설·approve/reject primitive 시그니처·본문 불변·Javadoc 재정의)·ClaimApproved(Javadoc 보강)
- **예비 대비 미채택**: SellerRejectRequest·SellerClaimSummaryResponse(Q5 α·Q6 α)·ClaimRepository Seller 쿼리(Q3 α getSellerId 경로)·OrderItemRepository findSellerIdById·GlobalExceptionHandler 403(404 재사용·무변경)
- **무변경 확정**: application.yml·build.gradle.kts·Claim·ClaimStatus·OrderItem·Entity·DDL·Flyway·SecurityConfig(미존재)
- **회귀**: clean build BUILD SUCCESSFUL·전체 331 tests·0 failures·0 errors·신규 12 PASS(Controller 8·Integration 4)·기존 회귀 0
- **편차**: ①~⑥ 구현 판단(@throws 보존·@Transactional 클래스 단위·메시지 정합·toResponse DRY·param명 processedAt·seed FK=0 fallback) 적용·⑦ commit-msg.txt Co-Authored-By trailer 정정 완료

---

## 7. 후속 자연 진입 트랙 식별자 (정찰 룰 #4)

| 트랙 | 범위 | 출처 |
|---|---|---|
| **Track 10-B / Admin** | Claim Admin API (SUPER_ADMIN·ADMIN_OPERATOR 권한 매트릭스·~70% Service 재사용) | D-87 Q3·D-88 Q2(Q2 결정에 따라 Track 10 통합 또는 분리) |
| **Track 11** | Claim RETURN/EXCHANGE 확장 (Delivery 수거·재출고·ClaimReasonCode +4값·OrderItemStatus 추가 매트릭스) | D-88 후속·D-90 후속 |
| **Track 12+** | Observability (이벤트 핸들러 멱등성·이벤트 저장소·correlationId/eventId 일괄) | D-90 후속 |
| **Spring Security 트랙** | SecurityFilterChain·UserDetailsService·auth RBAC enforcement·X-*-Id stub 일괄 대체 | D-39 범위 외·D-88 "Spring Security 진입 후보"(Q1 β 시 본 트랙 흡수) |
| **Refund 자동 트리거** | Claim 승인 → Refund 생성(CLM-3)·RefundService 연동 | D-90 Q2 "Track 10 이연"(WARN-4·Q8 후보) |
| **별도 트랙** | D-50 매트릭스 본문 정정·CheckoutIntegrationTest/RefundWebhookIntegrationTest LT-02 보정 | D-90 후속 |

식별자 **6건**.

---

## 8. 진입 조건 체크리스트

- [x] 정찰 룰 #1 (신규 테스트/대상 파일 전체 read 의무) — 11~14 파일군 전체 read 완료
- [x] 정찰 룰 #2 (메서드 목록 전체 확인) — ClaimService 6·Claim 5·Repository 메서드 목록 §3 박제
- [x] 정찰 룰 #3 (§6 영향 범위 예비 목록만·최종 확정 미박제) — §6 예비 표기·WARN 해소 후 확정 명시
- [x] 정찰 룰 #4 (후속 자연 진입 트랙 식별자 명시) — §7 6건 박제
- [x] 추가 룰 (MCP read 실측 의무·인용만으로 신규 박제 금지) — §3 각 항목 1줄 실측 인용
- [x] 외부 편집 검증 의무 (read-only·코드/테스트/DDL 변경 0건)
- [x] 결정 라운드 의제 도출 (Q1~Q7 + WARN-4 Q8 후보·옵션 α/β/γ·추천안·기조 정합)
- [x] SoT 정독 (D-87~D-91·D-39·D-40·invariants §2.13·state-machine §2·§3·aggregate-boundary §2.5·live-traps LT-01~03)
- [x] 결정 라운드 진입 (D-92 박제·1·2차 외부 검토 수렴·2026-06-30)
- [x] §6 영향 범위 최종 확정 (§6.5·2026-06-30)

**진입 가능 상태**: Track 10 정찰 산출물 완성. 결정 라운드(Q1~Q7 + WARN-4)·외부 검토(S급 후보·Q1·Q3 1순위) 후 PR 진입.

**구현 상태 (2026-06-30·2차 라운드)**: 결정 라운드 D-92 확정·구현 완료(신규 5·수정 2·전체 331 tests PASS·기존 회귀 0). feat/track-10 브랜치 커밋·push 명시 지시 대기.

---

## 9. 관련 결정·SoT

| 항목 | 핵심 |
|---|---|
| D-87 Q1·Q3 | 인증 인프라 선구축 기조 4 위배·Admin 별도 트랙 |
| D-88 Q2·후속 | Seller/Admin endpoint Track 10 이연·Spring Security 진입 후보 |
| D-89 Q3·Q7·Q8 | ClaimInvalidStateException 422·requestedBy 미노출·소유권 Service 2단계·404 정보 노출 회피 |
| D-90 Q2·백로그 | Refund 자동 트리거 Track 10 이연·ClaimApproved Javadoc 보강 Track 10 동반 |
| D-91 | 통합 테스트 FK 부모 그래프 시드 의무 |
| D-39 | X-Buyer-Id stub·Seller/Admin 인증 후속 트랙 |
| D-40 | URL 액터 중립·`/api/seller/` prefix 금지·Controller 액터별 분리 |
| invariants §2.13 | CLM-1~5 (CLM-3 Refund는 승인 후 생성) |
| state-machine §2·§3 | Claim REQUESTED→APPROVED/REJECTED("판매자 승인·거절")·OrderItem 12값 |
| aggregate-boundary §2.5 | Claim Aggregate·Refund 포함·OrderItem.id 외부 참조 |
| live-traps LT-01~03 | 신규 Entity 0건 예상→LT-01·03 무·LT-02 신규 테스트 의무 |
