<script setup lang="ts">
import { mdiMagnify } from '@mdi/js'
import type { AdminMemberSummary } from '#layers/admin/app/types/admin-member'
import {
  ADMIN_SELLER_BUSINESS_NO_MAX,
  ADMIN_SELLER_CEO_NAME_MAX,
  ADMIN_SELLER_COMPANY_NAME_MAX,
  ADMIN_SELLER_CONTACT_EMAIL_MAX,
  ADMIN_SELLER_CONTACT_PHONE_MAX,
  ADMIN_SELLER_INITIAL_STATUS_OPTIONS,
  ADMIN_SELLER_KEYWORD_MAX,
  ADMIN_SELLER_MEMBER_SEARCH_SIZE,
} from '#layers/admin/app/lib/constants/admin-seller'
import { DEFAULT_ADMIN_MEMBER_QUERY } from '#layers/admin/app/lib/admin-member-query'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminMembers } from '#layers/admin/app/composables/useAdminMembers'
import { useAdminSellers } from '#layers/admin/app/composables/useAdminSellers'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 셀러 입점 등록 다이얼로그(FE-40·BE Track 37 provisioning + D-187 §1-A 9 publicId 전환). 1단계 회원 검색(AdminOperatorProvisionDialog 패턴·회원 목록
 * API keyword·활성 BUYER) → owner 선택 → 2단계 사업자 정보 입력 → POST. 초기 상태는 즉시 활성 또는 승인 대기(BE 허용 2값).
 * 409 SELLER_BUSINESS_NO_DUPLICATE는 사업자번호 필드 오류("이미 등록된 사업자번호입니다."), 409 SELLER_USER_ALREADY_EXISTS(이미 다른 셀러 소속)는
 * 토스트 후 1단계로 되돌린다. 호출·토스트는 다이얼로그가 소유하고 부모는 done 시 목록을 다시 읽는다.
 */
const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ done: [sellerPublicId: string]; cancel: [] }>()

const membersApi = useAdminMembers()
const sellersApi = useAdminSellers()
const toast = useAdminToast()

const keyword = ref('')
const results = ref<AdminMemberSummary[]>([])
const searched = ref(false)
const searching = ref(false)
const searchError = ref<string | null>(null)
const owner = ref<AdminMemberSummary | null>(null)
let searchSequence = 0

const form = reactive({
  companyName: '',
  businessNo: '',
  ceoName: '',
  contactEmail: '',
  contactPhone: '',
  status: 'ACTIVE' as 'ACTIVE' | 'PENDING',
})
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

function reset(): void {
  keyword.value = ''
  results.value = []
  searched.value = false
  searching.value = false
  searchError.value = null
  owner.value = null
  Object.assign(form, { companyName: '', businessNo: '', ceoName: '', contactEmail: '', contactPhone: '', status: 'ACTIVE' })
  errors.value = {}
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })

function clearError(field: string): void {
  errors.value = { ...errors.value, [field]: '' }
}

async function search(): Promise<void> {
  const trimmed = keyword.value.trim()
  if (trimmed === '' || searching.value) return
  const sequence = ++searchSequence
  searching.value = true
  searchError.value = null
  try {
    const response = await membersApi.list(
      { ...DEFAULT_ADMIN_MEMBER_QUERY, keyword: trimmed, size: ADMIN_SELLER_MEMBER_SEARCH_SIZE },
      'ACTIVE',
    )
    if (sequence !== searchSequence) return
    results.value = response.items
    searched.value = true
  } catch (error) {
    if (sequence !== searchSequence) return
    searchError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === searchSequence) searching.value = false
  }
}

function ownerLabel(member: AdminMemberSummary): string {
  return member.name ?? member.email ?? member.publicId
}

const confirmDisabled = computed(() =>
  submitting.value || owner.value === null || form.companyName.trim() === '' || form.ceoName.trim() === '',
)

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

async function submit(): Promise<void> {
  if (submitting.value || !owner.value) return
  const localErrors: Record<string, string> = {}
  if (form.companyName.trim() === '') localErrors.companyName = '상호명을 입력하세요.'
  if (form.ceoName.trim() === '') localErrors.ceoName = '대표자명을 입력하세요.'
  if (Object.keys(localErrors).length > 0) {
    errors.value = localErrors
    return
  }
  submitting.value = true
  try {
    const created = await sellersApi.provision({
      companyName: form.companyName.trim(),
      businessNo: blankToNull(form.businessNo),
      ceoName: form.ceoName.trim(),
      contactEmail: blankToNull(form.contactEmail),
      contactPhone: blankToNull(form.contactPhone),
      status: form.status,
      ownerUserPublicId: owner.value.publicId,
    })
    toast.success(`${form.companyName.trim()} 셀러를 등록했습니다(${form.status === 'ACTIVE' ? '활성' : '승인 대기'}).`)
    emit('done', created.sellerPublicId)
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { companyName: toAdminErrorMessage(error) }
    } else if (code === 'SELLER_BUSINESS_NO_DUPLICATE') {
      errors.value = { businessNo: toAdminErrorMessage(error) }
    } else if (code === 'SELLER_USER_ALREADY_EXISTS' || code === 'USER_NOT_FOUND') {
      // owner 문제는 회원 선택을 다시 하도록 1단계로 되돌린다(입력한 사업자 정보는 유지).
      toast.warning(toAdminErrorMessage(error))
      owner.value = null
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="open" max-width="640" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-seller-provision-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">셀러 입점 등록</v-card-title>
      <v-card-text class="px-5">
        <!-- 1단계: owner 회원 검색 -->
        <p class="text-body-2 mb-2"><span class="font-weight-medium">1. 대표 계정(owner) 선택</span> — 기존 회원 중 한 명을 고릅니다. 한 회원은 한 셀러에만 소속될 수 있습니다.</p>
        <template v-if="owner === null">
          <v-text-field
            v-model="keyword"
            label="이름 · 이메일 · 연락처"
            placeholder="회원 검색어"
            :prepend-inner-icon="mdiMagnify"
            :maxlength="ADMIN_SELLER_KEYWORD_MAX"
            :loading="searching"
            :disabled="submitting"
            hide-details
            data-testid="seller-provision-keyword"
            @keyup.enter="search"
          >
            <template #append>
              <v-btn size="small" variant="tonal" :disabled="keyword.trim() === '' || searching || submitting" data-testid="seller-provision-search" @click="search">검색</v-btn>
            </template>
          </v-text-field>
          <v-alert v-if="searchError" type="error" variant="tonal" density="compact" class="mt-3" data-testid="seller-provision-search-error">{{ searchError }}</v-alert>
          <v-list v-else-if="results.length > 0" density="compact" class="mt-3 adm-provision-results" data-testid="seller-provision-results">
            <v-list-item v-for="member in results" :key="member.publicId" :disabled="submitting" data-testid="seller-provision-result" @click="owner = member">
              <v-list-item-title>{{ member.name ?? '—' }} <span class="text-medium-emphasis">{{ member.email ?? '' }}</span></v-list-item-title>
              <v-list-item-subtitle>{{ member.phone ?? '연락처 없음' }}</v-list-item-subtitle>
            </v-list-item>
          </v-list>
          <p v-else-if="searched" class="text-body-2 text-medium-emphasis mt-3" data-testid="seller-provision-empty">조건에 맞는 활성 회원이 없습니다.</p>
        </template>
        <v-alert v-else type="success" variant="tonal" density="compact" class="mb-3" data-testid="seller-provision-owner">
          <div class="d-flex align-center justify-space-between flex-wrap ga-2">
            <span>대표 계정: <span class="font-weight-medium">{{ ownerLabel(owner) }}</span> <span class="text-medium-emphasis">{{ owner.email ?? '' }}</span></span>
            <v-btn size="x-small" variant="text" :disabled="submitting" data-testid="seller-provision-owner-change" @click="owner = null">다시 선택</v-btn>
          </div>
        </v-alert>

        <!-- 2단계: 사업자 정보 -->
        <template v-if="owner !== null">
          <p class="text-body-2 mb-2 mt-2"><span class="font-weight-medium">2. 사업자 정보</span></p>
          <v-row dense>
            <v-col cols="12" md="7">
              <v-text-field v-model="form.companyName" label="상호명 *" :maxlength="ADMIN_SELLER_COMPANY_NAME_MAX" :error-messages="errors.companyName ? [errors.companyName] : []" :disabled="submitting" autofocus data-testid="seller-provision-company" @update:model-value="clearError('companyName')" />
            </v-col>
            <v-col cols="12" md="5">
              <v-text-field v-model="form.ceoName" label="대표자명 *" :maxlength="ADMIN_SELLER_CEO_NAME_MAX" :error-messages="errors.ceoName ? [errors.ceoName] : []" :disabled="submitting" data-testid="seller-provision-ceo" @update:model-value="clearError('ceoName')" />
            </v-col>
            <v-col cols="12" md="5">
              <v-text-field v-model="form.businessNo" label="사업자등록번호" placeholder="예: 123-45-67890" :maxlength="ADMIN_SELLER_BUSINESS_NO_MAX" :error-messages="errors.businessNo ? [errors.businessNo] : []" :disabled="submitting" data-testid="seller-provision-business-no" @update:model-value="clearError('businessNo')" />
            </v-col>
            <v-col cols="12" md="7">
              <v-text-field v-model="form.contactEmail" label="담당자 이메일" type="email" :maxlength="ADMIN_SELLER_CONTACT_EMAIL_MAX" :error-messages="errors.contactEmail ? [errors.contactEmail] : []" :disabled="submitting" data-testid="seller-provision-email" @update:model-value="clearError('contactEmail')" />
            </v-col>
            <v-col cols="12" md="5">
              <v-text-field v-model="form.contactPhone" label="담당자 연락처" :maxlength="ADMIN_SELLER_CONTACT_PHONE_MAX" :error-messages="errors.contactPhone ? [errors.contactPhone] : []" :disabled="submitting" data-testid="seller-provision-phone" @update:model-value="clearError('contactPhone')" />
            </v-col>
            <v-col cols="12" md="7">
              <v-select v-model="form.status" :items="ADMIN_SELLER_INITIAL_STATUS_OPTIONS" label="초기 상태" :disabled="submitting" data-testid="seller-provision-status" />
            </v-col>
          </v-row>
          <p class="text-caption text-medium-emphasis mb-0" data-testid="seller-provision-hint">
            승인 대기로 등록하면 상세 화면에서 활성화(입점 승인)할 때까지 상품이 노출되지 않습니다. 수수료율·정산계좌는 등록 후 상세에서 설정합니다.
          </p>
        </template>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="seller-provision-close" @click="emit('cancel')">닫기</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="confirmDisabled" data-testid="seller-provision-ok" @click="submit">입점 등록</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.adm-provision-results {
  max-height: 280px;
  overflow-y: auto;
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: 8px;
}
</style>
