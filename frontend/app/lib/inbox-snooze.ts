/**
 * 인박스 보류 시각 프리셋·직접 입력 변환(D-248 · KST 고정). 브라우저 시간대와 무관하게 KST 벽시계로 계산하고 BE가 받는 ISO offset(+09:00)으로
 * 만든다 — 해외 브라우저에서도 "오늘 18:00"은 서울 18:00이다.
 */

const MINUTE_MS = 60 * 1000
const HOUR_MS = 60 * MINUTE_MS
const DAY_MS = 24 * HOUR_MS
const KST_OFFSET_MS = 9 * HOUR_MS

export const INBOX_SNOOZE_EVENING_HOUR = 18
export const INBOX_SNOOZE_MORNING_HOUR = 9

export type InboxSnoozePresetKey = 'IN_ONE_HOUR' | 'TODAY_EVENING' | 'TOMORROW_MORNING'

export interface InboxSnoozePreset {
  key: InboxSnoozePresetKey
  label: string
  untilAt: string
}

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/** epoch ms → KST 벽시계 ISO offset 문자열('yyyy-MM-ddTHH:mm:ss+09:00'). */
export function toKstIso(epochMs: number): string {
  const kst = new Date(epochMs + KST_OFFSET_MS)
  return `${kst.getUTCFullYear()}-${pad(kst.getUTCMonth() + 1)}-${pad(kst.getUTCDate())}`
    + `T${pad(kst.getUTCHours())}:${pad(kst.getUTCMinutes())}:${pad(kst.getUTCSeconds())}+09:00`
}

/** KST 기준 오늘 00:00의 epoch ms. */
function kstStartOfDay(nowMs: number): number {
  return Math.floor((nowMs + KST_OFFSET_MS) / DAY_MS) * DAY_MS - KST_OFFSET_MS
}

/** 1시간 뒤 · 오늘 18:00(이미 지났으면 숨김) · 내일 09:00. */
export function inboxSnoozePresets(nowMs: number): InboxSnoozePreset[] {
  const today = kstStartOfDay(nowMs)
  const presets: InboxSnoozePreset[] = [{ key: 'IN_ONE_HOUR', label: '1시간 뒤', untilAt: toKstIso(nowMs + HOUR_MS) }]
  const evening = today + INBOX_SNOOZE_EVENING_HOUR * HOUR_MS
  if (evening > nowMs) presets.push({ key: 'TODAY_EVENING', label: '오늘 18:00', untilAt: toKstIso(evening) })
  presets.push({ key: 'TOMORROW_MORNING', label: '내일 09:00', untilAt: toKstIso(today + DAY_MS + INBOX_SNOOZE_MORNING_HOUR * HOUR_MS) })
  return presets
}

/**
 * 직접 선택한 값(input type=datetime-local의 'yyyy-MM-ddTHH:mm' · KST로 해석) → ISO offset. 형식이 틀리거나 지금 이후가 아니면 null.
 */
export function customSnoozeUntil(localValue: string, nowMs: number): string | null {
  const matched = localValue.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/)
  if (!matched) return null
  const [, year, month, day, hour, minute] = matched.map(Number)
  const epochMs = Date.UTC(year!, month! - 1, day!, hour!, minute!) - KST_OFFSET_MS
  if (Number.isNaN(epochMs) || epochMs <= nowMs) return null
  const iso = toKstIso(epochMs)
  // 존재하지 않는 날짜(2월 30일 등)는 Date.UTC가 다음 달로 넘기므로 원래 입력과 다르면 거른다.
  return iso.startsWith(`${localValue}:`) ? iso : null
}

/** datetime-local 입력의 최소값(KST 지금 · 분 단위). */
export function kstLocalInputMin(nowMs: number): string {
  return toKstIso(nowMs).slice(0, 16)
}
