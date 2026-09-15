/**
 * 공개 카테고리 목록 항목(백엔드 CategorySummaryResponse 대응·BE Track 72·D-161). 루트 카테고리만 내려오며
 * sortOrder·id 오름차순으로 정렬되어 있다. categoryId는 ProductSummary.categoryId와 동일 값이다.
 */
export interface CategorySummary {
  categoryId: number
  displayName: string
  sortOrder: number
}
