<script setup lang="ts">
import { DialogClose } from "reka-ui"
import Dialog from "./Dialog.vue"
import DialogContent from "./DialogContent.vue"
import DialogDescription from "./DialogDescription.vue"
import DialogFooter from "./DialogFooter.vue"
import DialogHeader from "./DialogHeader.vue"
import DialogTitle from "./DialogTitle.vue"

// 확인 용도 모달(FE-72 보완 1): 제목 + 설명 + 취소·확인. destructive면 확인 버튼을 위험 강조색으로 둔다.
// 열림 상태는 부모가 가진다(open + update:open). 취소·Esc·바깥 누름은 update:open(false), 확인은 confirm만 알린다(닫기는 부모 판단).
withDefaults(
  defineProps<{
    open: boolean
    title: string
    description: string
    confirmLabel: string
    cancelLabel?: string
    destructive?: boolean
  }>(),
  {
    cancelLabel: "취소",
    destructive: false,
  },
)
const emit = defineEmits<{ "update:open": [open: boolean]; confirm: [] }>()

const BUTTON =
  "flex min-h-11 items-center justify-center rounded-full px-6 text-sm font-bold transition duration-200 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
</script>

<template>
  <Dialog :open="open" @update:open="(value) => emit('update:open', value)">
    <DialogContent :show-close="false" class="md:max-w-md" data-testid="dialog-confirm">
      <DialogHeader class="pr-6 md:pr-8">
        <DialogTitle>{{ title }}</DialogTitle>
        <DialogDescription>{{ description }}</DialogDescription>
      </DialogHeader>
      <DialogFooter class="mt-6 border-t-0 pt-0 md:pt-0">
        <DialogClose :class="[BUTTON, 'border border-border bg-white text-foreground hover:border-foreground']">
          {{ cancelLabel }}
        </DialogClose>
        <button
          type="button"
          :class="[BUTTON, destructive ? 'bg-destructive text-destructive-foreground hover:opacity-90' : 'bg-primary text-primary-foreground hover:bg-primary-hover']"
          data-testid="dialog-confirm-action"
          @click="emit('confirm')"
        >
          {{ confirmLabel }}
        </button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
