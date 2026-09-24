import type { SkinDefinition } from '../registry'
import type { SkinViews } from '../contracts/views'
import LayoutShell from './views/LayoutShell.vue'
import HomeView from './views/HomeView.vue'
import ProductsView from './views/ProductsView.vue'
import CategoryView from './views/CategoryView.vue'
import ProductDetailView from './views/ProductDetailView.vue'
import CartView from './views/CartView.vue'
import CheckoutView from './views/CheckoutView.vue'
import PaymentMockView from './views/PaymentMockView.vue'
import CheckoutCompleteView from './views/CheckoutCompleteView.vue'
import MypageView from './views/MypageView.vue'
import ProfileView from './views/ProfileView.vue'
import PasswordView from './views/PasswordView.vue'
import AddressesView from './views/AddressesView.vue'
import WithdrawView from './views/WithdrawView.vue'
import OrdersView from './views/OrdersView.vue'
import OrderDetailView from './views/OrderDetailView.vue'
import ClaimNewView from './views/ClaimNewView.vue'
import ClaimDetailView from './views/ClaimDetailView.vue'
import LoginView from './views/LoginView.vue'
import SignupView from './views/SignupView.vue'
import SearchView from './views/SearchView.vue'
import HelpView from './views/HelpView.vue'
import ErrorView from './views/ErrorView.vue'

/**
 * 기준 스킨(FE-75). 모든 뷰를 정적 import로 가지며 다른 스킨의 최종 대체 대상이다 — 항상 쓰이므로 청크를 나눌 이득이 없다.
 * 색·모서리·서체는 main.css의 [data-skin] 토큰으로 바뀐다. needs = renew 뷰가 vm으로 받는 추가 데이터(선언한 스킨에서만 페이지가 조회).
 */
export const renewViews: SkinViews = {
  LayoutShell,
  HomeView,
  ProductsView,
  CategoryView,
  ProductDetailView,
  CartView,
  CheckoutView,
  PaymentMockView,
  CheckoutCompleteView,
  MypageView,
  ProfileView,
  PasswordView,
  AddressesView,
  WithdrawView,
  OrdersView,
  OrderDetailView,
  ClaimNewView,
  ClaimDetailView,
  LoginView,
  SignupView,
  SearchView,
  HelpView,
  ErrorView,
}

export const renewSkin: SkinDefinition = {
  views: renewViews,
  needs: ['layoutHeader', 'homeCuration', 'productList', 'productDetailMore', 'mypageHome', 'signupPasswordConfirm'],
}
