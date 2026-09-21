import type { SellerProductRank, SellerProductStatsResponse, SellerStockTurnover, SellerUnsoldProduct } from '#layers/seller/app/types/seller-product-stats'
import { SELLER_DELETED_NAME_LABELS } from '#layers/seller/app/lib/constants/seller-stats'

/**
 * 셀러 상품 통계 표시 규칙 순수 함수(Track 90-E-3·관리자 대응 없음). 상위/하위·미판매·재고 회전 행 뷰(상품 상세 링크 가능 여부)·소진 예상 표기·
 * 현재 품절 옵션 카드 문구를 여기 모아 vitest로 고정한다.
 */

export const DEPLETION_NO_SALES = '판매 없음'
export const DEPLETION_OUT_OF_STOCK = '재고 없음'

/** 소진 예상 표기: 판매 0(null/undefined) → "판매 없음" · 0 → "재고 없음" · N → "N일". */
export function formatDepletionDays(days: number | null | undefined): string {
  if (days === null || days === undefined) return DEPLETION_NO_SALES
  if (days === 0) return DEPLETION_OUT_OF_STOCK
  return `${days.toLocaleString('ko-KR')}일`
}

export interface SellerProductRankRowView {
  id: string
  key: string | null
  name: string
  deleted: boolean
  revenue: number
  orderCount: number
  quantity: number
  /** 상품 public_id가 있는 행만 셀러 상품 상세로 이동한다. */
  linkable: boolean
}

/** 상위·하위 행 뷰. key 없으면(soft-delete) 삭제 표기·이동 불가. */
export function productRankRows(rows: SellerProductRank[]): SellerProductRankRowView[] {
  return rows.map((row, index) => {
    const key = row.productKey ?? null
    return {
      id: key ?? `row-${index}`,
      key,
      name: row.productName || SELLER_DELETED_NAME_LABELS.PRODUCT,
      deleted: key === null,
      revenue: row.revenue,
      orderCount: row.orderCount,
      quantity: row.quantity,
      linkable: key !== null,
    }
  })
}

export interface SellerUnsoldRowView {
  key: string
  name: string
  basePrice: number
}

export function unsoldRows(rows: SellerUnsoldProduct[]): SellerUnsoldRowView[] {
  return rows.map((row) => ({ key: row.productKey, name: row.productName, basePrice: row.basePrice }))
}

export interface SellerStockTurnoverRowView {
  key: string
  name: string
  inboundQuantity: number
  soldQuantity: number
  availableQuantity: number
  depletionText: string
  /** 소진 임박(판매 있고 7일 이내 또는 재고 없음) 강조. */
  urgent: boolean
}

export const DEPLETION_URGENT_DAYS = 7

export function stockTurnoverRows(rows: SellerStockTurnover[]): SellerStockTurnoverRowView[] {
  return rows.map((row) => {
    const days = row.depletionDays ?? null
    return {
      key: row.productKey,
      name: row.productName,
      inboundQuantity: row.inboundQuantity,
      soldQuantity: row.soldQuantity,
      availableQuantity: row.availableQuantity,
      depletionText: formatDepletionDays(days),
      urgent: days !== null && days <= DEPLETION_URGENT_DAYS,
    }
  })
}

/** 품절 카드 캡션: "판매 중 옵션 N개 중 · 현재 시점". 판매 중 옵션 0이면 "판매 중 옵션 없음". */
export function soldOutCaption(stats: SellerProductStatsResponse | null): string {
  if (stats === null) return ''
  if (stats.saleOptionCount === 0) return '판매 중 옵션 없음 · 현재 시점'
  return `판매 중 옵션 ${stats.saleOptionCount.toLocaleString('ko-KR')}개 중 · 현재 시점(기간과 무관)`
}

/** 4개 표가 전부 비었는지(빈 상태 안내). */
export function isProductStatsEmpty(stats: SellerProductStatsResponse | null): boolean {
  return stats !== null && stats.topProducts.length === 0 && stats.unsoldProducts.length === 0 && stats.stockTurnover.length === 0
}
