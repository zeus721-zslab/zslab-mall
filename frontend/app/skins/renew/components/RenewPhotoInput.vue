<script setup lang="ts">
import { ImagePlus, RotateCw, X } from '@lucide/vue'
import type { ReviewFormPhoto, ReviewPhotoUploadResponse } from '~/types/review'
import { CLAIM_ATTACHMENT_ACCEPT_ATTRIBUTE, CLAIM_ATTACHMENT_MAX_MB, precheckClaimAttachments } from '~/lib/utils/claim-attachment'
import { reviewPhotoItemErrorMessage, reviewPhotoRequestErrorMessage } from '~/lib/utils/review-error'

// renew 사진 입력(Track 106-1). 고른 파일을 한 장씩 차례로 올린다(BE 요청당 1장 — D-237 결정 3). 미리보기는 로컬 object URL이다
// (리뷰에 연결되기 전 사진은 서버가 404 — D-237 결정 4). 칸마다 대기·업로드 중(진행 링)·실패(문구·다시 시도·빼기)를 보인다.
// 성공한 사진만 v-model 목록 끝에 붙는다(순서 = 고른 순서). 사전 검사(형식·5MB·장수)는 클레임 유틸을 그대로 쓴다(한도 동일).
// 업로드 함수는 부모(페이지 vm)가 준다 — 이 컴포넌트는 화면 상태만 가진다.
const props = withDefaults(
  defineProps<{
    modelValue: ReviewFormPhoto[]
    upload: (file: File) => Promise<ReviewPhotoUploadResponse>
    max: number
    disabled?: boolean
  }>(),
  { disabled: false },
)
const emit = defineEmits<{ 'update:modelValue': [photos: ReviewFormPhoto[]]; 'update:busy': [busy: boolean]; 'update:failed': [count: number] }>()

type PendingState = 'queued' | 'uploading' | 'failed'
interface PendingPhoto {
  key: number
  file: File
  previewUrl: string
  state: PendingState
  message: string
}

const fileInput = ref<HTMLInputElement | null>(null)
const pending = ref<PendingPhoto[]>([])
const rejections = ref<string[]>([])
let nextKey = 0
let running = false
// 이 컴포넌트가 만든 object URL(성공 사진 미리보기 포함) — 빠지거나 화면을 떠날 때 해제한다.
const createdUrls = new Set<string>()

const slotsLeft = computed(() => props.max - props.modelValue.length - pending.value.length)
const busy = computed(() => pending.value.some((item) => item.state !== 'failed'))
watch(busy, (value) => emit('update:busy', value))
// 실패 칸 수 — 부모가 남은 실패를 알고 제출을 막는다(빠진 사진이 조용히 제외되지 않게).
const failedCount = computed(() => pending.value.filter((item) => item.state === 'failed').length)
watch(failedCount, (count) => emit('update:failed', count))

function openPicker(): void {
  if (props.disabled || slotsLeft.value <= 0) return
  fileInput.value?.click()
}

function onFilesSelected(event: Event): void {
  const input = event.target as HTMLInputElement
  addFiles(Array.from(input.files ?? []))
  input.value = ''
}

function addFiles(files: File[]): void {
  if (files.length === 0) return
  const { accepted, rejected } = precheckClaimAttachments(files, props.modelValue.length + pending.value.length)
  rejections.value = rejected.map((entry) => `${entry.file.name}: ${entry.reason}`)
  for (const file of accepted) {
    const previewUrl = URL.createObjectURL(file)
    createdUrls.add(previewUrl)
    pending.value.push({ key: nextKey++, file, previewUrl, state: 'queued', message: '' })
  }
  void drain()
}

// 대기 칸을 하나씩 올린다. 이미 돌고 있으면 새로 들어온 칸은 같은 루프가 이어서 처리한다.
async function drain(): Promise<void> {
  if (running) return
  running = true
  try {
    let next = pending.value.find((item) => item.state === 'queued')
    while (next) {
      await uploadOne(next)
      next = pending.value.find((item) => item.state === 'queued')
    }
  } finally {
    running = false
  }
}

async function uploadOne(item: PendingPhoto): Promise<void> {
  item.state = 'uploading'
  try {
    const response = await props.upload(item.file)
    const result = response.results[0]
    if (result?.success && result.attachmentId) {
      pending.value = pending.value.filter((entry) => entry.key !== item.key)
      emit('update:modelValue', [...props.modelValue, { attachmentId: result.attachmentId, previewUrl: item.previewUrl, name: item.file.name }])
      return
    }
    markFailed(item, result ? reviewPhotoItemErrorMessage(result) : '사진을 올리지 못했습니다.')
  } catch (uploadError) {
    // 요청 단위 실패(413·400·503·네트워크)는 이 칸만 실패로 두고 다음 칸을 이어서 올린다(.catch(()=>{}) 금지 — 칸에 문구로 남긴다).
    markFailed(item, reviewPhotoRequestErrorMessage(uploadError))
  }
}

function markFailed(item: PendingPhoto, message: string): void {
  const target = pending.value.find((entry) => entry.key === item.key)
  if (!target) return
  target.state = 'failed'
  target.message = message
}

function retry(item: PendingPhoto): void {
  if (props.disabled) return
  item.state = 'queued'
  item.message = ''
  void drain()
}

function releaseUrl(url: string): void {
  if (!createdUrls.has(url)) return
  URL.revokeObjectURL(url)
  createdUrls.delete(url)
}

function dropPending(item: PendingPhoto): void {
  pending.value = pending.value.filter((entry) => entry.key !== item.key)
  releaseUrl(item.previewUrl)
}

function removePhoto(index: number): void {
  if (props.disabled) return
  const removed = props.modelValue[index]
  emit('update:modelValue', props.modelValue.filter((_, position) => position !== index))
  if (removed) releaseUrl(removed.previewUrl)
}

onBeforeUnmount(() => {
  for (const url of createdUrls) URL.revokeObjectURL(url)
  createdUrls.clear()
})

const TILE = 'relative aspect-square overflow-hidden rounded-[18px] bg-(--image-placeholder)'
const REMOVE_BUTTON =
  'absolute right-1.5 top-1.5 flex h-7 w-7 items-center justify-center rounded-full bg-white text-ink shadow-e1 transition duration-fast ease-soft hover:bg-surface-muted focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-40'
</script>

<template>
  <div class="space-y-3" data-testid="photo-input">
    <ul class="grid grid-cols-3 gap-2 sm:grid-cols-5" aria-label="첨부한 사진">
      <li v-for="(photo, index) in modelValue" :key="photo.attachmentId" :class="TILE" data-testid="photo-input-item">
        <img :src="photo.previewUrl" :alt="photo.name" class="h-full w-full object-cover" />
        <button type="button" :class="REMOVE_BUTTON" :disabled="disabled" :aria-label="`${photo.name} 빼기`" data-testid="photo-input-remove" @click="removePhoto(index)">
          <X class="h-4 w-4" aria-hidden="true" />
        </button>
      </li>

      <li v-for="item in pending" :key="`pending-${item.key}`" :class="TILE" :data-state="item.state" data-testid="photo-input-pending">
        <img :src="item.previewUrl" :alt="item.file.name" :class="['h-full w-full object-cover', item.state === 'failed' ? 'opacity-40' : 'opacity-60']" />
        <!-- 진행 링: 대기 = 옅은 원 · 업로드 중 = 도는 호(움직임 줄이기 설정이면 멈춘 호) -->
        <span v-if="item.state !== 'failed'" class="absolute inset-0 flex items-center justify-center" role="status">
          <svg class="h-10 w-10" viewBox="0 0 40 40" aria-hidden="true">
            <circle cx="20" cy="20" r="16" fill="none" stroke="currentColor" stroke-width="4" class="text-white/70" />
            <circle
              v-if="item.state === 'uploading'"
              cx="20"
              cy="20"
              r="16"
              fill="none"
              stroke="currentColor"
              stroke-width="4"
              stroke-linecap="round"
              stroke-dasharray="70 100"
              class="origin-center animate-spin text-primary motion-reduce:animate-none"
            />
          </svg>
          <span class="sr-only">{{ item.state === 'uploading' ? `${item.file.name} 올리는 중` : `${item.file.name} 대기 중` }}</span>
        </span>
        <div v-else class="absolute inset-0 flex flex-col items-center justify-center gap-1 p-1.5 text-center">
          <button
            type="button"
            class="flex h-9 w-9 items-center justify-center rounded-full bg-white text-primary shadow-e1 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary"
            :disabled="disabled"
            :aria-label="`${item.file.name} 다시 올리기`"
            data-testid="photo-input-retry"
            @click="retry(item)"
          >
            <RotateCw class="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
        <button
          v-if="item.state === 'failed'"
          type="button"
          :class="REMOVE_BUTTON"
          :aria-label="`${item.file.name} 빼기`"
          data-testid="photo-input-drop"
          @click="dropPending(item)"
        >
          <X class="h-4 w-4" aria-hidden="true" />
        </button>
      </li>

      <li v-if="slotsLeft > 0">
        <button
          type="button"
          class="flex aspect-square w-full flex-col items-center justify-center gap-1 rounded-[18px] border-2 border-dashed border-line bg-white text-sub transition duration-fast ease-soft hover:border-primary hover:text-primary focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-40"
          :disabled="disabled"
          data-testid="photo-input-add"
          @click="openPicker"
        >
          <ImagePlus class="h-6 w-6" aria-hidden="true" />
          <span class="text-caption font-semibold tabular-nums">{{ modelValue.length + pending.length }}/{{ max }}</span>
          <span class="sr-only">사진 추가</span>
        </button>
      </li>
    </ul>

    <input
      ref="fileInput"
      type="file"
      :accept="CLAIM_ATTACHMENT_ACCEPT_ATTRIBUTE"
      multiple
      hidden
      data-testid="photo-input-file"
      @change="onFilesSelected"
    />
    <p class="text-caption font-normal text-sub">jpg·png · 파일당 {{ CLAIM_ATTACHMENT_MAX_MB }}MB · 최대 {{ max }}장</p>

    <ul v-if="rejections.length > 0 || pending.some((item) => item.state === 'failed')" role="alert" class="space-y-0.5 text-caption font-semibold text-destructive" data-testid="photo-input-errors">
      <li v-for="(text, index) in rejections" :key="`rejected-${index}`">{{ text }}</li>
      <li v-for="item in pending.filter((entry) => entry.state === 'failed')" :key="`failed-${item.key}`">{{ item.file.name }}: {{ item.message }}</li>
    </ul>
  </div>
</template>
