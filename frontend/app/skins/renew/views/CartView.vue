<script setup lang="ts">
import type { CartPageVm } from '~/skins/contracts/cart'
import FadeAmount from '../components/FadeAmount.vue'
import MobileActionBar from '../components/MobileActionBar.vue'
import RenewBadge from '../components/RenewBadge.vue'
import RenewCheckbox from '../components/RenewCheckbox.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 장바구니(FE-71 · FE-78). 품목 = 목록 행 카드(≥768 왼쪽 정보 / 오른쪽 수량·금액·삭제). ≥1024 = 오른쪽 주문 금액 카드(sticky).
// <1024 = 금액 카드가 목록 아래로, 하단 고정 바(금액 + 주문하기).
// 선택·수량·삭제·주문은 모두 페이지 함수(runMutation)를 쓰고, busy 동안 컨트롤을 잠그는 규칙은 classic과 같다.
defineProps<{ vm: CartPageVm }>()

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
const CARD = 'rounded-card bg-white shadow-e1'
// 수량 스테퍼 버튼: <768 터치 영역 44 · 이상 36.
const STEP_BUTTON =
  'flex h-11 w-11 items-center justify-center rounded-full text-ink transition duration-fast ease-soft hover:bg-surface-muted disabled:cursor-default disabled:opacity-40 disabled:hover:bg-transparent md:h-9 md:w-9'
// 체크박스(24) 둘레를 label로 넓혀 터치 영역 44를 만든다(음수 여백으로 배치는 그대로).
const CHECKBOX_HIT = '-m-2.5 flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center'
// 요약 카드의 주문하기: 화면에 없을 때만 하단 고정 바가 나타난다(FE-71 도킹).
const checkoutButton = ref<HTMLButtonElement | null>(null)
</script>

<template>
  <div class="pb-8 pt-6 md:pt-10">
    <div :class="CONTAINER">
      <h1 class="mb-8 text-h1 text-ink">장바구니</h1>

      <!-- 로딩 -->
      <div v-if="vm.pending" class="lg:grid lg:grid-cols-[1fr_400px] lg:items-start lg:gap-8" aria-hidden="true">
        <div class="space-y-4">
          <div v-for="index in 3" :key="index" :class="[CARD, 'flex gap-4 p-5 md:p-6']">
            <div class="h-20 w-20 shrink-0 rounded-[18px] bg-(--image-placeholder) md:h-24 md:w-24"></div>
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
        <p class="mt-6 text-h3 text-ink">장바구니가 비어 있어요</p>
        <NuxtLink to="/products" class="btn btn-primary btn-md mt-6">쇼핑 계속하기</NuxtLink>
      </div>

      <!-- 목록 + 주문 금액 -->
      <div v-else class="lg:grid lg:grid-cols-[1fr_400px] lg:items-start lg:gap-8">
        <section aria-label="장바구니 상품">
          <!-- 전체 선택 -->
          <label class="mb-4 flex min-h-11 w-fit cursor-pointer items-center gap-3 text-body font-semibold text-ink">
            <RenewCheckbox :checked="vm.allSelected" :disabled="vm.busy" @change="vm.toggleSelectedAll" />
            전체 선택
          </label>

          <ul class="space-y-4">
            <li v-for="item in vm.items" :key="item.variantPublicId" :class="[CARD, 'p-5 md:flex md:items-center md:gap-6 md:p-6']">
              <!-- 왼쪽: 선택 · 이미지 · 정보 -->
              <div class="flex min-w-0 flex-1 items-start gap-4">
                <!-- 선택: 구매 불가는 선택 비활성(삭제만) -->
                <label :class="CHECKBOX_HIT">
                  <RenewCheckbox
                    :checked="item.selected"
                    :disabled="vm.busy || !item.purchasable"
                    :aria-label="`${item.productName ?? '상품'} 선택`"
                    @change="(checked) => vm.toggleSelected(item, checked)"
                  />
                </label>

                <div class="h-20 w-20 shrink-0 overflow-hidden rounded-[18px] bg-(--image-placeholder) md:h-24 md:w-24">
                  <img v-if="item.thumbnailUrl" :src="item.thumbnailUrl" :alt="item.productName ?? '상품 이미지'" class="h-full w-full object-cover" />
                </div>

                <div class="min-w-0 flex-1">
                  <p v-if="item.sellerName" class="truncate text-caption font-normal text-sub">{{ item.sellerName }}</p>
                  <p class="truncate text-h3 text-ink">{{ item.productName ?? '상품 정보 없음' }}</p>
                  <p v-if="item.optionLabel" data-testid="item-option-label" class="truncate text-small text-sub">{{ item.optionLabel }}</p>
                  <!-- 좁은 폭에서 사유가 배지 아래로 내려가도록 배지와 사유를 나눈다. -->
                  <p v-if="!item.purchasable" class="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1">
                    <RenewBadge tone="danger">구매 불가</RenewBadge>
                    <span class="text-caption font-normal text-sub">품절 또는 판매 중지</span>
                  </p>
                </div>
              </div>

              <!-- 오른쪽: 수량 · 금액 · 삭제(좁은 폭에서는 정보 아래 한 줄) -->
              <div class="mt-4 flex flex-wrap items-center gap-x-3 gap-y-2 md:mt-0 md:shrink-0 md:flex-nowrap md:gap-x-5">
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
                  <span class="min-w-8 text-center text-body font-semibold tabular-nums text-ink">{{ item.quantity }}</span>
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
                <p class="ml-auto whitespace-nowrap text-ink md:ml-0 md:min-w-28 md:text-right">
                  <span class="text-h3 font-semibold tabular-nums">{{ (item.displayPrice * item.quantity).toLocaleString('ko-KR') }}</span><span class="ml-0.5 text-small">원</span>
                </p>
                <!-- 3차 버튼(위험 글자색) -->
                <button
                  type="button"
                  class="btn btn-tertiary btn-sm text-destructive max-md:min-h-11"
                  :aria-label="`${item.productName ?? '상품'} 삭제`"
                  :disabled="vm.busy"
                  @click="vm.removeItem(item)"
                >
                  삭제
                </button>
              </div>
            </li>
          </ul>
        </section>

        <!-- 주문 금액: ≥1024 헤더 아래 24px에 고정 -->
        <aside :class="[CARD, 'mt-6 p-5 md:p-6 lg:sticky lg:top-[calc(var(--header-height)_+_24px)] lg:mt-0']" aria-label="주문 금액">
          <h2 class="text-h3 text-ink">주문 금액</h2>
          <dl class="mt-5 space-y-3 text-body">
            <div class="flex items-baseline justify-between">
              <dt class="text-sub">선택 상품 금액</dt>
              <dd><FadeAmount :value="vm.selectedTotal" /></dd>
            </div>
            <div class="flex items-baseline justify-between">
              <dt class="text-sub">배송비</dt>
              <dd class="font-semibold text-ink">무료</dd>
            </div>
            <div class="flex items-baseline justify-between border-t border-line pt-4">
              <dt class="font-semibold text-ink">결제 예정 금액</dt>
              <dd class="text-h2"><FadeAmount :value="vm.selectedTotal" /></dd>
            </div>
          </dl>
          <!-- 구매 불가 선택 품목 포함(FE-18): 주문 차단·삭제 유도 -->
          <RenewNotice v-if="vm.hasUnpurchasableSelected" tone="danger" class="mt-4">
            구매할 수 없는 상품이 포함되어 있습니다. 삭제 후 결제해 주세요.
          </RenewNotice>
          <RenewNotice v-if="vm.opErrorMessage" tone="danger" class="mt-4">{{ vm.opErrorMessage }}</RenewNotice>
          <button ref="checkoutButton" type="button" class="btn btn-primary btn-lg mt-6 w-full" :disabled="!vm.checkoutEnabled" @click="vm.handleCheckout">
            주문하기
          </button>
        </aside>

        <!-- <1024 하단 고정 바: 요약 카드 주문하기가 화면에 없을 때만·같은 함수·같은 비활성 규칙 -->
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
