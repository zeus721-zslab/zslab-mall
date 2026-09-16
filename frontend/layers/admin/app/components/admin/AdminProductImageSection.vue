<script setup lang="ts">
import { VueDraggable } from 'vue-draggable-plus'
import { mdiCloudUploadOutline, mdiClose, mdiRefresh, mdiStar, mdiStarOutline, mdiTrashCanOutline } from '@mdi/js'
import type { ProductFormImage } from '#layers/admin/app/types/admin-product-form'
import type { ProductImageType } from '#layers/admin/app/lib/constants/product'
import { ensureMainImage, nextLocalId } from '#layers/admin/app/lib/admin-product-form'
import {
  ACCEPT_ATTRIBUTE,
  firstUploadResult,
  precheckFiles,
  uploadItemFailureMessage,
  uploadRequestFailureMessage,
} from '#layers/admin/app/lib/admin-image-upload'
import { useAdminProducts } from '#layers/admin/app/composables/useAdminProducts'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

// 이미지 섹션(FE-26·갤러리/상세 공용). 드롭·클릭 선택 → 사전 검증 → 파일별 1요청 순차 업로드(진행 상태 카드) → 성공 시 폼 이미지에 추가.
// 정렬은 vue-draggable-plus(sortablejs·터치). 대표는 갤러리 전용 1장(ensureMainImage). 업로드 진행 중이면 부모가 저장을 막는다.
const props = defineProps<{
  imageType: ProductImageType
  title: string
  description: string
  /** 전체 이미지 목록(갤러리+상세) — 이 섹션은 자기 타입만 다루고 나머지는 보존한다. */
  images: ProductFormImage[]
}>()

const emit = defineEmits<{
  'update:images': [images: ProductFormImage[]]
  'update:uploading': [uploading: boolean]
}>()

const productsApi = useAdminProducts()
const toast = useAdminToast()

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
const preview = ref<ProductFormImage | null>(null)

const mine = computed<ProductFormImage[]>(() => props.images.filter((image) => image.imageType === props.imageType))
const others = computed<ProductFormImage[]>(() => props.images.filter((image) => image.imageType !== props.imageType))
const isGallery = computed(() => props.imageType === 'GALLERY')
const uploading = computed(() => pending.value.some((item) => item.state !== 'failed'))
watch(uploading, (value) => emit('update:uploading', value), { immediate: true })

function commit(nextMine: ProductFormImage[]): void {
  const merged = isGallery.value ? [...nextMine, ...others.value] : [...others.value, ...nextMine]
  emit('update:images', ensureMainImage(merged))
}

/** 드래그 정렬 결과(v-model 갱신값)를 받아 전체 목록에 반영한다. */
function onReorder(next: ProductFormImage[]): void {
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
      item.state = 'failed'
      item.message = uploadItemFailureMessage(result?.code, result?.message)
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
      class="adm-dropzone"
      :class="{ 'adm-dropzone--over': dragOver }"
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
      handle=".adm-image-card__drag"
      class="adm-image-grid mt-3"
      :data-testid="`image-grid-${imageType}`"
      @update:model-value="onReorder"
    >
      <div v-for="image in mine" :key="image.localId" class="adm-image-card" :class="{ 'adm-image-card--main': image.main }" data-testid="image-card">
        <img :src="image.thumbnailUrl || image.imageUrl" :alt="image.imageUrl" class="adm-image-card__img adm-image-card__drag" @click="preview = image">
        <div class="adm-image-card__bar">
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

    <div v-if="pending.length" class="adm-image-grid mt-3" :data-testid="`pending-grid-${imageType}`">
      <div v-for="item in pending" :key="item.localId" class="adm-image-card adm-image-card--pending" :data-state="item.state" data-testid="pending-card">
        <img :src="item.previewUrl" :alt="item.file.name" class="adm-image-card__img">
        <v-progress-linear v-if="item.state !== 'failed'" indeterminate color="primary" height="4" />
        <div class="adm-image-card__bar">
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
