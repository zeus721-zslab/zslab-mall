<script setup lang="ts">
import { VueDraggable } from 'vue-draggable-plus'
import { mdiCloudUploadOutline, mdiClose, mdiRefresh, mdiStar, mdiStarOutline, mdiTrashCanOutline } from '@mdi/js'
import type { SellerFormImage } from '#layers/seller/app/types/seller-product-form'
import type { SellerProductImageType } from '#layers/seller/app/lib/constants/seller-product'
import { ensureMainImage, nextLocalId } from '#layers/seller/app/lib/seller-product-form'
import {
  ACCEPT_ATTRIBUTE,
  firstUploadResult,
  precheckFiles,
  uploadItemFailureMessage,
  uploadRequestFailureMessage,
} from '#layers/seller/app/lib/seller-image-upload'
import { useSellerProducts } from '#layers/seller/app/composables/useSellerProducts'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'

// 이미지 섹션(Track 90-C-4·관리자 AdminProductImageSection 복제·갤러리/상세 공용). 드롭·클릭 선택 → 사전 검증 → 파일별 1요청 순차 업로드(진행 카드) →
// 성공 시 업로드 응답 url만 폼에 추가(직접 URL 입력 없음·D-174). 정렬은 vue-draggable-plus(터치). 대표는 갤러리 전용 1장(ensureMainImage). 업로드 중이면 부모가 저장을 막는다.
const props = defineProps<{
  imageType: SellerProductImageType
  title: string
  description: string
  /** 전체 이미지 목록(갤러리+상세) — 이 섹션은 자기 타입만 다루고 나머지는 보존한다. */
  images: SellerFormImage[]
}>()

const emit = defineEmits<{
  'update:images': [images: SellerFormImage[]]
  'update:uploading': [uploading: boolean]
}>()

const productsApi = useSellerProducts()
const toast = useSellerToast()

interface PendingUpload {
  localId: string
  file: File
  previewUrl: string
  state: 'queued' | 'uploading' | 'failed'
  message: string
}

const pending = ref<PendingUpload[]>([])
const dragOver = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const preview = ref<SellerFormImage | null>(null)

const mine = computed<SellerFormImage[]>(() => props.images.filter((image) => image.imageType === props.imageType))
const others = computed<SellerFormImage[]>(() => props.images.filter((image) => image.imageType !== props.imageType))
const isGallery = computed(() => props.imageType === 'GALLERY')
const uploading = computed(() => pending.value.some((item) => item.state !== 'failed'))
watch(uploading, (value) => emit('update:uploading', value), { immediate: true })

function commit(nextMine: SellerFormImage[]): void {
  const merged = isGallery.value ? [...nextMine, ...others.value] : [...others.value, ...nextMine]
  emit('update:images', ensureMainImage(merged))
}

/** 드래그 정렬 결과(v-model 갱신값)를 받아 전체 목록에 반영한다. */
function onReorder(next: SellerFormImage[]): void {
  commit(next)
}

function openPicker(): void {
  fileInput.value?.click()
}

function onFilesSelected(event: Event): void {
  const input = event.target as HTMLInputElement
  void addFiles(Array.from(input.files ?? []))
  input.value = ''
}

function onDrop(event: DragEvent): void {
  dragOver.value = false
  void addFiles(Array.from(event.dataTransfer?.files ?? []))
}

async function addFiles(files: File[]): Promise<void> {
  if (files.length === 0) return
  const { accepted, rejected } = precheckFiles(files, mine.value.length + pending.value.length)
  rejected.forEach((item) => toast.warning(`${item.file.name}: ${item.reason}`))
  const queued: PendingUpload[] = accepted.map((file) => ({
    localId: nextLocalId('u'),
    file,
    previewUrl: URL.createObjectURL(file),
    state: 'queued',
    message: '',
  }))
  pending.value = [...pending.value, ...queued]
  for (const item of queued) {
    await uploadOne(item.localId)
  }
}

async function uploadOne(localId: string): Promise<void> {
  const item = pending.value.find((candidate) => candidate.localId === localId)
  if (!item) return
  item.state = 'uploading'
  item.message = ''
  try {
    const response = await productsApi.uploadImages([item.file])
    const result = firstUploadResult(response)
    if (!result || !result.success || !result.url) {
      // 파일별 실패(형식·크기·해상도)는 카드에 남기고 토스트로도 개별 안내한다(요청은 200).
      item.state = 'failed'
      item.message = uploadItemFailureMessage(result?.code, result?.message)
      toast.warning(`${item.file.name}: ${item.message}`)
      return
    }
    commit([...mine.value, {
      localId: nextLocalId('i'),
      imageId: null,
      imageUrl: result.url,
      thumbnailUrl: result.thumbnailUrl ?? result.url,
      imageType: props.imageType,
      main: false,
    }])
    URL.revokeObjectURL(item.previewUrl)
    pending.value = pending.value.filter((candidate) => candidate.localId !== localId)
  } catch (error) {
    item.state = 'failed'
    item.message = uploadRequestFailureMessage(error)
    toast.danger(`${item.file.name}: ${item.message}`)
  }
}

function removePending(localId: string): void {
  const item = pending.value.find((candidate) => candidate.localId === localId)
  if (item) URL.revokeObjectURL(item.previewUrl)
  pending.value = pending.value.filter((candidate) => candidate.localId !== localId)
}

function setMain(localId: string): void {
  commit(mine.value.map((image) => ({ ...image, main: image.localId === localId })))
}

function removeImage(localId: string): void {
  commit(mine.value.filter((image) => image.localId !== localId))
}
</script>

<template>
  <div :data-testid="`image-section-${imageType}`">
    <div class="d-flex align-baseline justify-space-between flex-wrap ga-2 mb-2">
      <div>
        <p class="text-subtitle-2 font-weight-bold mb-0">{{ title }} <span class="text-medium-emphasis font-weight-regular">({{ mine.length }})</span></p>
        <p class="text-caption text-medium-emphasis mb-0">{{ description }}</p>
      </div>
    </div>

    <div
      class="slr-dropzone"
      :class="{ 'slr-dropzone--over': dragOver }"
      role="button"
      tabindex="0"
      :data-testid="`dropzone-${imageType}`"
      @click="openPicker"
      @keydown.enter.prevent="openPicker"
      @dragover.prevent="dragOver = true"
      @dragleave="dragOver = false"
      @drop.prevent="onDrop"
    >
      <v-icon :icon="mdiCloudUploadOutline" size="28" class="text-medium-emphasis" />
      <p class="text-body-2 mb-0 mt-2">이미지를 끌어다 놓거나 클릭해서 선택 (jpg·png·webp · 파일당 10MB · 최대 20장)</p>
      <input ref="fileInput" type="file" :accept="ACCEPT_ATTRIBUTE" multiple hidden :data-testid="`file-input-${imageType}`" @change="onFilesSelected">
    </div>

    <VueDraggable
      :model-value="mine"
      :animation="150"
      handle=".slr-image-card__drag"
      class="slr-image-grid mt-3"
      :data-testid="`image-grid-${imageType}`"
      @update:model-value="onReorder"
    >
      <div v-for="image in mine" :key="image.localId" class="slr-image-card" :class="{ 'slr-image-card--main': image.main }" data-testid="image-card">
        <img :src="image.thumbnailUrl || image.imageUrl" :alt="image.imageUrl" class="slr-image-card__img slr-image-card__drag" @click="preview = image">
        <div class="slr-image-card__bar">
          <v-btn
            v-if="isGallery"
            :icon="image.main ? mdiStar : mdiStarOutline"
            size="x-small"
            variant="text"
            :color="image.main ? 'warning' : undefined"
            :aria-label="image.main ? '대표 이미지' : '대표로 지정'"
            data-testid="image-main"
            @click.stop="setMain(image.localId)"
          />
          <span v-if="image.main" class="text-caption font-weight-medium">대표</span>
          <v-spacer />
          <v-btn :icon="mdiTrashCanOutline" size="x-small" variant="text" aria-label="삭제" data-testid="image-remove" @click.stop="removeImage(image.localId)" />
        </div>
      </div>
    </VueDraggable>

    <div v-if="pending.length" class="slr-image-grid mt-3" :data-testid="`pending-grid-${imageType}`">
      <div v-for="item in pending" :key="item.localId" class="slr-image-card slr-image-card--pending" :data-state="item.state" data-testid="pending-card">
        <img :src="item.previewUrl" :alt="item.file.name" class="slr-image-card__img">
        <v-progress-linear v-if="item.state !== 'failed'" indeterminate color="primary" height="4" />
        <div class="slr-image-card__bar">
          <span class="text-caption text-truncate" :title="item.file.name">
            {{ item.state === 'queued' ? '대기' : item.state === 'uploading' ? '업로드 중…' : item.message }}
          </span>
          <v-spacer />
          <template v-if="item.state === 'failed'">
            <v-btn :icon="mdiRefresh" size="x-small" variant="text" aria-label="재시도" data-testid="pending-retry" @click="uploadOne(item.localId)" />
            <v-btn :icon="mdiClose" size="x-small" variant="text" aria-label="제거" data-testid="pending-remove" @click="removePending(item.localId)" />
          </template>
        </div>
      </div>
    </div>

    <v-dialog :model-value="preview !== null" max-width="720" @update:model-value="(value: boolean) => !value && (preview = null)">
      <v-card v-if="preview" data-testid="image-preview-dialog">
        <v-img :src="preview.imageUrl" max-height="70vh" contain />
        <v-card-actions class="px-4 pb-3">
          <span class="text-caption text-medium-emphasis text-truncate">{{ preview.imageUrl }}</span>
          <v-spacer />
          <v-btn variant="text" @click="preview = null">닫기</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>
