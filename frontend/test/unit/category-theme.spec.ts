import { describe, it, expect } from 'vitest'
import { categoryTheme, categoryThemes } from '~/skins/renew/category-theme'
import type { CategorySummary } from '~/types/category'

/**
 * FE-79 카테고리 테마 대체 규칙. 이름 매핑 있음 → 매핑 그대로 · 매핑 없음 → id % 5 파스텔 + 대체 아이콘(FE-82 3종 순환) · 전체(id null) → 흰 면 + 격자.
 */
describe('categoryTheme', () => {
  it('이름 매핑 있음 → 매핑 테마(가운뎃점 이름은 첫 단어)', () => {
    expect(categoryTheme(7, '의류')).toEqual({ background: 'var(--pastel-lavender-bg)', ink: 'var(--pastel-lavender-ink)', illustration: 'shirt' })
    expect(categoryTheme(3, '리빙·주방').illustration).toBe('mug')
  })

  it('이름 매핑 없음 → id % 5 파스텔 순환(라벤더·페리윙클·핑크·민트·버터) + 대체 아이콘 id % 3 순환', () => {
    const pastels = [10, 11, 12, 13, 14].map((categoryId) => categoryTheme(categoryId, '데모'))
    expect(pastels.map((theme) => theme.background)).toEqual([
      'var(--pastel-lavender-bg)',
      'var(--pastel-periwinkle-bg)',
      'var(--pastel-pink-bg)',
      'var(--pastel-mint-bg)',
      'var(--pastel-butter-bg)',
    ])
    expect(pastels.map((theme) => theme.ink)[3]).toBe('var(--pastel-mint-ink)')
    expect(pastels.map((theme) => theme.illustration)).toEqual(['box', 'sparkle', 'tag', 'box', 'sparkle'])
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

/**
 * FE-82 목록 단위 테마. 매핑 없는 카테고리만 규칙을 쓴다.
 * - 색: 바로 앞 타일과 같으면 다음 파스텔
 * - 아이콘: 매핑 없는 카테고리의 id 오름차순 순번으로 tag·box·sparkle 순환
 */
describe('categoryThemes', () => {
  function category(categoryId: number, displayName: string): CategorySummary {
    return { categoryId, displayName, sortOrder: 0 }
  }
  // 운영 카테고리 구성(105-4g-1 실측): 기타 1 · 데모 7 · 리빙·주방 2 · 의류 3 · 잡화 4 · 디지털 5 · 문구 6
  const OPERATION = [
    category(1, '기타'),
    category(7, '데모'),
    category(2, '리빙·주방'),
    category(3, '의류'),
    category(4, '잡화'),
    category(5, '디지털'),
    category(6, '문구'),
  ]

  it('인접 타일 색이 겹치지 않는다(매핑 없는 카테고리가 앞 타일과 같은 색이면 다음 파스텔)', () => {
    const backgrounds = categoryThemes(OPERATION).map((theme) => theme.background)
    backgrounds.slice(1).forEach((background, index) => expect(background).not.toBe(backgrounds[index]))

    // id 1·6은 둘 다 id % 5 = 1(페리윙클) → 앞 타일이 다음 타일 기본색을 피해 핑크로 넘어간다 · 매핑(리빙 페리윙클) 뒤의 id 11은 앞 타일을 피한다.
    expect(categoryThemes([category(1, '기타'), category(6, '신규')]).map((theme) => theme.background)).toEqual([
      'var(--pastel-pink-bg)',
      'var(--pastel-periwinkle-bg)',
    ])
    expect(categoryThemes([category(2, '리빙·주방'), category(11, '신규')])[1]?.background).toBe('var(--pastel-pink-bg)')
  })

  it('매핑 없는 타일은 바로 다음 매핑 타일 색도 피한다 — 데모(id 1 · 기본 페리윙클) → 리빙·주방(매핑 페리윙클)', () => {
    const themes = categoryThemes([category(1, '데모'), category(2, '리빙·주방'), category(3, '의류')])
    expect(themes[1]?.background).toBe('var(--pastel-periwinkle-bg)')
    expect(themes[0]?.background).not.toBe(themes[1]?.background)
    expect(themes[0]?.background).toBe('var(--pastel-pink-bg)')
  })

  it('대체 아이콘은 매핑 없는 카테고리 id 순번으로 순환 — 운영 기타·데모가 다른 아이콘', () => {
    const themes = categoryThemes(OPERATION)
    expect(themes[0]?.illustration).toBe('tag')
    expect(themes[1]?.illustration).toBe('box')

    const three = categoryThemes([category(9, '가'), category(3, '나'), category(5, '다')])
    expect(three.map((theme) => theme.illustration)).toEqual(['sparkle', 'tag', 'box'])
  })

  it('전체 타일 유지 — 입력 순서·개수 그대로, 매핑 카테고리는 매핑 테마', () => {
    const themes = categoryThemes(OPERATION)
    expect(themes).toHaveLength(OPERATION.length)
    expect(themes[3]).toEqual(categoryTheme(3, '의류'))
    expect(themes.map((theme) => theme.illustration).slice(2)).toEqual(['mug', 'shirt', 'bag', 'monitor', 'pencil'])
    expect(categoryThemes([])).toEqual([])
  })
})
