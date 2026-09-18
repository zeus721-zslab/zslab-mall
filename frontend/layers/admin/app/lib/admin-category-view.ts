import type { AdminCategorySummary } from '#layers/admin/app/types/admin-category'
import {
  BASIS_POINTS_PER_PERCENT,
  COMMISSION_RATE_MAX_BP,
  COMMISSION_RATE_MIN_BP,
  COMMISSION_RATE_PERCENT_DECIMALS,
} from '#layers/admin/app/lib/constants/admin-category'

/** 카테고리 관리 화면 순수 판정·환산(FE-38). 컴포넌트가 아니라 여기 두어 vitest로 고정한다. */

/** bp → % 표시 문자열(불필요한 소수 0 제거: 1000 → "10%", 525 → "5.25%"). */
export function formatPercent(basisPoints: number): string {
  const percent = basisPoints / BASIS_POINTS_PER_PERCENT
  return `${Number(percent.toFixed(COMMISSION_RATE_PERCENT_DECIMALS))}%`
}

/** 목록 셀 문구: 설정값은 "5.25%", 미설정은 기본율을 함께 보여준다(운영자가 실제 적용률을 화면에서 알 수 있게·회신 추가 지시 1). */
export function formatCommissionRate(basisPoints: number | null | undefined, defaultBasisPoints: number): string {
  if (basisPoints === null || basisPoints === undefined) return `미설정 (기본율 ${formatPercent(defaultBasisPoints)} 적용)`
  return formatPercent(basisPoints)
}

/** bp → % 입력 초기값(미설정은 빈 문자열). */
export function toPercentInput(basisPoints: number | null | undefined): string {
  if (basisPoints === null || basisPoints === undefined) return ''
  return String(Number((basisPoints / BASIS_POINTS_PER_PERCENT).toFixed(COMMISSION_RATE_PERCENT_DECIMALS)))
}

export type PercentParseResult = { ok: true; basisPoints: number | null } | { ok: false; message: string }

/**
 * % 입력 → bp. 빈 값 = 미설정(null). 0~100·소수 2자리까지만 허용(bp 정수 정밀도). 범위 밖은 BE도 400이지만 체크아웃 차단 위험이 있는
 * 값이라 입력 단계에서 먼저 막는다.
 */
export function parsePercentInput(raw: string): PercentParseResult {
  const text = raw.trim()
  if (text === '') return { ok: true, basisPoints: null }
  if (!/^\d+(\.\d{1,2})?$/.test(text)) return { ok: false, message: '0~100 사이 숫자를 소수 2자리까지 입력하세요.' }
  const basisPoints = Math.round(Number(text) * BASIS_POINTS_PER_PERCENT)
  if (basisPoints < COMMISSION_RATE_MIN_BP || basisPoints > COMMISSION_RATE_MAX_BP) {
    return { ok: false, message: '수수료율은 0%~100% 사이여야 합니다.' }
  }
  return { ok: true, basisPoints }
}

/** 율이 실제로 바뀌는지(미설정 ↔ 값 포함). 사유 필수 여부와 경고 강조에 쓴다. */
export function commissionRateChanged(original: number | null | undefined, next: number | null): boolean {
  return (original ?? null) !== next
}

/** 삭제 가능 판정(BE 가드와 동일: 활성 상품 0건). */
export function canDeleteCategory(item: Pick<AdminCategorySummary, 'productCount'>): boolean {
  return item.productCount === 0
}

/** 삭제 불가 툴팁 문구(가능하면 null). */
export function deleteBlockedReason(item: Pick<AdminCategorySummary, 'productCount'>): string | null {
  return canDeleteCategory(item) ? null : `연결된 상품 ${item.productCount}건`
}

/**
 * 위/아래 이동 결과 id 배열. 경계(맨 위에서 위·맨 아래에서 아래)면 null. 반환 배열은 PATCH /order 본문 그대로다(전체 id·index=sortOrder).
 */
export function moveCategory(categoryIds: number[], index: number, direction: -1 | 1): number[] | null {
  const target = index + direction
  if (index < 0 || index >= categoryIds.length || target < 0 || target >= categoryIds.length) return null
  const next = [...categoryIds]
  const moved = next[index]
  const swapped = next[target]
  if (moved === undefined || swapped === undefined) return null
  next[index] = swapped
  next[target] = moved
  return next
}

/** 상품 수 클릭 → 상품 목록 카테고리 필터(admin-product-query.ts categoryId). */
export function toProductListPath(categoryId: number): string {
  return `/admin/products?categoryId=${categoryId}`
}
