import { formatDateTime } from '~/lib/utils/datetime'

/** 관리자 표 표시 포맷(FE-25). 가격은 사용자 영역에 공용 유틸이 없어(컴포넌트 인라인 2곳) 레이어 최소 구현·날짜는 사용자 유틸 재사용. */

export function formatWon(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return `${value.toLocaleString('ko-KR')}원`
}

/** 판매기간 표기: 시작~종료. 시작 없음=즉시, 종료 없음=무기한, 둘 다 없음=상시. */
export function formatSalePeriod(saleStartAt: string | undefined, saleEndAt: string | undefined): string {
  if (!saleStartAt && !saleEndAt) return '상시'
  const start = saleStartAt ? formatDateTime(saleStartAt) : '즉시'
  const end = saleEndAt ? formatDateTime(saleEndAt) : '무기한'
  return `${start} ~ ${end}`
}
