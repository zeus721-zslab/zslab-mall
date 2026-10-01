<script setup lang="ts">
import { mdiArrowLeft } from '@mdi/js'
import { useDisplay } from 'vuetify'
import type { InboxTypeCount } from '~/types/inbox'
import { type InboxItemType, type InboxTab, inboxItemTypeLabel } from '~/lib/constants/inbox'
import { type InboxQueryState, parseInboxQuery, toInboxRouteQuery } from '~/lib/inbox-query'
import { type InboxItem, inboxTotal, nextInboxSelection, normalizeInboxItem } from '~/lib/inbox-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminInbox } from '#layers/admin/app/composables/useAdminInbox'
import { useAdminInboxBadge } from '#layers/admin/app/composables/useAdminInboxBadge'
import { useAdminInboxStream } from '#layers/admin/app/composables/useAdminInboxStream'
import { inboxBulkKind } from '#layers/admin/app/lib/admin-inbox-view'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '인박스 · zslab-mall 관리자' })

/**
 * 관리자 운영 인박스(D-248 · FE-101). URL query(탭·유형·선택)가 단일 소스이고, PC(md 이상)는 목록 + 상세 2단, 모바일은 목록 전체 폭 + 오른쪽
 * 드로어 상세다. 처리·보류가 끝나면 다시 읽고 같은 탭의 다음 항목을 고른다. 갱신은 변경 신호 구독(FE-103 — 신호·재연결·KST 자정·화면 보임) · 새로고침 버튼.
 */
const route = useRoute()
const router = useRouter()
const inboxApi = useAdminInbox()
const badge = useAdminInboxBadge()
const { mdAndUp } = useDisplay()

const query = computed<InboxQueryState>(() => parseInboxQuery(route.query, 'ADMIN'))

const items = ref<InboxItem[]>([])
const counts = ref<InboxTypeCount[]>([])
const truncated = ref(false)
const loading = ref(false)
const loadError = ref<string | null>(null)
const nowMs = ref(Date.now())
let requestSequence = 0

const selectedItem = computed<InboxItem | null>(() => items.value.find((item) => item.key === query.value.selected) ?? null)

// 일괄 처리 선택(행 키 · 클레임 승인 D-250 · 셀러 독촉 D-252 — 한 번에 한 종류). 상세 선택과 별개이며 탭·유형이 바뀌면 비운다 · 재조회로 사라진 행은 빠진다.
const bulkSelected = ref<string[]>([])
const bulkItems = computed<InboxItem[]>(() => items.value.filter((item) => bulkSelected.value.includes(item.key)))
const claimBulkItems = computed<InboxItem[]>(() => bulkItems.value.filter((item) => inboxBulkKind(item) === 'CLAIM_APPROVE'))
const nudgeBulkItems = computed<InboxItem[]>(() => bulkItems.value.filter((item) => inboxBulkKind(item) === 'SELLER_NUDGE'))

async function onBulkDone(): Promise<void> {
  bulkSelected.value = []
  await reload()
}

function applyQuery(patch: Partial<InboxQueryState>): void {
  void router.replace({ query: toInboxRouteQuery({ ...query.value, ...patch }) })
}

// ---------- 모바일 드로어 열기·닫기(FE-102) ----------
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

/** 선택을 목록과 맞춘다: PC는 선택이 없거나 사라졌으면 첫 항목, 모바일은 사라진 선택만 닫는다(드로어를 저절로 열지 않는다). */
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
    bulkSelected.value = bulkSelected.value.filter((key) => items.value.some((item) => item.key === key && inboxBulkKind(item) !== null))
    counts.value = response.counts
    truncated.value = response.truncated
    nowMs.value = Date.now()
    if (query.value.tab === 'TODAY') badge.set(inboxTotal(response.counts))
    return true
  } catch (error) {
    if (sequence !== requestSequence) return false
    loadError.value = toAdminErrorMessage(error)
    return false
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

// 탭·유형이 바뀌면 다시 읽는다(선택만 바뀌면 읽지 않는다).
watch(() => [query.value.tab, query.value.type], async () => {
  bulkSelected.value = []
  if (await load()) reconcileSelection()
}, { immediate: true })

async function reload(): Promise<void> {
  if (await load()) reconcileSelection()
}

/** 처리·보류 후: 다시 읽고, 처리한 항목 다음 것을 고른다(같은 탭). */
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

// 선택이 URL query라 선택할 때마다 라우트가 바뀐다 — temporary 드로어는 라우트 변경 시 스스로 닫히므로(Vuetify route watcher) 템플릿에서
// disable-route-watcher로 끄고, 열림은 선택 유무로만 정한다. 스크림·스와이프·Esc로 닫혀도 같은 닫기 경로를 쓴다.
const drawerOpen = computed<boolean>({
  get: () => !mdAndUp.value && selectedItem.value !== null,
  set: (open) => { if (!open) closeDrawer() },
})

// ---------- 갱신: 변경 신호 구독(신호·재연결·KST 자정·화면 보임 — 사이드바와 같은 연결) · 1분마다 남은 시간 표시 ----------
useAdminInboxStream(reload)

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
    <AdminPageHeader
      title="인박스"
      description="처리할 일을 기한 순으로 모았습니다. 오늘 탭은 기한이 오늘이거나 지난 항목, 예정 탭은 내일 이후 항목입니다. 보류한 항목은 정한 시각까지 숨겨집니다."
    />
    <v-row>
      <v-col cols="12" md="5">
        <AdminInboxClaimBulkApprove :items="claimBulkItems" @done="onBulkDone" @clear="bulkSelected = []" />
        <AdminInboxSellerNudgeBulk :items="nudgeBulkItems" @done="onBulkDone" @clear="bulkSelected = []" />
        <AdminInboxList
          v-model:bulk-selected="bulkSelected"
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
        <AdminInboxDetail :item="selectedItem" :now-ms="nowMs" @processed="onProcessed" />
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
      <div class="adm-inbox-drawer-handle" aria-hidden="true" data-testid="inbox-drawer-handle" />
      <div class="adm-inbox-drawer-header" data-testid="inbox-drawer-header">
        <v-btn
          variant="text"
          size="small"
          :prepend-icon="mdiArrowLeft"
          aria-label="목록으로 돌아가기"
          data-testid="inbox-drawer-back"
          @click="closeDrawer"
        >목록</v-btn>
        <span class="adm-inbox-drawer-title text-body-2 font-weight-medium">{{ selectedItem ? inboxItemTypeLabel(selectedItem.type) : '' }}</span>
      </div>
      <AdminInboxDetail :item="selectedItem" :now-ms="nowMs" @processed="onProcessed" />
    </v-navigation-drawer>
  </div>
</template>

<style scoped>
/* 머리줄은 드로어 내용(스크롤 영역) 위에 고정한다. 버튼 폭만큼 오른쪽 여백을 두어 유형명을 가운데에 둔다. */
.adm-inbox-drawer-header {
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
.adm-inbox-drawer-header > :first-child {
  justify-self: start;
}
.adm-inbox-drawer-title {
  text-align: center;
}
.adm-inbox-drawer-handle {
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
