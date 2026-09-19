import type { Page } from '@playwright/test'

/**
 * 구매자(SSR) 페이지로 클라이언트 내비게이션한다. `page.goto`는 SSR 렌더라 useFetch가 서버(컨테이너→BE)에서 실행돼 `page.route` mock을 거치지 않는다.
 * 예전엔 `/login?redirect=` 데모 로그인의 복귀 이동으로 이를 확보했으나, 세션을 쿠키로 직접 심는 loginAs(helpers/login.ts) 뒤에는 로그인 화면이
 * SSR 단계에서 곧바로 리다이렉트하므로 Nuxt 라우터(vueApp.$nuxt.$router)로 직접 push한다. hydration이 끝난 페이지가 없으면 홈(/)을 먼저 연다.
 */
interface NuxtRootElement extends Element {
  __vue_app__?: { $nuxt?: { $router: { push(to: string): Promise<unknown> } } }
}

export async function gotoClientSide(page: Page, path: string): Promise<void> {
  if (page.url() === 'about:blank') {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
  }
  await page.evaluate((target) => {
    const router = (document.querySelector('#__nuxt') as NuxtRootElement | null)?.__vue_app__?.$nuxt?.$router
    if (!router) throw new Error('Nuxt 라우터를 찾을 수 없습니다(hydration 전 호출?)')
    return router.push(target)
  }, path)
  await page.waitForURL((url) => url.pathname === path.split('?')[0])
}
