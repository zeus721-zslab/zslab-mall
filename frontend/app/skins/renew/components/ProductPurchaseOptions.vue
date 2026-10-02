<script setup lang="ts">
import type { ProductDetail } from '~/types/product'
import type { ProductDetailPageVm } from '~/skins/contracts/product-detail'

// 상세 옵션 선택 + 수량(FE-70). 본문 구매 영역과 옵션 시트(FE-100)가 같은 vm(페이지 단일 상태)을 쓰므로 한쪽에서 고르면 다른 쪽에도 그대로 보인다.
defineProps<{ vm: ProductDetailPageVm; product: ProductDetail }>()
</script>

<template>
  <div>
    <!-- 옵션: 선택 = 칩 보라 채움(aria-pressed) · 품절 표시(흐림·취소선) = 현재 선택 조합 기준 · 비활성 = 이 값으로 살 variant가 전혀 없을 때만(교착 방지·FE-70) -->
    <div v-if="product.optionGroups.length > 0" class="space-y-5">
      <div v-for="group in product.optionGroups" :key="group.name">
        <p class="text-small font-bold text-ink">{{ group.name }}</p>
        <div class="mt-2 flex flex-wrap gap-2">
          <button
            v-for="optionValue in group.values"
            :key="optionValue.value"
            type="button"
            :disabled="vm.isOptionValueUnavailable(group.name, optionValue.value)"
            :aria-pressed="vm.selectedOptions[group.name] === optionValue.value"
            :class="['chip disabled:cursor-default', vm.isOptionValueSoldOut(group.name, optionValue.value) ? 'line-through opacity-40' : '']"
            @click="vm.selectOption(group.name, optionValue.value)"
          >
            {{ optionValue.value }}<span v-if="vm.isOptionValueSoldOut(group.name, optionValue.value)" class="sr-only"> (품절)</span>
          </button>
        </div>
      </div>
      <p v-if="!vm.selectedVariant" class="text-small text-sub">옵션을 모두 선택해 주세요.</p>
      <p v-else-if="vm.selectedVariant.soldOut" class="text-small font-bold text-ink">선택하신 옵션은 품절입니다.</p>
    </div>

    <!-- 수량 -->
    <div class="mt-6 flex items-center justify-between gap-4">
      <span class="text-small font-bold text-ink">수량</span>
      <div class="inline-flex items-center rounded-full border border-line bg-white p-1">
        <button
          type="button"
          aria-label="수량 감소"
          class="flex h-11 w-11 items-center justify-center rounded-full text-h3 font-normal text-ink transition duration-fast ease-soft hover:bg-surface-muted disabled:cursor-default disabled:opacity-40 disabled:hover:bg-transparent"
          :disabled="vm.quantity <= 1"
          @click="vm.decrementQuantity"
        >
          −
        </button>
        <span class="min-w-10 text-center text-body font-semibold tabular-nums text-ink">{{ vm.quantity }}</span>
        <button
          type="button"
          aria-label="수량 증가"
          class="flex h-11 w-11 items-center justify-center rounded-full text-h3 font-normal text-ink transition duration-fast ease-soft hover:bg-surface-muted"
          @click="vm.incrementQuantity"
        >
          +
        </button>
      </div>
    </div>
    <p v-if="vm.quantityNotice" role="status" class="mt-2 text-right text-small text-sub" data-testid="quantity-limit-notice">{{ vm.quantityNotice }}</p>
  </div>
</template>
