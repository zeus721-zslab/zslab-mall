import type { NuxtApp } from '#app'

/** 셀러 Vuetify 테마 primary — 관리자(브랜드 파랑 #2563EB)와 구분되는 teal. 주요 버튼·포커스·링크·활성 메뉴 배지에만 쓴다. */
const SELLER_PRIMARY_COLOR = '#0D9488'

/**
 * 셀러 light 팔레트(D-1 관리자 스타일 확장·자체 테마). 옅은 회색 배경 위 흰 카드(무테두리·radius 16·연한 그림자)는 관리자와 같은 골격이고
 * primary만 teal로 바꿔 "지금 어느 패널에 있는가"를 색으로 구분한다. 보조 색(success·info·warning·error)은 채도 있는 톤.
 */
const SELLER_LIGHT_THEME = {
  colors: {
    primary: SELLER_PRIMARY_COLOR,
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

/** 입력 계열 공통 기본값(outlined·comfortable·radius 8·primary). */
const INPUT_DEFAULTS = { variant: 'outlined', density: 'comfortable', rounded: 'lg', color: 'primary' }

/** 전역 컴포넌트 기본값 — 카드는 테두리 없이 그림자(radius 16px·그림자는 seller-vuetify.css .v-card), 버튼 radius 8px, 입력은 outlined·comfortable 통일. */
const SELLER_DEFAULTS = {
  VCard: { flat: true },
  VBtn: { flat: true, rounded: 'lg' },
  VTextField: INPUT_DEFAULTS,
  VSelect: INPUT_DEFAULTS,
  VAutocomplete: INPUT_DEFAULTS,
  VCombobox: INPUT_DEFAULTS,
  VTextarea: INPUT_DEFAULTS,
  VFileInput: INPUT_DEFAULTS,
  VList: { density: 'compact' },
  VNavigationDrawer: { elevation: 0 },
  VAlert: { variant: 'tonal', density: 'compact', rounded: 'lg' },
  VMenu: { offset: 6 },
}

/**
 * vueApp에 셀러 Vuetify가 설치됐음을 표시하는 플래그 키(1회 설치 가드·HMR·재진입 대비).
 * 관리자 플래그(__adminVuetifyInstalled)와 다른 키 — 같은 문서에 두 인스턴스가 공존하지 않도록 이탈 가드(seller-leave-guard)가 문서 경계를 만든다.
 */
const INSTALLED_FLAG = '__sellerVuetifyInstalled'

/**
 * 셀러 자체 Vuetify 인스턴스(자체 테마·defaults)를 셀러 진입 시점에 동적 로드해 vueApp에 1회 설치한다(관리자 FE-22c D-12 α 동형).
 * 정적 import를 두지 않아 사용자 entry 번들에 Vuetify JS/CSS가 포함되지 않는다. 아이콘은 @mdi/js SVG 셋(폰트·CSS 전역 0).
 */
export async function ensureSellerVuetify(nuxtApp: NuxtApp): Promise<void> {
  const app = nuxtApp.vueApp as unknown as Record<string, boolean | undefined>
  if (app[INSTALLED_FLAG]) return
  const [{ createVuetify }, { aliases, mdi }] = await Promise.all([
    import('vuetify'),
    import('vuetify/iconsets/mdi-svg'),
    import('#layers/seller/app/lib/vuetify-styles'),
  ])
  const vuetify = createVuetify({
    icons: { defaultSet: 'mdi', aliases, sets: { mdi } },
    theme: { defaultTheme: 'light', themes: { light: SELLER_LIGHT_THEME } },
    defaults: SELLER_DEFAULTS,
  })
  nuxtApp.vueApp.use(vuetify)
  app[INSTALLED_FLAG] = true
}
