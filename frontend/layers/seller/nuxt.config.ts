// 셀러 레이어(Track 90-A). layers/* 자동 등록은 이 파일이 있어야 성립한다(@nuxt/kit loadNuxtConfig — config 없는 디렉토리는 skip).
// /seller/**는 관리자(D-9)와 동형으로 CSR 전용(ssr:false·토큰 기반 조작 UI·SEO 불필요) + 검색엔진 색인 차단 헤더.
// D-8 α: vite-plugin-vuetify는 admin 레이어가 이미 앱 전역으로 등록하지만(플러그인 체인은 앱당 1개), admin 레이어에 대한 암묵 의존을
// 없애기 위해 여기에도 명시 등록한다. transform은 멱등(첫 플러그인이 _resolveComponent 선언을 지운 뒤라 두 번째는 매치 0)이라 중복 무해.
// layers/admin 파일은 참조하지 않는다(격리 원칙·recon-report-track90a §10).
import vuetify from 'vite-plugin-vuetify'

export default defineNuxtConfig({
  vite: { plugins: [vuetify({ autoImport: true })] },
  build: { transpile: ['vuetify'] },
  routeRules: {
    '/seller/**': { ssr: false, headers: { 'X-Robots-Tag': 'noindex, nofollow' } },
  },
  // 셀러 데모 로그인 계정. 비공개 키(public 금지)라 서버 라우트(layers/seller/server/routes/_seller-demo)만 읽는다.
  // 런타임 주입 키 = NUXT_SELLER_DEMO_EMAIL / NUXT_SELLER_DEMO_PASSWORD. 기본 ''=데모 비활성(404·버튼 미표시).
  runtimeConfig: {
    sellerDemoEmail: '',
    sellerDemoPassword: '',
  },
})
