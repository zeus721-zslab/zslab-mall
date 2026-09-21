<script setup lang="ts">
import { mdiAlertCircleOutline, mdiBankOutline, mdiInformationOutline, mdiLockOutline, mdiStar } from '@mdi/js'
import type { SellerBankAccount } from '#layers/seller/app/types/seller-bank-account'
import {
  SELLER_BANK_ACCOUNT_MESSAGES,
  SELLER_BANK_ACCOUNT_STATUS_LABEL,
  formatSellerBankAccount,
  validateSellerBankAccountForm,
} from '#layers/seller/app/lib/seller-bank-account'
import { extractErrorCode, mapFieldErrors, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerBankAccounts } from '#layers/seller/app/composables/useSellerBankAccounts'
import { useSellerMe } from '#layers/seller/app/composables/useSellerMe'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'
import { ACCOUNT_HOLDER_MAX, ACCOUNT_NUMBER_MAX, BANK_OPTIONS } from '~/lib/constants/bank'
import { formatDateTime } from '~/lib/utils/datetime'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '정산계좌 · zslab-mall 셀러' })

/**
 * 셀러 본인 정산계좌(Track 90-D-3·FE-51·D-199). 목록(끝 4자리·주 계좌 표시)은 모든 셀러 역할이 보고, 등록 폼은 SELLER_OWNER(GET /seller/me.roleCode)에게만
 * 보인다 — 서버도 같은 판정(403 SELLER_OWNER_REQUIRED)을 하므로 화면 분기는 안내용이다. 수정·삭제·주 계좌 전환은 관리자 전용이라 버튼이 없다.
 */
const bankAccountsApi = useSellerBankAccounts()
const { me: sellerMe } = useSellerMe()
const toast = useSellerToast()

const isOwner = computed<boolean>(() => sellerMe.value?.roleCode === 'SELLER_OWNER')

const accounts = ref<SellerBankAccount[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await bankAccountsApi.list()
    if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
    accounts.value = response
  } catch (error) {
    if (sequence !== requestSequence) return
    loadError.value = toSellerErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}
onMounted(() => { void load() })

const bankCode = ref('')
const accountNumber = ref('')
const accountHolder = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

function clearError(field: string): void {
  errors.value = { ...errors.value, [field]: '' }
}

async function submit(): Promise<void> {
  if (submitting.value) return
  const validation = validateSellerBankAccountForm({
    bankCode: bankCode.value,
    accountNumber: accountNumber.value,
    accountHolder: accountHolder.value,
  })
  errors.value = validation
  if (Object.keys(validation).length > 0) return

  submitting.value = true
  try {
    await bankAccountsApi.register({
      bankCode: bankCode.value,
      accountNumber: accountNumber.value.trim(),
      accountHolder: accountHolder.value.trim(),
    })
    toast.success(SELLER_BANK_ACCOUNT_MESSAGES.registered)
    bankCode.value = ''
    accountNumber.value = ''
    accountHolder.value = ''
    await load()
  } catch (error) {
    if (extractErrorCode(error) === 'VALIDATION_FAILED') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { accountNumber: toSellerErrorMessage(error) }
    } else {
      // SELLER_OWNER_REQUIRED(역할 변경 후 화면 잔존)·SELLER_SUSPENDED·그 외는 공통 문구 토스트
      toast.danger(toSellerErrorMessage(error))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div data-testid="seller-bank-account">
    <SellerPageHeader title="정산계좌" description="정산 지급을 받을 계좌입니다. 계좌번호는 끝 4자리만 표시됩니다." />
    <v-row>
      <v-col cols="12" md="7">
        <v-card data-testid="seller-bank-account-list-card">
          <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">등록된 계좌</v-card-title>
          <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="seller-bank-account-error">
            <div class="d-flex align-center justify-space-between flex-wrap ga-2">
              <span>{{ loadError }}</span>
              <v-btn size="small" variant="outlined" color="error" data-testid="seller-bank-account-retry" @click="load">다시 시도</v-btn>
            </div>
          </v-alert>
          <v-progress-linear v-else-if="loading" indeterminate color="primary" data-testid="seller-bank-account-loading" />
          <div v-else-if="accounts.length === 0" class="d-flex flex-column align-center text-center py-10" data-testid="seller-bank-account-empty">
            <v-avatar color="surface-variant" size="48" class="mb-3">
              <v-icon :icon="mdiBankOutline" size="22" class="text-medium-emphasis" />
            </v-avatar>
            <p class="text-subtitle-2 font-weight-medium mb-1">{{ SELLER_BANK_ACCOUNT_MESSAGES.emptyTitle }}</p>
            <p class="text-body-2 text-medium-emphasis mb-0">{{ SELLER_BANK_ACCOUNT_MESSAGES.emptyMessage }}</p>
          </div>
          <v-list v-else lines="two" data-testid="seller-bank-account-rows">
            <v-list-item v-for="account in accounts" :key="account.id" :data-testid="`seller-bank-account-row-${account.id}`">
              <template #prepend>
                <v-avatar :color="account.isPrimary ? 'primary' : 'surface-variant'" size="36">
                  <v-icon :icon="account.isPrimary ? mdiStar : mdiBankOutline" size="18" :color="account.isPrimary ? 'white' : undefined" />
                </v-avatar>
              </template>
              <v-list-item-title class="font-weight-medium" data-testid="seller-bank-account-number">
                {{ formatSellerBankAccount(account) }}
                <v-chip v-if="account.isPrimary" size="x-small" color="primary" variant="tonal" class="ml-2" data-testid="seller-bank-account-primary">주 정산계좌</v-chip>
              </v-list-item-title>
              <v-list-item-subtitle>
                예금주 {{ account.accountHolder }} · {{ SELLER_BANK_ACCOUNT_STATUS_LABEL[account.status] }} · 등록 {{ formatDateTime(account.createdAt) }}
              </v-list-item-subtitle>
            </v-list-item>
          </v-list>
        </v-card>
      </v-col>

      <v-col cols="12" md="5">
        <v-card v-if="isOwner" data-testid="seller-bank-account-form-card">
          <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">계좌 등록</v-card-title>
          <v-card-text class="pa-5 pt-2">
            <v-alert type="info" variant="tonal" density="compact" :icon="mdiInformationOutline" class="mb-4" data-testid="seller-bank-account-form-notice">
              {{ SELLER_BANK_ACCOUNT_MESSAGES.formNotice }}
            </v-alert>
            <v-form data-testid="seller-bank-account-form" @submit.prevent="submit">
              <v-select
                id="seller-bank-code"
                v-model="bankCode"
                label="은행"
                :items="BANK_OPTIONS"
                item-title="title"
                item-value="value"
                :error-messages="errors.bankCode ? [errors.bankCode] : []"
                data-testid="seller-bank-code"
                @update:model-value="clearError('bankCode')"
              />
              <v-text-field
                id="seller-account-number"
                v-model="accountNumber"
                label="계좌번호"
                inputmode="numeric"
                autocomplete="off"
                hint="숫자와 하이픈(-)만 입력"
                persistent-hint
                :maxlength="ACCOUNT_NUMBER_MAX"
                :error-messages="errors.accountNumber ? [errors.accountNumber] : []"
                class="mb-2"
                data-testid="seller-account-number"
                @update:model-value="clearError('accountNumber')"
              />
              <v-text-field
                id="seller-account-holder"
                v-model="accountHolder"
                label="예금주"
                autocomplete="off"
                :maxlength="ACCOUNT_HOLDER_MAX"
                :error-messages="errors.accountHolder ? [errors.accountHolder] : []"
                data-testid="seller-account-holder"
                @update:model-value="clearError('accountHolder')"
              />
              <v-btn type="submit" color="primary" size="large" block :loading="submitting" :disabled="submitting" data-testid="seller-bank-account-submit">
                계좌 등록
              </v-btn>
            </v-form>
          </v-card-text>
        </v-card>
        <v-alert v-else type="info" variant="tonal" density="compact" :icon="mdiLockOutline" data-testid="seller-bank-account-readonly-notice">
          {{ SELLER_BANK_ACCOUNT_MESSAGES.readOnlyNotice }}
        </v-alert>
      </v-col>
    </v-row>
  </div>
</template>
