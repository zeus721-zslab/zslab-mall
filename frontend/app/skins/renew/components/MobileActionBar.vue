<script setup lang="ts">
import { observeVisibility } from '../dock-visibility'

// renew <1024 화면 하단 고정 바(FE-71 도킹): 금액 + 주 동작 버튼. 768~1023은 sticky 요약 카드가 없어(lg부터) 바도 이 구간까지 둔다(FE-78). 버튼은 화면 본문 버튼과 같은 함수·같은 비활성 규칙을 받는다.
// 본문의 실제 주 버튼(anchor)이 화면(바 자리 제외)에 없을 때만 나타난다 — 두 버튼이 동시에 보이지 않는다.
// 첫 측정 전(SSR 포함)에는 숨겨 처음부터 버튼이 보이는 화면에서 깜빡이지 않는다.
const props = defineProps<{
  label: string
  amount: number | null
  pendingText: string
  buttonLabel: string
  disabled: boolean
  anchor: HTMLElement | null
  // true면 감싼 form을 제출한다(주문서).
  submit?: boolean
}>()
const emit = defineEmits<{ action: [] }>()

const barElement = ref<HTMLElement | null>(null)
// null = 아직 측정 전(숨김).
const anchorVisible = ref<boolean | null>(null)
const shown = computed(() => anchorVisible.value === false)

let stopObserving: (() => void) | null = null
function observeAnchor(): void {
  stopObserving?.()
  stopObserving = null
  anchorVisible.value = null
  if (!props.anchor || !barElement.value) return
  stopObserving = observeVisibility(props.anchor, barElement.value.offsetHeight, (visible) => {
    anchorVisible.value = visible
  })
}
onMounted(observeAnchor)
watch(() => props.anchor, observeAnchor, { flush: 'post' })
onBeforeUnmount(() => stopObserving?.())

// 바가 보이는 동안에만 body 하단에 바 높이만큼 여백을 둬 페이지 끝(푸터 마지막 줄)이 바에 가려지지 않게 한다. 숨김이면 0.
// 바 높이 = 상단 테두리 1 + 위 여백 12 + 버튼 48 + 아래 여백 12 + 하단 안전 영역(FE-78) — 아래 바 클래스와 같이 고친다.
useHead({
  bodyAttrs: {
    class: computed(() => (shown.value ? 'max-lg:pb-[calc(73px+env(safe-area-inset-bottom,0px))]' : '')),
  },
})
</script>

<template>
  <div
    ref="barElement"
    :class="[
      'fixed inset-x-0 bottom-0 z-40 border-t border-line bg-white px-5 pb-[calc(12px+env(safe-area-inset-bottom,0px))] pt-3 transition-[transform,opacity] duration-fast ease-soft motion-reduce:transition-none md:px-10 lg:hidden',
      shown ? 'translate-y-0 opacity-100' : 'pointer-events-none translate-y-full opacity-0',
    ]"
    :aria-hidden="shown ? undefined : 'true'"
    :inert="!shown || undefined"
  >
    <div class="flex h-12 items-center gap-4">
      <div class="min-w-0 flex-1">
        <p class="text-caption font-normal text-sub">{{ label }}</p>
        <p v-if="amount !== null" class="truncate text-ink">
          <span class="text-h3 font-semibold tabular-nums">{{ amount.toLocaleString('ko-KR') }}</span><span class="ml-0.5 text-small">원</span>
        </p>
        <p v-else class="truncate text-small text-sub">{{ pendingText }}</p>
      </div>
      <button
        :type="submit ? 'submit' : 'button'"
        class="btn btn-primary btn-md h-12 shrink-0"
        :disabled="disabled"
        @click="submit ? undefined : emit('action')"
      >
        {{ buttonLabel }}
      </button>
    </div>
  </div>
</template>
