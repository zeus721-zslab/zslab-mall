# Track 36 γ — RECON (정찰 실측 누적)

**트랙**: Track 36 γ — 판매자 구성원 user 통합·seller_user 도메인 분리
**관련 결정**: D-121([진행중])·D-119·D-120
**작성 원칙**: 본 문서는 실측(파일 read file:line·docker MariaDB 11.4 실행 인용)만 박제한다. 미측정 항목은 §6에 "미착수"로 명시(기조 5·추측 박제 금지). Phase별 정찰 결과를 누적한다.

---

## §1 목적·범위 (Phase 1)
Track 36(γ)의 선결 제약 = seller_user UNIQUE를 복합 (seller_id, user_id)→user_id 단독으로 전환("1 user = 1 seller"). Phase 1 정찰은 이 전환의 (a) 스키마 현황 (b) 마이그레이션 안전성·트랩 (c) 데이터·테스트 충돌 여부를 실측한다. 산출 = D-121 착수 헤더·본 RECON·Flyway V12.

## §2 seller_user 스키마 현황 (실측)
정의: `backend/src/main/resources/db/migration/V1__init.sql:238-252` (13번 테이블).

- 컬럼: id(PK AUTO_INCREMENT)·seller_id·user_id·role_id·created_at·created_by·updated_at·updated_by. created_at·updated_at 둘 다 NOT NULL·created_by·updated_by NULL 허용.
- 제약(V1:248-251):
  - `UNIQUE KEY uk_seller_user (seller_id, user_id)` — 복합 UNIQUE(전환 대상).
  - FK `fk_seller_user_seller`(seller_id)→seller(id) ON DELETE RESTRICT ON UPDATE CASCADE.
  - FK `fk_seller_user_user`(user_id)→`user`(id) ON DELETE RESTRICT ON UPDATE CASCADE.
  - FK `fk_seller_user_role`(role_id)→role(id) ON DELETE RESTRICT ON UPDATE CASCADE.
- 실인덱스 레이아웃 (docker MariaDB 11.4·V1 적용 후 `SHOW INDEX FROM seller_user`):

  | Key_name | 컬럼(순서) | Non_unique | 비고 |
  |---|---|---|---|
  | PRIMARY | id(1) | 0 | PK |
  | uk_seller_user | seller_id(1), user_id(2) | 0 | 복합 UNIQUE |
  | fk_seller_user_user | user_id(1) | 1 | FK 자동 생성 인덱스 |
  | fk_seller_user_role | role_id(1) | 1 | FK 자동 생성 인덱스 |

  → **seller_id 단독 지원 인덱스 부재.** FK fk_seller_user_seller는 uk_seller_user의 seller_id(leftmost prefix)에만 의존한다(별도 seller_id 인덱스 없음). 이 겸용이 §3 트랩의 근원.

## §3 ⚠ V12 마이그레이션 트랩 (실측·핵심)
### §3.1 증상 — bare DROP 실패
당초 STEP 2 명시 SQL의 bare drop을 docker MariaDB 11.4에서 실행:
```
ALTER TABLE seller_user DROP INDEX uk_seller_user;
→ ERROR 1553 (HY000): Cannot drop index 'uk_seller_user': needed in a foreign key constraint
```
### §3.2 원인
uk_seller_user (seller_id, user_id)가 FK fk_seller_user_seller(seller_id)의 **유일 지원 인덱스를 겸함**(seller_id가 leftmost). InnoDB는 FK 지원 인덱스의 단독 제거를 거부. Flyway 적용 시 V12가 이 지점에서 실패→context 기동 실패(라이브 트랩). 정찰 docker 실측으로 사전 포착.
### §3.3 해소 — A안(단일 atomic ALTER·D-121 채택)
seller_id 보상 인덱스를 같은 ALTER에서 선행 추가:
```
ALTER TABLE seller_user
  ADD INDEX idx_seller_user_seller (seller_id),
  DROP INDEX uk_seller_user,
  ADD UNIQUE KEY uk_seller_user_user_id (user_id);
```
실측 결과(docker MariaDB 11.4):
- 적용 OK. 결과 인덱스 = PRIMARY · `uk_seller_user_user_id`(user_id·UNIQUE) · `idx_seller_user_seller`(seller_id) · `fk_seller_user_role`(role_id). 기존 auto `fk_seller_user_user`(user_id)는 신규 user_id UNIQUE에 흡수되어 중복 인덱스 잔존 없음.
- 기능 실측: 동일 user_id를 다른 seller로 2차 INSERT → `ERROR 1062: Duplicate entry '1' for key 'uk_seller_user_user_id'`. "1 user = 1 seller" 강제 확인.
- B안(복합을 비-UNIQUE 인덱스로 강등) 기각: user_id 전역 UNIQUE 이후 복합 (seller_id, user_id) prefix 조회 이득 0(seller 구성원 조회는 seller_id 단독 prefix로 충분)·2컬럼 인덱스 과잉(기조 4).
### §3.4 역방향 ROLLBACK 대칭 트랩 (실측)
V12 후 auto fk_seller_user_user가 흡수되므로, 단순 역순(uk_seller_user_user_id 제거→idx_seller_user_seller 제거→복합 재생성)도 **대칭 트랩**을 만난다:
```
ALTER TABLE seller_user DROP INDEX uk_seller_user_user_id, DROP INDEX idx_seller_user_seller, ADD UNIQUE KEY uk_seller_user (seller_id, user_id);
→ ERROR 1553: Cannot drop index 'uk_seller_user_user_id': needed in a foreign key constraint
```
(uk_seller_user_user_id가 이제 fk_seller_user_user의 유일 지원 인덱스이기 때문.) 정확한 rollback은 user_id 인덱스를 선복원하는 4-op:
```
ALTER TABLE seller_user
  ADD INDEX fk_seller_user_user (user_id),
  DROP INDEX uk_seller_user_user_id,
  DROP INDEX idx_seller_user_seller,
  ADD UNIQUE KEY uk_seller_user (seller_id, user_id);
```
실측: 적용 OK·인덱스 레이아웃이 V1 원본과 정확히 일치(PRIMARY·uk_seller_user(seller_id,user_id UNIQUE)·fk_seller_user_user(user_id)·fk_seller_user_role(role_id)). → V12 파일 ROLLBACK 주석은 이 4-op 형으로 확정.

## §4 SellerUserRepository 메서드 현황 (실측)
`backend/src/main/java/com/zslab/mall/seller/repository/SellerUserRepository.java`: `JpaRepository<SellerUser, Long>` + `boolean existsByUserId(Long)` 단 1건(D-120 RBAC SELLER 판정용). 유일 소비처 = `common/security/DbRoleAuthorization`(existsByUserId·READ). → user_id 단독 조회가 이미 유일 접근 패턴이며, 단독 UNIQUE 전환은 이후 Phase resolver user.id→seller.id 단건 해소의 선결과 정합.

## §5 데이터·테스트 충돌 실측 (전환 안전성)
### §5.1 프로덕션 seller_user INSERT 경로 부재 (자체 실측)
`main/java` 전건 grep — SellerUser 참조 4파일: DbRoleAuthorization(READ)·SellerUserRepository(interface)·SellerUser(entity·create 팩토리)·RoleAuthorization(주석). **save/INSERT 프로덕션 호출처 0건**(SellerUser.create 호출은 테스트 코드 한정). → 단독 UNIQUE 전환이 충돌할 프로덕션 INSERT 경로 없음(D-119·D-120 §8 연장·자체 재측정).
### §5.2 기존 테스트 V12 영향 분석 (실측·중복 user_id fixture 부재)
seller_user 참조 테스트 = `SellerUserRepositoryTest`(4 @Test)·`AuthControllerIntegrationTest`(seed/cleanup).

SellerUserRepositoryTest 4건:
| # | 테스트 | V12 영향 | 판정 |
|---|---|---|---|
| 1 | save_findById_success (L48) | 단건 INSERT | 무영향 GREEN |
| 2 | insert_duplicateSellerUser_throwsDataIntegrityViolation (L67) | 동일 (seller, userId) 2회 INSERT | **동일 user_id → uk_seller_user_user_id로 여전히 거부·GREEN** (단 DisplayName "UK(seller_id, user_id)"는 의미 stale) |
| 3 | insert_invalidSellerId (L80) | seller_id FK 위반 | fk_seller_user_seller(+idx) 유지·무영향 |
| 4 | insert_invalidUserId (L95) | user_id FK 위반 | fk_seller_user_user 유지·무영향 |

AuthControllerIntegrationTest: seller_user에 1 user=1 seller 행만 seed(중복 user_id 없음)·cleanup은 자식 선삭제(FK RESTRICT)·무영향.

**결론: V12 단독 적용 시 기존 테스트 RED 0건.** 중복 (seller_id, user_id)·중복 user_id를 요구하는 fixture 부재로 단독 UNIQUE 전환과 충돌 없음. #2는 user_id 동일이라 새 제약으로도 통과하는 "은폐성 GREEN"(원 프롬프트 "통합테스트 은폐" 힌트 = 이 지점)이며, DisplayName 의미 정정만 Phase 2+ 후속 대상(즉시 RED 아님).

## §6 미착수 정찰 항목 (Phase별 예정·현재 미측정)
기조 5 준수 — 아래는 Phase 1에서 측정하지 않았으므로 박제하지 않는다. 해당 Phase 진입 시 실측 후 본 문서에 누적:
- resolver passthrough 현황·user.id→seller.id 단건 해소 진입점 설계.
- authorize 정합(소유권 2계층·SELLER 자원 인가 경로) 결함 유무.
- Seller 도메인 endpoint의 seller_user 매핑 소비 여부.

**Phase 2·3 실측 완료 (갱신·2026-07-04)**: 위 3항목은 본 트랙 Phase 2(resolver 배선)·Phase 3(테스트 정비)에서 전건 실측·해소됨(결정 본문 D-121 §1-B). 원 항목은 이력으로 보존하고 해소 상태를 병기한다.
- resolver passthrough → HeaderSellerActorResolver가 findSellerIdByUserId로 user.id→seller.id 단건 해소(passthrough 결함 교정).
- authorize 정합 → SELLER user.id를 seller_id에 직접 대조하던 결함을 4 통합테스트가 user.id==seller.id 우연일치로 은폐 중이었음을 확인·resolver 실 매핑 + actorId≠seller_id 분리 시드로 해소.
- endpoint 매핑 소비 → 4 Seller endpoint(Inventory·Shipping·Delivery·Claim)가 resolver 경유로 seller_user 실조회를 소비함을 확인.
- 잔여 미측정: 없음(본 트랙 범위 내 전건 해소). 범위 밖 후속 = D-121 §8 carry-over 참조.
## §7 기조 5 자체 감사
- §2·§3·§5 전건 = docker MariaDB 11.4 실행 인용 또는 파일 read(file:line) 인용. 추측 서술 없음.
- 미측정 항목은 §6에 "미착수"로 분리·성급한 박제 회피.
- V12 정·역 트랩(§3.1·§3.4)은 STEP 3 검증 이전 정찰(docker 실측)로 선발견 → CLAUDE.md "CI/라이브 트랩 방지" 규칙 부합(신규 native DDL은 실제 호출 검증).