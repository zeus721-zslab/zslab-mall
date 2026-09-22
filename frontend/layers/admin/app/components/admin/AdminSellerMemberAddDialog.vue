<script setup lang="ts">
import { mdiInformationOutline, mdiMagnify } from '@mdi/js'
import type { AdminMemberSummary } from '#layers/admin/app/types/admin-member'
import type { AdminSellerDetail, AdminSellerMemberAddRequest } from '#layers/admin/app/types/admin-seller'
import {
  ADMIN_SELLER_KEYWORD_MAX,
  ADMIN_SELLER_MEMBER_EMAIL_MAX,
  ADMIN_SELLER_MEMBER_NAME_MAX,
  ADMIN_SELLER_MEMBER_PHONE_MAX,
  ADMIN_SELLER_MEMBER_ROLE_LABEL,
  ADMIN_SELLER_MEMBER_ROLE_OPTIONS,
  ADMIN_SELLER_MEMBER_SEARCH_SIZE,
  SELLER_MEMBER_NEW_USER_NOTICE,
  SELLER_MEMBER_ROLE_NOTICE,
  type AdminSellerMemberRole,
} from '#layers/admin/app/lib/constants/admin-seller'
import { DEFAULT_ADMIN_MEMBER_QUERY } from '#layers/admin/app/lib/admin-member-query'
import { mapFieldErrors } from '#layers/admin/app/lib/admin-order-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { toSellerMemberErrorMessage, validateNewUserInput } from '#layers/admin/app/lib/admin-seller-member-view'
import { useAdminMembers } from '#layers/admin/app/composables/useAdminMembers'
import { useAdminSellers } from '#layers/admin/app/composables/useAdminSellers'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

/**
 * 셀러 구성원 추가 다이얼로그(FE-42·D-189 POST /members 201). 2경로 탭 — "기존 회원 검색"(FE-39 운영자 등록·FE-40 입점 owner 검색 패턴·활성 BUYER 목록 API)
 * / "새 계정 생성"(이름·이메일·휴대폰 → BE가 계정 + BUYER 자격 + 임시 비밀번호 SMS·응답 평문을 결과 다이얼로그에 1회 표시 후 done·D-204). 역할은 공통(권한에
 * 영향 없음 안내). 사유 없음.
 * 이미 이 셀러의 구성원인 회원은 선택 시 인라인 안내로 막고(같은 UK라 BE 409 코드로는 같은 셀러·타 셀러를 구분할 수 없음), 그 외 409는 코드별 문구.
 */
type Mode = 'existing' | 'new'

const props = defineProps<{ open: boolean; detail: AdminSellerDetail | null }>()
const emit = defineEmits<{ done: []; stale: []; cancel: [] }>()

const membersApi = useAdminMembers()
const sellersApi = useAdminSellers()
const toast = useAdminToast()

const mode = ref<Mode>('existing')
const role = ref<AdminSellerMemberRole>('SELLER_STAFF')
const keyword = ref('')
const results = ref<AdminMemberSummary[]>([])
const searched = ref(false)
const searching = ref(false)
const searchError = ref<string | null>(null)
const selected = ref<AdminMemberSummary | null>(null)
const newUser = reactive({ email: '', name: '', phone: '' })
const errors = ref<Record<string, string>>({})
const submitting = ref(false)
const submitError = ref<string | null>(null)
// 신규 계정의 임시 비밀번호 평문 — 결과 다이얼로그가 열린 동안만 보관하고 닫히면 null(D-204·토스트·스토어 금지).
const issuedPassword = ref<string | null>(null)
let searchSequence = 0

function reset(): void {
  issuedPassword.value = null
  mode.value = 'existing'
  role.value = 'SELLER_STAFF'
  keyword.value = ''
  results.value = []
  searched.value = false
  searching.value = false
  searchError.value = null
  selected.value = null
  Object.assign(newUser, { email: '', name: '', phone: '' })
  errors.value = {}
  submitError.value = null
  submitting.value = false
}
watch(() => props.open, (open) => { if (open) reset() })
watch(mode, () => { errors.value = {}; submitError.value = null })

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
    const response = await membersApi.list({ ...DEFAULT_ADMIN_MEMBER_QUERY, keyword: trimmed, size: ADMIN_SELLER_MEMBER_SEARCH_SIZE }, 'ACTIVE')
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

/** 이미 이 셀러의 구성원이면 선택 자체를 인라인으로 막는다(서버 409 SELLER_USER_ALREADY_EXISTS는 타 셀러 소속과 같은 코드). */
function alreadyMember(member: AdminMemberSummary): boolean {
  return (props.detail?.members ?? []).some((row) => row.userPublicId === member.publicId)
}
function memberLabel(member: AdminMemberSummary): string {
  return member.name ?? member.email ?? member.publicId
}

// FE-58: :disabled와 같은 값을 핸들러가 재검사한다 — Vuetify VListItem은 disabled여도 click을 emit한다(프로그래밍 클릭 fallthrough).
function resultDisabled(member: AdminMemberSummary): boolean {
  return submitting.value || alreadyMember(member)
}
function selectMember(member: AdminMemberSummary): void {
  if (resultDisabled(member)) return
  selected.value = member
}

const confirmDisabled = computed(() => {
  if (submitting.value) return true
  if (mode.value === 'existing') return selected.value === null
  return newUser.email.trim() === '' || newUser.name.trim() === '' || newUser.phone.trim() === ''
})

async function submit(): Promise<void> {
  if (submitting.value || !props.detail) return
  let body: AdminSellerMemberAddRequest
  if (mode.value === 'existing') {
    if (!selected.value) return
    body = { userPublicId: selected.value.publicId, role: role.value }
  } else {
    const input = { email: newUser.email.trim(), name: newUser.name.trim(), phone: newUser.phone.trim() }
    const localErrors = validateNewUserInput(input)
    if (Object.keys(localErrors).length > 0) {
      errors.value = localErrors
      return
    }
    body = { newUser: input, role: role.value }
  }
  submitting.value = true
  submitError.value = null
  try {
    const created = await sellersApi.addMember(props.detail.sellerPublicId, body)
    const who = created.name ?? created.email ?? created.userPublicId ?? '구성원'
    if (mode.value === 'new') {
      // 신규 계정: 평문이 있으면 결과 다이얼로그(1회 표시)를 먼저 띄우고 닫힌 뒤 done. 평문이 없거나 빈 문자열이면 fail-closed —
      // 계정은 이미 만들어졌으므로(201) 성공으로 안내하지 않고 재발급 경로를 알린 뒤 done으로 목록만 갱신한다(외부 검토 R2 Q6).
      if (created.temporaryPassword) {
        toast.success(`${who} 계정을 만들고 ${ADMIN_SELLER_MEMBER_ROLE_LABEL[role.value]}(으)로 추가했습니다. 임시 비밀번호를 확인해 전달해 주세요.`)
        issuedPassword.value = created.temporaryPassword
        return
      }
      toast.danger(`${who} 계정은 생성되었지만 임시 비밀번호를 받지 못했습니다. 회원 상세에서 재발급해 주세요.`)
      emit('done')
      return
    }
    toast.success(`${who} 회원을 ${ADMIN_SELLER_MEMBER_ROLE_LABEL[role.value]}(으)로 추가했습니다. 지금부터 셀러 로그인이 가능합니다.`)
    emit('done')
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'VALIDATION_FAILED') {
      // BE fieldErrors는 newUser.email 형태 → 입력 필드 키로 정규화. XOR 위반(exactlyOneTarget)은 화면 구조상 발생하지 않는다.
      const mapped = mapFieldErrors(error)
      const normalized: Record<string, string> = {}
      for (const [field, message] of Object.entries(mapped)) normalized[field.replace(/^newUser\./, '')] = message
      errors.value = normalized
      if (Object.keys(normalized).length === 0) toast.danger(toAdminErrorMessage(error))
    } else if (code === 'EMAIL_ALREADY_EXISTS') {
      errors.value = { email: toSellerMemberErrorMessage(error) }
    } else if (code === 'TEMPORARY_PASSWORD_DELIVERY_FAILED') {
      // R2 지적 15: SMS 실패는 게이트웨이 장애일 수 있어 번호 필드 오류로 붙이지 않고 다이얼로그 전역 오류로 표시(계정 미생성·롤백 사실 유지·입력 유지).
      submitError.value = toSellerMemberErrorMessage(error)
    } else if (code === 'MEMBER_ALREADY_WITHDRAWN' || code === 'USER_NOT_FOUND') {
      // 대상 회원 문제 → 다른 회원을 고르도록 검색 단계로(검색어·결과 유지).
      toast.warning(toSellerMemberErrorMessage(error))
      selected.value = null
    } else {
      // 409 SELLER_USER_ALREADY_EXISTS(타 셀러 소속·또는 화면이 오래돼 이미 이 셀러 구성원)·404 SELLER_NOT_FOUND → 토스트 후 부모가 다시 읽는다.
      toast.warning(toSellerMemberErrorMessage(error))
      emit('stale')
    }
  } finally {
    submitting.value = false
  }
}

function closeIssuedPassword(): void {
  issuedPassword.value = null
  emit('done')
}
</script>

<template>
  <v-dialog :model-value="open" max-width="640" :persistent="submitting" @update:model-value="(value: boolean) => !value && emit('cancel')">
    <v-card data-testid="admin-seller-member-add-dialog">
      <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">구성원 추가</v-card-title>
      <v-card-text class="px-5">
        <p v-if="detail" class="text-body-2 text-medium-emphasis mb-3" data-testid="seller-member-add-headline">{{ detail.companyName }} 셀러에 구성원을 추가합니다. 추가 즉시 해당 계정으로 셀러 로그인이 가능해집니다.</p>

        <v-tabs v-model="mode" density="compact" color="primary" class="mb-4" data-testid="seller-member-add-mode">
          <v-tab value="existing" :disabled="submitting" data-testid="seller-member-add-tab-existing">기존 회원 검색</v-tab>
          <v-tab value="new" :disabled="submitting" data-testid="seller-member-add-tab-new">새 계정 생성</v-tab>
        </v-tabs>

        <!-- 경로 1: 기존 회원 검색(활성 BUYER) -->
        <template v-if="mode === 'existing'">
          <template v-if="selected === null">
            <v-text-field
              v-model="keyword"
              label="이름 · 이메일 · 연락처"
              placeholder="회원 검색어"
              :prepend-inner-icon="mdiMagnify"
              :maxlength="ADMIN_SELLER_KEYWORD_MAX"
              :loading="searching"
              :disabled="submitting"
              hide-details
              autofocus
              data-testid="seller-member-keyword"
              @keyup.enter="search"
            >
              <template #append>
                <v-btn size="small" variant="tonal" :disabled="keyword.trim() === '' || searching || submitting" data-testid="seller-member-search" @click="search">검색</v-btn>
              </template>
            </v-text-field>
            <v-alert v-if="searchError" type="error" variant="tonal" density="compact" class="mt-3" data-testid="seller-member-search-error">{{ searchError }}</v-alert>
            <v-list v-else-if="results.length > 0" density="compact" class="mt-3 adm-member-results" data-testid="seller-member-results">
              <v-list-item v-for="member in results" :key="member.publicId" :disabled="resultDisabled(member)" data-testid="seller-member-result" @click="selectMember(member)">
                <v-list-item-title>{{ member.name ?? '—' }} <span class="text-medium-emphasis">{{ member.email ?? '' }}</span></v-list-item-title>
                <v-list-item-subtitle>{{ alreadyMember(member) ? '이미 이 셀러의 구성원입니다' : (member.phone ?? '연락처 없음') }}</v-list-item-subtitle>
              </v-list-item>
            </v-list>
            <p v-else-if="searched" class="text-body-2 text-medium-emphasis mt-3" data-testid="seller-member-empty">조건에 맞는 활성 회원이 없습니다. 미가입자는 "새 계정 생성"으로 추가하세요.</p>
            <p v-else class="text-caption text-medium-emphasis mt-2 mb-0">한 회원은 한 셀러에만 소속될 수 있습니다. 탈퇴한 회원은 추가할 수 없습니다.</p>
          </template>
          <v-alert v-else type="success" variant="tonal" density="compact" class="mb-1" data-testid="seller-member-selected">
            <div class="d-flex align-center justify-space-between flex-wrap ga-2">
              <span>선택한 회원: <span class="font-weight-medium">{{ memberLabel(selected) }}</span> <span class="text-medium-emphasis">{{ selected.email ?? '' }}</span></span>
              <v-btn size="x-small" variant="text" :disabled="submitting" data-testid="seller-member-reselect" @click="selected = null">다시 선택</v-btn>
            </div>
          </v-alert>
        </template>

        <!-- 경로 2: 새 계정 생성 -->
        <template v-else>
          <v-alert v-if="submitError" type="error" variant="tonal" density="compact" class="mb-3" data-testid="seller-member-submit-error">{{ submitError }}</v-alert>
          <v-alert type="info" variant="tonal" density="compact" :icon="mdiInformationOutline" class="mb-3" data-testid="seller-member-new-notice">
            <p v-for="line in SELLER_MEMBER_NEW_USER_NOTICE" :key="line" class="text-body-2 mb-0">{{ line }}</p>
          </v-alert>
          <v-row dense>
            <v-col cols="12" md="7">
              <v-text-field v-model="newUser.email" label="이메일(로그인 ID) *" type="email" :maxlength="ADMIN_SELLER_MEMBER_EMAIL_MAX" :error-messages="errors.email ? [errors.email] : []" :disabled="submitting" autofocus data-testid="seller-member-new-email" @update:model-value="clearError('email')" />
            </v-col>
            <v-col cols="12" md="5">
              <v-text-field v-model="newUser.name" label="이름 *" :maxlength="ADMIN_SELLER_MEMBER_NAME_MAX" :error-messages="errors.name ? [errors.name] : []" :disabled="submitting" data-testid="seller-member-new-name" @update:model-value="clearError('name')" />
            </v-col>
            <v-col cols="12" md="7">
              <v-text-field v-model="newUser.phone" label="휴대폰(임시 비밀번호 SMS 수신) *" placeholder="010-1234-5678" inputmode="tel" :maxlength="ADMIN_SELLER_MEMBER_PHONE_MAX" :error-messages="errors.phone ? [errors.phone] : []" :disabled="submitting" data-testid="seller-member-new-phone" @update:model-value="clearError('phone')" />
            </v-col>
          </v-row>
        </template>

        <!-- 공통: 역할 -->
        <v-select v-model="role" :items="ADMIN_SELLER_MEMBER_ROLE_OPTIONS" label="역할" :disabled="submitting" class="mt-3" data-testid="seller-member-add-role" />
        <p class="text-caption text-medium-emphasis mb-0" data-testid="seller-member-role-notice">
          <template v-for="(line, index) in SELLER_MEMBER_ROLE_NOTICE" :key="line">{{ line }}<br v-if="index < SELLER_MEMBER_ROLE_NOTICE.length - 1"></template>
        </p>
      </v-card-text>
      <v-card-actions class="px-5 pb-4">
        <v-spacer />
        <v-btn variant="text" :disabled="submitting" data-testid="seller-member-add-cancel" @click="emit('cancel')">닫기</v-btn>
        <v-btn color="primary" variant="flat" :loading="submitting" :disabled="confirmDisabled || issuedPassword !== null" data-testid="seller-member-add-ok" @click="submit">{{ mode === 'new' ? '계정 생성 후 추가' : '구성원 추가' }}</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
  <AdminTemporaryPasswordDialog
    :open="issuedPassword !== null"
    :temporary-password="issuedPassword"
    :recipient-label="`${newUser.name || '—'}(${newUser.email || '—'}) 계정의 임시 비밀번호입니다.`"
    test-id="seller-member-password-result"
    @closed="closeIssuedPassword"
  />
</template>

<style scoped>
.adm-member-results {
  max-height: 280px;
  overflow-y: auto;
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: 8px;
}
</style>
