<script setup lang="ts">
import type { AdminAuditLog } from '#layers/admin/app/types/admin-audit'
import { formatDateTime } from '~/lib/utils/datetime'
import {
  ADMIN_AUDIT_ACTION_LABEL,
  auditActorText,
  auditChangeSummary,
} from '#layers/admin/app/lib/admin-audit-view'

/**
 * 처리 이력 섹션(Track 101-A). 대상 1건의 감사 행을 최신순으로 보여 준다 — 되돌릴 수 없는 조작이 "누가·언제" 이뤄졌는지
 * 화면에서 확인할 수 있게 하는 것이 목적이다(정찰 라운드 3 §2-4: 감사 로그 조회 화면 부재).
 *
 * 조회는 부모가 주입한 loader가 맡는다(클레임·정산이 서로 다른 endpoint를 쓰므로). 첫 페이지만 읽고 더 있으면 건수만 알린다 —
 * 운영에서 한 대상의 이력이 20건을 넘는 경우가 사실상 없어 페이지네이션 UI는 두지 않는다.
 */
const props = defineProps<{
  /** 첫 페이지 로더. 부모가 대상 id를 클로저로 묶어 넘긴다. null이면 아직 대상이 정해지지 않은 상태(조회 안 함). */
  loader: (() => Promise<{ items: AdminAuditLog[]; totalCount: number }>) | null
}>()

const items = ref<AdminAuditLog[]>([])
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref(false)

async function load(): Promise<void> {
  if (!props.loader) return
  loading.value = true
  loadError.value = false
  try {
    const response = await props.loader()
    items.value = response.items
    totalCount.value = response.totalCount
  } catch (error) {
    // 이력은 보조 정보라 본문 조회를 막지 않는다 — 섹션 안에서만 실패를 알리고 재시도를 준다.
    loadError.value = true
    console.warn('[admin] 처리 이력 조회 실패', error)
  } finally {
    loading.value = false
  }
}

watch(() => props.loader, () => { void load() }, { immediate: true })

const hiddenCount = computed(() => Math.max(totalCount.value - items.value.length, 0))
</script>

<template>
  <v-card variant="outlined" class="mb-4" data-testid="admin-audit-log-section">
    <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5 pb-2">처리 이력</v-card-title>
    <v-card-text class="px-5 pb-4">
      <div v-if="loading" class="text-body-2 text-medium-emphasis" data-testid="audit-log-loading">불러오는 중…</div>

      <div v-else-if="loadError" class="d-flex align-center ga-2" data-testid="audit-log-error">
        <span class="text-body-2 text-medium-emphasis">처리 이력을 불러오지 못했습니다.</span>
        <v-btn size="x-small" variant="outlined" data-testid="audit-log-retry" @click="load">다시 시도</v-btn>
      </div>

      <div v-else-if="items.length === 0" class="text-body-2 text-medium-emphasis" data-testid="audit-log-empty">
        기록된 처리 이력이 없습니다.
      </div>

      <template v-else>
        <div
          v-for="log in items"
          :key="log.auditPublicId"
          class="d-flex flex-wrap align-baseline ga-2 py-1"
          data-testid="audit-log-row"
        >
          <span class="text-caption text-medium-emphasis text-no-wrap">{{ formatDateTime(log.occurredAt) }}</span>
          <span
            class="text-body-2 font-weight-medium text-no-wrap"
            :title="log.actorEmail"
            data-testid="audit-log-actor"
          >{{ auditActorText(log.actorRole, log.actorName) }}</span>
          <span class="text-body-2 text-no-wrap">{{ ADMIN_AUDIT_ACTION_LABEL[log.action] }}</span>
          <span class="text-caption text-medium-emphasis" data-testid="audit-log-changes">
            {{ auditChangeSummary(log.changes) || '변경 내역 없음' }}
          </span>
        </div>
        <p v-if="hiddenCount > 0" class="text-caption text-medium-emphasis mb-0 mt-2" data-testid="audit-log-more">
          이전 이력 {{ hiddenCount }}건이 더 있습니다.
        </p>
      </template>
    </v-card-text>
  </v-card>
</template>
