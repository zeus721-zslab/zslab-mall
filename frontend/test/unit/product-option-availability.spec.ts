import { describe, it, expect } from 'vitest'
import { isOptionValueSoldOut } from '~/lib/utils/product-option-availability'
import type { ProductVariant } from '~/types/product'

function variant(publicId: string, soldOut: boolean, options: Record<string, string>): ProductVariant {
  return {
    variantPublicId: publicId,
    salePrice: 10000,
    soldOut,
    options: Object.entries(options).map(([groupName, value]) => ({ groupName, value })),
  }
}

// FE-70: 옵션 값 품절 = 이 값 + 다른 그룹 선택값을 모두 포함하는 variant가 전부 품절(또는 없음).
describe('isOptionValueSoldOut', () => {
  it('단일 그룹 — 그 값의 variant가 품절이면 품절·재고 있으면 아님·variant가 없으면 품절', () => {
    const variants = [variant('var_1', false, { 색상: '검정' }), variant('var_2', true, { 색상: '빨강' })]
    expect(isOptionValueSoldOut(variants, {}, '색상', '검정')).toBe(false)
    expect(isOptionValueSoldOut(variants, {}, '색상', '빨강')).toBe(true)
    expect(isOptionValueSoldOut(variants, {}, '색상', '파랑')).toBe(true)
  })

  const combos = [
    variant('var_1', false, { 색상: '검정', 사이즈: 'S' }),
    variant('var_2', true, { 색상: '검정', 사이즈: 'M' }),
    variant('var_3', true, { 색상: '빨강', 사이즈: 'S' }),
    variant('var_4', true, { 색상: '빨강', 사이즈: 'M' }),
  ]

  it('다중 조합·미선택 — 다른 그룹 조건 없이 그 값을 가진 variant 중 하나라도 재고 있으면 아님', () => {
    expect(isOptionValueSoldOut(combos, {}, '색상', '검정')).toBe(false)
    expect(isOptionValueSoldOut(combos, {}, '색상', '빨강')).toBe(true)
    expect(isOptionValueSoldOut(combos, {}, '사이즈', 'S')).toBe(false)
    expect(isOptionValueSoldOut(combos, {}, '사이즈', 'M')).toBe(true)
  })

  it('다중 조합·일부 선택 — 다른 그룹 선택값과의 조합만 본다', () => {
    expect(isOptionValueSoldOut(combos, { 색상: '검정' }, '사이즈', 'S')).toBe(false)
    expect(isOptionValueSoldOut(combos, { 색상: '검정' }, '사이즈', 'M')).toBe(true)
    expect(isOptionValueSoldOut(combos, { 사이즈: 'M' }, '색상', '검정')).toBe(true)
  })

  it('같은 그룹의 현재 선택값은 조건에서 빠진다 — 다른 값으로 바꿀 때도 판단이 같다', () => {
    expect(isOptionValueSoldOut(combos, { 색상: '빨강', 사이즈: 'S' }, '색상', '검정')).toBe(false)
    expect(isOptionValueSoldOut(combos, { 색상: '검정', 사이즈: 'S' }, '색상', '검정')).toBe(false)
  })

  it('대각 품절 — 조합 기준으로는 품절 표시지만 선택 없이 보면 살 수 있다(비활성 아님·교착 방지)', () => {
    const diagonal = [
      variant('var_1', false, { 색상: '검정', 사이즈: 'S' }),
      variant('var_2', true, { 색상: '검정', 사이즈: 'M' }),
      variant('var_3', true, { 색상: '빨강', 사이즈: 'S' }),
      variant('var_4', false, { 색상: '빨강', 사이즈: 'M' }),
    ]
    const selected = { 색상: '검정', 사이즈: 'S' }
    expect(isOptionValueSoldOut(diagonal, selected, '색상', '빨강')).toBe(true)
    expect(isOptionValueSoldOut(diagonal, selected, '사이즈', 'M')).toBe(true)
    expect(isOptionValueSoldOut(diagonal, {}, '색상', '빨강')).toBe(false)
    expect(isOptionValueSoldOut(diagonal, {}, '사이즈', 'M')).toBe(false)
  })

  it('전부 품절 — 어떤 값도 선택 가능하지 않다', () => {
    const allSoldOut = combos.map((item) => ({ ...item, soldOut: true }))
    expect(isOptionValueSoldOut(allSoldOut, {}, '색상', '검정')).toBe(true)
    expect(isOptionValueSoldOut(allSoldOut, { 색상: '검정' }, '사이즈', 'S')).toBe(true)
  })
})
