import type { Config } from 'tailwindcss'

// 나눔고딕 self-host 전역 적용(FE-04). Tailwind Preflight가 html 기본 font-family를 theme.fontFamily.sans로 설정하므로
// sans 스택 맨 앞에 'NanumGothic'을 두면 전 페이지가 나눔고딕으로 렌더된다(@font-face 선언은 app/assets/css/main.css).
// content는 @nuxtjs/tailwindcss v6가 Nuxt 디렉토리 기준으로 자동 구성하지만, Nuxt4 app/ 표준 경로를 명시 병기한다.
export default {
  content: [
    './app/components/**/*.{vue,js,ts}',
    './app/layouts/**/*.vue',
    './app/pages/**/*.vue',
    './app/plugins/**/*.{js,ts}',
    './app/composables/**/*.{js,ts}',
    './app/utils/**/*.{js,ts}',
    './app/app.vue',
    './app/error.vue',
    './app/app.config.{js,ts}',
  ],
  theme: {
    extend: {
      fontFamily: {
        sans: [
          'NanumGothic',
          'ui-sans-serif',
          'system-ui',
          '-apple-system',
          'Segoe UI',
          'Roboto',
          'Apple SD Gothic Neo',
          'Malgun Gothic',
          'sans-serif',
        ],
      },
    },
  },
} satisfies Config
