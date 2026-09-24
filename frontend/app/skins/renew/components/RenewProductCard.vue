<script setup lang="ts">
import type { ProductSummary } from '~/types/product'
import RenewBadge from './RenewBadge.vue'

// renew 상품 카드. 호버 면은 카드 바깥 12px(p-3)까지 넓히고 같은 만큼 음수 마진(-m-3)으로 상쇄해 호버 전 그리드 정렬을 그대로 둔다.
// 이동·확대는 motion-safe에서만(prefers-reduced-motion이면 흰 면·그림자만). 가격은 본문색, 순위 숫자만 포인트색.
// 링크는 상품명에만 걸고 after:inset-0으로 카드 전체를 누를 수 있게 넓힌다(Track 105-4g-3): 접근 이름 = 보이는 상품명,
// 셀러·가격은 링크 밖 일반 텍스트라 중복 낭독이 없다. 포커스 링은 넓힌 영역(after)에 그려 카드 전체에 보인다. 이미지는 이름과 겹쳐 alt="".
const props = defineProps<{
  product: ProductSummary
  rank?: number
}>()

const formattedPrice = computed(() => props.product.displayPrice.toLocaleString('ko-KR'))
</script>

<template>
  <div
    data-testid="product-card"
    class="group relative -m-3 block rounded-(--panel-radius) p-3 transition duration-fast ease-soft hover:z-10 hover:bg-white hover:shadow-e2 focus-within:z-10 motion-safe:hover:-translate-y-1"
  >
    <div class="relative aspect-square overflow-hidden rounded-card bg-(--image-placeholder)">
      <img
        v-if="product.mainImageUrl"
        :src="product.mainImageUrl"
        alt=""
        loading="lazy"
        class="h-full w-full object-cover transition duration-fast ease-soft motion-safe:group-hover:scale-[1.04]"
      />
      <span
        v-if="rank !== undefined"
        class="absolute left-3 top-3 flex h-9 min-w-9 items-center justify-center rounded-full bg-white/90 px-2 text-h3 font-extrabold tabular-nums text-primary"
      >
        {{ rank }}
      </span>
      <RenewBadge v-if="product.soldOut" tone="neutral" class="absolute right-3 top-3">품절</RenewBadge>
    </div>
    <div class="space-y-1 px-1 pt-3">
      <p class="truncate text-caption font-normal text-sub">{{ product.sellerName }}</p>
      <p class="line-clamp-2 break-keep text-body text-ink" data-testid="product-card-name">
        <NuxtLink
          :to="`/products/${product.productPublicId}`"
          class="after:absolute after:inset-0 after:rounded-(--panel-radius) focus-visible:outline-hidden focus-visible:after:ring-2 focus-visible:after:ring-primary"
        >
          {{ product.name }}
        </NuxtLink>
      </p>
      <p class="pt-1 text-ink">
        <span class="text-h3 tabular-nums">{{ formattedPrice }}</span><span class="ml-0.5 text-small">원</span>
      </p>
    </div>
  </div>
</template>
