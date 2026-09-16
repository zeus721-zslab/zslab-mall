import type { NuxtApp } from '#app'

/** 관리자 Vuetify 테마 primary — 사용자 브랜드 primary(main.css --primary #2563EB)와 동일. 주요 버튼·포커스·링크에만 쓴다. */
const ADMIN_PRIMARY_COLOR = '#2563EB'

/**
 * Argon형 톤(FE-22f) light 팔레트 — 옅은 회색 배경 위 흰 카드(무테두리·radius 16·넓게 퍼지는 연한 그림자).
 * 보조 색(success·info·warning·error)은 채도 있는 톤(그룹 아이콘 배지·그라데이션용). primary는 브랜드 #2563EB 유지.
 */
const ADMIN_LIGHT_THEME = {
  colors: {
    primary: ADMIN_PRIMARY_COLOR,
    background: '#F4F4F5', // zinc-100
    surface: '#FFFFFF',
    'surface-variant': '#F4F4F5',
    'on-background': '#18181B', // zinc-900
    'on-surface': '#18181B',
    'on-surface-variant': '#52525B', // zinc-600
    secondary: '#71717A', // zinc-500
    success: '#22C55E',
    info: '#0EA5E9',
    warning: '#F97316',
    error: '#EF4444',
  },
  variables: {
    'border-color': '#E4E4E7', // zinc-200
    'border-opacity': 1,
    'medium-emphasis-opacity': 0.62,
    'high-emphasis-opacity': 0.92,
    'activated-opacity': 0.06,
    'hover-opacity': 0.04,
  },
}

/**
 * 전역 컴포넌트 기본값 — 카드는 테두리 없이 그림자(radius 16px·그림자는 admin-vuetify.css .v-card), 버튼 radius 8px(rounded lg),
 * 입력은 outlined·comfortable 통일. 상단바는 밴드 위 투명(레이아웃에서 지정)이라 기본값 없음.
 */
const ADMIN_DEFAULTS = {
  VCard: { flat: true },
  VBtn: { flat: true, rounded: 'lg' },
  VTextField: { variant: 'outlined', density: 'comfortable', rounded: 'lg', color: 'primary' },
  VSelect: { variant: 'outlined', density: 'comfortable', rounded: 'lg', color: 'primary' },
  VList: { density: 'compact' },
  VNavigationDrawer: { elevation: 0 },
  VAlert: { variant: 'tonal', density: 'compact', rounded: 'lg' },
  VMenu: { offset: 6 },
}

/** vueApp에 Vuetify가 설치됐음을 표시하는 플래그 키(1회 설치 가드·HMR·재진입 대비). */
const INSTALLED_FLAG = '__adminVuetifyInstalled'

/**
 * Vuetify 인스턴스·전역 스타일을 관리자 진입 시점에 동적 로드해 vueApp에 1회 설치한다(FE-22c D-12 α·관리자 한정 로딩).
 * 정적 import를 두지 않아 사용자 entry 번들에 Vuetify JS/CSS가 포함되지 않는다(fresh load 사용자 페이지 0px·entry CSS 불변 실측).
 * 아이콘은 @mdi/js SVG 셋(폰트·CSS 전역 0).
 */
export async function ensureVuetify(nuxtApp: NuxtApp): Promise<void> {
  const app = nuxtApp.vueApp as unknown as Record<string, boolean | undefined>
  if (app[INSTALLED_FLAG]) return
  const [{ createVuetify }, { aliases, mdi }] = await Promise.all([
    import('vuetify'),
    import('vuetify/iconsets/mdi-svg'),
    import('#layers/admin/app/lib/vuetify-styles'),
  ])
  const vuetify = createVuetify({
    icons: { defaultSet: 'mdi', aliases, sets: { mdi } },
    theme: { defaultTheme: 'light', themes: { light: ADMIN_LIGHT_THEME } },
    defaults: ADMIN_DEFAULTS,
  })
  nuxtApp.vueApp.use(vuetify)
  app[INSTALLED_FLAG] = true
}
