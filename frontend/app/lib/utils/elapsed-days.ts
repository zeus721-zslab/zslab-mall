/**
 * 목록 행 "경과 N일" 계산 단일 소스(Track 96-1 FE-53·C-15·관리자·셀러 공용). 방치 건 탐지용: 기준 시각(클레임 요청일·결제일·발송일)부터
 * 지금까지 지난 일수를 FE가 계산하고 임계 이상이면 주의 톤을 준다. 임계는 이 파일 한 곳에서만 바꾼다.
 * 기준 시각 문자열은 KST 오프셋 ISO(+09:00)라 Date.parse가 절대 시각으로 해석한다(오프셋 없는 문자열은 로컬로 해석되며 KST 브라우저 기준).
 */

/** 3일 이상 = 주의(warning), 7일 이상 = 위험(danger). */
export const ELAPSED_WARNING_DAYS = 3
export const ELAPSED_DANGER_DAYS = 7

export type ElapsedTone = 'warning' | 'danger' | null

const DAY_MS = 24 * 60 * 60 * 1000

/** 기준 시각부터 now까지 지난 만 일수(내림·음수는 0). 파싱 실패는 null. */
export function elapsedDays(fromIso: string, now: Date = new Date()): number | null {
  const from = Date.parse(fromIso)
  if (Number.isNaN(from)) return null
  return Math.max(0, Math.floor((now.getTime() - from) / DAY_MS))
}

/** 경과 일수 → 톤. 임계 미만은 null(중립). */
export function elapsedTone(days: number): ElapsedTone {
  if (days >= ELAPSED_DANGER_DAYS) return 'danger'
  if (days >= ELAPSED_WARNING_DAYS) return 'warning'
  return null
}

/** 목록 행 chip 데이터. 톤은 각 레이어 chip 클래스(adm-chip--*·slr-chip--*)의 접미로 쓴다. */
export interface ElapsedChip {
  days: number
  text: string
  tone: 'warning' | 'danger' | 'neutral'
}

/** 기준 시각이 없거나 파싱 불가면 null(표시 없음). */
export function elapsedChip(fromIso: string | undefined, now: Date = new Date()): ElapsedChip | null {
  if (!fromIso) return null
  const days = elapsedDays(fromIso, now)
  if (days === null) return null
  return { days, text: `경과 ${days}일`, tone: elapsedTone(days) ?? 'neutral' }
}
