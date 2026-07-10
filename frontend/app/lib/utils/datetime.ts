/**
 * LocalDateTime 문자열 표시 포맷 단일 소스(orders/index.vue formatOrderedAt 추출·소비처 3: 주문 목록·클레임 목록·클레임 상세).
 *
 * BE는 LocalDateTime을 KST +09:00 오프셋을 부착한 ISO-8601 문자열로 내려준다(Track 69·'yyyy-MM-ddTHH:mm:ss.SSS+09:00').
 * new Date 파싱은 로컬 타임존 변환으로 SSR/클라 hydration 불일치를 유발하므로 문자열을 직접 자른다(앞 16자=KST 벽시계 그대로).
 */

/** ISO 문자열을 'yyyy.MM.dd HH:mm'로 변환한다. 패턴 불일치 시 원본을 폴백 반환한다(방어). */
export function formatDateTime(iso: string): string {
  const matched = iso.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/)
  if (!matched) return iso
  const [, year, month, day, hour, minute] = matched
  return `${year}.${month}.${day} ${hour}:${minute}`
}
