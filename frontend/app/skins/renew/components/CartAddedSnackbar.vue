<script setup lang="ts">
// 담기 성공 스낵바(FE-99): 본문 버튼·하단 바 어느 쪽에서 담아도 보이도록 화면 하단에 띄운다(본문 버튼 아래 카드는 바에서 담으면 화면 밖이었다).
// signal 값이 바뀔 때마다 나타나고 약 4초 뒤 사라진다. 포인터·터치·포커스가 머무는 동안은 멈추고, 떠나면 처음부터 다시 센다.
const props = defineProps<{ signal: number }>()

const SNACKBAR_DURATION_MS = 4000
const SLIDE = {
  enterActiveClass: 'transition-[transform,opacity] duration-fast ease-soft motion-reduce:transition-none',
  leaveActiveClass: 'transition-[transform,opacity] duration-fast ease-soft motion-reduce:transition-none',
  enterFromClass: 'translate-y-2 opacity-0',
  leaveToClass: 'translate-y-2 opacity-0',
}

const visible = ref<boolean>(false)
const pointerInside = ref<boolean>(false)
const focusInside = ref<boolean>(false)
let hideTimer: ReturnType<typeof setTimeout> | null = null

function clearHideTimer(): void {
  if (hideTimer !== null) clearTimeout(hideTimer)
  hideTimer = null
}

function restartHideTimer(): void {
  clearHideTimer()
  if (!visible.value || pointerInside.value || focusInside.value) return
  hideTimer = setTimeout(() => {
    visible.value = false
  }, SNACKBAR_DURATION_MS)
}

watch(
  () => props.signal,
  () => {
    visible.value = true
    restartHideTimer()
  },
)
watch([pointerInside, focusInside], restartHideTimer)
onBeforeUnmount(clearHideTimer)

// 채팅 도우미 버튼이 스낵바 위로 올라가는 신호(data-mobile-action-bar와 같은 방식 · 스킨은 composable을 부르지 않는다 · FE-67).
useHead({
  bodyAttrs: {
    'data-cart-snackbar': computed(() => (visible.value ? 'shown' : undefined)),
  },
})
</script>

<template>
  <!-- 알림 영역은 늘 두고 안의 내용만 바꿔 스크린리더가 새 내용을 읽게 한다. 빈 영역은 클릭을 막지 않는다. -->
  <div
    role="status"
    aria-live="polite"
    class="cart-snackbar pointer-events-none fixed inset-x-4 bottom-[calc(16px+env(safe-area-inset-bottom,0px))] z-45 flex justify-center transition-[bottom] duration-fast ease-soft motion-reduce:transition-none lg:bottom-6"
    data-testid="cart-added-snackbar"
  >
    <Transition v-bind="SLIDE">
      <!-- 높이 56 고정: 채팅 도우미 버튼 올림 값(FaqAssistant.vue)이 이 높이 기준이라 같이 고친다. -->
      <div
        v-if="visible"
        class="pointer-events-auto flex h-14 w-full max-w-sm items-center gap-3 rounded-card bg-ink pl-5 pr-2 text-white shadow-e2"
        @pointerenter="pointerInside = true"
        @pointerleave="pointerInside = false"
        @focusin="focusInside = true"
        @focusout="focusInside = false"
      >
        <span class="flex-1 text-small font-bold">담았어요</span>
        <NuxtLink to="/cart" class="btn btn-sm shrink-0 bg-white text-primary hover:bg-surface-muted max-md:min-h-11">장바구니 보기</NuxtLink>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
/* 1024px 미만 하단 고정 바(MobileActionBar · 높이 73px + 안전 영역)가 보이면 바 위 8px로 올린다(FaqAssistant.vue와 같은 :global 방식). */
@media (width < 64rem) {
  :global(body[data-mobile-action-bar='shown'] .cart-snackbar) {
    bottom: calc(81px + env(safe-area-inset-bottom, 0px));
  }
}
</style>
