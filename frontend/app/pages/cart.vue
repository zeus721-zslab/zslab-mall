<script setup lang="ts">
import type { CartItemView } from '~/types/cart'

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
const checkoutEnabled = computed<boolean>(() =>
  items.value.some((item) => item.selected && item.purchasable),
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
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[1240px] px-4 md:px-6">
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">장바구니</h1>

      <!-- 로딩 -->
      <div v-if="pending" class="space-y-3">
        <div v-for="n in 3" :key="n" class="h-24 animate-pulse rounded-card bg-gray-100"></div>
      </div>

      <!-- 에러 -->
      <CommonErrorState v-else-if="error" message="장바구니를 불러오지 못했습니다" @retry="refresh" />

      <!-- 빈 장바구니 -->
      <CommonEmptyState v-else-if="items.length === 0" message="장바구니가 비어 있습니다" />

      <!-- 목록 -->
      <div v-else class="grid grid-cols-1 gap-8 lg:grid-cols-[1fr_320px]">
        <div class="space-y-4">
          <!-- 전체 선택 -->
          <div class="flex items-center justify-between border-b border-line pb-3">
            <label class="flex items-center gap-2 text-sm font-medium text-ink">
              <input
                type="checkbox"
                class="h-4 w-4 rounded border-line accent-primary"
                :checked="allSelected"
                :disabled="busy"
                @change="toggleSelectedAll(($event.target as HTMLInputElement).checked)"
              />
              전체 선택
            </label>
          </div>

          <!-- 조작 오류 -->
          <p v-if="opErrorMessage" role="alert" class="text-sm text-soldout">{{ opErrorMessage }}</p>

          <!-- 품목행 -->
          <div
            v-for="item in items"
            :key="item.variantPublicId"
            class="flex gap-4 rounded-card border border-line p-4"
            :class="{ 'opacity-60': !item.purchasable }"
          >
            <!-- 선택 체크박스: dangling(구매불가)은 선택 비활성 -->
            <div class="flex items-start pt-1">
              <input
                type="checkbox"
                class="h-4 w-4 rounded border-line accent-primary"
                :checked="item.selected"
                :disabled="busy || !item.purchasable"
                :aria-label="`${item.productName ?? '상품'} 선택`"
                @change="toggleSelected(item, ($event.target as HTMLInputElement).checked)"
              />
            </div>

            <!-- 썸네일 -->
            <div class="relative h-20 w-20 shrink-0 overflow-hidden rounded-control border border-line bg-gray-100">
              <img
                v-if="item.thumbnailUrl"
                :src="item.thumbnailUrl"
                :alt="item.productName ?? '상품 이미지'"
                class="h-full w-full object-cover"
              />
              <div v-else class="flex h-full w-full items-center justify-center text-gray-300">
                <svg class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M2.25 15.75l5.159-5.159a2.25 2.25 0 013.182 0l5.159 5.159m-1.5-1.5l1.409-1.409a2.25 2.25 0 013.182 0l2.909 2.909M6 12h.008v.008H6V12zm18 0a1.5 1.5 0 01-1.5 1.5H3.75A1.5 1.5 0 012.25 12V6A1.5 1.5 0 013.75 4.5h16.5A1.5 1.5 0 0122.5 6v6z" />
                </svg>
              </div>
            </div>

            <!-- 정보 + 컨트롤 -->
            <div class="flex min-w-0 flex-1 flex-col gap-2">
              <div class="min-w-0">
                <p class="truncate text-sm font-medium text-ink">
                  {{ item.productName ?? '상품 정보 없음' }}
                </p>
                <p v-if="item.sellerName" class="truncate text-xs text-seller">{{ item.sellerName }}</p>
                <!-- 구매 불가(dangling·품절·판매중지): 삭제만 허용 -->
                <p v-if="!item.purchasable" class="mt-1 text-xs text-soldout">구매 불가 (품절 또는 판매 중지)</p>
              </div>

              <div class="mt-auto flex items-center justify-between gap-3">
                <!-- 수량: 구매불가면 비활성 -->
                <div class="inline-flex items-center rounded-control border border-line">
                  <button
                    type="button"
                    aria-label="수량 감소"
                    class="px-2.5 py-1.5 text-ink transition duration-normal hover:bg-gray-50 disabled:opacity-40"
                    :disabled="busy || !item.purchasable || item.quantity <= 1"
                    @click="changeQuantity(item, -1)"
                  >
                    −
                  </button>
                  <span class="min-w-8 text-center text-sm font-medium text-ink">{{ item.quantity }}</span>
                  <button
                    type="button"
                    aria-label="수량 증가"
                    class="px-2.5 py-1.5 text-ink transition duration-normal hover:bg-gray-50 disabled:opacity-40"
                    :disabled="busy || !item.purchasable"
                    @click="changeQuantity(item, 1)"
                  >
                    +
                  </button>
                </div>

                <div class="flex items-center gap-3">
                  <span class="text-sm font-bold text-price">{{ formatPrice(item.displayPrice * item.quantity) }}</span>
                  <button
                    type="button"
                    class="text-sm text-sub transition duration-normal hover:text-soldout disabled:opacity-40"
                    :disabled="busy"
                    @click="removeItem(item)"
                  >
                    삭제
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 합계 + 결제(seam) -->
        <aside class="h-fit rounded-card border border-line p-5 lg:sticky lg:top-24">
          <div class="flex items-center justify-between">
            <span class="text-sm text-sub">선택 상품 합계</span>
            <span class="text-xl font-bold text-price">{{ formatPrice(selectedTotal) }}</span>
          </div>
          <!-- 결제: 선택 품목이 있으면 체크아웃 페이지로 이동 -->
          <Button size="lg" class="mt-4 w-full" :disabled="!checkoutEnabled" @click="handleCheckout">
            결제하기
          </Button>
        </aside>
      </div>
    </div>
  </div>
</template>
