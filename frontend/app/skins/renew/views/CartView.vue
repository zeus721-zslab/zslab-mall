<script setup lang="ts">
import type { CartPageVm } from '~/skins/contracts/cart'
import FadeAmount from '../components/FadeAmount.vue'
import MobileActionBar from '../components/MobileActionBar.vue'
import RenewCheckbox from '../components/RenewCheckbox.vue'

// renew 장바구니(FE-71). ≥1024 = 왼쪽 품목 목록 카드 · 오른쪽 주문 금액 카드(sticky). <768 = 금액 카드가 목록 아래로, 하단 고정 바(금액 + 주문하기).
// 선택·수량·삭제·주문은 모두 페이지 함수(runMutation)를 쓰고, busy 동안 컨트롤을 잠그는 규칙은 classic과 같다.
defineProps<{ vm: CartPageVm }>()

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
const CARD = 'rounded-[28px] bg-white'
const STEP_BUTTON =
  'flex h-9 w-9 items-center justify-center rounded-full text-ink transition duration-200 hover:bg-surface-muted disabled:cursor-default disabled:opacity-40 disabled:hover:bg-transparent'
// 요약 카드의 주문하기: 화면에 없을 때만 모바일 고정 바가 나타난다(FE-71 도킹).
const checkoutButton = ref<HTMLButtonElement | null>(null)

const PRIMARY_BUTTON =
  'flex w-full items-center justify-center rounded-full bg-primary font-bold text-primary-foreground transition duration-200 hover:bg-primary-hover focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:cursor-default disabled:opacity-40 disabled:hover:bg-primary'
</script>

<template>
  <div class="pb-8 pt-6 md:pt-10">
    <div :class="CONTAINER">
      <h1 class="mb-8 text-3xl font-bold tracking-tight text-ink">장바구니</h1>

      <!-- 로딩 -->
      <div v-if="vm.pending" class="lg:grid lg:grid-cols-[1fr_400px] lg:items-start lg:gap-8" aria-hidden="true">
        <div :class="[CARD, 'space-y-6 p-5 md:p-8']">
          <div v-for="index in 3" :key="index" class="flex gap-4">
            <div class="h-24 w-24 shrink-0 rounded-[18px] bg-(--image-placeholder)"></div>
            <div class="flex-1 space-y-3 pt-1">
              <div class="h-3 w-1/4 rounded-full bg-surface-muted"></div>
              <div class="h-4 w-2/3 rounded-full bg-surface-muted"></div>
              <div class="h-9 w-32 rounded-full bg-surface-muted"></div>
            </div>
          </div>
        </div>
        <div :class="[CARD, 'mt-6 h-64 lg:mt-0']"></div>
      </div>

      <!-- 에러 -->
      <CommonErrorState v-else-if="vm.error" message="장바구니를 불러오지 못했습니다" @retry="vm.refresh" />

      <!-- 빈 장바구니 -->
      <div v-else-if="vm.items.length === 0" :class="[CARD, 'flex flex-col items-center px-6 py-16 text-center']">
        <span class="flex h-20 w-20 items-center justify-center rounded-full bg-surface-muted text-primary" aria-hidden="true">
          <svg class="h-10 w-10" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.6">
            <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 10.5V6a3.75 3.75 0 10-7.5 0v4.5m11.356-1.993l1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 01-1.12-1.243l1.264-12A1.125 1.125 0 015.513 7.5h12.974c.576 0 1.059.435 1.119 1.007z" />
          </svg>
        </span>
        <p class="mt-6 text-lg font-bold text-ink">장바구니가 비어 있어요</p>
        <NuxtLink
          to="/products"
          class="mt-6 inline-flex min-h-11 items-center rounded-full bg-primary px-8 text-sm font-bold text-primary-foreground transition duration-200 hover:bg-primary-hover focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2"
        >
          쇼핑 계속하기
        </NuxtLink>
      </div>

      <!-- 목록 + 주문 금액 -->
      <div v-else class="lg:grid lg:grid-cols-[1fr_400px] lg:items-start lg:gap-8">
        <section :class="[CARD, 'p-5 md:p-8']" aria-label="장바구니 상품">
          <!-- 전체 선택 -->
          <label class="flex w-fit cursor-pointer items-center gap-3 text-sm font-bold text-ink">
            <RenewCheckbox :checked="vm.allSelected" :disabled="vm.busy" @change="vm.toggleSelectedAll" />
            전체 선택
          </label>

          <ul class="mt-4 divide-y divide-line border-t border-line">
            <li v-for="item in vm.items" :key="item.variantPublicId" class="flex gap-4 py-5">
              <!-- 선택: 구매 불가는 선택 비활성(삭제만) -->
              <div class="pt-1">
                <RenewCheckbox
                  :checked="item.selected"
                  :disabled="vm.busy || !item.purchasable"
                  :aria-label="`${item.productName ?? '상품'} 선택`"
                  @change="(checked) => vm.toggleSelected(item, checked)"
                />
              </div>

              <div class="h-24 w-24 shrink-0 overflow-hidden rounded-[18px] bg-(--image-placeholder)">
                <img v-if="item.thumbnailUrl" :src="item.thumbnailUrl" :alt="item.productName ?? '상품 이미지'" class="h-full w-full object-cover" />
              </div>

              <div class="flex min-w-0 flex-1 flex-col gap-3">
                <div class="flex items-start justify-between gap-3">
                  <div class="min-w-0">
                    <p v-if="item.sellerName" class="truncate text-xs text-sub">{{ item.sellerName }}</p>
                    <p class="truncate text-base font-bold text-ink">{{ item.productName ?? '상품 정보 없음' }}</p>
                    <p v-if="item.optionLabel" data-testid="item-option-label" class="truncate text-sm text-sub">{{ item.optionLabel }}</p>
                    <span
                      v-if="!item.purchasable"
                      class="mt-2 inline-flex rounded-full bg-(--pastel-pink-bg) px-3 py-1 text-xs font-bold text-(--pastel-pink-ink)"
                    >
                      구매 불가 (품절 또는 판매 중지)
                    </span>
                  </div>
                  <button
                    type="button"
                    class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-sub transition duration-200 hover:bg-surface-muted hover:text-ink disabled:cursor-default disabled:opacity-40"
                    :aria-label="`${item.productName ?? '상품'} 삭제`"
                    :disabled="vm.busy"
                    @click="vm.removeItem(item)"
                  >
                    <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
                      <path d="M6 6l12 12M18 6L6 18" />
                    </svg>
                  </button>
                </div>

                <!-- 좁은 폭에서는 금액이 다음 줄 오른쪽으로 내려간다(금액 자체는 줄바꿈 없음). -->
                <div class="mt-auto flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
                  <!-- 수량: 구매 불가면 비활성 -->
                  <div class="inline-flex items-center rounded-full border border-line bg-white p-0.5">
                    <button
                      type="button"
                      aria-label="수량 감소"
                      :class="STEP_BUTTON"
                      :disabled="vm.busy || !item.purchasable || item.quantity <= 1"
                      @click="vm.changeQuantity(item, -1)"
                    >
                      −
                    </button>
                    <span class="min-w-8 text-center font-mono text-sm font-semibold text-ink">{{ item.quantity }}</span>
                    <button
                      type="button"
                      aria-label="수량 증가"
                      :class="STEP_BUTTON"
                      :disabled="vm.busy || !item.purchasable"
                      @click="vm.changeQuantity(item, 1)"
                    >
                      +
                    </button>
                  </div>
                  <p class="ml-auto whitespace-nowrap text-ink">
                    <span class="font-mono text-base font-semibold">{{ (item.displayPrice * item.quantity).toLocaleString('ko-KR') }}</span><span class="ml-0.5 text-sm">원</span>
                  </p>
                </div>
              </div>
            </li>
          </ul>
        </section>

        <!-- 주문 금액: ≥1024 헤더 아래 24px에 고정 -->
        <aside :class="[CARD, 'mt-6 p-6 lg:sticky lg:top-[calc(var(--header-height)_+_24px)] lg:mt-0']" aria-label="주문 금액">
          <h2 class="text-lg font-bold text-ink">주문 금액</h2>
          <dl class="mt-5 space-y-3 text-sm">
            <div class="flex items-baseline justify-between">
              <dt class="text-sub">선택 상품 금액</dt>
              <dd class="text-base"><FadeAmount :value="vm.selectedTotal" /></dd>
            </div>
            <div class="flex items-baseline justify-between">
              <dt class="text-sub">배송비</dt>
              <dd class="font-bold text-ink">무료</dd>
            </div>
            <div class="flex items-baseline justify-between border-t border-line pt-4">
              <dt class="font-bold text-ink">결제 예정 금액</dt>
              <dd class="text-2xl"><FadeAmount :value="vm.selectedTotal" /></dd>
            </div>
          </dl>
          <!-- 구매 불가 선택 품목 포함(FE-18): 주문 차단·삭제 유도 -->
          <p v-if="vm.hasUnpurchasableSelected" role="alert" class="mt-4 text-sm font-bold text-ink">
            구매할 수 없는 상품이 포함되어 있습니다. 삭제 후 결제해 주세요.
          </p>
          <p v-if="vm.opErrorMessage" role="alert" class="mt-4 text-sm font-bold text-destructive">{{ vm.opErrorMessage }}</p>
          <button ref="checkoutButton" type="button" :class="[PRIMARY_BUTTON, 'mt-6 h-14 text-base']" :disabled="!vm.checkoutEnabled" @click="vm.handleCheckout">
            주문하기
          </button>
        </aside>

        <!-- <768 하단 고정 바: 요약 카드 주문하기가 화면에 없을 때만·같은 함수·같은 비활성 규칙 -->
        <MobileActionBar
          :anchor="checkoutButton"
          label="결제 예정 금액"
          :amount="vm.selectedTotal"
          pending-text=""
          button-label="주문하기"
          :disabled="!vm.checkoutEnabled"
          @action="vm.handleCheckout"
        />
      </div>
    </div>
  </div>
</template>
