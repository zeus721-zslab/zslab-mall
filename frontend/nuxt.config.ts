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
