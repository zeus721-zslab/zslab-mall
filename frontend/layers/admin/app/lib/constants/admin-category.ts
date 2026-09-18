/**
 * 관리자 카테고리 관리 상수 단일 소스(FE-38·Track 89-C D-185). 수수료율은 BE basis-point 정수(1000 = 10.00%)이며 화면은 %로 보여준다.
 * 범위는 BE CommissionRateResolver.MIN/MAX(0~10000 bp)와 같다 — 범위 밖 값이 저장되면 그 카테고리 상품의 체크아웃이 차단되므로 입력 단계에서 막는다.
 */

/** BE Category.displayName @Column(length=200)·UpdateCategoryRequest @Size(max=200). */
export const ADMIN_CATEGORY_NAME_MAX = 200

/** BE UpdateCategoryRequest.reason @Size(max=200). 수수료율이 실제로 바뀔 때만 필수. */
export const ADMIN_CATEGORY_REASON_MAX = 200

/** basis-point ↔ % 환산 계수(1% = 100 bp). */
export const BASIS_POINTS_PER_PERCENT = 100

export const COMMISSION_RATE_MIN_BP = 0
export const COMMISSION_RATE_MAX_BP = 10_000

/** % 입력 소수 자릿수(bp 정수 정밀도 = 0.01%). */
export const COMMISSION_RATE_PERCENT_DECIMALS = 2

/**
 * 수수료율 변경 경고(D-185 §STEP 445-2 조사 결론). 율 참조는 체크아웃 1곳이며 정산 생성·재생성은 order_item 스냅샷만 읽는다.
 * 셀러 개별율(seller.commission_rate)이 카테고리율보다 우선한다(3단 판정).
 */
export const COMMISSION_RATE_CHANGE_WARNING = [
  '수수료율은 변경 시점 이후 새로 생성되는 주문부터 적용됩니다.',
  '이미 생성된 주문·정산(재생성 포함)에는 영향이 없습니다.',
  '셀러 개별 수수료율이 설정된 셀러의 상품에는 적용되지 않습니다.',
] as const
