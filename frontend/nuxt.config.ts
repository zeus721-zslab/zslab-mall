import tailwindcss from '@tailwindcss/vite'

// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',
  devtools: { enabled: true },
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
    },
  },
  vite: {
    plugins: [tailwindcss()],
    server: {
      watch: { usePolling: true },
    },
  },
})
