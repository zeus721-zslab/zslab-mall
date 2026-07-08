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
      // FE-07 디자인 파운데이션 토큰. 역할 분리: primary=파랑(CTA·링크·브랜드)·price=빨강(가격 전용)·soldout=회색.
      // 중립색(ink·sub·seller·line)은 기존 gray-900/500/400/200과 동일 hex라 시맨틱 별칭 성격(recon-report-69).
      colors: {
        primary: {
          DEFAULT: '#2563EB',
          hover: '#1D4ED8',
        },
        price: '#E11D48',
        surface: {
          page: '#FAFAFA',
          card: '#FFFFFF',
          section: '#F5F5F5',
        },
        ink: '#111827',
        sub: '#6B7280',
        seller: '#9CA3AF',
        line: '#E5E7EB',
        // success/warning은 시안에 hex 미지정(역할만 green/amber). 소비처 부재로 관용값(Tailwind green-600·amber-500) 선점.
        success: '#16A34A',
        warning: '#F59E0B',
        soldout: '#6B7280',
        badge: {
          'new-bg': '#DBEAFE',
          'new-ink': '#1D4ED8',
          'sale-bg': '#FEE2E2',
          'sale-ink': '#E11D48',
          'soldout-bg': '#F3F4F6',
        },
      },
      // 기존 기본 radius 유지·시맨틱 키만 추가(card 16·control 14·badge 6).
      borderRadius: {
        card: '16px',
        control: '14px',
        badge: '6px',
      },
      boxShadow: {
        'card-hover': '0 4px 12px rgba(0, 0, 0, 0.08)',
      },
      // 기존 기본 duration 유지·의미 별칭 추가(fast 150·normal 180·slow 250ms).
      transitionDuration: {
        fast: '150ms',
        normal: '180ms',
        slow: '250ms',
      },
    },
  },
} satisfies Config
