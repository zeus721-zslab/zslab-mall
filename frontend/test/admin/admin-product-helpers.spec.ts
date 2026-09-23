import { describe, it, expect } from 'vitest'
import { extractErrorCode, toAdminErrorMessage, toBulkFailureMessage } from '#layers/admin/app/lib/admin-error-message'
import { formatSalePeriod, formatWon } from '#layers/admin/app/lib/format'
import { ADMIN_PRODUCT_ALLOWED_TRANSITIONS, ADMIN_PRODUCT_STATUS_TARGETS } from '#layers/admin/app/lib/constants/product'
import { resolveActiveMenuPath } from '#layers/admin/app/lib/constants/admin-menu'
import {
  ESCALATE_STOP_TITLE,
  countEscalated,
  escalateConfirmMessage,
  isEscalation,
  saleStopSourceAfterAdminChange,
  statusTargetTitle,
  statusTargetsFor,
} from '#layers/admin/app/lib/admin-product-view'

// FE-25: 에러 코드 메시지·표 포맷·허용 전이·하위 경로 메뉴 활성.
describe('admin-error-message', () => {
  it('ProblemDetail.code 추출·매핑(409 주문 이력 포함)', () => {
    const error = { data: { code: 'PRODUCT_HAS_ORDER_HISTORY', detail: 'x' } }
    expect(extractErrorCode(error)).toBe('PRODUCT_HAS_ORDER_HISTORY')
    expect(toAdminErrorMessage(error)).toContain('판매중지')
    expect(toAdminErrorMessage({ data: { code: 'PRODUCT_INVALID_STATE' } })).toContain('허용되지 않는')
  })

  it('알 수 없는 코드는 detail → 일반 문구 순 폴백·네트워크 오류(null)도 일반 문구', () => {
    expect(toAdminErrorMessage({ data: { code: 'UNKNOWN_X', detail: '서버 상세' } })).toBe('서버 상세')
    expect(toAdminErrorMessage(null)).toContain('잠시 후')
    expect(extractErrorCode(new Error('network'))).toBeNull()
  })

  it('일괄 실패 항목: 코드 매핑 우선·없으면 message·둘 다 없으면 폴백', () => {
    expect(toBulkFailureMessage('PRODUCT_NOT_FOUND', 'raw')).toContain('찾을 수 없')
    expect(toBulkFailureMessage('X', 'raw')).toBe('raw')
    expect(toBulkFailureMessage(undefined, undefined)).toContain('잠시 후')
  })
})

describe('format', () => {
  it('가격 천단위·원, null은 —', () => {
    expect(formatWon(19900)).toBe('19,900원')
    expect(formatWon(null)).toBe('—')
  })

  it('판매기간: 상시 / 즉시~종료 / 시작~무기한', () => {
    expect(formatSalePeriod(undefined, undefined)).toBe('상시')
    expect(formatSalePeriod(undefined, '2026-12-31T00:00:00+09:00')).toBe('즉시 ~ 2026.12.31 00:00')
    expect(formatSalePeriod('2026-09-01T09:30:00+09:00', undefined)).toBe('2026.09.01 09:30 ~ 무기한')
  })
})

describe('허용 전이(상태 전환 메뉴 비활성 근거)', () => {
  it('PENDING→SALE·REJECTED / SALE→STOPPED / STOPPED→SALE만 허용·REJECTED·HIDDEN은 전이 없음', () => {
    expect(ADMIN_PRODUCT_ALLOWED_TRANSITIONS.PENDING).toEqual(['SALE', 'REJECTED'])
    expect(ADMIN_PRODUCT_ALLOWED_TRANSITIONS.SALE).toEqual(['STOPPED'])
    expect(ADMIN_PRODUCT_ALLOWED_TRANSITIONS.STOPPED).toEqual(['SALE'])
    expect(ADMIN_PRODUCT_ALLOWED_TRANSITIONS.REJECTED).toEqual([])
    // 메뉴는 3항목 고정이며 허용 목록의 값은 전부 메뉴에 존재한다.
    const menuValues = ADMIN_PRODUCT_STATUS_TARGETS.map((target) => target.value)
    Object.values(ADMIN_PRODUCT_ALLOWED_TRANSITIONS).flat().forEach((value) => expect(menuValues).toContain(value))
  })
})

describe('제재 전환(D-206 보정·FE-57)', () => {
  it('셀러 판매중지 상품만 STOPPED 목표가 열리고 라벨은 "관리자 판매중지로 전환" · 관리자 판매중지·SALE·PENDING은 기존 전이표 그대로', () => {
    const sellerStopped = { status: 'STOPPED' as const, saleStopSource: 'SELLER' as const }
    const adminStopped = { status: 'STOPPED' as const, saleStopSource: 'ADMIN' as const }
    expect(statusTargetsFor(sellerStopped)).toEqual(['SALE', 'STOPPED'])
    expect(statusTargetsFor(adminStopped)).toEqual(['SALE'])
    expect(statusTargetsFor({ status: 'STOPPED' })).toEqual(['SALE']) // 주체 불명은 fail-closed(전환 안 열림)
    expect(statusTargetsFor({ status: 'SALE' })).toEqual(['STOPPED'])
    expect(statusTargetsFor({ status: 'PENDING' })).toEqual(['SALE', 'REJECTED'])
    expect(isEscalation(sellerStopped, 'STOPPED')).toBe(true)
    expect(isEscalation(sellerStopped, 'SALE')).toBe(false)
    expect(isEscalation({ status: 'SALE' }, 'STOPPED')).toBe(false)
    expect(statusTargetTitle(sellerStopped, 'STOPPED')).toBe(ESCALATE_STOP_TITLE)
    expect(statusTargetTitle({ status: 'SALE' }, 'STOPPED')).toBe('판매중지로')
    expect(statusTargetTitle(sellerStopped, 'SALE')).toBe('판매중으로')
    expect(escalateConfirmMessage('반찬통')).toContain('셀러는 재판매할 수 없게')
    expect(saleStopSourceAfterAdminChange('STOPPED')).toBe('ADMIN')
    expect(saleStopSourceAfterAdminChange('SALE')).toBeUndefined()
    expect(countEscalated({ results: [
      { productPublicId: 'a', success: true },
      { productPublicId: 'b', success: true, code: 'ESCALATED_TO_ADMIN' },
      { productPublicId: 'c', success: false, code: 'PRODUCT_INVALID_STATE', message: 'x' },
    ], successCount: 2, failureCount: 1 })).toBe(1)
  })
})

describe('resolveActiveMenuPath', () => {
  it('정확 일치 우선·하위 경로는 가장 긴 메뉴·대시보드는 prefix 매칭 제외·메뉴 밖 null', () => {
    expect(resolveActiveMenuPath('/admin/products')).toBe('/admin/products')
    expect(resolveActiveMenuPath('/admin/products/new')).toBe('/admin/products/new')
    expect(resolveActiveMenuPath('/admin/products/prd_01ABC')).toBe('/admin/products')
    expect(resolveActiveMenuPath('/admin/orders/claims')).toBe('/admin/orders/claims')
    expect(resolveActiveMenuPath('/admin/unknown')).toBeNull()
    expect(resolveActiveMenuPath('/admin')).toBe('/admin')
  })
})

describe('admin-product-view', () => {
  it('품절 표시 분기: 수동 > 재고 판정 > 재고 있음 · 품절은 danger·판매 가능은 success', async () => {
    const { soldOutLabel } = await import('#layers/admin/app/lib/admin-product-view')
    expect(soldOutLabel({ soldOut: true, soldOutManual: true })).toEqual({ text: '품절(수동)', semantic: 'danger' })
    expect(soldOutLabel({ soldOut: true, soldOutManual: false })).toEqual({ text: '품절(재고)', semantic: 'danger' })
    expect(soldOutLabel({ soldOut: false, soldOutManual: false })).toEqual({ text: '재고 있음', semantic: 'success' })
  })

  it('품절 토글 결과 색: ON=danger·OFF=success', async () => {
    const { soldOutToggleSemantic } = await import('#layers/admin/app/lib/admin-product-view')
    expect(soldOutToggleSemantic(true)).toBe('danger')
    expect(soldOutToggleSemantic(false)).toBe('success')
  })

  it('일괄 결과 집계: 일부 실패 warning + 상세 열기 / 전부 실패 danger / 전부 성공은 의도별(품절 ON danger·OFF success·상태 info)', async () => {
    const { summarizeBulkResult } = await import('#layers/admin/app/lib/admin-product-view')
    expect(summarizeBulkResult({ results: [], successCount: 2, failureCount: 1 }, { kind: 'status' })).toEqual({
      message: '일괄 변경 완료 — 성공 2 / 실패 1', semantic: 'warning', hasFailure: true,
    })
    expect(summarizeBulkResult({ results: [], successCount: 0, failureCount: 2 }, { kind: 'soldOut', soldOut: false }).semantic).toBe('danger')
    expect(summarizeBulkResult({ results: [], successCount: 3, failureCount: 0 }, { kind: 'status' }).semantic).toBe('info')
    expect(summarizeBulkResult({ results: [], successCount: 3, failureCount: 0 }, { kind: 'soldOut', soldOut: true }).semantic).toBe('danger')
    expect(summarizeBulkResult({ results: [], successCount: 3, failureCount: 0 }, { kind: 'soldOut', soldOut: false }).semantic).toBe('success')
  })

  it('상태 chip 의미 색·semantic 매핑 테이블(Vuetify 색·sonner variant)', async () => {
    const { ADMIN_PRODUCT_STATUS_SEMANTIC } = await import('#layers/admin/app/lib/constants/product')
    const { ADMIN_SEMANTIC_VUETIFY_COLOR, ADMIN_SEMANTIC_TOAST_TYPE, semanticChipClass } = await import('#layers/admin/app/lib/constants/semantic')
    expect(ADMIN_PRODUCT_STATUS_SEMANTIC.SALE).toBe('success')
    expect(ADMIN_PRODUCT_STATUS_SEMANTIC.STOPPED).toBe('danger')
    expect(ADMIN_PRODUCT_STATUS_SEMANTIC.PENDING).toBe('warning')
    expect(ADMIN_SEMANTIC_VUETIFY_COLOR.danger).toBe('error')
    expect(ADMIN_SEMANTIC_TOAST_TYPE.danger).toBe('error')
    expect(semanticChipClass('info')).toBe('adm-chip adm-chip--info')
  })
})
