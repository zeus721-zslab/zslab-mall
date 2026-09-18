<script setup lang="ts">
import type { AdminMe, AdminOperatorSummary } from '#layers/admin/app/types/admin-operator'
import { ADMIN_OPERATOR_PAGE_SIZES } from '#layers/admin/app/lib/constants/admin-operator'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'
import { isSelf, operatorRoleChip, revokeBlockedReason } from '#layers/admin/app/lib/admin-operator-view'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 운영자 목록 표(FE-39). 역할은 배지(복수 가능), 일반회원 겸직은 chip, 자기 행은 "나" chip. 회수 버튼은 revokeBlockedReason으로 비활성·툴팁
 * (SUPER_ADMIN 아님·자기 행). 비활성 버튼은 이벤트를 받지 않으므로 툴팁은 감싸는 span에 건다(AdminCategoryTable 패턴).
 */
defineProps<{
  items: AdminOperatorSummary[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  me: AdminMe | null
  showWithdrawn: boolean
}>()
const emit = defineEmits<{ revoke: [item: AdminOperatorSummary]; 'update:page': [page: number]; 'update:size': [size: number] }>()

const baseHeaders = [
  { title: '이름', key: 'name', sortable: false },
  { title: '이메일', key: 'email', sortable: false },
  { title: '역할', key: 'roles', sortable: false },
  { title: '상태', key: 'status', sortable: false, width: 96 },
  { title: '가입일', key: 'createdAt', sortable: false, width: 150 },
]
const withdrawnHeader = { title: '탈퇴일', key: 'withdrawnAt', sortable: false, width: 150 }
const actionsHeader = { title: '관리', key: 'actions', sortable: false, align: 'end' as const, width: 120 }
</script>

<template>
  <v-data-table-server
    :headers="showWithdrawn ? [...baseHeaders, withdrawnHeader, actionsHeader] : [...baseHeaders, actionsHeader]"
    :items="items"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="ADMIN_OPERATOR_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="userPublicId"
    hover
    class="adm-table adm-table--compact"
    data-testid="admin-operator-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.name`]="{ item }">
      <div class="d-flex align-center ga-2">
        <span class="font-weight-medium" data-testid="row-name">{{ item.name ?? '—' }}</span>
        <v-chip v-if="isSelf(item, me)" :class="semanticChipClass('success')" size="x-small" variant="flat" data-testid="row-self-chip">나</v-chip>
      </div>
    </template>
    <template #[`item.email`]="{ item }">
      <span data-testid="row-email">{{ item.email ?? '—' }}</span>
    </template>
    <template #[`item.roles`]="{ item }">
      <div class="d-flex align-center flex-wrap ga-1" data-testid="row-roles">
        <v-chip
          v-for="role in item.roles"
          :key="role"
          :class="semanticChipClass(operatorRoleChip(role).semantic)"
          size="small"
          variant="flat"
          data-testid="row-role-chip"
        >
          {{ operatorRoleChip(role).text }}
        </v-chip>
        <v-chip v-if="item.hasBuyerRole" size="small" variant="outlined" data-testid="row-buyer-chip">일반회원 겸직</v-chip>
      </div>
    </template>
    <template #[`item.status`]="{ item }">
      <span :class="item.withdrawnAt ? 'text-medium-emphasis' : ''" data-testid="row-status">{{ item.withdrawnAt ? '탈퇴' : '활성' }}</span>
    </template>
    <template #[`item.createdAt`]="{ item }">
      {{ formatDateTime(item.createdAt) }}
    </template>
    <template #[`item.withdrawnAt`]="{ item }">
      <span data-testid="row-withdrawn-at">{{ item.withdrawnAt ? formatDateTime(item.withdrawnAt) : '-' }}</span>
    </template>
    <template #[`item.actions`]="{ item }">
      <div class="d-flex justify-end">
        <v-tooltip :disabled="revokeBlockedReason(item, me) === null" location="top">
          <template #activator="{ props: tooltipProps }">
            <span v-bind="tooltipProps" data-testid="row-revoke-wrapper">
              <v-btn
                size="small"
                variant="outlined"
                color="error"
                :disabled="revokeBlockedReason(item, me) !== null"
                data-testid="row-revoke"
                @click="emit('revoke', item)"
              >
                역할 회수
              </v-btn>
            </span>
          </template>
          <span data-testid="row-revoke-blocked">{{ revokeBlockedReason(item, me) }}</span>
        </v-tooltip>
      </div>
    </template>
    <template #no-data>
      <slot name="empty" />
    </template>
  </v-data-table-server>
</template>
