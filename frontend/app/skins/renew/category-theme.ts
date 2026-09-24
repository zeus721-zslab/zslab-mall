import type { CategorySummary } from '~/types/category'

/** renew 카테고리 일러스트 종류(CategoryIllustration.vue와 1:1). */
export type CategoryIllustrationName = 'shirt' | 'mug' | 'bottle' | 'bowl' | 'bag' | 'monitor' | 'pencil' | 'tag' | 'box' | 'sparkle' | 'grid'

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
// 대체 아이콘 3종(FE-82). 매핑 없는 카테고리가 여럿이어도 같은 아이콘이 이어지지 않게 돌려 쓴다.
const FALLBACK_ILLUSTRATIONS: readonly CategoryIllustrationName[] = ['tag', 'box', 'sparkle']

function pastelTheme(pastelIndex: number, illustration: CategoryIllustrationName): CategoryTheme {
  const pastel = FALLBACK_PASTELS[pastelIndex % FALLBACK_PASTELS.length]
  return { background: `var(--pastel-${pastel}-bg)`, ink: `var(--pastel-${pastel}-ink)`, illustration }
}

function fallbackIllustration(order: number): CategoryIllustrationName {
  return FALLBACK_ILLUSTRATIONS[order % FALLBACK_ILLUSTRATIONS.length] ?? 'tag'
}

// "리빙·주방"처럼 가운뎃점으로 묶인 이름은 첫 단어로 찾는다(시드 카테고리명 실측).
const NAME_SEPARATOR = '·'

function mappedTheme(displayName: string | null): CategoryTheme | null {
  const primaryName = displayName === null ? '' : (displayName.split(NAME_SEPARATOR)[0]?.trim() ?? '')
  return CATEGORY_THEMES[primaryName] ?? null
}

/** 카테고리 하나의 테마. 목록을 모를 때(전체·목록 밖 id)만 쓴다 — 목록이 있으면 categoryThemes가 이웃 색 겹침까지 피한다. */
export function categoryTheme(categoryId: number | null, displayName: string | null): CategoryTheme {
  if (categoryId === null) return ALL_THEME
  const absoluteId = Math.abs(categoryId)
  return mappedTheme(displayName) ?? pastelTheme(absoluteId, fallbackIllustration(absoluteId))
}

/**
 * 목록 순서대로 테마를 만든다(FE-82 · 결과 순서 = 입력 순서). 이름 매핑이 있는 카테고리는 매핑 그대로다.
 * 매핑이 없는 카테고리의 규칙:
 * - 색: id % 5 파스텔. 바로 앞 타일 색이나 바로 다음 타일 색(매핑 여부 무관)과 같으면 다음 파스텔로 넘긴다.
 *   다음 타일이 매핑 없는 카테고리면 그 타일의 기본색(id % 5)과 비교한다. 그 타일은 다시 자기 앞(이 타일)을 피하므로 인접 색은 겹치지 않는다.
 *   피할 색은 최대 2개이고 파스텔은 5색이라 항상 고를 수 있다.
 * - 아이콘: 매핑 없는 카테고리를 id 오름차순으로 세운 순번으로 tag·box·sparkle을 돌린다.
 *   id % 3을 쓰면 운영의 기타(1)·데모(7)가 같은 아이콘이 된다.
 */
export function categoryThemes(categories: readonly CategorySummary[]): CategoryTheme[] {
  const unmappedIds = categories
    .filter((category) => mappedTheme(category.displayName) === null)
    .map((category) => category.categoryId)
    .sort((left, right) => left - right)
  const baseBackground = (category: CategorySummary): string =>
    (mappedTheme(category.displayName) ?? pastelTheme(Math.abs(category.categoryId), 'tag')).background
  const themes: CategoryTheme[] = []
  categories.forEach((category, index) => {
    const mapped = mappedTheme(category.displayName)
    if (mapped) {
      themes.push(mapped)
      return
    }
    const illustration = fallbackIllustration(unmappedIds.indexOf(category.categoryId))
    const nextCategory = categories[index + 1]
    const avoided = new Set<string>()
    const previousTheme = themes.at(-1)
    if (previousTheme) avoided.add(previousTheme.background)
    if (nextCategory) avoided.add(baseBackground(nextCategory))
    let pastelIndex = Math.abs(category.categoryId)
    while (avoided.has(pastelTheme(pastelIndex, illustration).background)) pastelIndex++
    themes.push(pastelTheme(pastelIndex, illustration))
  })
  return themes
}
