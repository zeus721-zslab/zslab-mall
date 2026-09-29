<script setup lang="ts">
import { trackScrollEdges } from '../scroll-edges'

// renew 사진 가로 띠(Track 106-1). 한 줄 가로 스크롤 · 칸 경계 스냅 · 끝에 닿기 전 오른쪽 흐림(홈 가로 줄과 같은 규칙).
// 보여 줄 칸보다 사진이 많으면 마지막 칸에 "+N"을 덮는다(누르면 그 사진부터 확대). 칸을 누르면 open(순번)을 알린다 — 확대는 부모가 연다.
const props = withDefaults(defineProps<{ photos: string[]; max?: number; label?: string }>(), { max: 8, label: '리뷰 사진' })
const emit = defineEmits<{ open: [index: number] }>()

const shown = computed(() => props.photos.slice(0, props.max))
// 마지막 칸이 가리는(보이지 않는) 사진 수 — 마지막 칸 자신을 포함한다.
const hiddenCount = computed(() => props.photos.length - props.max + 1)

const scroller = ref<HTMLElement | null>(null)
const edges = trackScrollEdges(scroller)
</script>

<template>
  <div class="relative -mx-1" data-testid="photo-strip">
    <ul ref="scroller" class="flex snap-x snap-mandatory gap-2 overflow-x-auto px-1 py-1 scrollbar-none" :aria-label="label">
      <li v-for="(photo, index) in shown" :key="`${photo}-${index}`" class="shrink-0 snap-start">
        <button
          type="button"
          class="relative block h-24 w-24 overflow-hidden rounded-[18px] bg-(--image-placeholder) focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary md:h-28 md:w-28"
          :aria-label="index === shown.length - 1 && photos.length > max ? `${label} ${hiddenCount}장 더 보기` : `${label} ${index + 1} 크게 보기`"
          data-testid="photo-strip-item"
          @click="emit('open', index)"
        >
          <img :src="photo" alt="" loading="lazy" class="h-full w-full object-cover transition duration-fast ease-soft motion-safe:hover:scale-[1.04]" />
          <span
            v-if="index === shown.length - 1 && photos.length > max"
            class="absolute inset-0 flex items-center justify-center bg-foreground/55 text-h3 font-bold tabular-nums text-white"
            data-testid="photo-strip-more"
          >
            +{{ hiddenCount }}
          </span>
        </button>
      </li>
    </ul>
    <div
      v-show="!edges.atEnd"
      class="pointer-events-none absolute inset-y-0 right-0 w-10 bg-linear-to-r from-transparent to-white"
      aria-hidden="true"
    ></div>
  </div>
</template>
