import type { Component } from 'vue'

/** 스킨이 제공하는 뷰 이름. 페이지·레이아웃이 useSkinView로 조회한다. */
export type SkinViewName =
  | 'LayoutShell'
  | 'HomeView'
  | 'HelpView'
  | 'CategoryView'
  | 'ProductsView'
  | 'SearchView'
  | 'CheckoutCompleteView'
  | 'MypageView'
  | 'WithdrawView'
  | 'LoginView'
  | 'SignupView'
  | 'PasswordView'
  | 'ProfileView'
  | 'OrdersView'
  | 'PaymentMockView'
  | 'CartView'
  | 'CheckoutView'
  | 'ClaimDetailView'
  | 'ClaimNewView'
  | 'AddressesView'
  | 'OrderDetailView'
  | 'ProductDetailView'
  | 'ErrorView'

/** 뷰 이름 → 컴포넌트. 기준 스킨(classic)은 전부 채워야 한다(누락은 typecheck에서 차단). */
export type SkinViews = Record<SkinViewName, Component>
