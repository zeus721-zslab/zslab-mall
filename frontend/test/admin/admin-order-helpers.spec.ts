import { describe, it, expect } from 'vitest'
import type { AdminOrderItem } from '#layers/admin/app/types/admin-order'
import {
  cancelResultMessage,
  cancellableItems,
  claimRefundLabel,
  deliverableItems,
  isPaymentCancelLost,
  isUnpaidOrder,
  mapFieldErrors,
  sellerNamesLabel,
  shippableItems,
  showDeliveryChip,
  validateCancelForm,
  validateShipmentForm,
} from '#layers/admin/app/lib/admin-order-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { ADMIN_ORDERS_PATH, resolveBackPath } from '#layers/admin/app/lib/admin-back-path'
import {
  ADMIN_CLAIM_STATUS_SEMANTIC,
  ADMIN_DELIVERY_CARRIER_OPTIONS,
  ADMIN_DELIVERY_STATUS_SEMANTIC,
  ADMIN_ORDER_ITEM_STATUS_SEMANTIC,
  ADMIN_ORDER_STATUS_CODES,
  ADMIN_ORDER_STATUS_SEMANTIC,
  ADMIN_PAYMENT_STATUS_SEMANTIC,
  paymentMethodLabel,
} from '#layers/admin/app/lib/constants/admin-order'
import { ORDER_ITEM_STATUS_LABELS, CLAIM_STATUS_LABELS } from '~/lib/constants/claim'
import { ORDER_STATUS_LABELS } from '~/lib/constants/order'

// FE-27: 액션 대상 판정·폼 검증·에러 매핑·복귀 경로·상수 정합.
function item(overrides: Partial<AdminOrderItem>): AdminOrderItem {
  return {
    orderItemId: 'oit_1', productName: '상품', quantity: 1, unitPrice: 1000, totalPrice: 1000, status: 'PAID', claims: [], ...overrides,
  }
}

describe('액션 대상 판정(BE actions·canTransitionTo 정합)', () => {
  const items = [
    item({ orderItemId: 'oit_paid', status: 'PAID' }),
    item({ orderItemId: 'oit_preparing', status: 'PREPARING' }),
    item({ orderItemId: 'oit_shipping', status: 'SHIPPING', delivery: { deliveryId: 'dlv_1', carrier: 'CJ', trackingNo: '1', status: 'SHIPPING' } }),
    item({ orderItemId: 'oit_delivered', status: 'DELIVERED', delivery: { deliveryId: 'dlv_2', carrier: 'CJ', trackingNo: '2', status: 'DELIVERED' } }),
    item({ orderItemId: 'oit_cancelled', status: 'CANCELLED' }),
  ]

  it('취소 가능 = PAID·PREPARING(사용자 claimableTypes CANCEL과 동일)', () => {
    expect(cancellableItems(items).map((entry) => entry.orderItemId)).toEqual(['oit_paid', 'oit_preparing'])
  })

  it('발송 처리 = PAID만 · 배송완료 = 최신 배송 SHIPPING만', () => {
    expect(shippableItems(items).map((entry) => entry.orderItemId)).toEqual(['oit_paid'])
    expect(deliverableItems(items).map((entry) => entry.orderItemId)).toEqual(['oit_shipping'])
  })

  it('미결제 판정·셀러 표기(없음/1곳/2곳↑·null 제외)', () => {
    expect(isUnpaidOrder({ status: 'PENDING_PAYMENT' })).toBe(true)
    expect(isUnpaidOrder({ status: 'PAID' })).toBe(false)
    expect(sellerNamesLabel([])).toBe('—')
    expect(sellerNamesLabel([null])).toBe('—')
    expect(sellerNamesLabel(['A'])).toBe('A')
    expect(sellerNamesLabel(['A', null, 'B', 'C'])).toBe('A 외 2')
  })

  it('배송 chip: 주문·배송 라벨이 같으면 숨김(배송중·배송중), 다르면 표시(부분취소·배송중), 배송 없음은 숨김', () => {
    expect(showDeliveryChip('SHIPPING', 'SHIPPING')).toBe(false)
    expect(showDeliveryChip('DELIVERED', 'DELIVERED')).toBe(false)
    expect(showDeliveryChip('PARTIAL_CANCEL', 'SHIPPING')).toBe(true)
    expect(showDeliveryChip('PAID', 'READY')).toBe(true)
    expect(showDeliveryChip('PAID', undefined)).toBe(false)
    expect(showDeliveryChip('PAID', null)).toBe(false)
  })

  it('환불 표기: 취소 클레임 APPROVED=진행 중(warning)·COMPLETED=완료(success)·그 외 없음', () => {
    expect(claimRefundLabel({ type: 'CANCEL', status: 'APPROVED' })).toEqual({ text: '환불 진행 중', semantic: 'warning' })
    expect(claimRefundLabel({ type: 'CANCEL', status: 'COMPLETED' })).toEqual({ text: '환불 완료', semantic: 'success' })
    expect(claimRefundLabel({ type: 'CANCEL', status: 'REQUESTED' })).toBeNull()
    expect(claimRefundLabel({ type: 'RETURN', status: 'APPROVED' })).toBeNull()
  })
})

describe('폼 검증·에러 매핑', () => {
  it('취소: 결제 후 0개 선택·사유 없음·메모 500 초과는 에러, 미결제는 품목 선택 불요', () => {
    expect(validateCancelForm({ unpaid: false, selectedItemIds: [], reasonCode: null, reasonDetail: 'x'.repeat(501) }))
      .toEqual({ items: expect.stringContaining('1개 이상'), reasonCode: expect.stringContaining('사유'), reasonDetail: expect.stringContaining('500') })
    expect(validateCancelForm({ unpaid: true, selectedItemIds: [], reasonCode: 'OTHER', reasonDetail: '' })).toEqual({})
    expect(validateCancelForm({ unpaid: false, selectedItemIds: ['oit_1'], reasonCode: 'OTHER', reasonDetail: '' })).toEqual({})
  })

  it('송장: 품목·택배사 필수·송장번호 공백 불가·100자 초과 불가', () => {
    expect(validateShipmentForm({ orderItemId: null, carrier: null, trackingNo: '   ' })).toEqual({
      orderItemId: expect.any(String), carrier: expect.any(String), trackingNo: expect.stringContaining('입력'),
    })
    expect(validateShipmentForm({ orderItemId: 'oit_1', carrier: 'CJ', trackingNo: '1'.repeat(101) })).toEqual({ trackingNo: expect.stringContaining('100') })
    expect(validateShipmentForm({ orderItemId: 'oit_1', carrier: 'CJ', trackingNo: ' 123 ' })).toEqual({})
  })

  it('fieldErrors → 필드별 첫 메시지·orderItemPublicIds[n]은 items로 묶음·비배열은 빈 객체', () => {
    const error = { data: { code: 'VALIDATION_FAILED', fieldErrors: [
      { field: 'reasonCode', message: 'null 불가' }, { field: 'reasonCode', message: '둘째' },
      { field: 'orderItemPublicIds[0]', message: '형식' }, { field: 'trackingNo', message: '' },
    ] } }
    expect(mapFieldErrors(error)).toEqual({ reasonCode: 'null 불가', items: '형식', trackingNo: '입력값을 확인해 주세요.' })
    expect(mapFieldErrors({ data: { code: 'VALIDATION_FAILED' } })).toEqual({})
    expect(mapFieldErrors(null)).toEqual({})
  })

  it('취소 결과 문구: 미결제(claims 0)는 종료·결제 후는 건수·둘 다 danger', () => {
    expect(cancelResultMessage({ orderId: 'o', orderStatus: 'PAYMENT_EXPIRED', claims: [] })).toEqual({ text: expect.stringContaining('종료'), semantic: 'danger' })
    expect(cancelResultMessage({ orderId: 'o', orderStatus: 'PARTIAL_CANCEL', claims: [
      { claimId: 'c1', orderItemId: 'i1', status: 'APPROVED' }, { claimId: 'c2', orderItemId: 'i2', status: 'APPROVED' },
    ] })).toEqual({ text: expect.stringContaining('2개'), semantic: 'danger' })
  })

  it('주문 관련 에러 코드 문구(409·422·404)', () => {
    expect(toAdminErrorMessage({ data: { code: 'OPTIMISTIC_LOCK_FAILURE', detail: '동시 수정 충돌이 발생했습니다.' } })).toContain('결제가 완료')
    expect(toAdminErrorMessage({ data: { code: 'CLAIM_STATE_INVALID' } })).toContain('클레임')
    expect(toAdminErrorMessage({ data: { code: 'DELIVERY_INVALID_STATE' } })).toContain('배송')
    expect(toAdminErrorMessage({ data: { code: 'ORDER_NOT_FOUND' } })).toContain('주문')
  })
})

describe('resolveBackPath base 파라미터화', () => {
  it('주문 base: /admin/orders(쿼리 포함)만 허용·상품 경로·외부 URL은 주문 목록 기본', () => {
    expect(resolveBackPath('/admin/orders?status=PAID&page=1', ADMIN_ORDERS_PATH)).toBe('/admin/orders?status=PAID&page=1')
    expect(resolveBackPath('/admin/orders', ADMIN_ORDERS_PATH)).toBe('/admin/orders')
    expect(resolveBackPath('/admin/orders/payments', ADMIN_ORDERS_PATH)).toBe('/admin/orders')
    expect(resolveBackPath('/admin/products?page=2', ADMIN_ORDERS_PATH)).toBe('/admin/orders')
    expect(resolveBackPath('https://evil.example/', ADMIN_ORDERS_PATH)).toBe('/admin/orders')
  })

  it('base 생략 시 상품 목록 동작 불변(FE-26 회귀)', () => {
    expect(resolveBackPath('/admin/products?status=SALE')).toBe('/admin/products?status=SALE')
    expect(resolveBackPath('/admin/orders')).toBe('/admin/products')
    expect(resolveBackPath(undefined)).toBe('/admin/products')
  })
})

describe('상수 정합(4층위 4단)', () => {
  it('semantic 맵은 사용자 라벨 키와 1:1·필터 옵션은 9값 전부·택배사 4값·결제수단 라벨 폴백', () => {
    expect(Object.keys(ADMIN_ORDER_STATUS_SEMANTIC).sort()).toEqual(Object.keys(ORDER_STATUS_LABELS).sort())
    expect(Object.keys(ADMIN_ORDER_ITEM_STATUS_SEMANTIC).sort()).toEqual(Object.keys(ORDER_ITEM_STATUS_LABELS).sort())
    expect(Object.keys(ADMIN_CLAIM_STATUS_SEMANTIC).sort()).toEqual(Object.keys(CLAIM_STATUS_LABELS).sort())
    expect(ADMIN_ORDER_STATUS_CODES).toHaveLength(9)
    expect(Object.keys(ADMIN_PAYMENT_STATUS_SEMANTIC)).toEqual(['PENDING', 'PAID', 'FAILED', 'CANCELLED', 'EXPIRED'])
    expect(Object.keys(ADMIN_DELIVERY_STATUS_SEMANTIC)).toEqual(['READY', 'SHIPPING', 'DELIVERED'])
    expect(ADMIN_DELIVERY_CARRIER_OPTIONS.map((option) => option.value)).toEqual(['CJ', 'HANJIN', 'POST', 'LOGEN'])
    expect(paymentMethodLabel('KAKAO')).toBe('카카오페이')
    expect(paymentMethodLabel('UNKNOWN')).toBe('UNKNOWN')
  })
})

// Track 96-1(FE-53·C-12): Payment CANCELLED 자동 전이 유실 판정 = BE markCancelled 전액 가드(D-71)와 같은 조건.
describe('isPaymentCancelLost(C-12)', () => {
  it('PAID + 전액 환불 완료만 true · 부분 환불·미환불·이미 CANCELLED·0원은 false', () => {
    expect(isPaymentCancelLost({ status: 'PAID', amount: 32900, refundedAmount: 32900 })).toBe(true)
    expect(isPaymentCancelLost({ status: 'PAID', amount: 32900, refundedAmount: 10000 })).toBe(false)
    expect(isPaymentCancelLost({ status: 'PAID', amount: 32900, refundedAmount: 0 })).toBe(false)
    expect(isPaymentCancelLost({ status: 'CANCELLED', amount: 32900, refundedAmount: 32900 })).toBe(false)
    expect(isPaymentCancelLost({ status: 'PAID', amount: 0, refundedAmount: 0 })).toBe(false)
  })
})
