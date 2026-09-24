import type { CartItemView } from '~/types/cart'

/** pages/cart.vue → CartView. 조작 함수는 모두 페이지의 runMutation을 거친다(busy 동안 뷰가 컨트롤을 잠근다). */
export interface CartPageVm {
  pending: boolean
  error: Error | undefined
  refresh: () => Promise<void>
  items: CartItemView[]
  allSelected: boolean
  busy: boolean
  opErrorMessage: string
  selectedTotal: number
  hasUnpurchasableSelected: boolean
  checkoutEnabled: boolean
  formatPrice: (value: number) => string
  toggleSelectedAll: (selected: boolean) => void
  toggleSelected: (item: CartItemView, selected: boolean) => void
  changeQuantity: (item: CartItemView, delta: number) => void
  removeItem: (item: CartItemView) => void
  handleCheckout: () => void
}
