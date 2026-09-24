import { SKIN_COOKIE, SKIN_QUERY, SKIN_RESET_VALUE, SKIN_STATE_KEY } from '~/lib/constants/skin'
import { DEFAULT_SKIN, isSkinName, type SkinName } from '~/skins/registry'

const NON_BUYER_PREFIXES = ['/admin', '/seller']

function isBuyerPath(pathname: string): boolean {
  return !NON_BUYER_PREFIXES.some((prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`))
}

/**
 * 구매자 스킨 결정(FE-67). 우선순위: 쿠키 zslab_skin → NUXT_PUBLIC_SKIN → classic. 미등록 값은 무시한다.
 * ?skin=<이름>은 쿠키를 설정하고 ?skin=reset은 지운다 — 구매자 경로에서만(관리자·셀러 경로는 무관).
 * useState 초기화 함수는 SSR에서만 실행되고 CSR은 페이로드 값을 그대로 써서 요청 단위 1회 결정이 SSR·CSR에서 일치한다.
 */
export default defineNuxtPlugin(() => {
  const url = useRequestURL()
  const cookie = useCookie<string | null>(SKIN_COOKIE, { path: '/', sameSite: 'lax', secure: true })

  if (isBuyerPath(url.pathname)) {
    const requested = url.searchParams.get(SKIN_QUERY)
    if (requested === SKIN_RESET_VALUE) {
      cookie.value = null
    } else if (isSkinName(requested)) {
      cookie.value = requested
    }
  }

  useState<SkinName>(SKIN_STATE_KEY, () => {
    const configured = useRuntimeConfig().public.skin
    if (isSkinName(cookie.value)) return cookie.value
    if (isSkinName(configured)) return configured
    return DEFAULT_SKIN
  })
})
