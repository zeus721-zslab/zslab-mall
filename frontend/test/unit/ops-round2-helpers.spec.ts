import { describe, it, expect } from 'vitest'
import { parseLastCarrier, LAST_CARRIER_MAX_AGE_SECONDS } from '~/lib/utils/last-carrier'
import {
  PRICE_CHANGE_EMPHASIS_PERCENT,
  priceChangeMessage,
  priceChangeOf,
  priceChangeWarnings,
} from '~/lib/utils/price-change'
import { inspectDialogTitle, shouldChainExchangeShipment } from '#layers/admin/app/lib/admin-claim-view'

/**
 * Track 99 라운드 2 개선(FE-61)의 순수 판정 함수. 화면 조립이 아니라 "무엇을 기본값으로 둘지·언제 확인을 받을지·언제 다음 다이얼로그를
 * 이어 열지"만 고정한다.
 */
describe('parseLastCarrier — 직전 출고 택배사 쿠키 해석', () => {
  it('택배사 4값은 그대로 돌려준다', () => {
    expect(parseLastCarrier('CJ')).toBe('CJ')
    expect(parseLastCarrier('HANJIN')).toBe('HANJIN')
    expect(parseLastCarrier('POST')).toBe('POST')
    expect(parseLastCarrier('LOGEN')).toBe('LOGEN')
  })

  it('값 집합 밖·빈 값·비문자열은 null(기본 선택 없음 = 현행 동작)', () => {
    expect(parseLastCarrier('UNKNOWN')).toBeNull()
    expect(parseLastCarrier('cj')).toBeNull()
    expect(parseLastCarrier('')).toBeNull()
    expect(parseLastCarrier(null)).toBeNull()
    expect(parseLastCarrier(undefined)).toBeNull()
  })

  it('쿠키 수명은 30일', () => {
    expect(LAST_CARRIER_MAX_AGE_SECONDS).toBe(30 * 24 * 60 * 60)
  })
})

describe('priceChangeOf — 판매가 변경 확인 판정', () => {
  it('가격이 같으면 null(확인 불필요)', () => {
    expect(priceChangeOf(15900, 15900)).toBeNull()
  })

  it('폼 미입력(null)은 확인 대상이 아니다', () => {
    expect(priceChangeOf(null, 19900)).toBeNull()
    expect(priceChangeOf(15900, null)).toBeNull()
  })

  it('인상·인하 변동률을 소수 첫째 자리로 계산한다', () => {
    expect(priceChangeOf(10000, 12500)?.ratePercent).toBe(25)
    expect(priceChangeOf(10000, 9000)?.ratePercent).toBe(-10)
    expect(priceChangeOf(15900, 19900)?.ratePercent).toBe(25.2)
  })

  it('원래 가격 0은 비율을 낼 수 없어 null이며 강조하지 않는다', () => {
    const change = priceChangeOf(0, 10000)
    expect(change?.ratePercent).toBeNull()
    expect(change?.emphasized).toBe(false)
    expect(priceChangeMessage(change!)).toBe('판매가를 0원 → 10,000원로 바꿉니다.')
  })

  it('절대 변동률이 임계 이상이면 강조(인하도 대상)', () => {
    expect(priceChangeOf(10000, 15000)?.emphasized).toBe(true)
    expect(priceChangeOf(10000, 5000)?.emphasized).toBe(true)
    expect(priceChangeOf(10000, 14900)?.emphasized).toBe(false)
    expect(PRICE_CHANGE_EMPHASIS_PERCENT).toBe(50)
  })

  it('본문은 전 → 후 (변동률)이고 강조 문구는 임계 이상일 때만 나온다', () => {
    const small = priceChangeOf(15900, 19900)!
    expect(priceChangeMessage(small)).toBe('판매가를 15,900원 → 19,900원 (+25.2%)로 바꿉니다.')
    expect(priceChangeWarnings(small)).toEqual([])

    const big = priceChangeOf(10000, 100000)!
    expect(priceChangeMessage(big)).toContain('(+900%)')
    expect(priceChangeWarnings(big)).toHaveLength(1)
    expect(priceChangeWarnings(big)[0]).toContain('50% 이상')
  })
})

describe('inspectDialogTitle — 검수 다이얼로그 제목', () => {
  it('교환은 교환 검수, 그 외는 반품 검수', () => {
    expect(inspectDialogTitle('EXCHANGE')).toBe('교환 검수')
    expect(inspectDialogTitle('RETURN')).toBe('반품 검수')
    expect(inspectDialogTitle('CANCEL')).toBe('반품 검수')
  })
})

describe('shouldChainExchangeShipment — 검수 후 교환품 발송 이어 열기', () => {
  it('교환 + 합격 + 발송 등록 가능이면 연다', () => {
    expect(shouldChainExchangeShipment('EXCHANGE', 'PASS', ['REGISTER_EXCHANGE_SHIPMENT'])).toBe(true)
  })

  it('반품·불합격·액션 없음이면 열지 않는다', () => {
    expect(shouldChainExchangeShipment('RETURN', 'PASS', ['REGISTER_EXCHANGE_SHIPMENT'])).toBe(false)
    expect(shouldChainExchangeShipment('EXCHANGE', 'FAIL', ['REGISTER_EXCHANGE_SHIPMENT'])).toBe(false)
    expect(shouldChainExchangeShipment('EXCHANGE', 'PASS', [])).toBe(false)
    expect(shouldChainExchangeShipment('EXCHANGE', 'PASS', ['MARK_EXCHANGE_DELIVERED'])).toBe(false)
  })
})
