<script setup lang="ts">
import { mdiImageBrokenVariant, mdiImageOffOutline } from '@mdi/js'
import { useSellerAttachmentImage } from '#layers/seller/app/composables/useSellerAttachmentImage'

// 클레임 첨부 썸네일 1장(Track 90-D-1). `<img :src>` 대신 blob 로더(useSellerAttachmentImage)로 표시한다 — 셀러 세션 쿠키가 이미지 요청에 실리지 않기 때문.
// 로딩 스켈레톤 → 성공 시 클릭으로 부모(확대 다이얼로그)에 object URL을 넘긴다 → 실패(404·403 등)는 아이콘 플레이스홀더 + 상태 문구.
const props = defineProps<{
  url: string
  index: number
}>()

const emit = defineEmits<{
  open: [objectUrl: string]
}>()

const { src, status, errorStatus, reload } = useSellerAttachmentImage(() => props.url)

const errorLabel = computed(() => {
  if (errorStatus.value === 404 || errorStatus.value === 403) return '열람 권한이 없거나 삭제된 사진'
  return '불러오지 못함'
})
</script>

<template>
  <div class="slr-claim-thumb" data-testid="claim-attachment">
    <v-skeleton-loader v-if="status === 'loading'" type="image" class="slr-claim-thumb__skeleton" data-testid="claim-attachment-loading" />
    <button
      v-else-if="status === 'ready' && src"
      type="button"
      class="slr-claim-thumb__button"
      :title="`첨부 사진 ${index + 1}`"
      data-testid="claim-attachment-thumb"
      @click="emit('open', src)"
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
