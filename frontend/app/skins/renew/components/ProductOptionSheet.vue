<script setup lang="ts">
import { X } from '@lucide/vue'
import { DialogClose, DialogContent, DialogDescription, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui'
import type { ProductDetail } from '~/types/product'
import type { ProductDetailPageVm } from '~/skins/contracts/product-detail'
import ProductPurchaseOptions from './ProductPurchaseOptions.vue'

// 옵션 상품 하단 바의 옵션 시트(FE-100): 옵션 선택 · 선택 요약 · 수량 · [장바구니 담기][바로구매].
// 하단 바가 있는 <1024 전체에서 아래에서 올라오는 시트다(공용 Dialog는 768부터 가운데 모달이라 쓰지 않는다 · D5 β).
// 옵션·수량은 본문과 같은 vm을 쓴다. 포커스 가두기·Esc·배경 탭 닫기·연 버튼으로 포커스 복귀·배경 스크롤 잠금은 reka Dialog가 맡는다.
const props = defineProps<{ open: boolean; vm: ProductDetailPageVm; product: ProductDetail }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; add: []; buy: [] }>()

const selectedOptionLabel = computed(() => props.vm.selectedVariant?.options.map((option) => option.value).join(' / ') ?? '')
const actionsDisabled = computed(() => !props.vm.canAddToCart || props.vm.adding)
</script>

<template>
  <DialogRoot :open="open" @update:open="(value: boolean) => emit('update:open', value)">
    <DialogPortal>
      <DialogOverlay
        class="fixed inset-0 z-50 bg-foreground/40 data-[state=open]:animate-[dialog-fade-in_200ms_ease-out] data-[state=closed]:animate-[dialog-fade-out_150ms_ease-in] motion-reduce:animate-none"
      />
      <DialogContent
        class="fixed inset-x-0 bottom-0 z-50 flex max-h-[90dvh] flex-col rounded-t-card bg-white pb-[env(safe-area-inset-bottom,0px)] text-ink shadow-e3 outline-hidden data-[state=open]:animate-[dialog-sheet-in_250ms_cubic-bezier(0.32,0.72,0,1)] data-[state=closed]:animate-[dialog-sheet-out_180ms_ease-in] motion-reduce:animate-none"
        data-testid="product-option-sheet"
      >
        <div class="flex items-center justify-between gap-3 px-5 pb-2 pt-4 md:px-10">
          <DialogTitle class="text-h3 text-ink">옵션 선택</DialogTitle>
          <DialogDescription class="sr-only">옵션과 수량을 고른 뒤 장바구니에 담거나 바로 구매할 수 있습니다.</DialogDescription>
          <DialogClose class="flex h-11 w-11 items-center justify-center rounded-full text-sub transition duration-fast ease-soft hover:bg-surface-muted focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary">
            <X class="h-5 w-5" aria-hidden="true" />
            <span class="sr-only">닫기</span>
          </DialogClose>
        </div>

        <div class="min-h-0 flex-1 overflow-y-auto px-5 py-2 md:px-10">
          <ProductPurchaseOptions :vm="vm" :product="product" />
        </div>

        <div class="border-t border-line px-5 pb-3 pt-4 md:px-10">
          <!-- 선택 요약: 옵션 확정 전에는 합계를 만들지 않는다(본문 금액 박스와 같은 규칙). -->
          <div class="flex items-baseline justify-between gap-4" data-testid="product-option-sheet-summary">
            <p class="min-w-0 truncate text-small text-sub">{{ selectedOptionLabel || (vm.unavailableLabel ?? '옵션을 선택해 주세요') }}</p>
            <p v-if="vm.totalPrice !== null" class="shrink-0 text-ink">
              <span class="text-h3 font-semibold tabular-nums">{{ vm.totalPrice.toLocaleString('ko-KR') }}</span><span class="ml-0.5 text-small">원</span>
            </p>
          </div>
          <div class="mt-3 flex gap-3">
            <button type="button" class="btn btn-secondary btn-lg flex-1" :disabled="actionsDisabled" @click="emit('add')">장바구니 담기</button>
            <button type="button" class="btn btn-primary btn-lg flex-1" :disabled="actionsDisabled" @click="emit('buy')">바로구매</button>
          </div>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
