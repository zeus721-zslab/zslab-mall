<script setup lang="ts">
import { DialogClose } from "reka-ui"
import Dialog from "./Dialog.vue"
import DialogContent from "./DialogContent.vue"
import DialogDescription from "./DialogDescription.vue"
import DialogFooter from "./DialogFooter.vue"
import DialogHeader from "./DialogHeader.vue"
import DialogTitle from "./DialogTitle.vue"
// 확인창은 구매자(renew) 화면에서만 쓰므로 결과 안내는 renew 공용 알림으로 보인다.
import RenewNotice from "~/skins/renew/components/RenewNotice.vue"

// 확인 용도 모달(FE-72 보완 1): 제목 + 설명(대상) + 결과 안내(notice 슬롯) + 취소·확인.
// FE-79: 강조는 색을 늘리지 않고 구조로 한다 — 결과 안내(RenewNotice warning · 슬롯이 비면 없음) + 동작을 적은 확인 버튼 이름(호출처 지정).
// tone: 확인 버튼 색 = 동작의 의미. 기본 primary(구매확정 같은 긍정 동작) · danger는 삭제·취소·철회처럼 없애는 동작만.
// 열림 상태는 부모가 가진다(open + update:open). 취소·Esc·바깥 누름은 update:open(false), 확인은 confirm만 알린다(닫기는 부모 판단).
// FE-73: pending이면 두 버튼을 잠근다(처리 중 중복 확인 방지). testid는 기존 화면의 e2e 단언을 잇도록 사용처가 바꿀 수 있다.
// FE-79: pending 동안 확인 버튼에 스피너·aria-busy를 보이고, Esc·바깥 누름·닫기 요청을 무시한다(처리 결과를 보기 전에 닫히지 않게).
// 결과 안내는 줄바꿈(\n)을 그대로 보인다(위험 조작 문구 규약의 2줄 경고). testid는 안내에 둔다(noticeTestId).
// FE-80: 설명을 여러 줄·다른 글자 크기로 보여야 하면 description 슬롯을 쓴다(구매확정 "구매 확정할 품목" + 대상). 슬롯이 있으면 prop보다 우선한다.
const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    description?: string
    confirmLabel: string
    cancelLabel?: string
    tone?: "primary" | "danger"
    pending?: boolean
    contentTestId?: string
    noticeTestId?: string
    cancelTestId?: string
    confirmTestId?: string
  }>(),
  {
    description: "",
    cancelLabel: "취소",
    tone: "primary",
    pending: false,
    contentTestId: "dialog-confirm",
    noticeTestId: undefined,
    cancelTestId: undefined,
    confirmTestId: "dialog-confirm-action",
  },
)
const emit = defineEmits<{ "update:open": [open: boolean]; confirm: [] }>()

function onOpenChange(value: boolean): void {
  if (!value && props.pending) return
  emit("update:open", value)
}
function preventWhilePending(event: Event): void {
  if (props.pending) event.preventDefault()
}
</script>

<template>
  <Dialog :open="open" @update:open="onOpenChange">
    <DialogContent
      :show-close="false"
      class="md:max-w-md"
      :data-testid="contentTestId"
      @escape-key-down="preventWhilePending"
      @pointer-down-outside="preventWhilePending"
      @interact-outside="preventWhilePending"
    >
      <DialogHeader class="pr-6 md:pr-8">
        <DialogTitle>{{ title }}</DialogTitle>
        <DialogDescription><slot name="description">{{ description }}</slot></DialogDescription>
      </DialogHeader>
      <div v-if="$slots.notice" class="mt-4 px-6 md:px-8">
        <RenewNotice tone="warning" :data-testid="noticeTestId" data-slot="confirm-notice">
          <p class="whitespace-pre-line"><slot name="notice" /></p>
        </RenewNotice>
      </div>
      <DialogFooter class="mt-6 border-t-0 pt-0 md:pt-0">
        <DialogClose class="btn btn-secondary btn-md" :disabled="pending" :data-testid="cancelTestId">
          {{ cancelLabel }}
        </DialogClose>
        <button
          type="button"
          :class="['btn btn-md', tone === 'danger' ? 'btn-danger' : 'btn-primary']"
          :disabled="pending"
          :aria-busy="pending"
          :data-testid="confirmTestId"
          @click="emit('confirm')"
        >
          <span
            v-if="pending"
            class="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent motion-reduce:animate-none"
            aria-hidden="true"
            data-slot="confirm-spinner"
          ></span>
          {{ confirmLabel }}
        </button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
