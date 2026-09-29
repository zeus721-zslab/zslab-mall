<script setup lang="ts">
import { ChevronLeft, ChevronRight, X } from '@lucide/vue'
import { DialogClose, DialogContent, DialogDescription, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui'

// renew 사진 확대(Track 106-1). 전체 화면 어두운 면 위에 한 장씩 — 좌우 버튼 · ←/→ 키 · 좌우 스와이프로 이동, Esc·닫기 버튼으로 닫는다.
// 포커스 가두기·Esc·닫을 때 연 요소로 포커스 복귀·배경 스크롤 잠금은 reka Dialog가 맡는다. 움직임 줄이기 설정이면 애니메이션 없이 연다.
const props = withDefaults(defineProps<{ open: boolean; photos: string[]; startIndex?: number; title?: string }>(), {
  startIndex: 0,
  title: '리뷰 사진',
})
const emit = defineEmits<{ 'update:open': [open: boolean] }>()

// 이 거리(px) 이상 가로로 끌면 스와이프로 본다(세로 스크롤·탭과 구분).
const SWIPE_THRESHOLD_PX = 40

const index = ref(props.startIndex)
watch(
  () => [props.open, props.startIndex] as const,
  ([open, start]) => {
    if (open) index.value = Math.min(Math.max(start, 0), Math.max(props.photos.length - 1, 0))
  },
)

const hasPrevious = computed(() => index.value > 0)
const hasNext = computed(() => index.value < props.photos.length - 1)
const current = computed(() => props.photos[index.value] ?? '')

function move(step: 1 | -1): void {
  const next = index.value + step
  if (next >= 0 && next < props.photos.length) index.value = next
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'ArrowLeft') move(-1)
  else if (event.key === 'ArrowRight') move(1)
}

let pointerStartX: number | null = null
function onPointerDown(event: PointerEvent): void {
  pointerStartX = event.clientX
}
function onPointerUp(event: PointerEvent): void {
  if (pointerStartX === null) return
  const distance = event.clientX - pointerStartX
  pointerStartX = null
  if (Math.abs(distance) >= SWIPE_THRESHOLD_PX) move(distance < 0 ? 1 : -1)
}

const NAV_BUTTON =
  'absolute top-1/2 flex h-12 w-12 -translate-y-1/2 items-center justify-center rounded-full bg-white/15 text-white transition duration-fast ease-soft hover:bg-white/25 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-white disabled:cursor-default disabled:opacity-30 disabled:hover:bg-white/15'
</script>

<template>
  <DialogRoot :open="open" @update:open="(value: boolean) => emit('update:open', value)">
    <DialogPortal>
      <DialogOverlay
        class="fixed inset-0 z-50 bg-foreground/90 data-[state=open]:animate-[dialog-fade-in_200ms_ease-out] data-[state=closed]:animate-[dialog-fade-out_150ms_ease-in] motion-reduce:animate-none"
      />
      <DialogContent
        class="fixed inset-0 z-50 flex flex-col text-white outline-hidden data-[state=open]:animate-[dialog-fade-in_200ms_ease-out] data-[state=closed]:animate-[dialog-fade-out_150ms_ease-in] motion-reduce:animate-none"
        data-testid="lightbox"
        @keydown="onKeydown"
      >
        <div class="flex items-center justify-between px-5 py-4">
          <DialogTitle class="text-small font-semibold">
            {{ title }} <span class="tabular-nums" data-testid="lightbox-counter">{{ index + 1 }} / {{ photos.length }}</span>
          </DialogTitle>
          <DialogDescription class="sr-only">왼쪽·오른쪽 화살표 키나 스와이프로 사진을 넘길 수 있습니다.</DialogDescription>
          <DialogClose
            class="flex h-11 w-11 items-center justify-center rounded-full bg-white/15 transition duration-fast ease-soft hover:bg-white/25 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-white"
            data-testid="lightbox-close"
          >
            <X class="h-5 w-5" aria-hidden="true" />
            <span class="sr-only">닫기</span>
          </DialogClose>
        </div>

        <div
          class="relative flex min-h-0 flex-1 touch-pan-y select-none items-center justify-center px-4 pb-8 md:px-20"
          @pointerdown="onPointerDown"
          @pointerup="onPointerUp"
          @pointercancel="pointerStartX = null"
        >
          <img
            v-if="current"
            :key="current"
            :src="current"
            :alt="`${title} ${index + 1}`"
            draggable="false"
            class="max-h-full max-w-full rounded-card object-contain"
            data-testid="lightbox-image"
          />
          <button type="button" :class="[NAV_BUTTON, 'left-3 md:left-6']" :disabled="!hasPrevious" aria-label="이전 사진" data-testid="lightbox-prev" @click="move(-1)">
            <ChevronLeft class="h-6 w-6" aria-hidden="true" />
          </button>
          <button type="button" :class="[NAV_BUTTON, 'right-3 md:right-6']" :disabled="!hasNext" aria-label="다음 사진" data-testid="lightbox-next" @click="move(1)">
            <ChevronRight class="h-6 w-6" aria-hidden="true" />
          </button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
