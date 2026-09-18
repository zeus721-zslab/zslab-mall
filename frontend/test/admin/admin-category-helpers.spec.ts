import { describe, it, expect } from 'vitest'
import {
  canDeleteCategory,
  commissionRateChanged,
  deleteBlockedReason,
  formatCommissionRate,
  formatPercent,
  moveCategory,
  parsePercentInput,
  toPercentInput,
  toProductListPath,
} from '#layers/admin/app/lib/admin-category-view'

// FE-38: 율 bp↔% 환산·삭제 가능 판정·순서 이동 계산 순수 함수.
describe('formatPercent / formatCommissionRate / toPercentInput', () => {
  it('bp → %: 불필요한 소수 0 제거·소수 2자리', () => {
    expect(formatPercent(1000)).toBe('10%')
    expect(formatPercent(525)).toBe('5.25%')
    expect(formatPercent(0)).toBe('0%')
    expect(formatPercent(10_000)).toBe('100%')
  })

  it('미설정(null·undefined)은 기본율을 병기하고, 설정값은 그대로 %', () => {
    expect(formatCommissionRate(null, 1000)).toBe('미설정 (기본율 10% 적용)')
    expect(formatCommissionRate(undefined, 1250)).toBe('미설정 (기본율 12.5% 적용)')
    expect(formatCommissionRate(500, 1000)).toBe('5%')
  })

  it('입력 초기값: 미설정은 빈 문자열, 값은 % 문자열', () => {
    expect(toPercentInput(null)).toBe('')
    expect(toPercentInput(undefined)).toBe('')
    expect(toPercentInput(525)).toBe('5.25')
    expect(toPercentInput(1000)).toBe('10')
  })
})

describe('parsePercentInput', () => {
  it('빈 값은 미설정(null)·정수/소수 2자리는 bp 정수', () => {
    expect(parsePercentInput('')).toEqual({ ok: true, basisPoints: null })
    expect(parsePercentInput('  ')).toEqual({ ok: true, basisPoints: null })
    expect(parsePercentInput('5')).toEqual({ ok: true, basisPoints: 500 })
    expect(parsePercentInput('5.25')).toEqual({ ok: true, basisPoints: 525 })
    expect(parsePercentInput('0')).toEqual({ ok: true, basisPoints: 0 })
    expect(parsePercentInput('100')).toEqual({ ok: true, basisPoints: 10_000 })
  })

  it('형식 오류(음수·소수 3자리·문자)와 범위 초과(100.01)는 ok=false', () => {
    expect(parsePercentInput('-1').ok).toBe(false)
    expect(parsePercentInput('5.255').ok).toBe(false)
    expect(parsePercentInput('abc').ok).toBe(false)
    expect(parsePercentInput('100.01')).toEqual({ ok: false, message: '수수료율은 0%~100% 사이여야 합니다.' })
  })
})

describe('commissionRateChanged', () => {
  it('미설정 ↔ 값·값 ↔ 다른 값은 변경, 같은 값·미설정 유지는 무변경', () => {
    expect(commissionRateChanged(null, 500)).toBe(true)
    expect(commissionRateChanged(undefined, 500)).toBe(true)
    expect(commissionRateChanged(500, null)).toBe(true)
    expect(commissionRateChanged(500, 600)).toBe(true)
    expect(commissionRateChanged(500, 500)).toBe(false)
    expect(commissionRateChanged(undefined, null)).toBe(false)
  })
})

describe('canDeleteCategory / deleteBlockedReason', () => {
  it('상품 0건만 삭제 가능·아니면 건수 툴팁', () => {
    expect(canDeleteCategory({ productCount: 0 })).toBe(true)
    expect(canDeleteCategory({ productCount: 7 })).toBe(false)
    expect(deleteBlockedReason({ productCount: 0 })).toBeNull()
    expect(deleteBlockedReason({ productCount: 7 })).toBe('연결된 상품 7건')
  })
})

describe('moveCategory', () => {
  it('위/아래 이동은 인접 항목과 자리를 바꾼 전체 배열을 돌려준다(원본 불변)', () => {
    const ids = [1, 2, 3]
    expect(moveCategory(ids, 1, -1)).toEqual([2, 1, 3])
    expect(moveCategory(ids, 1, 1)).toEqual([1, 3, 2])
    expect(ids).toEqual([1, 2, 3])
  })

  it('경계(맨 위에서 위·맨 아래에서 아래)·범위 밖 index는 null', () => {
    expect(moveCategory([1, 2, 3], 0, -1)).toBeNull()
    expect(moveCategory([1, 2, 3], 2, 1)).toBeNull()
    expect(moveCategory([1, 2, 3], 5, -1)).toBeNull()
    expect(moveCategory([], 0, 1)).toBeNull()
  })
})

describe('toProductListPath', () => {
  it('상품 목록 카테고리 필터 경로', () => {
    expect(toProductListPath(5)).toBe('/admin/products?categoryId=5')
  })
})
