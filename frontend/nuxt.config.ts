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
  },
  shadcn: {
    prefix: '',
    componentDir: '~/components/ui',
  },
  runtimeConfig: {
    apiInternalBase: process.env.API_INTERNAL_BASE || 'http://zslab_mall_backend:8080',
    public: {
      apiBase: process.env.NUXT_PUBLIC_API_BASE || '',
      // 데모 로그인 버튼용 공개 자격증명(저권한 BUYER·포트폴리오 방문자 편의). 클라가 읽어야 해 public에 둔다.
      demoEmail: process.env.NUXT_PUBLIC_DEMO_EMAIL || '',
      demoPassword: process.env.NUXT_PUBLIC_DEMO_PASSWORD || '',
    },
  },
  vite: {
    plugins: [tailwindcss()],
    server: {
      watch: { usePolling: true },
    },
  },
})
