// 관리자 레이어(FE-22). layers/* 자동 등록은 이 파일이 있어야 성립한다(@nuxt/kit loadNuxtConfig — config 없는 디렉토리는 skip).
// D-9: /admin/**는 CSR 전용(ssr:false·관리자 화면은 SEO 불필요·토큰 기반 조작 UI) + 검색엔진 색인 차단 헤더.
// FE-22c(D-12 α): Vuetify는 vite-plugin-vuetify autoImport(빌드타임·vuetify 컴포넌트를 쓰는 레이어 파일만 변환)로 연동한다.
// nuxt module(vuetify-nuxt-module)은 modules 배열 concat으로 앱 전체 등록·사용자 번들 오염이라 채택하지 않는다.
// vite.plugins 배열 선언만 vue-tsc를 통과한다(vite:extendConfig push는 TS2540·@nuxt/kit addVitePlugin은 TS2307).
import vuetify from 'vite-plugin-vuetify'

export default defineNuxtConfig({
  vite: { plugins: [vuetify({ autoImport: true })] },
  build: { transpile: ['vuetify'] },
  routeRules: {
    '/admin/**': { ssr: false, headers: { 'X-Robots-Tag': 'noindex, nofollow' } },
  },
  // FE-23 관리자 데모 로그인 계정. 비공개 키(public 금지)라 서버 라우트(layers/admin/server/routes/_admin-demo)만 읽는다.
  // 런타임 주입 키 = NUXT_ADMIN_DEMO_EMAIL / NUXT_ADMIN_DEMO_PASSWORD(compose가 ADMIN_BOOTSTRAP_*에서 매핑). 기본 ''=데모 비활성(404·버튼 미표시).
  runtimeConfig: {
    adminDemoEmail: '',
    adminDemoPassword: '',
  },
})
