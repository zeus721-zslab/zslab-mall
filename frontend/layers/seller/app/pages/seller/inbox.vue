<script setup lang="ts">
import { mdiArrowLeft } from '@mdi/js'
import { useDisplay } from 'vuetify'
import type { InboxTypeCount } from '~/types/inbox'
import { type InboxItemType, type InboxTab, inboxItemTypeLabel } from '~/lib/constants/inbox'
import { type InboxQueryState, parseInboxQuery, toInboxRouteQuery } from '~/lib/inbox-query'
import { type InboxItem, inboxTotal, nextInboxSelection, normalizeInboxItem } from '~/lib/inbox-view'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerInbox } from '#layers/seller/app/composables/useSellerInbox'
import { useSellerInboxBadge } from '#layers/seller/app/composables/useSellerInboxBadge'
import { useSellerInboxStream } from '#layers/seller/app/composables/useSellerInboxStream'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '인박스 · zslab-mall 셀러' })

/**
 * 셀러 운영 인박스(D-248 · FE-101 · 관리자 pages/admin/inbox 복제 · 레이어 격리). 자기 셀러의 발송 대기 · Q&A 미답변 · 장기 배송중 · 재고 임박을
 * 기한 순으로 본다. URL query(탭·유형·선택)가 단일 소스이고 PC 2단 · 모바일 오른쪽 드로어. 갱신은 변경 신호 구독(FE-103 — 신호·재연결·KST 자정·화면 보임) · 새로고침 버튼.
 */
const route = useRoute()
const router = useRouter()
const inboxApi = useSellerInbox()
const badge = useSellerInboxBadge()
const { mdAndUp } = useDisplay()

const query = computed<InboxQueryState>(() => parseInboxQuery(route.query, 'SELLER'))

const items = ref<InboxItem[]>([])
const counts = ref<InboxTypeCount[]>([])
const truncated = ref(false)
const loading = ref(false)
const loadError = ref<string | null>(null)
const nowMs = ref(Date.now())
let requestSequence = 0

const selectedItem = computed<InboxItem | null>(() => items.value.find((item) => item.key === query.value.selected) ?? null)

function applyQuery(patch: Partial<InboxQueryState>): void {
  void router.replace({ query: toInboxRouteQuery({ ...query.value, ...patch }) })
}

// ---------- 모바일 드로어 열기·닫기(FE-102 · 관리자 인박스와 동일) ----------
// 모바일에서 목록 → 상세를 열 때만 기록 1건을 쌓아(push) 뒤로가기가 드로어만 닫게 한다. 드로어 안에서 다음 항목으로 바뀔 때·PC는 replace.
// 닫기 버튼·스크림·스와이프는 push로 연 경우 history.back으로 그 기록을 되돌리고(목록 URL 중복 기록 방지), 아니면 selected만 지운다.
let openedByPush = false

function openOnMobile(key: string): void {
  openedByPush = true
  void router.push({ query: toInboxRouteQuery({ ...query.value, selected: key }) })
}

function closeDrawer(): void {
  if (query.value.selected === null) return
  if (openedByPush) {
    openedByPush = false
    router.back()
    return
  }
  applyQuery({ selected: null })
}

// 브라우저 뒤로가기로 selected가 사라지면 push 기록도 소비된 것이다.
watch(() => query.value.selected, (selected) => {
  if (selected === null) openedByPush = false
})

/** 선택을 목록과 맞춘다: PC는 선택이 없거나 사라졌으면 첫 항목, 모바일은 사라진 선택만 닫는다. */
function reconcileSelection(): void {
  const selected = query.value.selected
  const exists = selected !== null && items.value.some((item) => item.key === selected)
  if (exists) return
  if (mdAndUp.value) {
    const first = items.value[0]?.key ?? null
    if (first !== selected) applyQuery({ selected: first })
  } else if (selected !== null) {
    closeDrawer()
  }
}

async function load(): Promise<boolean> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await inboxApi.list(query.value)
    if (sequence !== requestSequence) return false // 늦게 도착한 이전 요청은 버린다
    items.value = response.items.map(normalizeInboxItem)
    counts.value = response.counts
    truncated.value = response.truncated
    nowMs.value = Date.now()
    if (query.value.tab === 'TODAY') badge.set(inboxTotal(response.counts))
    return true
  } catch (error) {
    if (sequence !== requestSequence) return false
    loadError.value = toSellerErrorMessage(error)
    return false
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

watch(() => [query.value.tab, query.value.type], async () => {
  if (await load()) reconcileSelection()
}, { immediate: true })

async function reload(): Promise<void> {
  if (await load()) reconcileSelection()
}

async function onProcessed(): Promise<void> {
  const previousKeys = items.value.map((item) => item.key)
  const processedKey = query.value.selected
  if (!(await load())) return
  const next = nextInboxSelection(previousKeys, items.value.map((item) => item.key), processedKey)
  // 모바일 드로어 안에서 다음 항목으로 바뀌는 것은 replace(기록을 쌓지 않음) · 남은 항목이 없으면 드로어를 닫는다.
  if (next === null && !mdAndUp.value) closeDrawer()
  else applyQuery({ selected: next })
}

function onTab(tab: InboxTab): void {
  applyQuery({ tab, selected: null })
}
function onType(type: InboxItemType | null): void {
  applyQuery({ type, selected: null })
}
function onSelect(key: string): void {
  if (!mdAndUp.value && query.value.selected === null) openOnMobile(key)
  else applyQuery({ selected: key })
}

// 선택이 URL query라 temporary 드로어의 라우트 변경 자동 닫힘을 템플릿에서 disable-route-watcher로 끈다(관리자 인박스와 동일).
// 스크림·스와이프·Esc로 닫혀도 같은 닫기 경로를 쓴다.
const drawerOpen = computed<boolean>({
  get: () => !mdAndUp.value && selectedItem.value !== null,
  set: (open) => { if (!open) closeDrawer() },
})

// 갱신: 변경 신호 구독(신호·재연결·KST 자정·화면 보임 — 사이드바와 같은 연결) · 1분마다 남은 시간 표시
useSellerInboxStream(reload)

let clockTimer: ReturnType<typeof setInterval> | null = null
const CLOCK_TICK_MS = 60 * 1000

onMounted(() => {
  clockTimer = setInterval(() => { nowMs.value = Date.now() }, CLOCK_TICK_MS)
})
onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<template>
  <div>
    <SellerPageHeader
      title="인박스"
      description="처리할 일을 기한 순으로 모았습니다. 오늘 탭은 기한이 오늘이거나 지난 항목, 예정 탭은 내일 이후 항목입니다. 보류한 항목은 정한 시각까지 숨겨집니다."
    />
    <v-row>
      <v-col cols="12" md="5">
        <SellerInboxList
          :items="items"
          :counts="counts"
          :tab="query.tab"
          :type="query.type"
          :selected-key="query.selected"
          :loading="loading"
          :error="loadError"
          :truncated="truncated"
          :now-ms="nowMs"
          @update:tab="onTab"
          @update:type="onType"
          @select="onSelect"
          @retry="reload"
          @refresh="reload"
        />
      </v-col>
      <v-col v-if="mdAndUp" md="7">
        <SellerInboxDetail :item="selectedItem" :now-ms="nowMs" @processed="onProcessed" />
      </v-col>
    </v-row>
    <v-navigation-drawer
      v-if="!mdAndUp"
      v-model="drawerOpen"
      location="end"
      temporary
      disable-route-watcher
      width="360"
      data-testid="inbox-detail-drawer"
    >
      <!-- 스와이프 단서(FE-102): Vuetify temporary 드로어는 바깥쪽으로 끌면 닫힌다(touchless 아님) — 왼쪽 가장자리 손잡이 막대로 알린다 -->
      <div class="slr-inbox-drawer-handle" aria-hidden="true" data-testid="inbox-drawer-handle" />
      <div class="slr-inbox-drawer-header" data-testid="inbox-drawer-header">
        <v-btn
          variant="text"
          size="small"
          :prepend-icon="mdiArrowLeft"
          aria-label="목록으로 돌아가기"
          data-testid="inbox-drawer-back"
          @click="closeDrawer"
        >목록</v-btn>
        <span class="slr-inbox-drawer-title text-body-2 font-weight-medium">{{ selectedItem ? inboxItemTypeLabel(selectedItem.type) : '' }}</span>
      </div>
      <SellerInboxDetail :item="selectedItem" :now-ms="nowMs" @processed="onProcessed" />
    </v-navigation-drawer>
  </div>
</template>

<style scoped>
/* 머리줄은 드로어 내용(스크롤 영역) 위에 고정한다. 버튼 폭만큼 오른쪽 여백을 두어 유형명을 가운데에 둔다(관리자 인박스와 동일). */
.slr-inbox-drawer-header {
  position: sticky;
  top: 0;
  z-index: 1;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 8px 8px 8px 12px;
  background: rgb(var(--v-theme-surface));
  border-bottom: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
}
.slr-inbox-drawer-header > :first-child {
  justify-self: start;
}
.slr-inbox-drawer-title {
  text-align: center;
}
.slr-inbox-drawer-handle {
  position: absolute;
  top: 50%;
  left: 4px;
  z-index: 2;
  width: 4px;
  height: 40px;
  border-radius: 2px;
  background: rgba(var(--v-theme-on-surface), 0.24);
  transform: translateY(-50%);
  pointer-events: none;
}
</style>
