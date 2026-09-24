<script setup lang="ts">
import type { CartItemView } from '~/types/cart'
import type { CartPageVm } from '~/skins/contracts/cart'

// BUYER 전용 페이지 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다(진입 보호 첫 소비처).
definePageMeta({ middleware: 'buyer' })

const cart = useCartStore()

// 진입 시 최신 상태 로드. SSR(하드 진입)·클라 네비게이션(플러그인 callOnce 이후) 양쪽에서 재조회한다.
// 렌더는 store의 cart.items(반응)를 읽고, data는 SSR 직렬화·상태 판정용으로만 쓴다.
const { pending, error, refresh } = useAsyncData('cart-page', async () => {
  await cart.load()
  // setup store 외부 접근은 ref가 자동 언랩된다 — cart.items는 이미 배열(.value 아님). 반환값은 SSR 직렬화·상태 판정용.
  return cart.items.length
})

const items = computed<CartItemView[]>(() => cart.items)

// 전체 선택 체크박스 상태(품목이 있고 전부 selected일 때만 체크).
const allSelected = computed<boolean>(
  () => items.value.length > 0 && items.value.every((item) => item.selected),
)

// 합계: 선택 ∧ 구매가능 품목만(dangling·미선택 제외). BE는 조작만 Void라 금액은 FE가 계산한다.
const selectedTotal = computed<number>(() =>
  items.value
    .filter((item) => item.selected && item.purchasable)
    .reduce((sum, item) => sum + item.displayPrice * item.quantity, 0),
)
// 구매 불가 선택 품목 존재 여부(FE-18). 서버는 selected 전체를 주문에 넣으므로 1개라도 있으면 결제를 막고 삭제를 유도한다
// (구매 불가 품목은 선택 해제 불가·삭제만 가능). 판정식은 checkout-summary.hasUnpurchasableSelected와 같으나 1줄 술어라 인라인으로 둔다.
const hasUnpurchasableSelected = computed<boolean>(() =>
  items.value.some((item) => item.selected && !item.purchasable),
)
const checkoutEnabled = computed<boolean>(() =>
  items.value.some((item) => item.selected && item.purchasable) && !hasUnpurchasableSelected.value,
)

function formatPrice(value: number): string {
  return `${value.toLocaleString('ko-KR')}원`
}

// 조작 진행 상태·오류. 조작 중엔 컨트롤을 잠가 중복 요청을 막는다(조작마다 전체 재load).
const busy = ref<boolean>(false)
const opErrorMessage = ref<string>('')

/** 조작 공통 실행. 401(세션 만료)이면 로그인으로 유도, 그 외 실패는 안내만 한다. */
async function runMutation(action: () => Promise<void>): Promise<void> {
  if (busy.value) return
  busy.value = true
  opErrorMessage.value = ''
  try {
    await action()
  } catch (mutationError) {
    const statusCode = (mutationError as { statusCode?: number }).statusCode
    if (statusCode === 401) {
      await navigateTo(`/login?redirect=${encodeURIComponent('/cart')}`)
      return
    }
    opErrorMessage.value = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  } finally {
    busy.value = false
  }
}

function changeQuantity(item: CartItemView, delta: number): void {
  const next = item.quantity + delta
  if (next < 1) return
  runMutation(() => cart.updateQuantity(item.variantPublicId, next))
}
function toggleSelected(item: CartItemView, selected: boolean): void {
  runMutation(() => cart.setSelected(item.variantPublicId, selected))
}
function toggleSelectedAll(selected: boolean): void {
  runMutation(() => cart.setSelectedAll(selected))
}
function removeItem(item: CartItemView): void {
  runMutation(() => cart.remove([item.variantPublicId]))
}

/** 결제하기 → 체크아웃 페이지로 이동(FE-11). 선택 품목은 서버가 selected 조회하므로 페이지 전환만 한다. */
function handleCheckout(): void {
  navigateTo('/checkout')
}

useSeoMeta({ title: '장바구니 · zslab-mall', description: 'zslab-mall 장바구니' })

const vm: CartPageVm = reactive({
  pending,
  error,
  refresh,
  items,
  allSelected,
  busy,
  opErrorMessage,
  selectedTotal,
  hasUnpurchasableSelected,
  checkoutEnabled,
  formatPrice,
  toggleSelectedAll,
  toggleSelected,
  changeQuantity,
  removeItem,
  handleCheckout,
})
</script>

<template>
  <component :is="useSkinView('CartView')" :vm="vm" />
</template>
