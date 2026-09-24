<script setup lang="ts">
import type { ProductSummary } from '~/types/product'

// renew 상품 카드. 호버 면은 카드 바깥 12px(p-3)까지 넓히고 같은 만큼 음수 마진(-m-3)으로 상쇄해 호버 전 그리드 정렬을 그대로 둔다.
// 이동·확대는 motion-safe에서만(prefers-reduced-motion이면 흰 면·그림자만). 가격은 본문색, 순위 숫자만 포인트색.
const props = defineProps<{
  product: ProductSummary
  rank?: number
}>()

const formattedPrice = computed(() => props.product.displayPrice.toLocaleString('ko-KR'))
</script>

<template>
  <NuxtLink
    :to="`/products/${product.productPublicId}`"
    :aria-label="product.name"
    data-testid="product-card"
    class="group relative -m-3 block rounded-(--panel-radius) p-3 transition duration-300 ease-out hover:z-10 hover:bg-white hover:shadow-[0_20px_40px_-20px_rgba(34,31,43,0.35)] focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary motion-safe:hover:-translate-y-1.5"
  >
    <div class="relative aspect-square overflow-hidden rounded-card bg-(--image-placeholder)">
      <img
        v-if="product.mainImageUrl"
        :src="product.mainImageUrl"
        :alt="product.name"
        loading="lazy"
        class="h-full w-full object-cover transition duration-500 ease-out motion-safe:group-hover:scale-[1.04]"
      />
      <span
        v-if="rank !== undefined"
        class="absolute left-3 top-3 flex h-9 min-w-9 items-center justify-center rounded-full bg-white/90 px-2 font-mono text-lg font-semibold text-primary"
      >
        {{ rank }}
      </span>
      <span
        v-if="product.soldOut"
        class="absolute right-3 top-3 rounded-full bg-white/90 px-3 py-1 text-xs font-bold text-ink"
      >
        품절
      </span>
    </div>
    <div class="space-y-1 px-1 pt-3">
      <p class="truncate text-xs text-sub">{{ product.sellerName }}</p>
      <p class="line-clamp-2 text-sm leading-snug text-ink" data-testid="product-card-name">{{ product.name }}</p>
      <p class="pt-1 text-ink">
        <span class="font-mono text-base font-semibold">{{ formattedPrice }}</span><span class="ml-0.5 text-sm">원</span>
      </p>
    </div>
  </NuxtLink>
</template>
