import type { SellerMe } from '#layers/seller/app/types/seller-me'
import { useSellerAuthStore } from '#layers/seller/app/stores/sellerAuth'

/**
 * 셀러 본인 정보(GET /seller/me·D-191) 셸 소비(Track 90-B-3). 레이아웃이 1회 로드하고 상단바(상호·상태)·정지 배너가 같은 상태를 읽도록
 * useState('seller-me')로 공유한다. status=SUSPENDED면 진입 시점에 sellerAuth.markSuspended()를 호출해 첫 쓰기 거부 전에도 배너가 뜬다
 * (FE-44 이월: 진입 시 자기 상태를 볼 경로). 401은 useSellerApi가 로그인으로 보내고, 그 외 실패는 표시만 비운다(셸 렌더는 계속).
 */
export function useSellerMe() {
  const me = useState<SellerMe | null>('seller-me', () => null)
  const error = useState<boolean>('seller-me-error', () => false)
  const sellerAuth = useSellerAuthStore()
  const api = useSellerApi()

  async function load(): Promise<void> {
    error.value = false
    try {
      const response = await api<SellerMe>('/v1/seller/me')
      me.value = response
      if (response.status === 'SUSPENDED') sellerAuth.markSuspended()
    } catch (loadError) {
      // 상단바 표시만 비운다. 원인은 콘솔에 남긴다(.catch(()=>{}) 금지).
      console.warn('[seller-me] load failed', loadError)
      me.value = null
      error.value = true
    }
  }

  function clear(): void {
    me.value = null
    error.value = false
  }

  return { me, error, load, clear }
}
