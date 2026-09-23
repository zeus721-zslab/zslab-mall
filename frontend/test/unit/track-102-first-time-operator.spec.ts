import { describe, it, expect } from 'vitest'
import { IRREVERSIBLE, reversalLine, reversibleBy, riskConfirmMessage } from '~/lib/utils/risk-confirm'
import { PRODUCT_STATUS_LABELS, SALE_STOP_SOURCE_LABELS, VARIANT_STATUS_LABELS } from '~/lib/constants/product-status'
import { SELLER_MEMBER_ROLE_LABELS, SELLER_STATUS_LABELS } from '~/lib/constants/seller'
import { SETTLEMENT_ITEM_TYPE_LABELS, SETTLEMENT_STATUS_LABELS } from '~/lib/constants/settlement'
import { MARK_DELIVERED_CONFIRM_MESSAGE } from '~/lib/constants/delivery'
import { CLAIM_STATUS_LABELS } from '~/lib/constants/claim'
import { canResumePayment, isPaymentExpired, paymentResumeFailure, PAYMENT_EXPIRED_NOTICE } from '~/lib/utils/payment-resume'
import { ADMIN_PRODUCT_STATUS_LABEL, ADMIN_SALE_STOP_SOURCE_LABEL } from '#layers/admin/app/lib/constants/product'
import { ADMIN_SELLER_MEMBER_ROLE_LABEL, ADMIN_SELLER_STATUS_LABEL } from '#layers/admin/app/lib/constants/admin-seller'
import { ADMIN_SETTLEMENT_ITEM_TABS, ADMIN_SETTLEMENT_STATUS_LABEL } from '#layers/admin/app/lib/constants/admin-settlement'
import { PENDING_TILES } from '#layers/admin/app/lib/admin-dashboard-view'
import { rowActionAbsenceReason } from '#layers/admin/app/lib/admin-claim-view'
import * as adminRiskConfirm from '#layers/admin/app/lib/admin-risk-confirm'
import {
  SELLER_PRODUCT_STATUS_LABEL,
  SELLER_SALE_STOP_SOURCE_LABEL,
  SELLER_VARIANT_STATUS_LABEL,
} from '#layers/seller/app/lib/constants/seller-product'
import { SELLER_ROLE_LABEL, SELLER_STATUS_LABEL } from '#layers/seller/app/lib/constants/seller-me'
import { SELLER_SETTLEMENT_ITEM_TABS, SELLER_SETTLEMENT_STATUS_LABEL } from '#layers/seller/app/lib/constants/seller-settlement'

/**
 * Track 102 FE-64 — 처음 쓰는 운영자 관점 4건(용어 표준·위험 조작 문구·액션 부재 사유·결제 재개 + 대시보드 hint).
 * 역할 레이어가 각자 라벨을 다시 정의하면 여기서 깨진다(용어가 갈리는 재발을 테스트로 막는 것이 목적).
 */

describe('용어 표준 — 같은 값은 역할이 달라도 같은 말', () => {
  it('상품 상태·판매중지 주체·옵션 상태: 관리자 = 셀러 = 공용 소스', () => {
    expect(ADMIN_PRODUCT_STATUS_LABEL).toBe(PRODUCT_STATUS_LABELS)
    expect(SELLER_PRODUCT_STATUS_LABEL).toBe(PRODUCT_STATUS_LABELS)
    expect(ADMIN_SALE_STOP_SOURCE_LABEL).toBe(SALE_STOP_SOURCE_LABELS)
    expect(SELLER_SALE_STOP_SOURCE_LABEL).toBe(SALE_STOP_SOURCE_LABELS)
    expect(SELLER_VARIANT_STATUS_LABEL).toBe(VARIANT_STATUS_LABELS)
  })

  it('셀러 상태·구성원 역할: 관리자 = 셀러 = 공용 소스', () => {
    expect(ADMIN_SELLER_STATUS_LABEL).toBe(SELLER_STATUS_LABELS)
    expect(SELLER_STATUS_LABEL).toBe(SELLER_STATUS_LABELS)
    expect(ADMIN_SELLER_MEMBER_ROLE_LABEL).toBe(SELLER_MEMBER_ROLE_LABELS)
    expect(SELLER_ROLE_LABEL).toBe(SELLER_MEMBER_ROLE_LABELS)
  })

  it('정산 상태·품목 유형: 관리자 = 셀러 = 공용 소스', () => {
    expect(ADMIN_SETTLEMENT_STATUS_LABEL).toBe(SETTLEMENT_STATUS_LABELS)
    expect(SELLER_SETTLEMENT_STATUS_LABEL).toBe(SETTLEMENT_STATUS_LABELS)
    expect(ADMIN_SETTLEMENT_ITEM_TABS.map((tab) => tab.label)).toEqual(SELLER_SETTLEMENT_ITEM_TABS.map((tab) => tab.label))
    expect(SETTLEMENT_ITEM_TYPE_LABELS.SALE).toBe('판매')
  })

  it('표준 문구 확정값: 승인대기·거부됨·관리자 판매중지·활성·매니저·확정 대기·거부', () => {
    expect(PRODUCT_STATUS_LABELS.PENDING).toBe('승인대기')
    expect(PRODUCT_STATUS_LABELS.REJECTED).toBe('거부됨')
    expect(SALE_STOP_SOURCE_LABELS.ADMIN).toBe('관리자 판매중지')
    expect(SELLER_STATUS_LABELS.ACTIVE).toBe('활성')
    expect(SELLER_MEMBER_ROLE_LABELS.SELLER_MANAGER).toBe('매니저')
    expect(SETTLEMENT_STATUS_LABELS.PENDING).toBe('확정 대기')
    expect(CLAIM_STATUS_LABELS.REJECTED).toBe('거부')
  })
})

describe('위험 조작 확인 문구 — 가역성 줄이 항상 마지막', () => {
  it('riskConfirmMessage: effect 뒤에 가역성 1줄 · effect 끝 마침표는 자동 보정', () => {
    expect(riskConfirmMessage('무언가를 합니다', IRREVERSIBLE)).toBe('무언가를 합니다.\n되돌릴 수 없습니다.')
    expect(riskConfirmMessage('무언가를 합니다.', reversibleBy('되살리기로 복구'))).toBe('무언가를 합니다.\n되돌릴 수 있습니다: 되살리기로 복구')
  })

  it('여러 줄 effect도 가역성 줄은 맨 끝 한 줄', () => {
    const message = riskConfirmMessage('첫 줄.\n둘째 줄 경고.', IRREVERSIBLE)
    expect(message.split('\n')).toHaveLength(3)
    expect(message.split('\n').at(-1)).toBe(reversalLine(IRREVERSIBLE))
  })

  it('관리자 문구 전수: 모든 생성 함수의 마지막 줄이 가역성이다', () => {
    const samples: string[] = [
      adminRiskConfirm.settlementConfirmMessage('2026.08', '테스트상사'),
      adminRiskConfirm.settlementPayMessage('2026.08', '테스트상사', '1,000원', '국민 ****1234'),
      adminRiskConfirm.productDeleteMessage('테스트 상품'),
      adminRiskConfirm.categoryDeleteMessage('의류'),
      adminRiskConfirm.memberWithdrawMessage('홍길동', 'a@test.local'),
      adminRiskConfirm.temporaryPasswordMessage('010-0000-0000'),
      adminRiskConfirm.refundInitiateMessage('테스트 상품'),
      MARK_DELIVERED_CONFIRM_MESSAGE,
    ]
    for (const message of samples) {
      expect(message.split('\n').at(-1)).toMatch(/^되돌릴 수 (없습니다\.|있습니다: )/)
    }
  })
})

describe('액션이 없는 행의 사유', () => {
  const base = { type: 'RETURN' as const, availableActions: [] as string[] }

  it('회수 대기 · 처리 완료 · 거부 종결을 구분하고, 액션이 있으면 null', () => {
    expect(rowActionAbsenceReason({ ...base, status: 'APPROVED' })).toBe('구매자 회수 송장 등록 대기')
    expect(rowActionAbsenceReason({ ...base, status: 'COMPLETED' })).toBe('처리 완료')
    expect(rowActionAbsenceReason({ ...base, status: 'REJECTED' })).toBe('거부로 종결')
    expect(rowActionAbsenceReason({ ...base, status: 'COMPLETED', availableActions: ['APPROVE'] })).toBeNull()
  })

  it('취소 클레임은 회수 단계가 없어 승인 상태에 회수 대기 문구를 쓰지 않는다', () => {
    expect(rowActionAbsenceReason({ type: 'CANCEL', status: 'APPROVED', availableActions: [] })).toBeNull()
  })
})

describe('결제 재개 노출 조건', () => {
  it('결제대기만 결제하기 · 미결제 종료는 사유 안내', () => {
    expect(canResumePayment('PENDING_PAYMENT')).toBe(true)
    for (const code of ['PAID', 'PREPARING', 'SHIPPING', 'DELIVERED', 'CONFIRMED', 'CANCELLED', 'PARTIAL_CANCEL', 'PAYMENT_EXPIRED']) {
      expect(canResumePayment(code)).toBe(false)
    }
    expect(isPaymentExpired('PAYMENT_EXPIRED')).toBe(true)
    expect(isPaymentExpired('PENDING_PAYMENT')).toBe(false)
    expect(PAYMENT_EXPIRED_NOTICE).toContain('30분')
  })
})

describe('결제 재개 실패 매핑 — BE 실패 8종 전수(Track 102 보완)', () => {
  const problem = (statusCode: number, code: string, detail?: string) => ({ statusCode, data: { code, detail } })

  it('401은 로그인 유도 · 그 외는 문구를 돌려준다', () => {
    const unauthorized = paymentResumeFailure(problem(401, 'UNAUTHENTICATED'))
    expect(unauthorized.login).toBe(true)
    expect(unauthorized.refresh).toBe(false)
  })

  it('404 ORDER_NOT_FOUND는 재시도가 아니라 주문 내역 확인을 안내한다', () => {
    const failure = paymentResumeFailure(problem(404, 'ORDER_NOT_FOUND'))
    expect(failure.message).toContain('주문 내역에서 다시 확인')
    expect(failure.message).not.toContain('잠시 후')
    expect(failure.refresh).toBe(false)
  })

  it('422 ORDER_NOT_PAYABLE은 detail(차단 사유 2값)로 갈라 상품 문제임을 말한다', () => {
    const notOnSale = paymentResumeFailure(problem(422, 'ORDER_NOT_PAYABLE', 'PRODUCT_NOT_ON_SALE'))
    expect(notOnSale.message).toContain('판매 중지')
    expect(notOnSale.refresh).toBe(true)
    const outOfStock = paymentResumeFailure(problem(422, 'ORDER_NOT_PAYABLE', 'OUT_OF_STOCK'))
    expect(outOfStock.message).toContain('재고가 부족')
    expect(outOfStock.refresh).toBe(true)
    // 사유 코드가 늘어도 상품 문제라는 사실은 전해진다(폴백).
    expect(paymentResumeFailure(problem(422, 'ORDER_NOT_PAYABLE', 'SHIPPING_UNAVAILABLE')).message).toContain('판매 상태')
  })

  it('422 결제·주문 상태 위반 2종은 최신 상태 재조회를 지시한다', () => {
    for (const code of ['PAYMENT_ALREADY_COMPLETED', 'ORDER_NOT_PENDING_PAYMENT']) {
      const failure = paymentResumeFailure(problem(422, code))
      expect(failure.refresh).toBe(true)
      expect(failure.login).toBe(false)
    }
    expect(paymentResumeFailure(problem(422, 'PAYMENT_ALREADY_COMPLETED')).message).toContain('이미 결제가 완료')
  })

  it('409 PAYMENT_IN_PROGRESS는 만료를 기다리라고 알린다(재조회 없음)', () => {
    const failure = paymentResumeFailure(problem(409, 'PAYMENT_IN_PROGRESS'))
    expect(failure.message).toContain('진행 중인 결제')
    expect(failure.message).toContain('30분')
    expect(failure.refresh).toBe(false)
  })

  it('매핑에 없는 실패(400 VALIDATION_FAILED·500·네트워크)는 일반 문구 + 재시도 가능', () => {
    for (const error of [problem(400, 'VALIDATION_FAILED'), problem(500, 'INTERNAL_ERROR'), {}]) {
      const failure = paymentResumeFailure(error)
      expect(failure.message).toBe('결제를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.')
      expect(failure.refresh).toBe(false)
      expect(failure.login).toBe(false)
    }
  })
})

describe('관리자 대시보드 처리 대기 hint', () => {
  it('8칸 모두 hint가 있고, 카운트 기준이 목록과 어긋나는 3칸은 건수 차이를 알린다', () => {
    expect(PENDING_TILES).toHaveLength(8)
    for (const tile of PENDING_TILES) {
      expect(tile.hint.length).toBeGreaterThan(0)
    }
    const approximate = ['deliveryReady', 'lowStock', 'longShipping']
    for (const key of approximate) {
      expect(PENDING_TILES.find((tile) => tile.key === key)?.hint).toContain('다를 수 있음')
    }
    expect(PENDING_TILES.find((tile) => tile.key === 'longShipping')?.hint).toContain('3일')
  })
})
