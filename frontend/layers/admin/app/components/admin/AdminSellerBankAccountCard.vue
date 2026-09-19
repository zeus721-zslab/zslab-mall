<script setup lang="ts">
import { mdiPlus, mdiStar } from '@mdi/js'
import type { AdminSellerBankAccountRow, AdminSellerDetail } from '#layers/admin/app/types/admin-seller'
import { SELLER_BANK_ACCOUNT_REFERENCED_NOTE, SELLER_BANK_ACCOUNT_REFERENCED_TOOLTIP } from '#layers/admin/app/lib/constants/admin-seller'
import {
  bankAccountStatusLabel,
  bankLabel,
  canEditBankAccount,
  canMakePrimary,
  hasReferencedBankAccount,
  maskedAccountNumber,
} from '#layers/admin/app/lib/admin-seller-bank-view'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 셀러 상세 정산계좌 카드(FE-41·D-188). 계좌 목록(은행·예금주·끝 4자리·주 계좌 배지·상태·등록일)과 등록·수정·주 계좌 전환 액션을 갖고 두 다이얼로그를
 * 소유한다. 성공(201/204) 후 changed를 올려 부모가 상세를 다시 읽는다(주 계좌·경고·정산 지급 가능 여부 갱신). 계좌번호 전체는 응답에 없어 화면
 * 어디에도 없다. 정산 참조 여부는 응답 `referencedBySettlement`(외부 검토 Q6·BE 수정 409와 같은 판정)가 SoT — true면 수정 버튼 비활성 + 툴팁,
 * 조회~요청 사이 변화는 서버 409가 막고 다이얼로그가 토스트 후 stale을 올린다(89-D terminable 선례). 주 계좌 행은 전환 버튼 비활성 + 툴팁.
 */
const props = defineProps<{ detail: AdminSellerDetail }>()
const emit = defineEmits<{ changed: [] }>()

const accounts = computed<AdminSellerBankAccountRow[]>(() => props.detail.bankAccounts)
const hasReferenced = computed(() => hasReferencedBankAccount(accounts.value))

const registerOpen = ref(false)
const editTarget = ref<AdminSellerBankAccountRow | null>(null)
const primaryTarget = ref<AdminSellerBankAccountRow | null>(null)
const dialogOpen = computed(() => registerOpen.value || editTarget.value !== null)

function openRegister(): void {
  editTarget.value = null
  registerOpen.value = true
}
function openEdit(row: AdminSellerBankAccountRow): void {
  registerOpen.value = false
  editTarget.value = row
}
function closeDialog(): void {
  registerOpen.value = false
  editTarget.value = null
}
function onDone(): void {
  closeDialog()
  primaryTarget.value = null
  emit('changed')
}
</script>

<template>
  <v-card class="mb-4" data-testid="seller-bank-account">
    <v-card-title class="d-flex align-center justify-space-between flex-wrap ga-2 pt-4 px-5">
      <span class="text-subtitle-2 font-weight-bold">정산계좌 ({{ accounts.length }})</span>
      <v-btn
        size="small"
        :variant="accounts.length === 0 ? 'flat' : 'outlined'"
        color="primary"
        :prepend-icon="mdiPlus"
        data-testid="seller-bank-register"
        @click="openRegister"
      >계좌 등록</v-btn>
    </v-card-title>
    <v-card-text class="px-5 pb-5">
      <v-table v-if="accounts.length > 0" density="compact" class="adm-table">
        <thead>
          <tr><th>은행</th><th>계좌번호</th><th>예금주</th><th>구분</th><th>상태</th><th>등록일</th><th class="text-right">작업</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in accounts" :key="row.id" :class="{ 'adm-bank-row--primary': row.isPrimary }" data-testid="seller-bank-row">
            <td data-testid="seller-bank-row-bank">{{ bankLabel(row.bankCode) }}</td>
            <td class="font-weight-medium" data-testid="seller-bank-row-number">{{ maskedAccountNumber(row) }}</td>
            <td data-testid="seller-bank-row-holder">{{ row.accountHolder }}</td>
            <td>
              <v-chip v-if="row.isPrimary" size="small" variant="flat" class="adm-chip adm-chip--success" :prepend-icon="mdiStar" data-testid="seller-bank-primary-badge">주 계좌</v-chip>
              <span v-else class="text-medium-emphasis text-caption">보조</span>
            </td>
            <td data-testid="seller-bank-row-status">{{ bankAccountStatusLabel(row.status) }}<span v-if="row.verifiedAt" class="text-medium-emphasis text-caption"> ({{ formatDateTime(row.verifiedAt) }})</span></td>
            <td class="text-medium-emphasis">{{ formatDateTime(row.createdAt) }}</td>
            <td class="text-right text-no-wrap">
              <v-tooltip :disabled="canEditBankAccount(row)" location="top">
                <template #activator="{ props: tooltipProps }">
                  <span v-bind="tooltipProps" data-testid="seller-bank-edit-wrapper">
                    <v-btn size="x-small" variant="text" color="primary" :disabled="!canEditBankAccount(row)" data-testid="seller-bank-edit" @click="openEdit(row)">수정</v-btn>
                  </span>
                </template>
                <span data-testid="seller-bank-edit-blocked">{{ SELLER_BANK_ACCOUNT_REFERENCED_TOOLTIP }}</span>
              </v-tooltip>
              <!-- 비활성 버튼은 이벤트를 받지 않으므로 툴팁은 감싸는 span에 건다(전이 버튼 패턴). -->
              <v-tooltip :disabled="canMakePrimary(row)" location="top">
                <template #activator="{ props: tooltipProps }">
                  <span v-bind="tooltipProps" data-testid="seller-bank-primary-wrapper">
                    <v-btn size="x-small" variant="text" color="primary" :disabled="!canMakePrimary(row)" data-testid="seller-bank-make-primary" @click="primaryTarget = row">주 계좌로</v-btn>
                  </span>
                </template>
                <span>이미 주 정산계좌입니다.</span>
              </v-tooltip>
            </td>
          </tr>
        </tbody>
      </v-table>
      <div v-else class="d-flex flex-column align-start ga-2" data-testid="seller-bank-missing">
        <p class="text-body-2 text-warning mb-0">미등록 — 정산 지급이 차단됩니다.</p>
        <p class="text-caption text-medium-emphasis mb-0">계좌를 등록하면 자동으로 주 정산계좌가 되어 정산 지급이 가능해집니다.</p>
      </div>
      <p v-if="accounts.length > 0" class="text-caption text-medium-emphasis mt-3 mb-0" data-testid="seller-bank-primary-note">
        정산 지급은 <strong>주 계좌</strong>로 이루어집니다. 지급되지 않은 정산은 지급 시점의 주 계좌로, 지급완료된 정산은 지급 당시 계좌로 기록됩니다.
        <span v-if="hasReferenced" data-testid="seller-bank-referenced-note"> {{ SELLER_BANK_ACCOUNT_REFERENCED_NOTE }}</span>
      </p>
    </v-card-text>

    <AdminSellerBankAccountDialog :open="dialogOpen" :detail="detail" :target="editTarget" @done="onDone" @stale="onDone" @cancel="closeDialog" />
    <AdminSellerBankAccountPrimaryDialog :open="primaryTarget !== null" :detail="detail" :target="primaryTarget" @done="onDone" @stale="onDone" @cancel="primaryTarget = null" />
  </v-card>
</template>

<style scoped>
.adm-bank-row--primary td {
  background: rgba(var(--v-theme-success), 0.06);
}
</style>
