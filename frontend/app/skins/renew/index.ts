import { defineAsyncComponent } from 'vue'
import type { SkinDefinition } from '../registry'

/**
 * renew 스킨(FE-68·FE-69). 바꾸는 뷰만 비동기 청크로 두고 나머지는 classic 뷰를 쓴다 — 색·모서리·서체는 main.css의
 * [data-skin="renew"] 토큰으로 바뀐다. needs = renew 뷰가 vm으로 받는 추가 데이터(선언한 스킨에서만 페이지가 조회).
 */
export const renewSkin: SkinDefinition = {
  parent: 'classic',
  views: {
    LayoutShell: defineAsyncComponent(() => import('./views/LayoutShell.vue')),
    HomeView: defineAsyncComponent(() => import('./views/HomeView.vue')),
    ProductsView: defineAsyncComponent(() => import('./views/ProductsView.vue')),
    CategoryView: defineAsyncComponent(() => import('./views/CategoryView.vue')),
    ProductDetailView: defineAsyncComponent(() => import('./views/ProductDetailView.vue')),
    CartView: defineAsyncComponent(() => import('./views/CartView.vue')),
    CheckoutView: defineAsyncComponent(() => import('./views/CheckoutView.vue')),
    PaymentMockView: defineAsyncComponent(() => import('./views/PaymentMockView.vue')),
    CheckoutCompleteView: defineAsyncComponent(() => import('./views/CheckoutCompleteView.vue')),
    MypageView: defineAsyncComponent(() => import('./views/MypageView.vue')),
    ProfileView: defineAsyncComponent(() => import('./views/ProfileView.vue')),
    PasswordView: defineAsyncComponent(() => import('./views/PasswordView.vue')),
    AddressesView: defineAsyncComponent(() => import('./views/AddressesView.vue')),
    WithdrawView: defineAsyncComponent(() => import('./views/WithdrawView.vue')),
    OrdersView: defineAsyncComponent(() => import('./views/OrdersView.vue')),
    OrderDetailView: defineAsyncComponent(() => import('./views/OrderDetailView.vue')),
    ClaimNewView: defineAsyncComponent(() => import('./views/ClaimNewView.vue')),
    ClaimDetailView: defineAsyncComponent(() => import('./views/ClaimDetailView.vue')),
    LoginView: defineAsyncComponent(() => import('./views/LoginView.vue')),
    SignupView: defineAsyncComponent(() => import('./views/SignupView.vue')),
    SearchView: defineAsyncComponent(() => import('./views/SearchView.vue')),
    HelpView: defineAsyncComponent(() => import('./views/HelpView.vue')),
    ErrorView: defineAsyncComponent(() => import('./views/ErrorView.vue')),
  },
  needs: ['layoutHeader', 'homeCuration', 'productList', 'productDetailMore', 'mypageHome', 'signupPasswordConfirm'],
}
