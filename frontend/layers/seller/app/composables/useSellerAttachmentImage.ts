import { useSellerAuthStore } from '#layers/seller/app/stores/sellerAuth'

/**
 * 클레임 첨부 이미지 blob 로더(Track 90-D-1). 첨부 서빙(GET /api/v1/files/claims/**)은 Bearer·admin_token·auth_token 후보만 인가하고(D-176)
 * 셀러 세션 쿠키(seller_token)는 path=/seller라 이미지 요청에 실리지 않으므로 `<img :src>`로는 열 수 없다 → fetch + Authorization Bearer로 받아
 * object URL을 만들어 표시한다. 상태는 loading → ready | error(404/403 등 HTTP 상태 보존)이며, URL이 바뀌거나 스코프가 사라지면 object URL을 해제한다.
 */
export type SellerAttachmentImageStatus = 'loading' | 'ready' | 'error'

export class SellerAttachmentFetchError extends Error {
  readonly status: number

  constructor(status: number) {
    super(`attachment fetch failed: ${status}`)
    this.name = 'SellerAttachmentFetchError'
    this.status = status
  }
}

/** 첨부 URL을 Bearer로 받아 object URL로 바꾼다(순수·테스트 가능). 비-2xx는 SellerAttachmentFetchError(status). */
export async function fetchAttachmentObjectUrl(url: string, token: string, fetcher: typeof fetch = fetch): Promise<string> {
  const response = await fetcher(url, { headers: { Authorization: `Bearer ${token}` }, cache: 'no-store' })
  if (!response.ok) throw new SellerAttachmentFetchError(response.status)
  return URL.createObjectURL(await response.blob())
}

export function useSellerAttachmentImage(url: MaybeRefOrGetter<string>) {
  const sellerAuth = useSellerAuthStore()
  const src = ref<string | null>(null)
  const status = ref<SellerAttachmentImageStatus>('loading')
  const errorStatus = ref<number | null>(null)
  let requestSequence = 0

  function release(): void {
    if (src.value) URL.revokeObjectURL(src.value)
    src.value = null
  }

  async function load(): Promise<void> {
    const sequence = ++requestSequence
    release()
    status.value = 'loading'
    errorStatus.value = null
    try {
      const objectUrl = await fetchAttachmentObjectUrl(toValue(url), sellerAuth.token ?? '')
      if (sequence !== requestSequence) {
        URL.revokeObjectURL(objectUrl) // 늦게 도착한 이전 요청의 blob은 바로 해제
        return
      }
      src.value = objectUrl
      status.value = 'ready'
    } catch (error) {
      if (sequence !== requestSequence) return
      errorStatus.value = error instanceof SellerAttachmentFetchError ? error.status : null
      status.value = 'error'
    }
  }

  watch(() => toValue(url), () => { void load() }, { immediate: true })
  onScopeDispose(() => { requestSequence++; release() })

  return { src, status, errorStatus, reload: load }
}
