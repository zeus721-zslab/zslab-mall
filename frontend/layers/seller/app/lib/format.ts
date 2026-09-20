/** 셀러 표 표시 포맷(Track 90-B-3·관리자 lib/format 복제). 날짜는 사용자 유틸(~/lib/utils/datetime.formatDateTime)을 그대로 쓴다. */
export function formatWon(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return `${value.toLocaleString('ko-KR')}원`
}

export function formatCount(value: number, unit = '건'): string {
  return `${value.toLocaleString('ko-KR')}${unit}`
}
