<script setup lang="ts">
import { useDisplay } from 'vuetify'
import type { InboxTypeCount } from '~/types/inbox'
import type { InboxItemType, InboxTab } from '~/lib/constants/inbox'
import { type InboxQueryState, parseInboxQuery, toInboxRouteQuery } from '~/lib/inbox-query'
import { type InboxItem, inboxTotal, msUntilNextKstMidnight, nextInboxSelection, normalizeInboxItem } from '~/lib/inbox-view'
import { toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerInbox } from '#layers/seller/app/composables/useSellerInbox'
import { useSellerInboxBadge } from '#layers/seller/app/composables/useSellerInboxBadge'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '인박스 · zslab-mall 셀러' })

/**
 * 셀러 운영 인박스(D-248 · FE-101 · 관리자 pages/admin/inbox 복제 · 레이어 격리). 자기 셀러의 발송 대기 · Q&A 미답변 · 장기 배송중 · 재고 임박을
 * 기한 순으로 본다. URL query(탭·유형·선택)가 단일 소스이고 PC 2단 · 모바일 오른쪽 드로어. 갱신은 창 포커스 · 다음 KST 자정 · 새로고침 버튼.
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

/** 선택을 목록과 맞춘다: PC는 선택이 없거나 사라졌으면 첫 항목, 모바일은 사라진 선택만 지운다. */
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

// 선택이 URL query라 temporary 드로어의 라우트 변경 자동 닫힘을 템플릿에서 disable-route-watcher로 끈다(관리자 인박스와 동일).
const drawerOpen = computed<boolean>({
  get: () => !mdAndUp.value && selectedItem.value !== null,
  set: (open) => { if (!open) applyQuery({ selected: null }) },
})

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
      <SellerInboxDetail :item="selectedItem" :now-ms="nowMs" @processed="onProcessed" />
    </v-navigation-drawer>
  </div>
</template>
