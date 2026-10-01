<script setup lang="ts">
import { useDisplay } from 'vuetify'
import type { InboxTypeCount } from '~/types/inbox'
import type { InboxItemType, InboxTab } from '~/lib/constants/inbox'
import { type InboxQueryState, parseInboxQuery, toInboxRouteQuery } from '~/lib/inbox-query'
import { type InboxItem, inboxTotal, msUntilNextKstMidnight, nextInboxSelection, normalizeInboxItem } from '~/lib/inbox-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminInbox } from '#layers/admin/app/composables/useAdminInbox'
import { useAdminInboxBadge } from '#layers/admin/app/composables/useAdminInboxBadge'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '인박스 · zslab-mall 관리자' })

/**
 * 관리자 운영 인박스(D-248 · FE-101). URL query(탭·유형·선택)가 단일 소스이고, PC(md 이상)는 목록 + 상세 2단, 모바일은 목록 전체 폭 + 오른쪽
 * 드로어 상세다. 처리·보류가 끝나면 다시 읽고 같은 탭의 다음 항목을 고른다. 갱신은 창 포커스 · 다음 KST 자정 · 새로고침 버튼(SSE는 P1b-2).
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

function applyQuery(patch: Partial<InboxQueryState>): void {
  void router.replace({ query: toInboxRouteQuery({ ...query.value, ...patch }) })
}

/** 선택을 목록과 맞춘다: PC는 선택이 없거나 사라졌으면 첫 항목, 모바일은 사라진 선택만 지운다(드로어를 저절로 열지 않는다). */
function reconcileSelection(): void {
  const selected = query.value.selected
  const exists = selected !== null && items.value.some((item) => item.key === selected)
  if (exists) return
  if (mdAndUp.value) {
    const first = items.value[0]?.key ?? null
    if (first !== selected) applyQuery({ selected: first })
  } else if (selected !== null) {
    applyQuery({ selected: null })
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
    loadError.value = toAdminErrorMessage(error)
    return false
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

// 탭·유형이 바뀌면 다시 읽는다(선택만 바뀌면 읽지 않는다).
watch(() => [query.value.tab, query.value.type], async () => {
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
  applyQuery({ selected: next })
}

function onTab(tab: InboxTab): void {
  applyQuery({ tab, selected: null })
}
function onType(type: InboxItemType | null): void {
  applyQuery({ type, selected: null })
}
function onSelect(key: string): void {
  applyQuery({ selected: key })
}

// 선택이 URL query라 선택할 때마다 라우트가 바뀐다 — temporary 드로어는 라우트 변경 시 스스로 닫히므로(Vuetify route watcher) 템플릿에서
// disable-route-watcher로 끄고, 열림은 선택 유무로만 정한다.
const drawerOpen = computed<boolean>({
  get: () => !mdAndUp.value && selectedItem.value !== null,
  set: (open) => { if (!open) applyQuery({ selected: null }) },
})

// ---------- 갱신: 창 포커스 · 다음 KST 자정 · 1분마다 남은 시간 표시 ----------
let midnightTimer: ReturnType<typeof setTimeout> | null = null
let clockTimer: ReturnType<typeof setInterval> | null = null
const CLOCK_TICK_MS = 60 * 1000
const MIDNIGHT_SLACK_MS = 1000

function scheduleMidnightReload(): void {
  if (midnightTimer) clearTimeout(midnightTimer)
  midnightTimer = setTimeout(() => {
    void reload()
    scheduleMidnightReload()
  }, msUntilNextKstMidnight(Date.now()) + MIDNIGHT_SLACK_MS)
}

function onFocus(): void {
  void reload()
}
function onVisibility(): void {
  if (document.visibilityState === 'visible') void reload()
}

onMounted(() => {
  window.addEventListener('focus', onFocus)
  document.addEventListener('visibilitychange', onVisibility)
  scheduleMidnightReload()
  clockTimer = setInterval(() => { nowMs.value = Date.now() }, CLOCK_TICK_MS)
})
onBeforeUnmount(() => {
  window.removeEventListener('focus', onFocus)
  document.removeEventListener('visibilitychange', onVisibility)
  if (midnightTimer) clearTimeout(midnightTimer)
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
        <AdminInboxList
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
      <AdminInboxDetail :item="selectedItem" :now-ms="nowMs" @processed="onProcessed" />
    </v-navigation-drawer>
  </div>
</template>
