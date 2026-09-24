import { describe, it, expect } from 'vitest'
import { categoryTheme } from '~/skins/renew/category-theme'

/**
 * FE-79 카테고리 테마 대체 규칙. 이름 매핑 있음 → 매핑 그대로 · 매핑 없음 → id % 5 파스텔 + 태그 · 전체(id null) → 흰 면 + 격자.
 */
describe('categoryTheme', () => {
  it('이름 매핑 있음 → 매핑 테마(가운뎃점 이름은 첫 단어)', () => {
    expect(categoryTheme(7, '의류')).toEqual({ background: 'var(--pastel-lavender-bg)', ink: 'var(--pastel-lavender-ink)', illustration: 'shirt' })
    expect(categoryTheme(3, '리빙·주방').illustration).toBe('mug')
  })

  it('이름 매핑 없음 → id % 5 파스텔 순환(라벤더·페리윙클·핑크·민트·버터) + 태그 아이콘', () => {
    const pastels = [10, 11, 12, 13, 14].map((categoryId) => categoryTheme(categoryId, '데모'))
    expect(pastels.map((theme) => theme.background)).toEqual([
      'var(--pastel-lavender-bg)',
      'var(--pastel-periwinkle-bg)',
      'var(--pastel-pink-bg)',
      'var(--pastel-mint-bg)',
      'var(--pastel-butter-bg)',
    ])
    expect(pastels.map((theme) => theme.ink)[3]).toBe('var(--pastel-mint-ink)')
    expect(pastels.every((theme) => theme.illustration === 'tag')).toBe(true)
    expect(categoryTheme(15, '데모').background).toBe('var(--pastel-lavender-bg)')
  })

  it('전체(id null) → 흰 면 + 격자 유지', () => {
    expect(categoryTheme(null, null)).toEqual({ background: 'var(--surface-card)', ink: 'var(--pastel-lavender-ink)', illustration: 'grid' })
  })

  it('이름이 바뀌어도(매핑 이름 → 새 이름·이름 없음) 실제 카테고리는 흰 면으로 떨어지지 않는다', () => {
    for (const displayName of ['의류 신상', '데모', '', null]) {
      const theme = categoryTheme(1, displayName)
      expect(theme.background).not.toBe('var(--surface-card)')
      expect(theme.illustration).not.toBe('grid')
    }
  })
})
