/**
 * 상품 상태·판매중지 출처·옵션(변형) 상태 라벨 공용 단일 소스(Track 102 FE-64).
 *
 * 같은 BE enum 값을 관리자 레이어(constants/product.ts)와 셀러 레이어(constants/seller-product.ts)가 각자 라벨링해
 * PENDING이 "판매대기"와 "승인대기"로, REJECTED가 "거부됨"과 "반려"로 갈려 있었다. 값 하나에 말 하나를 보장하려고
 * 라벨 문자열만 여기로 모으고, 각 레이어는 기존 이름으로 re-export한다(호출부 무변경·admin-seller.ts 은행 상수 선례).
 * 유니온 타입·의미색(semantic)은 레이어 관심사라 그대로 둔다.
 */

/** BE ProductStatus 7값(product.status ENUM). */
export type ProductStatusCode = 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'SALE' | 'HIDDEN' | 'STOPPED'

/**
 * 상품 상태 라벨 표준. PENDING은 "무엇을 기다리는지"를 말하는 승인대기(관리자 대시보드 타일이 이미 "상품 승인 대기"),
 * REJECTED는 클레임·정산계좌의 REJECTED와 같은 말인 거부됨(APPROVED "승인됨"과 어미도 짝)으로 고정한다.
 */
export const PRODUCT_STATUS_LABELS: Record<ProductStatusCode, string> = {
  DRAFT: '임시저장',
  PENDING: '승인대기',
  APPROVED: '승인됨',
  REJECTED: '거부됨',
  SALE: '판매중',
  HIDDEN: '숨김',
  STOPPED: '판매중지',
}

/** BE SaleStopSource 2값(V34·D-206). STOPPED일 때만 존재한다. */
export type SaleStopSourceCode = 'ADMIN' | 'SELLER'

/** 무엇을 중지했는지가 드러나도록 상태 라벨 "판매중지"와 같은 말을 쓴다("관리자 중지"는 대상이 모호하다). */
export const SALE_STOP_SOURCE_LABELS: Record<SaleStopSourceCode, string> = {
  ADMIN: '관리자 판매중지',
  SELLER: '셀러 판매중지',
}

/** BE ProductVariantStatus 3값. 상품 상태와 다른 enum이지만 같은 code는 같은 말로 읽히도록 맞춘다. */
export type VariantStatusCode = 'SALE' | 'HIDDEN' | 'STOPPED'

/** HIDDEN만 상품(숨김)과 다르다 — 변형은 "사용" 스위치를 꺼서 비활성화하는 것이라 화면 조작 이름과 같은 말을 쓴다. */
export const VARIANT_STATUS_LABELS: Record<VariantStatusCode, string> = {
  SALE: '판매중',
  HIDDEN: '비활성',
  STOPPED: '판매중지',
}
