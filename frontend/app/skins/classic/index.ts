import type { SkinDefinition } from '../registry'
import type { SkinViews } from '../contracts/views'
import LayoutShell from './views/LayoutShell.vue'
import HomeView from './views/HomeView.vue'
import HelpView from './views/HelpView.vue'
import CategoryView from './views/CategoryView.vue'
import ProductsView from './views/ProductsView.vue'
import SearchView from './views/SearchView.vue'
import CheckoutCompleteView from './views/CheckoutCompleteView.vue'
import MypageView from './views/MypageView.vue'
import WithdrawView from './views/WithdrawView.vue'
import LoginView from './views/LoginView.vue'
import SignupView from './views/SignupView.vue'
import PasswordView from './views/PasswordView.vue'
import ProfileView from './views/ProfileView.vue'
import OrdersView from './views/OrdersView.vue'
import PaymentMockView from './views/PaymentMockView.vue'
import CartView from './views/CartView.vue'
import CheckoutView from './views/CheckoutView.vue'
import ClaimDetailView from './views/ClaimDetailView.vue'
import ClaimNewView from './views/ClaimNewView.vue'
import AddressesView from './views/AddressesView.vue'
import OrderDetailView from './views/OrderDetailView.vue'
import ProductDetailView from './views/ProductDetailView.vue'

/** 기준 스킨. 모든 뷰를 가지며 다른 스킨의 최종 대체 대상이다. */
export const classicViews: SkinViews = {
  LayoutShell,
  HomeView,
  HelpView,
  CategoryView,
  ProductsView,
  SearchView,
  CheckoutCompleteView,
  MypageView,
  WithdrawView,
  LoginView,
  SignupView,
  PasswordView,
  ProfileView,
  OrdersView,
  PaymentMockView,
  CartView,
  CheckoutView,
  ClaimDetailView,
  ClaimNewView,
  AddressesView,
  OrderDetailView,
  ProductDetailView,
}

export const classicSkin: SkinDefinition = { views: classicViews }
