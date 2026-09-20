<script setup lang="ts">
import { mdiImageBrokenVariant, mdiImageOffOutline } from '@mdi/js'
import { useSellerAttachmentImage } from '#layers/seller/app/composables/useSellerAttachmentImage'

// 클레임 첨부 이미지 1장(Track 90-D-1). `<img :src>` 대신 blob 로더(useSellerAttachmentImage)로 표시한다 — 셀러 세션 쿠키가 이미지 요청에 실리지 않기 때문.
// object URL의 소유권은 이 컴포넌트(의 컴포저블)에만 있다: 부모는 URL 문자열을 보관·revoke하지 않고 어떤 첨부를 확대할지(attachmentId)만 들고,
// 확대 다이얼로그는 variant="preview"로 이 컴포넌트를 다시 렌더한다(외부 검토 r4 반영·이중 revoke·누수 방지).
// thumb: 로딩 스켈레톤 → 성공 시 클릭으로 부모에 open 이벤트 → 실패(404·403 등)는 아이콘 플레이스홀더 + 상태 문구 + 재시도.
// preview: 원본 크기 이미지(클릭 없음)·같은 로딩/실패 표시.
const props = withDefaults(defineProps<{
  url: string
  index: number
  variant?: 'thumb' | 'preview'
}>(), { variant: 'thumb' })

const emit = defineEmits<{
  open: []
}>()

const { src, status, errorStatus, reload } = useSellerAttachmentImage(() => props.url)

const errorLabel = computed(() => {
  if (errorStatus.value === 404 || errorStatus.value === 403) return '열람 권한이 없거나 삭제된 사진'
  return '불러오지 못함'
})
</script>

<template>
  <div v-if="variant === 'preview'" class="slr-claim-preview-frame" data-testid="claim-attachment-preview-image">
    <v-skeleton-loader v-if="status === 'loading'" type="image" class="slr-claim-preview-skeleton" data-testid="claim-attachment-loading" />
    <img v-else-if="status === 'ready' && src" :src="src" :alt="`첨부 사진 ${index + 1} 확대`" class="slr-claim-preview">
    <button v-else type="button" class="slr-claim-thumb__button slr-claim-thumb__error" :title="`${errorLabel} · 다시 시도`" data-testid="claim-attachment-error" @click="reload">
      <v-icon :icon="errorStatus === 404 || errorStatus === 403 ? mdiImageOffOutline : mdiImageBrokenVariant" size="22" class="text-medium-emphasis" />
      <span class="text-caption text-medium-emphasis">{{ errorLabel }}</span>
    </button>
  </div>
  <div v-else class="slr-claim-thumb" data-testid="claim-attachment">
    <v-skeleton-loader v-if="status === 'loading'" type="image" class="slr-claim-thumb__skeleton" data-testid="claim-attachment-loading" />
    <button
      v-else-if="status === 'ready' && src"
      type="button"
      class="slr-claim-thumb__button"
      :title="`첨부 사진 ${index + 1}`"
      data-testid="claim-attachment-thumb"
      @click="emit('open')"
    >
      <img :src="src" :alt="`첨부 사진 ${index + 1}`" class="slr-claim-thumb__img">
    </button>
    <button
      v-else
      type="button"
      class="slr-claim-thumb__button slr-claim-thumb__error"
      :title="`첨부 사진 ${index + 1} · ${errorLabel} · 다시 시도`"
      data-testid="claim-attachment-error"
      @click="reload"
    >
      <v-icon :icon="errorStatus === 404 || errorStatus === 403 ? mdiImageOffOutline : mdiImageBrokenVariant" size="22" class="text-medium-emphasis" />
      <span class="text-caption text-medium-emphasis">{{ errorLabel }}</span>
    </button>
  </div>
</template>
