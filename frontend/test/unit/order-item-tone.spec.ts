import { describe, it, expect } from 'vitest'
import { ORDER_ITEM_STATUS_LABELS } from '~/lib/constants/claim'
import { ORDER_ITEM_STATUS_TONE, orderItemStatusTone } from '~/skins/renew/order-item-tone'
import { CLAIM_TYPE_BADGE_TONE } from '~/skins/renew/claim-type-tone'

/**
 * FE-80 품목 상태 배지 tone 매핑(주문 목록 카드·주문 상세 품목 행 공용). 정상 흐름은 지시 값, 클레임 계열은 관리자·셀러 품목 색 의미를 따른다.
 */
describe('ORDER_ITEM_STATUS_TONE — 품목 상태 배지 tone', () => {
  it('정상 흐름: 결제 완료 info · 준비·배송 neutral · 배송 완료 success · 구매 확정 neutral', () => {
    expect(orderItemStatusTone('PAID')).toBe('info')
    expect(orderItemStatusTone('PREPARING')).toBe('neutral')
    expect(orderItemStatusTone('SHIPPING')).toBe('neutral')
    expect(orderItemStatusTone('DELIVERED')).toBe('success')
    expect(orderItemStatusTone('CONFIRMED')).toBe('neutral')
  })

  it('주문접수 info · 요청 3종 warning · 취소·반품 완료 danger · 교환 완료 info', () => {
    expect(orderItemStatusTone('ORDERED')).toBe('info')
    expect(['CANCEL_REQUESTED', 'RETURN_REQUESTED', 'EXCHANGE_REQUESTED'].map(orderItemStatusTone)).toEqual(['warning', 'warning', 'warning'])
    expect(orderItemStatusTone('CANCELLED')).toBe('danger')
    expect(orderItemStatusTone('RETURNED')).toBe('danger')
    expect(orderItemStatusTone('EXCHANGED')).toBe('info')
  })

  it('라벨 상수의 12값을 모두 덮고, 모르는 값은 neutral', () => {
    expect(Object.keys(ORDER_ITEM_STATUS_TONE).sort()).toEqual(Object.keys(ORDER_ITEM_STATUS_LABELS).sort())
    expect(orderItemStatusTone('UNKNOWN_NEW_STATUS')).toBe('neutral')
  })

  it('클레임 유형 배지 tone은 기존 파스텔 의미 그대로(취소 butter=warning · 반품 pink=danger · 교환 periwinkle=neutral)', () => {
    expect(CLAIM_TYPE_BADGE_TONE).toEqual({ CANCEL: 'warning', RETURN: 'danger', EXCHANGE: 'neutral' })
  })
})
