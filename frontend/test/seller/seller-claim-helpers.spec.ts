import { describe, it, expect, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { LocationQuery } from 'vue-router'
import { useSellerClaims } from '#layers/seller/app/composables/useSellerClaims'
import {
  DEFAULT_SELLER_CLAIM_QUERY,
  hasActiveClaimFilters,
  parseSellerClaimQuery,
  toSellerClaimApiParams,
  toSellerClaimRouteQuery,
} from '#layers/seller/app/lib/seller-claim-query'
import { attachmentCountLabel, claimProductLabel, claimReasonLabel, claimTimeline } from '#layers/seller/app/lib/seller-claim-view'
import { claimChipLabel } from '#layers/seller/app/lib/seller-order-view'
import { resolveBackPath, SELLER_CLAIMS_PATH, SELLER_ORDERS_PATH } from '#layers/seller/app/lib/seller-back-path'

/**
 * 셀러 클레임 순수 함수(Track 90-D-1): URL query ↔ 상태 ↔ BE 파라미터 매핑, 표시 헬퍼(사유 라벨·타임라인·클레임 칩), 클레임 상세 복귀 경로,
 * API 표면(useSellerClaims는 조회 2개만). 처리 액션 관련 헬퍼는 존재하지 않는다(조회 전용).
 */
mockNuxtImport('useSellerApi', () => () => vi.fn())

describe('useSellerClaims 표면(조회 전용)', () => {
  it('노출 키는 정확히 list·detail — 처리(approve·reject·inspect 등) 호출 함수가 생기면 이 단언이 먼저 깨진다(외부 검토 r4)', () => {
    expect(Object.keys(useSellerClaims()).sort()).toEqual(['detail', 'list'])
  })
})
describe('seller-claim-query', () => {
  it('parse: 기본값 · 유효 type/status/기간/page/size · 미지의 값은 기본값 · keyword 50자 절단', () => {
    expect(parseSellerClaimQuery({})).toEqual(DEFAULT_SELLER_CLAIM_QUERY)
    expect(parseSellerClaimQuery({ type: 'RETURN', status: 'REQUESTED', from: '2026-09-01', to: '2026-09-20', page: '2', size: '50', keyword: ' 반찬통 ' } as LocationQuery))
      .toEqual({ keyword: '반찬통', type: 'RETURN', status: 'REQUESTED', from: '2026-09-01', to: '2026-09-20', page: 2, size: 50 })
    expect(parseSellerClaimQuery({ type: 'REFUND', status: 'DONE', from: '2026/09/01', page: '-1', size: '33' } as LocationQuery))
      .toEqual(DEFAULT_SELLER_CLAIM_QUERY)
    expect(parseSellerClaimQuery({ keyword: 'K'.repeat(60) } as LocationQuery).keyword).toHaveLength(50)
    expect(parseSellerClaimQuery({ type: ['EXCHANGE', 'RETURN'] } as LocationQuery).type).toBe('EXCHANGE')
  })

  it('route query: 기본값 항목 생략 · api params: page/size 항상 + 기간 시각 부착', () => {
    expect(toSellerClaimRouteQuery(DEFAULT_SELLER_CLAIM_QUERY)).toEqual({})
    expect(toSellerClaimRouteQuery({ ...DEFAULT_SELLER_CLAIM_QUERY, type: 'CANCEL', status: 'REJECTED', keyword: ' 주전자 ', from: '2026-09-01', to: null, page: 1, size: 100 }))
      .toEqual({ type: 'CANCEL', status: 'REJECTED', keyword: '주전자', from: '2026-09-01', page: '1', size: '100' })
    expect(toSellerClaimApiParams(DEFAULT_SELLER_CLAIM_QUERY)).toEqual({ page: 0, size: 20 })
    expect(toSellerClaimApiParams({ ...DEFAULT_SELLER_CLAIM_QUERY, type: 'RETURN', status: 'APPROVED', keyword: '반찬통', from: '2026-09-01', to: '2026-09-20', page: 3, size: 50 }))
      .toEqual({ page: 3, size: 50, keyword: '반찬통', type: 'RETURN', status: 'APPROVED', from: '2026-09-01T00:00:00', to: '2026-09-20T23:59:59' })
  })

  it('hasActiveClaimFilters: 검색·유형·상태·기간 중 하나라도 있으면 true', () => {
    expect(hasActiveClaimFilters(DEFAULT_SELLER_CLAIM_QUERY)).toBe(false)
    expect(hasActiveClaimFilters({ ...DEFAULT_SELLER_CLAIM_QUERY, page: 3, size: 50 })).toBe(false)
    expect(hasActiveClaimFilters({ ...DEFAULT_SELLER_CLAIM_QUERY, type: 'EXCHANGE' })).toBe(true)
    expect(hasActiveClaimFilters({ ...DEFAULT_SELLER_CLAIM_QUERY, status: 'COMPLETED' })).toBe(true)
    expect(hasActiveClaimFilters({ ...DEFAULT_SELLER_CLAIM_QUERY, keyword: ' x ' })).toBe(true)
    expect(hasActiveClaimFilters({ ...DEFAULT_SELLER_CLAIM_QUERY, to: '2026-09-20' })).toBe(true)
  })
})

describe('seller-claim-view', () => {
  it('사유 라벨(미지 코드는 원본) · 상품 라벨(옵션 유무) · 첨부 개수 라벨(0장은 빈 문자열)', () => {
    expect(claimReasonLabel('PRODUCT_DEFECT')).toBe('상품 불량')
    expect(claimReasonLabel('UNKNOWN_X')).toBe('UNKNOWN_X')
    expect(claimProductLabel({ productName: '반찬통', optionLabel: '블랙' })).toBe('반찬통 · 블랙')
    expect(claimProductLabel({ productName: '반찬통' })).toBe('반찬통')
    expect(attachmentCountLabel(0)).toBe('')
    expect(attachmentCountLabel(3)).toBe('첨부 3장')
  })

  it('타임라인: REQUESTED 대기(current) · APPROVED 승인 시각 + 진행 중 · REJECTED 거부로 종결(2단계) · COMPLETED 완료 시각 = processedAt', () => {
    const requestedAt = '2026-09-10T10:00:00+09:00'
    const processedAt = '2026-09-11T10:00:00+09:00'
    expect(claimTimeline({ status: 'REQUESTED', requestedAt }).map((step) => [step.key, step.state, step.at]))
      .toEqual([['requested', 'done', requestedAt], ['processed', 'current', null], ['completed', 'pending', null]])
    expect(claimTimeline({ status: 'APPROVED', requestedAt, processedAt }).map((step) => [step.label, step.state, step.at]))
      .toEqual([['요청 접수', 'done', requestedAt], ['승인', 'done', processedAt], ['처리 진행 중', 'current', null]])
    const rejected = claimTimeline({ status: 'REJECTED', requestedAt, processedAt })
    expect(rejected).toHaveLength(2)
    expect(rejected[1]).toMatchObject({ key: 'processed', label: '거부', state: 'done', at: processedAt })
    const completed = claimTimeline({ status: 'COMPLETED', requestedAt, processedAt })
    expect(completed.map((step) => step.state)).toEqual(['done', 'done', 'done'])
    expect(completed[1]?.at).toBeNull()
    expect(completed[2]?.at).toBe(processedAt)
  })
})

describe('claimChipLabel(품목 행)', () => {
  const claim = { claimId: 'clm_1', type: 'RETURN' as const, status: 'REQUESTED' as const, requestedAt: '2026-09-10T10:00:00+09:00' }

  it('클레임 없음 null · 1건 "반품 요청" · 2건 이상 " · N건"', () => {
    expect(claimChipLabel({ claimCount: 0 })).toBeNull()
    expect(claimChipLabel({ claim, claimCount: 1 })).toBe('반품 요청')
    expect(claimChipLabel({ claim: { ...claim, type: 'EXCHANGE', status: 'APPROVED' }, claimCount: 2 })).toBe('교환 승인 · 2건')
  })
})

describe('resolveBackPath(클레임 상세)', () => {
  it('클레임 목록(쿼리 포함)·주문 목록·품목 상세 경로는 통과 · 그 외는 클레임 목록', () => {
    expect(resolveBackPath(undefined, SELLER_CLAIMS_PATH)).toBe(SELLER_CLAIMS_PATH)
    expect(resolveBackPath('/seller/claims?type=RETURN&page=1', SELLER_CLAIMS_PATH)).toBe('/seller/claims?type=RETURN&page=1')
    expect(resolveBackPath('/seller/orders?status=PAID', SELLER_CLAIMS_PATH)).toBe('/seller/orders?status=PAID')
    expect(resolveBackPath('/seller/orders/oit_1?back=%2Fseller%2Forders', SELLER_CLAIMS_PATH)).toBe('/seller/orders/oit_1?back=%2Fseller%2Forders')
    expect(resolveBackPath('/seller/products', SELLER_CLAIMS_PATH)).toBe(SELLER_CLAIMS_PATH)
    expect(resolveBackPath('https://evil.example', SELLER_CLAIMS_PATH)).toBe(SELLER_CLAIMS_PATH)
    // 주문 상세 복귀 규칙은 클레임 base에만 열린다(주문 목록 base는 기존대로 품목 상세 경로 거부)
    expect(resolveBackPath('/seller/orders/oit_1', SELLER_ORDERS_PATH)).toBe(SELLER_ORDERS_PATH)
  })
})
