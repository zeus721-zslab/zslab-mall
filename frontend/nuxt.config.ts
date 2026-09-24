import tailwindcss from '@tailwindcss/vite'

// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',
  devtools: { enabled: true },
  // nuxt.config.ts 등 node 컨텍스트에서 process.env 인식용. .nuxt/tsconfig.node.json(types:[])을 확장해 @types/node 활성화.
  typescript: {
    nodeTsConfig: {
      compilerOptions: {
        types: ['node'],
      },
    },
  },
  modules: ['@pinia/nuxt', 'shadcn-nuxt'],
  css: ['~/assets/css/main.css'],
  // Playwright Browser/SSR Smoke(FE-15 STEP3): :3000 직접 접근(게이트웨이 미경유) 시 client-side /api를 backend로 프록시한다.
  // target은 API_INTERNAL_BASE env(컨테이너 내부 alias)에서 읽고 하드코딩하지 않는다. 기본값은 언더스코어 없는 mall-backend alias
  // (Tomcat 엄격 Host 검증이 zslab_mall_backend 언더스코어 호스트를 400 거부하기 때문).
  // 실 dev는 gateway_nginx가 /api를 먼저 처리하므로 이 규칙까지 오지 않는다(SSR도 apiInternalBase 직결이라 /api 미사용) → 충돌 없음.
  routeRules: {
    '/api/**': { proxy: `${process.env.API_INTERNAL_BASE || 'http://mall-backend:8080'}/api/**` },
    // FE-63: 구매자 클레임 목록은 /orders 탭으로 통합됐다. 북마크·외부 링크 호환을 위해 경로만 흡수한다
    // (정확 일치라 /claims/new·/claims/{id} 상세는 그대로 살아 있다).
    '/claims': { redirect: '/orders?tab=cancel' },
    // FE-76: 폰트 1년 캐시. 파일명에 버전이 없으므로 폰트 교체 시 폴더명 버전(예: pretendard-1.3.9)을 올려 캐시를 깬다.
    '/fonts/**': { headers: { 'cache-control': 'public, max-age=31536000, immutable' } },
  },
  shadcn: {
    prefix: '',
    componentDir: '~/components/ui',
  },
  runtimeConfig: {
    // 런타임 주입 키=NUXT_API_INTERNAL_BASE(Nuxt runtimeConfig override 규약). default는 언더스코어 없는 alias 고정
    // — 빌드타임 process.env 참조를 제거해 prod 이미지에 언더스코어 트랩값(zslab_mall_backend·Tomcat 400)이 구워지던 문제 차단(FE-03).
    apiInternalBase: 'http://mall-backend:8080',
    // FE-43 구매자 데모 로그인 계정. 비공개 키(public 금지)라 서버 라우트(server/routes/_demo)만 읽는다.
    // 런타임 주입 키 = NUXT_BUYER_DEMO_EMAIL / NUXT_BUYER_DEMO_PASSWORD. 기본 ''=데모 비활성(404·버튼 미표시).
    buyerDemoEmail: '',
    buyerDemoPassword: '',
    public: {
      apiBase: process.env.NUXT_PUBLIC_API_BASE || '',
      // FE-67 구매자 기본 스킨. 런타임 env NUXT_PUBLIC_SKIN으로 교체(재빌드 불필요). 미등록 값은 renew로 대체(FE-75).
      skin: 'renew',
    },
  },
  vite: {
    plugins: [tailwindcss()],
    server: {
      watch: { usePolling: true },
    },
  },
})
