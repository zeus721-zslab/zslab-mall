import { describe, it, expect } from 'vitest'
import {
  DEFAULT_RECONCILIATION_QUERY,
  RECONCILIATION_STATUS_ALL,
  parseReconciliationQuery,
  reconciliationFacts,
  reconciliationSourceLabel,
  reconciliationSummary,
  resolveConfirmMessage,
  toReconciliationApiParams,
  toReconciliationRouteQuery,
} from '#layers/admin/app/lib/admin-reconciliation-view'
import {
  RECONCILIATION_ISSUE_TYPE_LABEL,
  RECONCILIATION_ISSUE_TYPE_OPTIONS,
} from '#layers/admin/app/lib/constants/reconciliation'
import { ADMIN_ORDERS_PATH, ADMIN_RECONCILIATION_PATH, resolveBackPath } from '#layers/admin/app/lib/admin-back-path'
import { ADMIN_MENU } from '#layers/admin/app/lib/constants/admin-menu'
import { auditActionText, auditChangeSummary } from '#layers/admin/app/lib/admin-audit-view'

// Track 104-2 FE-66 불일치 화면: URL ↔ 조회 조건, 요약·세부 사실·기록 주체 표기, 해결 확인 문구(FE-64 형식), 진입·복귀 경로.

describe('유형 상수(4층위 (4)프론트)', () => {
  it('BE ReconciliationIssueType 11값과 같은 키·옵션 순서', () => {
    expect(Object.keys(RECONCILIATION_ISSUE_TYPE_LABEL)).toEqual([
      'PG_PAYMENT_SUCCESS_CONFLICT', 'PG_PAYMENT_CANCEL_ON_PAID', 'PG_TID_CONFLICT', 'PG_UNMATCHED_CALLBACK',
      'PG_REFUND_EXCEEDS_PAYMENT', 'PG_REFUND_SUCCESS_ON_FAILED', 'PAYMENT_CANCELLED_WITHOUT_REFUND',
      'FULL_REFUND_PAYMENT_NOT_CANCELLED', 'FULL_REFUND_WITH_CONFIRMED_ITEM', 'REFUND_ON_INVALID_CLAIM', 'ITEM_STATE_DRIFT',
    ])
    expect(RECONCILIATION_ISSUE_TYPE_OPTIONS).toHaveLength(11)
  })
})

describe('조회 조건 ↔ URL', () => {
  it('기본은 확인 필요(OPEN)·URL 생략, 전체는 status=ALL로 명시', () => {
    expect(parseReconciliationQuery({})).toEqual(DEFAULT_RECONCILIATION_QUERY)
    expect(toReconciliationRouteQuery(DEFAULT_RECONCILIATION_QUERY)).toEqual({})
    expect(parseReconciliationQuery({ status: RECONCILIATION_STATUS_ALL }).status).toBeNull()
    expect(toReconciliationRouteQuery({ ...DEFAULT_RECONCILIATION_QUERY, status: null })).toEqual({ status: 'ALL' })
  })

  it('잘못된 값은 기본값으로 정규화하고 API 파라미터는 null을 뺀다', () => {
    const parsed = parseReconciliationQuery({ status: 'NOPE', type: 'NOPE', page: '-3', size: '7' })
    expect(parsed).toEqual(DEFAULT_RECONCILIATION_QUERY)
    const state = parseReconciliationQuery({ status: 'RESOLVED', type: 'PG_TID_CONFLICT', page: '2', size: '50' })
    expect(toReconciliationRouteQuery(state)).toEqual({ status: 'RESOLVED', type: 'PG_TID_CONFLICT', page: '2', size: '50' })
    expect(toReconciliationApiParams(state)).toEqual({ status: 'RESOLVED', type: 'PG_TID_CONFLICT', page: 2, size: 50 })
    expect(toReconciliationApiParams({ ...state, status: null, type: null })).toEqual({ page: 2, size: 50 })
  })
})

describe('표시', () => {
  it('요약 = 사유 라벨 → 모르는 사유는 원문 → 사유 없으면 유형 라벨', () => {
    expect(reconciliationSummary({ issueType: 'PG_PAYMENT_SUCCESS_CONFLICT', detail: { reason: 'ORDER_NOT_PENDING_PAYMENT' } }))
      .toBe('결제대기가 아닌 주문(만료·취소)에 결제 성공 통지가 왔습니다')
    expect(reconciliationSummary({ issueType: 'ITEM_STATE_DRIFT', detail: { reason: 'SOMETHING_NEW' } })).toBe('SOMETHING_NEW')
    expect(reconciliationSummary({ issueType: 'PG_TID_CONFLICT', detail: {} })).toBe('PG 거래번호 중복')
  })

  it('세부 사실은 정해진 키만 라벨·금액 변환해 순서대로 보인다', () => {
    expect(reconciliationFacts({
      reason: 'PAY1_EXCEEDED', paymentStatus: 'PAID', refundAmount: 6000, completedTotalAfter: 11000, paymentAmount: 10000,
    })).toEqual(['결제 결제완료', '결제액 10,000원', '환불액 6,000원', '완료 시 환불 합계 11,000원'])
    expect(reconciliationFacts({ reason: 'SHIPPED_ITEM_NOT_TRANSITIONED', itemStatus: 'PREPARING', deliveryStatus: 'SHIPPING', detectedBy: 'SYSTEM' }))
      .toEqual(['품목 상품준비중', '배송 배송중'])
    expect(reconciliationFacts({})).toEqual([])
  })

  it('기록 주체: 점검 스케줄러(SYSTEM)는 정기 점검, 그 외는 처리 중 기록', () => {
    expect(reconciliationSourceLabel({ detectedBy: 'SYSTEM' })).toBe('정기 점검')
    expect(reconciliationSourceLabel({ reason: 'CANCEL_ON_PAID' })).toBe('처리 중 기록')
  })

  it('해결 확인 문구는 FE-64 형식 — 데이터 불변 안내 후 마지막 줄이 되돌릴 수 없음', () => {
    const message = resolveConfirmMessage({ issueType: 'PG_TID_CONFLICT' })
    const lines = message.split('\n')
    expect(lines[0]).toContain('"PG 거래번호 중복" 불일치를 해결됨으로 표시합니다')
    expect(lines[0]).toContain('데이터는 바뀌지 않으니')
    expect(lines.at(-1)).toBe('되돌릴 수 없습니다.')
  })
})

describe('처리 이력 표기(감사 대상 RECONCILIATION_ISSUE)', () => {
  it('해결 감사는 "불일치 해결"·상태 값은 불일치 상태 라벨·메모 라벨', () => {
    const changes = [{ field: 'status', before: 'OPEN', after: 'RESOLVED' }, { field: 'memo', before: null, after: '재전송 매칭으로 자동 해소' }]
    expect(auditActionText({ targetType: 'RECONCILIATION_ISSUE', action: 'UPDATE', changes })).toBe('불일치 해결')
    expect(auditChangeSummary(changes, 'RECONCILIATION_ISSUE')).toBe('상태 확인 필요 → 해결됨 · 메모 재전송 매칭으로 자동 해소')
  })
})

describe('진입·복귀', () => {
  it('주문 관리 메뉴에 불일치가 있고, 주문 상세는 불일치 목록으로 복귀할 수 있다', () => {
    const orderMenu = ADMIN_MENU.find((group) => group.label === '주문 관리')
    expect(orderMenu?.children?.map((child) => child.to)).toContain(ADMIN_RECONCILIATION_PATH)
    expect(resolveBackPath(`${ADMIN_RECONCILIATION_PATH}?status=ALL`, ADMIN_ORDERS_PATH)).toBe('/admin/orders/reconciliation?status=ALL')
  })
})
