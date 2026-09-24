<script setup lang="ts">
import type { DialogContentEmits, DialogContentProps } from "reka-ui"
import type { HTMLAttributes } from "vue"
import { reactiveOmit } from "@vueuse/core"
import {
  DialogClose,
  DialogContent,
  DialogOverlay,
  DialogPortal,
  useForwardPropsEmits,
} from "reka-ui"
import { cn } from '~/lib/utils'

// 구매자 공용 모달(FE-72 보완 1). ≥768 가운데 모달(페이드 + 살짝 확대) · <768 하단 시트(아래에서 위로·최대 90dvh·하단 safe-area).
// 포커스 가두기·Esc 닫기·aria(제목·설명 연결)·닫을 때 열었던 요소로 포커스 복귀는 reka DialogContent, 배경 스크롤 잠금은 DialogOverlay 기본 동작이다.
// 본문 스크롤은 DialogBody가 맡는다(머리·바닥은 고정). 움직임 줄이기 설정이면 애니메이션 없이 바로 열고 닫는다.
defineOptions({
  inheritAttrs: false,
})

// overlayClass(FE-79): 모달 위에 모달을 겹칠 때 위 모달의 오버레이를 덮어써(예: bg-transparent) 배경이 두 번 어두워지지 않게 한다.
const props = withDefaults(
  defineProps<DialogContentProps & { class?: HTMLAttributes["class"]; overlayClass?: HTMLAttributes["class"]; showClose?: boolean }>(),
  {
    showClose: true,
    overlayClass: undefined,
  },
)
const emits = defineEmits<DialogContentEmits>()

const delegatedProps = reactiveOmit(props, "class", "overlayClass", "showClose")

const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <DialogPortal>
    <DialogOverlay
      data-slot="dialog-overlay"
      :class="cn('fixed inset-0 z-50 bg-foreground/40 data-[state=closed]:animate-[dialog-fade-out_150ms_ease-in] data-[state=open]:animate-[dialog-fade-in_200ms_ease-out] motion-reduce:animate-none', props.overlayClass)"
    />
    <DialogContent
      data-slot="dialog-content"
      v-bind="{ ...$attrs, ...forwarded }"
      :class="cn(
        'fixed z-50 flex flex-col bg-white text-foreground shadow-[0_24px_48px_-24px_rgba(0,0,0,0.35)] outline-hidden motion-reduce:animate-none',
        'inset-x-0 bottom-0 max-h-[90dvh] rounded-t-card pb-[env(safe-area-inset-bottom)] max-md:data-[state=closed]:animate-[dialog-sheet-out_180ms_ease-in] max-md:data-[state=open]:animate-[dialog-sheet-in_250ms_cubic-bezier(0.32,0.72,0,1)]',
        'md:inset-x-auto md:bottom-auto md:left-1/2 md:top-1/2 md:max-h-[85dvh] md:w-[calc(100%-4rem)] md:max-w-lg md:-translate-x-1/2 md:-translate-y-1/2 md:rounded-card md:pb-0 md:data-[state=closed]:animate-[dialog-zoom-out_150ms_ease-in] md:data-[state=open]:animate-[dialog-zoom-in_200ms_ease-out]',
        props.class,
      )"
    >
      <slot />

      <!-- 닫기 버튼은 DOM 끝에 둬 열릴 때 첫 초점이 본문(첫 입력칸·취소 버튼)에 가게 한다. -->
      <DialogClose
        v-if="showClose"
        class="absolute right-4 top-4 flex h-10 w-10 items-center justify-center rounded-full text-muted-foreground transition duration-200 hover:bg-muted hover:text-foreground focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-ring md:right-5 md:top-5"
      >
        <svg class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
          <path d="M6 6l12 12M18 6L6 18" />
        </svg>
        <span class="sr-only">닫기</span>
      </DialogClose>
    </DialogContent>
  </DialogPortal>
</template>
