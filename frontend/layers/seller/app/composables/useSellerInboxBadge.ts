import { DEFAULT_INBOX_QUERY } from '~/lib/inbox-query'
import { inboxTotal } from '~/lib/inbox-view'
import { useSellerInbox } from '#layers/seller/app/composables/useSellerInbox'

/**
 * 셀러 사이드바 "인박스" 메뉴 배지(오늘 탭 건수 · 관리자 useAdminInboxBadge 복제). 사이드바와 인박스 페이지가 useState로 같은 값을 본다.
 */
export function useSellerInboxBadge() {
  const count = useState<number | null>('seller-inbox-today-count', () => null)
  const inboxApi = useSellerInbox()

  function set(value: number): void {
    count.value = value
  }

  async function refresh(): Promise<void> {
    try {
      const response = await inboxApi.list(DEFAULT_INBOX_QUERY)
      count.value = inboxTotal(response.counts)
    } catch (error) {
      // 배지는 보조 표시라 화면을 막지 않는다 — 원인은 인박스 화면 진입 시 오류 안내로 드러난다.
      console.warn('[inbox] 메뉴 배지 건수를 불러오지 못했습니다', error)
      count.value = null
    }
  }

  return { count, set, refresh }
}
