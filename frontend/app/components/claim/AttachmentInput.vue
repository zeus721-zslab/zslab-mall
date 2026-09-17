<script setup lang="ts">
import { CLAIM_ATTACHMENT_MAX } from '~/lib/constants/claim'
import {
  CLAIM_ATTACHMENT_ACCEPT_ATTRIBUTE,
  precheckClaimAttachments,
  uploadItemErrorMessage,
} from '~/lib/utils/claim-attachment'

/** 업로드 완료된 첨부(요청 body attachmentIds 순서 = 배열 순서). */
export interface ClaimAttachedPhoto {
  attachmentId: string
  url: string
  thumbnailUrl: string
  fileName: string
}

/**
 * 반품 사진 첨부 입력(FE-29·사용자 영역·무채색 톤). 파일 선택 즉시 업로드해 attachmentId를 확보하고(BE 2단계 계약·D-171), 부모는 v-model로
 * 완료 목록만 받는다. 삭제는 목록에서만 제거한다(미연결 첨부 정리는 BE 이월 항목). 사전 검증(형식·10MB·5장)은 순수 함수로 분리했다.
 */
const props = defineProps<{
  modelValue: ClaimAttachedPhoto[]
  disabled?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [photos: ClaimAttachedPhoto[]] }>()

const { uploadAttachments } = useClaim()

const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const failures = ref<{ fileName: string; reason: string }[]>([])

const remaining = computed(() => CLAIM_ATTACHMENT_MAX - props.modelValue.length)

function openPicker(): void {
  if (props.disabled || uploading.value || remaining.value <= 0) return
  fileInput.value?.click()
}

function onFilesSelected(event: Event): void {
  const input = event.target as HTMLInputElement
  void addFiles(Array.from(input.files ?? []))
  input.value = ''
}

async function addFiles(files: File[]): Promise<void> {
  if (files.length === 0 || uploading.value) return
  const { accepted, rejected } = precheckClaimAttachments(files, props.modelValue.length)
  failures.value = rejected.map((entry) => ({ fileName: entry.file.name, reason: entry.reason }))
  if (accepted.length === 0) return

  uploading.value = true
  try {
    const response = await uploadAttachments(accepted)
    const added: ClaimAttachedPhoto[] = []
    for (const item of response.results) {
      if (item.success && item.attachmentId && item.url) {
        added.push({ attachmentId: item.attachmentId, url: item.url, thumbnailUrl: item.thumbnailUrl ?? item.url, fileName: item.fileName ?? '' })
      } else {
        failures.value.push({ fileName: item.fileName ?? '', reason: uploadItemErrorMessage(item) })
      }
    }
    if (added.length > 0) emit('update:modelValue', [...props.modelValue, ...added])
  } catch (uploadError) {
    // 413(용량)·400(장수)·401 등 요청 단위 실패: 파일별이 아니라 묶음 실패로 안내한다(.catch(()=>{}) 금지).
    const statusCode = (uploadError as { statusCode?: number }).statusCode
    const reason = statusCode === 413 ? '파일당 10MB를 초과합니다.' : '사진 업로드에 실패했습니다. 잠시 후 다시 시도하세요.'
    failures.value.push(...accepted.map((file) => ({ fileName: file.name, reason })))
  } finally {
    uploading.value = false
  }
}

function remove(index: number): void {
  if (props.disabled || uploading.value) return
  emit('update:modelValue', props.modelValue.filter((_, position) => position !== index))
}
</script>

<template>
  <div class="space-y-2" data-testid="claim-attachment-input">
    <div class="flex items-center justify-between">
      <span class="text-sm font-medium text-ink">사진 첨부 (선택)</span>
      <span class="text-xs text-sub">{{ modelValue.length }}/{{ CLAIM_ATTACHMENT_MAX }}</span>
    </div>

    <ul v-if="modelValue.length > 0" class="grid grid-cols-5 gap-2" data-testid="claim-attachment-list">
      <li v-for="(photo, index) in modelValue" :key="photo.attachmentId" class="relative aspect-square overflow-hidden rounded-control border border-line">
        <img :src="photo.thumbnailUrl" :alt="photo.fileName" class="h-full w-full object-cover">
        <button
          type="button"
          class="absolute right-1 top-1 flex h-6 w-6 items-center justify-center rounded-full bg-white/90 text-xs text-ink shadow"
          :aria-label="`${photo.fileName} 삭제`"
          :disabled="disabled || uploading"
          data-testid="claim-attachment-remove"
          @click="remove(index)"
        >✕</button>
      </li>
    </ul>

    <input
      ref="fileInput"
      type="file"
      :accept="CLAIM_ATTACHMENT_ACCEPT_ATTRIBUTE"
      multiple
      hidden
      data-testid="claim-attachment-file-input"
      @change="onFilesSelected"
    >
    <Button type="button" variant="outline" size="sm" :disabled="disabled || uploading || remaining <= 0" data-testid="claim-attachment-add" @click="openPicker">
      {{ uploading ? '업로드 중…' : remaining <= 0 ? '최대 장수 도달' : '사진 추가' }}
    </Button>
    <p class="text-xs text-sub">jpg·png·webp · 파일당 10MB · 최대 {{ CLAIM_ATTACHMENT_MAX }}장. 상품 불량·오배송 확인에 사용됩니다.</p>

    <ul v-if="failures.length > 0" role="alert" class="space-y-0.5 text-xs text-soldout" data-testid="claim-attachment-failures">
      <li v-for="(failure, index) in failures" :key="`${failure.fileName}-${index}`">{{ failure.fileName }}: {{ failure.reason }}</li>
    </ul>
  </div>
</template>
