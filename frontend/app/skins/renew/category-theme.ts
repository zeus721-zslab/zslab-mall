/** renew 카테고리 일러스트 종류(CategoryIllustration.vue와 1:1). */
export type CategoryIllustrationName = 'shirt' | 'mug' | 'bottle' | 'bowl' | 'bag' | 'monitor' | 'pencil' | 'tag' | 'grid'

/** 카테고리 배너·카드의 파스텔 배경·글자 CSS 변수(main.css renew 블록)와 일러스트. */
export interface CategoryTheme {
  background: string
  ink: string
  illustration: CategoryIllustrationName
}

// 카테고리 표시명 → 테마(FE-68·키 = 이름). 이름 변경 시 여기 한 곳만 고친다.
// 파스텔은 5색이라 색을 다시 쓸 때는 탭 순서(sortOrder)에서 이웃하지 않게 둔다 — 잡화·문구는 버터지만 사이에 디지털(민트)이 있다.
const CATEGORY_THEMES: Record<string, CategoryTheme> = {
  의류: { background: 'var(--pastel-lavender-bg)', ink: 'var(--pastel-lavender-ink)', illustration: 'shirt' },
  리빙: { background: 'var(--pastel-periwinkle-bg)', ink: 'var(--pastel-periwinkle-ink)', illustration: 'mug' },
  뷰티: { background: 'var(--pastel-pink-bg)', ink: 'var(--pastel-pink-ink)', illustration: 'bottle' },
  푸드: { background: 'var(--pastel-mint-bg)', ink: 'var(--pastel-mint-ink)', illustration: 'bowl' },
  잡화: { background: 'var(--pastel-butter-bg)', ink: 'var(--pastel-butter-ink)', illustration: 'bag' },
  디지털: { background: 'var(--pastel-mint-bg)', ink: 'var(--pastel-mint-ink)', illustration: 'monitor' },
  문구: { background: 'var(--pastel-butter-bg)', ink: 'var(--pastel-butter-ink)', illustration: 'pencil' },
}

/** 전체(카테고리 없음): 카드 면 색(FE-76부터 흰색) + 그리드. 실제 카테고리는 이 면으로 떨어지지 않는다(FE-79). */
const ALL_THEME: CategoryTheme = {
  background: 'var(--surface-card)',
  ink: 'var(--pastel-lavender-ink)',
  illustration: 'grid',
}

// 이름 매핑이 없는 실제 카테고리(운영에서 새로 만든 카테고리 등): id % 5로 파스텔을 돌려 쓴다(FE-79).
// id 기준이라 이름이 바뀌어도 색이 유지된다. 환경마다 id가 달라 id → 테마 고정 매핑은 두지 않는다.
const FALLBACK_PASTELS = ['lavender', 'periwinkle', 'pink', 'mint', 'butter'] as const

function fallbackTheme(categoryId: number): CategoryTheme {
  const pastel = FALLBACK_PASTELS[Math.abs(categoryId) % FALLBACK_PASTELS.length]
  return { background: `var(--pastel-${pastel}-bg)`, ink: `var(--pastel-${pastel}-ink)`, illustration: 'tag' }
}

// "리빙·주방"처럼 가운뎃점으로 묶인 이름은 첫 단어로 찾는다(시드 카테고리명 실측).
const NAME_SEPARATOR = '·'

export function categoryTheme(categoryId: number | null, displayName: string | null): CategoryTheme {
  if (categoryId === null) return ALL_THEME
  const primaryName = displayName === null ? '' : (displayName.split(NAME_SEPARATOR)[0]?.trim() ?? '')
  return CATEGORY_THEMES[primaryName] ?? fallbackTheme(categoryId)
}
