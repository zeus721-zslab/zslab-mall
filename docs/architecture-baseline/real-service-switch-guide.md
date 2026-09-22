# 실 서비스 전환 가이드 — PG · SMS · 이메일 · 비밀번호 찾기

> Track 97 D-209. Mock 어댑터를 실 구현체로 바꿀 때 손대는 지점·필요 env·절차·확인 사항. 모든 인용은 작성 시점(2026-09-22·브랜치 feat/track-97-real-service-readiness)의 file:line이며 정찰 원본은 docs/track-97/recon-report-readiness.md(gitignore·로컬). 위치: docs/architecture-baseline/(docs/infra/는 .gitignore 미추적이라 이동·D-209 §8).
> 이 문서는 "무엇을 어디서 바꾸는가"만 다룬다. 실 구현체·웹훅 서명 검증·비밀번호 찾기는 **미구현**이며 해당 트랙에서 결정(D-XX)과 함께 진행한다.

## 0. 공통 구조

| 항목 | 위치 |
|---|---|
| 선택 프로퍼티 | `backend/src/main/resources/application.yml:91-98` — `zslab.payment.gateway=${PAYMENT_GATEWAY:mock}` · `zslab.notification.sms-sender=${SMS_SENDER:mock}` · `zslab.notification.email-sender=${EMAIL_SENDER:mock}` |
| compose 전달 | `docker-compose.mall.yml:40-42`(backend environment·기본 mock). 값 변경은 **컨테이너 재생성**(`docker compose … up -d`)이 필요하다 — `docker restart`는 environment를 다시 읽지 않는다 |
| env 자리 | `.env.example:113-115`(선택 키) · `:121-136`(PG_*·SMTP_*·SMS_API_KEY — 현재 미참조 자리) |
| Mock 조건 | `@ConditionalOnProperty(name=…, havingValue="mock", matchIfMissing=true)` — `MockPaymentGateway.java:23` · `MockPaymentCallbackController.java:26` · `MockPaymentCallbackService.java:32` · `MockSmsSender.java:16` · `MockNotificationSender.java:17` |
| 검증 테스트 | `backend/src/test/java/com/zslab/mall/common/config/ExternalServiceSelectionTest.java`(DB 없음·키 3종 × 미지정/mock/실 값) |

**동작 원칙**: 키가 `mock` 외 값이면 Mock 빈이 빠지고, 같은 값으로 등록된 실 구현체가 없으면 `PaymentGateway`·`SmsSender`·`NotificationSender` 주입 지점에서 **기동 실패**한다(조용한 Mock 폴백 없음·의도). 실 구현체는 `@ConditionalOnProperty(name="zslab.payment.gateway", havingValue="<값>")`으로 등록해 포트 빈이 항상 1개가 되게 한다.

**운영 fail-fast 권고**: 실 모드 전환 시 필수 키(API key·secret)는 `application-prod.yml`에서 기본값 없이 선언해 미주입 시 기동 실패하게 한다 — `application-prod.yml:22-29`의 `jwt.secret: ${JWT_SECRET}`·`bank-account.encryption-key: ${BANK_ACCOUNT_ENCRYPTION_KEY}` 동형. base `application.yml`에는 로컬 더미를 두지 말고(실 키에 더미는 의미 없음) 실 구현체가 blank를 거부하게 한다(`BankAccountEncryptionConfig` 패턴).

---

## 1. PG

### 1-1. 교체 지점
| 구분 | 파일:라인 | 할 일 |
|---|---|---|
| 포트 | `payment/gateway/PaymentGateway.java:14,22,33` — `provider()` · `requestPayment(attemptKey, amount, method)` → 결제창 URL · `refund(paymentPgTid, amount)` → `PgRefundResponse` | 실 구현체가 구현. 계약 무변경 |
| 응답 타입 | `payment/gateway/PgRefundResponse.java`(pgRefundId·success·failureReason) | 실 PG 응답을 이 레코드로 매핑 |
| Mock 구현체 | `payment/gateway/MockPaymentGateway.java`(:23 조건) | 그대로 둔다. `PAYMENT_GATEWAY=<값>`이면 빠짐 |
| 실 구현체 추가 | `payment/gateway/<Provider>PaymentGateway.java`(신규) | `@Component` + `@ConditionalOnProperty(name="zslab.payment.gateway", havingValue="<값>")`. `PaymentGatewayException`(`PaymentGatewayException.java:10`)을 던지면 `PaymentService.initiate` TX 롤백·INITIATE_FAILED(§5) |
| 결제 콜백 | `payment/controller/PaymentWebhookController.java:21,31-35` `POST /api/webhooks/payments` · DTO `PaymentCallbackRequest.java:25-31`(provider·callbackType·paymentAttemptKey·pgTid·occurredAt·metadata) | PG 웹훅 페이로드 → 이 DTO로 변환하는 어댑터 컨트롤러 또는 DTO 확장 |
| 환불 콜백 | `refund/controller/RefundWebhookController.java:22,31-36` `POST /api/webhooks/refunds` · `RefundCallbackRequest`(pgRefundId·status·failureReason) | 동형 |
| Mock 자동 완료 | `payment/gateway/MockRefundAutoCallbackListener.java:26` · `refund/scheduler/MockRefundPendingRecoveryScheduler.java:29`(`@ConditionalOnBean(MockPaymentGateway.class)`) | 손대지 않음. Mock 빈이 빠지면 자동 미등록(ExternalServiceSelectionTest 단언) |
| Mock 전용 엔드포인트 | `payment/controller/MockPaymentCallbackController.java:26`(:36 `POST /api/v1/payments/mock-callback`) · `payment/service/MockPaymentCallbackService.java:32` | 실 모드에서 자동 미등록(404). `common/security/SecurityConfig.java:88-90` BUYER 매처는 무해하게 남는다 → 실 전환 트랙에서 제거 |
| FE 결제창 | `frontend/app/lib/payment-redirect.ts:14-17` — `redirectUrl.origin !== MOCK_PG_ORIGIN`이면 `{kind:'external'}` · `frontend/app/pages/checkout/index.vue:149-156` `navigateTo(url, {external:true})` · `MOCK_PG_ORIGIN` = `frontend/app/lib/constants/payment.ts:7`(BE `MockPaymentGateway.java:21`과 동일) | 실 PG는 코드 무수정. 결제창에서 돌아오는 return URL 페이지는 PG사 흐름에 맞춰 신설 |
| Mock 결제 페이지 | `frontend/app/pages/payment/mock.vue` | 실 모드에서 도달 불가(redirectUrl이 외부). 제거 여부는 실 전환 트랙 |
| 데모 시드 | `scripts/demo-seed/seed.py:557-559`(mock-callback SUCCESS) | 실 모드에서는 404 → 시드 결제 단계 재설계 필요 |

### 1-2. 필요 env
`.env.example:121-125` `PG_PROVIDER`·`PG_API_KEY`·`PG_SECRET_KEY`(현재 미참조 자리). 실 구현체가 `@Value`/`@ConfigurationProperties`로 읽도록 `application.yml`(+`application-prod.yml` 기본값 없음)·`docker-compose.mall.yml` backend environment에 연결한다. 키 이름은 PG사에 맞춰 바꿔도 된다.

### 1-3. 전환 절차
1. 실 구현체·콜백 어댑터·IT 추가 → `./gradlew.bat test --rerun-tasks` 0 fail.
2. 웹훅 보안 체크리스트(§1-4) 완료 → `/api/webhooks/**` 서명 검증 IT GREEN.
3. gateway `return 404` 해제(§1-5) — **서명 검증이 배포된 뒤**에만.
4. `.env`에 `PAYMENT_GATEWAY=<값>` + PG 키 → `docker compose -f docker-compose.mall.yml [-f docker-compose.dev.yml] up -d`(재생성).
5. 기동 로그 `Started ZslabMallApplication` · ERROR 0 · `MockRefundAutoCallbackListener`/`MockRefundPendingRecoveryScheduler` 미등록(Mock 로그 `[MockPaymentGateway]` 0).
6. 실 결제 1건 → 웹훅 수신 → Payment PAID · 환불 1건 → 환불 웹훅 → Refund COMPLETED 확인.

### 1-4. 웹훅 보안 체크리스트(D-198 §8 전 항목·decisions.md:11722-11731·현재 전부 미구현)
- [ ] PG 서명 검증 필터(raw body 캐싱·HMAC·타임스탬프 창·nonce) — `/api/webhooks/**` 한정. 현재 main에 `hmac`·`signature`·`ContentCachingRequestWrapper` 0건(`JwtTokenProvider.java:39`의 HmacSHA256은 JWT용).
- [ ] provider 화이트리스트(`PaymentGateway.provider()`와 일치 강제)·`@Size(max=50)`·occurredAt 허용 범위 — 현재 `PaymentCallbackRequest.java:26` `@NotBlank`만·`PaymentService.handleCallback`(:173-195)은 provider를 검증 없이 `payment.complete`(:299)에 기록.
- [ ] 금액 대조(PG 승인액 vs `payment.amount` → 422) — 현재 DTO에 금액 필드 없음.
- [ ] `/api/webhooks/refunds` 동형 적용.
- [ ] IT: 무서명 401·서명 위조 401·nonce 재사용·provider 불일치·금액 불일치.
- [ ] `SecurityConfig.java:57-58` `/api/webhooks/**` permitAll 유지 여부(서명 필터가 인증을 대신) 결정.

### 1-5. gateway 404 해제 조건(LT-29·`docs/troubleshooting/live-traps.md:638-650`)
`docs/infra/05-ssl-domain.md:64-70` `location ^~ /api/webhooks { return 404; }`는 운영·로컬 동일. 해제는 §1-4 서명 검증 + PG 발신 IP 화이트리스트가 같이 들어갈 때만(zslab 수동·스냅샷 `05-ssl-domain.md` 즉시 갱신). 판별은 응답 본문 출처(nginx html 404 vs RFC7807 JSON·`live-traps.md:650`).

### 1-6. 전환 후 확인 사항
- **Mock 자동 완료 소멸**: 승인 API 응답이 COMPLETED → PENDING으로 바뀐다. 실 Mock 빈에 의존한 IT 2건 — `AdminOrderIntegrationTest.java:185-208`(T3·Refund 자동 COMPLETED) · `Track80CancelFlowIntegrationTest.java:230-239`(T3·status COMPLETED) — 실 모드 프로파일에서는 단언이 달라진다(테스트는 기본 mock으로 돌므로 현재 무영향·실 모드 IT를 따로 둘지 결정). 데모 시드 `seed.py:607` 주석의 동기 완료 가정도 동일.
- **`uk_payment_provider_pg_tid`**(`V3__payment_track3.sql:49`·`PaymentService.java:69`): provider 값이 `MOCK_PG` → 실 provider로 바뀐다. 기존 Mock 결제 행과 유니크 충돌은 없음(provider가 다름). 409 판별(`PaymentService.java:199-212`)은 제약명 문자열 의존(D-198 §8 전제).
- **환불 PENDING 복구**: Mock 복구 스케줄러가 빠지므로 유실 환불 콜백은 PG 재전송 또는 운영자 수동 initiate(`RefundService.java:150-160` 주석)에 의존.
- `SecurityConfig.java:88-90` mock-callback 매처 제거 · `pages/payment/mock.vue`·`e2e/mock-payment.spec.ts`·`test/unit/useCheckout-mock-callback.spec.ts` 처리 결정.

---

## 2. SMS

### 2-1. 교체 지점
| 구분 | 파일:라인 | 할 일 |
|---|---|---|
| 포트 | `notification/adapter/SmsSender.java:11-21` `send(phoneNumber, content)`·실패는 `RuntimeException` | 실 구현체가 구현 |
| Mock | `notification/adapter/MockSmsSender.java:16`(조건) | 그대로 |
| 실 구현체 추가 | `notification/adapter/<Vendor>SmsSender.java`(신규) | `@Component` + `@ConditionalOnProperty(name="zslab.notification.sms-sender", havingValue="<값>")`. 로그는 `PhoneMasker`(`MockSmsSender.java:23`)로 번호 마스킹 유지 |
| 호출부(무변경) | `NotificationService.java:414,443,497`(dispatch 람다) · 임시 비밀번호 `AdminMemberCommandService.java:157-162` · `AdminMemberProvisioningService.java:126-131` | — |
| 문구 | `notification/template/NotificationMessages.java`(임시 비밀번호) · 나머지 SMS 본문은 `NotificationService` 각 record 메서드 인라인 | — |

### 2-2. 필요 env
`.env.example:134-136` `SMS_API_KEY`(현재 미참조 자리) + 업체별 추가 키. yml·compose 연결·prod 기본값 없음(§0 fail-fast).

### 2-3. 전환 절차
1. 실 구현체 + 단위 테스트(HTTP 클라이언트 mock) 추가.
2. **임시 비밀번호 TX 분리**(D-178 §8·decisions.md:10805 · D-204 N9·decisions.md:11978): 현재 `sendSensitiveSms` FAILED → `TemporaryPasswordDeliveryFailedException`(`AdminMemberCommandService.java:162`·`AdminMemberProvisioningService.java:131`) → 비밀번호 변경·notification_log **전체 롤백**. 실 업체는 호출 성공 후 롤백 불가이므로 "PENDING 커밋 → 발송 → SENT/FAILED 별도 TX"로 전환하고 "발송 실패 시 기존 비밀번호 유지" 정책을 폐기한다. 관련 IT(502 계약) 변경.
3. 업체 SDK·HTTP 클라이언트의 **요청 본문 로깅 차단** 확인(임시 비밀번호 평문·D-178 §8). `MockSmsSender.java:22-23`처럼 본문 길이만 남긴다.
4. `.env` `SMS_SENDER=<값>` + 키 → compose 재생성 → 기동 로그 `[MockSmsSender]` 0.
5. 클레임 요청 1건으로 실발송 확인 → `notification_log.status=SENT`.

### 2-4. 전환 후 확인
- 발송 실패는 `dispatch`(`NotificationService.java:517-529`)가 FAILED·`zslab.notification.failed{event,channel}` 계측·warn만 남기고 **재발송 없음**. 재시도 큐는 실 어댑터 도입 후 결정(CLAUDE-DEV 5대 기조 4 "실 어댑터가 도입돼야만 호출되는 경로").
- 수신번호 원천: 구매자 `User.phone`(`User.java:42`·nullable·없으면 skip+warn `NotificationService.java:397`) · 셀러 `Seller.contactPhone` → OWNER `user.phone`(`NotificationService.java:455-472`).
- D-169 §1 C5: 발송은 요청 스레드 AFTER_COMMIT 동기 — 실 업체 지연이 응답 시간에 더해진다. `@Async` 전환은 실 어댑터 시 재검토(decisions.md D-169 :14).

---

## 3. 이메일

### 3-1. 교체 지점
| 구분 | 파일:라인 | 할 일 |
|---|---|---|
| 포트 | `notification/adapter/NotificationSender.java:16-25` `send(NotificationLog)` | **계약 유지**(D-209 §1-A). 수신 주소는 어댑터가 조회 |
| Mock | `notification/adapter/MockNotificationSender.java:17`(조건) | 그대로 |
| 실 구현체 추가 | `notification/adapter/SmtpNotificationSender.java`(신규) | `@Component` + `@ConditionalOnProperty(name="zslab.notification.email-sender", havingValue="<값>")`. `notificationLog.getRecipientUserId()`로 `User.email`(`User.java:36`·nullable) 조회 → 주소 없으면 `RuntimeException`으로 FAILED 전이(dispatch가 처리) 또는 skip 정책 결정. `recipient_user_id`는 nullable(`NotificationLog.java:40`·셀러 SMS 경로 null) |
| 의존성 | `backend/build.gradle.kts`(현재 mail 의존성 없음) | `spring-boot-starter-mail` 등 추가 |
| 호출부(무변경) | `NotificationService.java:508`(EMAIL 12경로) | — |
| 문구 | 제목·본문은 `NotificationService` 각 record 메서드 인라인(예 :95·:114). 템플릿 파일 없음 | HTML 템플릿이 필요하면 별도 결정 |

### 3-2. 필요 env
`.env.example:127-132` `SMTP_HOST`·`SMTP_PORT`·`SMTP_USERNAME`·`SMTP_PASSWORD`(현재 미참조 자리) → `spring.mail.*` 또는 자체 프로퍼티. prod 기본값 없음.

### 3-3. 전환 절차
1. 실 구현체 + User 조회 + 단위 테스트.
2. `.env` `EMAIL_SENDER=<값>` + SMTP 키 → compose 재생성 → `[MockNotificationSender]` 0.
3. 주문 1건으로 OrderPlaced·PaymentCompleted 2통 실발송 확인.

### 3-4. 전환 후 확인
- EMAIL 12경로(주문·결제·클레임 5·배송 2·환불 실패·픽업)가 **전부 실발송**된다 — 데모 시드·테스트 계정 이메일(`@zslab.test` 등)로 외부 발송되지 않게 도메인 필터 또는 시드 계정 정리 필요.
- `User.email` null(탈퇴 비식별화 D-22) 수신자 처리 정책.
- SMS와 동일: 재발송 없음·요청 스레드 동기.

---

## 4. 구매자 비밀번호 찾기 — 설계 메모(미구현·추천 없음)

### 4-1. 있는 것
| 요소 | 위치 |
|---|---|
| 관리자 임시 비밀번호 발급·1회 표시 | `AdminMemberController.java:97-99` → `AdminMemberCommandService.resetPassword`(:135-166) · D-204 |
| 강제 변경 플래그·FE 가드 | `user.password_change_required`(V28) · `frontend/app/middleware/password-change.global.ts` · `lib/password-change-guard.ts` |
| 기존 토큰 무효화 | `user.credentials_changed_at`(V28) · `AuthenticatedUserStateVerifier.java:40-51`(iat < 갱신 초 → 401) — 재설정 완료 시 그대로 재사용 가능 |
| 셀프 변경 | `PATCH /api/v1/users/me/password`(`UserController.java:44`) |
| 발송 경계 | `SmsSender` + `NotificationService.sendSensitiveSms`(:490-499·마스킹 저장) |

### 4-2. 없는 것
재설정 토큰 저장소·만료·1회성·발급 횟수(D-204 X4) · 공개 요청/확인 엔드포인트(permitAll은 `/api/v1/auth/**` `SecurityConfig.java:63`·실매핑 `POST /api/v1/auth/login`뿐) · FE 진입점(로그인 페이지 링크 없음) · 레이트 리밋·계정 열거 방지(앱 `bucket4j`/`resilience4j` 없음·gateway `limit_req` 없음) · 이메일 주소 기반 발송 계약.

### 4-3. 선택지(나열)
- 토큰: (a) `password_reset_token` 테이블(Flyway·해시 저장·만료·used_at) / (b) 서명 토큰(JWT·무저장·`credentials_changed_at`으로 1회성 대체).
- 채널: (a) SMS — `SmsSender`·`sendSensitiveSms` 재사용·`User.phone` 필요 / (b) 이메일 — §3 실 구현체 + 링크형 본문·`User.email` 필요.
- 레이트 리밋: (a) gateway nginx `limit_req`(운영 conf·저장소 밖·`05-ssl-domain.md` 스냅샷 갱신) / (b) 앱 필터(bucket4j 등 의존성 추가).
- 응답 통일: 존재/부재 무관 동일 응답(열거 방지)과 `PaymentNotFoundException` 404 은닉 관례(`MockPaymentCallbackService.java:22-23`) 참고.

### 4-4. D-204 이월(decisions.md:11943-)
X2 관리자 영역 변경 강제 없음 · X3 SUPER_ADMIN 재발급 경로 0(DB 직접 갱신뿐) · X4 유효기간·횟수 제한 없음 · N1 gateway 캐시/로그 확인 · N9 실 SMS 시 TX 분리(§2-3 2).

---

## 5. 회귀 위험 요약
1. 실 구현체 없이 `mock` 외 값 → 기동 실패(의도). prod 스모크(`ProdSecurityContextSmokeTest`·`ProdBankAccountKeyFailFastTest`)는 키 미지정(mock)이라 무영향.
2. Mock 자동 완료 소멸(§1-6) — IT 2건·데모 시드.
3. TX 분리 시 502 계약 변경(§2-3 2).
4. gateway 404 해제 전 서명 검증 부재 상태 노출 금지(§1-5).
5. compose environment 변경은 재생성 필요(§0).
