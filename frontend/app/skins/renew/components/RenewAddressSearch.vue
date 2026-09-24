<script setup lang="ts">
import { Search, X } from '@lucide/vue'
import { loadPostcode, toAddressFields, type PostcodeData, type PostcodeTheme } from '../postcode-loader'
import RenewNotice from './RenewNotice.vue'

// renew 주소 검색(FE-78 A-2): 폼에는 "주소 검색" 버튼 또는 선택한 주소 요약 카드만 두고, 검색은 공용 모달 안 카카오 우편번호 임베드로 한다.
// 고르면 모달을 닫고 폼 값만 채운다(vm 계약 불변). 서비스 로드 실패·타임아웃이면 모달에서 "다시 시도" 또는 "직접 입력"을 고르게 하고,
// 직접 입력이면 manualEntry를 켜 부모가 우편번호·도로명·지번 입력칸을 펼친다(주문이 막히지 않게).
const props = defineProps<{
  // 주소를 고른 뒤 포커스를 옮길 상세 주소 입력칸 id.
  detailInputId: string
  // 폼 모달 안에서 쓸 때(배송지 관리 · FE-79): 아래 모달이 이미 배경을 어둡게 하므로 검색 모달 오버레이는 투명(어둡기 1단계 유지).
  nested?: boolean
}>()

const zonecode = defineModel<string>('zonecode', { required: true })
const addressRoad = defineModel<string>('addressRoad', { required: true })
const addressJibun = defineModel<string>('addressJibun', { required: true })
const manualEntry = defineModel<boolean>('manualEntry', { default: false })

// main.css [data-skin] 토큰 값과 같게 유지한다(배경 흰색 · 글자 ink · 강조 primary · 테두리 line).
const POSTCODE_THEME: PostcodeTheme = {
  bgColor: '#FFFFFF',
  searchBgColor: '#FFFFFF',
  contentBgColor: '#FFFFFF',
  pageBgColor: '#FFFFFF',
  textColor: '#221F2B',
  queryTextColor: '#221F2B',
  postcodeTextColor: '#5B3FA8',
  emphTextColor: '#5B3FA8',
  outlineColor: '#E4DEF1',
}
// 공용 모달 배치 덮어쓰기: <1024 전체 화면 시트(상·하단 안전 영역) · ≥1024 가운데 520×600.
// cn(tailwind-merge)이 rounded-*-card·max-h-none을 기본값과 충돌로 보지 않아 둘 다 남는다 — 같은 속성 유틸의 출력 순서(이름순)로 덮어쓰기가 이긴다.
const DIALOG_LAYOUT =
  'inset-0 h-dvh max-h-none rounded-t-none pt-[env(safe-area-inset-top,0px)] pb-[env(safe-area-inset-bottom,0px)] md:inset-0 md:h-dvh md:w-full md:max-w-none md:max-h-none md:translate-x-0 md:translate-y-0 md:rounded-none md:pb-[env(safe-area-inset-bottom,0px)] lg:inset-auto lg:left-1/2 lg:top-1/2 lg:h-[600px] lg:max-h-[85dvh] lg:w-[520px] lg:max-w-[calc(100%-4rem)] lg:-translate-x-1/2 lg:-translate-y-1/2 lg:rounded-card lg:py-0'

const open = ref(false)
const loading = ref(false)
const failed = ref(false)
const needsRoadAddress = ref(false)
const embedElement = ref<HTMLElement | null>(null)
// 주소를 골라 닫을 때만 true — 닫힘 자동 포커스(여는 버튼 복귀)를 막고 상세 주소로 옮긴다.
let focusDetailOnClose = false

const hasAddress = computed(() => addressRoad.value.trim() !== '')

async function openSearch(): Promise<void> {
  needsRoadAddress.value = false
  open.value = true
  await startEmbed()
}

async function startEmbed(): Promise<void> {
  failed.value = false
  loading.value = true
  try {
    const Postcode = await loadPostcode()
    await nextTick()
    if (!open.value || !embedElement.value) return
    new Postcode({
      width: '100%',
      height: '100%',
      focusInput: true,
      theme: POSTCODE_THEME,
      oncomplete: onComplete,
    }).embed(embedElement.value, { autoClose: false })
  } catch (error) {
    console.warn('[address-search] 우편번호 서비스를 불러오지 못했습니다:', error)
    failed.value = true
  } finally {
    loading.value = false
  }
}

function onComplete(data: PostcodeData): void {
  const fields = toAddressFields(data)
  if (!fields) {
    needsRoadAddress.value = true
    return
  }
  zonecode.value = fields.zonecode
  addressRoad.value = fields.addressRoad
  addressJibun.value = fields.addressJibun
  focusDetailOnClose = true
  open.value = false
}

function enterManually(): void {
  manualEntry.value = true
  open.value = false
}

function onCloseAutoFocus(event: Event): void {
  if (!focusDetailOnClose) return
  focusDetailOnClose = false
  event.preventDefault()
  document.getElementById(props.detailInputId)?.focus()
}
</script>

<template>
  <div>
    <template v-if="!manualEntry">
      <!-- 선택 후: 요약 카드 -->
      <div v-if="hasAddress" class="flex items-start gap-3 rounded-card bg-surface-muted py-4 pl-5 pr-2" data-testid="address-summary">
        <div class="min-w-0 flex-1 space-y-0.5 break-keep">
          <p class="text-small font-semibold tabular-nums text-ink">{{ zonecode }}</p>
          <p class="text-body text-ink">{{ addressRoad }}</p>
          <p v-if="addressJibun" class="text-small text-sub">{{ addressJibun }}</p>
        </div>
        <button type="button" class="btn btn-tertiary btn-sm shrink-0 max-md:min-h-11" @click="openSearch">변경</button>
      </div>
      <!-- 선택 전: 보조 버튼 -->
      <button v-else type="button" class="btn btn-secondary btn-lg w-full" @click="openSearch">
        <Search class="h-5 w-5" aria-hidden="true" />
        주소 검색
      </button>
    </template>

    <Dialog :open="open" @update:open="(value) => (open = value)">
      <DialogContent
        :show-close="false"
        :class="DIALOG_LAYOUT"
        :overlay-class="nested ? 'bg-transparent' : undefined"
        data-testid="address-search-dialog"
        @open-auto-focus.prevent
        @close-auto-focus="onCloseAutoFocus"
      >
        <div class="flex items-center justify-between gap-3 border-b border-line py-1 pl-5 pr-2">
          <!-- 스케일 유틸은 cn(DialogTitle)에 넘기면 글자색으로 합쳐져 사라지므로 안쪽 span에 둔다(FE-77 cn 트랩). -->
          <DialogTitle><span class="text-h3 text-ink">주소 검색</span></DialogTitle>
          <button
            type="button"
            aria-label="닫기"
            class="flex h-11 w-11 items-center justify-center rounded-full text-sub transition duration-fast ease-soft hover:bg-surface-muted hover:text-ink"
            @click="open = false"
          >
            <X class="h-5 w-5" aria-hidden="true" />
          </button>
        </div>
        <DialogDescription class="sr-only">도로명·건물명·지번으로 검색한 뒤 주소를 선택해 주세요.</DialogDescription>

        <RenewNotice v-if="needsRoadAddress" tone="warning" class="mx-5 mt-4">도로명 주소가 있는 결과를 선택해 주세요</RenewNotice>

        <div v-if="failed" class="p-5">
          <RenewNotice tone="warning">주소 검색을 불러오지 못했습니다. 다시 시도하거나 주소를 직접 입력해 주세요.</RenewNotice>
          <div class="mt-4 flex flex-wrap gap-2">
            <button type="button" class="btn btn-secondary btn-md" :disabled="loading" @click="startEmbed">다시 시도</button>
            <button type="button" class="btn btn-tertiary btn-md" @click="enterManually">직접 입력</button>
          </div>
        </div>
        <div v-else ref="embedElement" class="min-h-0 flex-1"></div>
      </DialogContent>
    </Dialog>
  </div>
</template>
