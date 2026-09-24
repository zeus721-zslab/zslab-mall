<script setup lang="ts">
import { mdiArrowLeft } from '@mdi/js'
import type { AdminMemberDetail } from '#layers/admin/app/types/admin-member'
import type { AdminMemberActivityRow } from '#layers/admin/app/components/admin/AdminMemberActivityTable.vue'
import { orderStatusLabel } from '~/lib/constants/order'
import { claimStatusLabel, claimTypeLabel } from '~/lib/constants/claim'
import { formatDateTime } from '~/lib/utils/datetime'
import { ADMIN_CLAIM_STATUS_SEMANTIC, ADMIN_ORDER_STATUS_SEMANTIC } from '#layers/admin/app/lib/constants/admin-order'
import {
  ADMIN_MEMBER_ACTIVITY_TABS,
  ADMIN_MEMBER_PAGE_SIZES,
  DEFAULT_ADMIN_MEMBER_ACTIVITY_TAB,
  DEFAULT_ADMIN_MEMBER_PAGE_SIZE,
  type AdminMemberActivityTab,
} from '#layers/admin/app/lib/constants/admin-member'
import { canResetPassword, gradeLabel, gradeSourceLabel, isWithdrawn, tabClaimType, withdrawSellerWarning } from '#layers/admin/app/lib/admin-member-view'
import { ADMIN_MEMBERS_PATH, ADMIN_MEMBERS_WITHDRAWN_PATH, resolveBackPath } from '#layers/admin/app/lib/admin-back-path'
import { memberWithdrawMessage, temporaryPasswordMessage } from '#layers/admin/app/lib/admin-risk-confirm'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminMembers } from '#layers/admin/app/composables/useAdminMembers'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'
import { formatPhone } from '~/lib/format/phone'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '회원 상세 · zslab-mall 관리자' })

// 회원 상세(Track 84 FE). 회원정보·등급·배송지를 읽기 전용으로 보이고, 정보 수정·탈퇴·임시 비밀번호·등급 변경은 여기서만 실행한다.
// 모든 변경 후에는 상세를 다시 읽는다. 탈퇴 회원은 액션 4종 전부 비활성 + 안내. 주문정보 탭(주문/취소/반품/교환)은 기존 주문·클레임 목록
// API에 buyerPublicId를 붙여 읽고 탭·페이지를 URL query(?tab=·?page=·?size=)에 반영한다. 미존재(404)는 안내 + 목록 이동.
const route = useRoute()
const router = useRouter()
const membersApi = useAdminMembers()
const toast = useAdminToast()

const publicId = computed<string>(() => String(route.params.id ?? ''))
const backPath = computed(() => resolveBackPath(route.query.back, ADMIN_MEMBERS_PATH))

const detail = ref<AdminMemberDetail | null>(null)
const loading = ref(true)
const notFound = ref(false)
const loadError = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  notFound.value = false
  loadError.value = null
  try {
    detail.value = await membersApi.get(publicId.value)
  } catch (error) {
    if (extractErrorCode(error) === 'USER_NOT_FOUND') {
      notFound.value = true
    } else {
      loadError.value = toAdminErrorMessage(error)
    }
  } finally {
    loading.value = false
  }
}
onMounted(load)

const withdrawn = computed(() => (detail.value ? isWithdrawn(detail.value) : false))
// STEP 498: 셀러 구성원이면 탈퇴 다이얼로그에 경고(차단 아님·확정 4). lastActiveMember면 강조 + 한 줄 추가.
const sellerWarning = computed(() => (detail.value ? withdrawSellerWarning(detail.value) : null))
const resetAllowed = computed(() => (detail.value ? canResetPassword(detail.value) : false))

// ---------- 액션 다이얼로그 ----------
type MemberDialog = 'edit' | 'withdraw' | 'reset' | 'reset-result' | 'grade'
const activeDialog = ref<MemberDialog | null>(null)
const actionBusy = ref(false)
// 임시 비밀번호 평문은 결과 다이얼로그가 열린 동안만 이 로컬 ref에 있고 닫히면 null(D-204·토스트·스토어 금지).
const temporaryPassword = ref<string | null>(null)

function closeDialog(refresh: boolean): void {
  activeDialog.value = null
  if (refresh) void load()
}

async function runWithdraw(): Promise<void> {
  if (!detail.value || actionBusy.value) return
  actionBusy.value = true
  try {
    await membersApi.withdraw(detail.value.publicId)
    toast.info('회원을 탈퇴 처리했습니다.')
    activeDialog.value = null
    await navigateTo(ADMIN_MEMBERS_WITHDRAWN_PATH)
  } catch (error) {
    activeDialog.value = null
    const code = extractErrorCode(error)
    if (code === 'MEMBER_ACTIVITY_IN_PROGRESS' || code === 'MEMBER_ALREADY_WITHDRAWN') {
      toast.warning(toAdminErrorMessage(error))
      if (code === 'MEMBER_ALREADY_WITHDRAWN') await load()
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    actionBusy.value = false
  }
}

async function runResetPassword(): Promise<void> {
  if (!detail.value || actionBusy.value) return
  actionBusy.value = true
  try {
    const issued = await membersApi.resetPassword(detail.value.publicId)
    // 확인 다이얼로그 → 결과 다이얼로그(1회 표시). 성공 토스트는 띄우지 않는다(평문이 아니어도 결과 창과 중복).
    temporaryPassword.value = issued.temporaryPassword
    activeDialog.value = 'reset-result'
    await load()
  } catch (error) {
    activeDialog.value = null
    const code = extractErrorCode(error)
    if (code === 'MEMBER_PHONE_MISSING' || code === 'MEMBER_ALREADY_WITHDRAWN' || code === 'MEMBER_ADMIN_ROLE_ASSIGNED' || code === 'TEMPORARY_PASSWORD_DELIVERY_FAILED') {
      toast.warning(toAdminErrorMessage(error))
      if (code !== 'TEMPORARY_PASSWORD_DELIVERY_FAILED') await load()
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    actionBusy.value = false
  }
}

function closeResetResult(): void {
  temporaryPassword.value = null
  activeDialog.value = null
}

// ---------- 주문정보 탭(URL query 단일 소스: tab·page·size) ----------
function parseTab(value: unknown): AdminMemberActivityTab {
  const single = Array.isArray(value) ? value[0] : value
  return ADMIN_MEMBER_ACTIVITY_TABS.some((tab) => tab.value === single) ? (single as AdminMemberActivityTab) : DEFAULT_ADMIN_MEMBER_ACTIVITY_TAB
}
function parsePage(value: unknown): number {
  const page = Number(Array.isArray(value) ? value[0] : value)
  return Number.isInteger(page) && page > 0 ? page : 0
}
function parseSize(value: unknown): number {
  const size = Number(Array.isArray(value) ? value[0] : value)
  return ADMIN_MEMBER_PAGE_SIZES.includes(size) ? size : DEFAULT_ADMIN_MEMBER_PAGE_SIZE
}
const activeTab = computed<AdminMemberActivityTab>(() => parseTab(route.query.tab))
const activityPage = computed<number>(() => parsePage(route.query.page))
const activitySize = computed<number>(() => parseSize(route.query.size))

function applyActivityQuery(patch: { tab?: AdminMemberActivityTab; page?: number; size?: number }): void {
  const tab = patch.tab ?? activeTab.value
  const size = patch.size ?? activitySize.value
  const page = patch.tab !== undefined || patch.size !== undefined ? 0 : (patch.page ?? activityPage.value)
  const query: Record<string, string> = {}
  const back = Array.isArray(route.query.back) ? route.query.back[0] : route.query.back
  if (typeof back === 'string') query.back = back
  if (tab !== DEFAULT_ADMIN_MEMBER_ACTIVITY_TAB) query.tab = tab
  if (page > 0) query.page = String(page)
  if (size !== DEFAULT_ADMIN_MEMBER_PAGE_SIZE) query.size = String(size)
  void router.replace({ query })
}

const activityRows = ref<AdminMemberActivityRow[]>([])
const activityTotal = ref(0)
const activityLoading = ref(false)
const activityError = ref<string | null>(null)
let activitySequence = 0

async function loadActivity(): Promise<void> {
  const sequence = ++activitySequence
  activityLoading.value = true
  activityError.value = null
  const claimType = tabClaimType(activeTab.value)
  try {
    if (claimType === null) {
      const response = await membersApi.listOrders(publicId.value, activityPage.value, activitySize.value)
      if (sequence !== activitySequence) return
      activityRows.value = response.items.map((item) => ({
        key: item.orderId,
        orderId: item.orderId,
        orderNo: item.orderNo,
        caption: item.productSummary,
        statusLabel: orderStatusLabel(item.status),
        statusSemantic: ADMIN_ORDER_STATUS_SEMANTIC[item.status],
        amount: item.paymentAmount,
        at: item.orderedAt,
      }))
      activityTotal.value = response.totalCount
    } else {
      const response = await membersApi.listClaims(publicId.value, claimType, activityPage.value, activitySize.value)
      if (sequence !== activitySequence) return
      activityRows.value = response.items.map((item) => ({
        key: item.claimId,
        orderId: item.orderId,
        orderNo: item.orderNo ?? '—',
        caption: `${claimTypeLabel(item.type)} · ${item.productName ?? '—'}`,
        statusLabel: claimStatusLabel(item.status),
        statusSemantic: ADMIN_CLAIM_STATUS_SEMANTIC[item.status],
        amount: item.amount,
        at: item.requestedAt,
      }))
      activityTotal.value = response.totalCount
    }
  } catch (error) {
    if (sequence !== activitySequence) return
    activityError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === activitySequence) activityLoading.value = false
  }
}
watch([activeTab, activityPage, activitySize], () => { void loadActivity() }, { immediate: true })

const activityEmptyMessage = computed(() => {
  const label = ADMIN_MEMBER_ACTIVITY_TABS.find((tab) => tab.value === activeTab.value)?.label ?? ''
  return activeTab.value === 'orders' ? '주문이 없습니다' : `${label} 내역이 없습니다`
})

function openOrder(row: AdminMemberActivityRow): void {
  if (!row.orderId) return
  void navigateTo({ path: `/admin/orders/${row.orderId}`, query: { back: route.fullPath } })
}
</script>

<template>
  <div>
    <AdminPageHeader title="회원 상세" :description="detail ? `${detail.name ?? '—'} · ${detail.email ?? '—'}` : undefined" guide="회원정보 수정·등급 변경·임시 비밀번호 발급·탈퇴 처리를 하고, 이 회원의 주문·클레임 이력을 봅니다.">
      <template #actions>
        <v-btn variant="text" :prepend-icon="mdiArrowLeft" :to="backPath" data-testid="member-back">목록</v-btn>
      </template>
    </AdminPageHeader>

    <v-card v-if="loading" class="mb-4"><v-card-text><v-skeleton-loader type="article, table" /></v-card-text></v-card>

    <v-card v-else-if="notFound" data-testid="member-not-found">
      <v-card-text class="d-flex flex-column align-center text-center py-12">
        <p class="text-subtitle-1 font-weight-medium mb-1">회원을 찾을 수 없습니다</p>
        <p class="text-body-2 text-medium-emphasis mb-4">존재하지 않거나 대상이 아닌 회원입니다: {{ publicId }}</p>
        <v-btn color="primary" :to="backPath">목록으로</v-btn>
      </v-card-text>
    </v-card>

    <v-alert v-else-if="loadError" type="error" class="mb-4" data-testid="member-load-error">
      {{ loadError }} <v-btn size="small" variant="outlined" color="error" class="ml-2" @click="load">다시 시도</v-btn>
    </v-alert>

    <template v-else-if="detail">
      <v-alert v-if="withdrawn" type="warning" variant="tonal" density="compact" class="mb-4" data-testid="member-withdrawn-notice">
        탈퇴한 회원입니다({{ detail.withdrawnAt ? formatDateTime(detail.withdrawnAt) : '—' }}). 정보 수정·탈퇴·임시 비밀번호·등급 변경을 할 수 없습니다.
      </v-alert>

      <!-- 회원정보 -->
      <v-card class="mb-4" data-testid="member-info">
        <v-card-title class="d-flex align-center justify-space-between flex-wrap ga-2 pt-4 px-5">
          <span class="text-subtitle-2 font-weight-bold">회원정보</span>
          <div class="d-flex align-center flex-wrap ga-2">
            <v-btn size="small" variant="outlined" color="primary" :disabled="withdrawn || actionBusy" data-testid="action-edit" @click="activeDialog = 'edit'">정보 수정</v-btn>
            <v-btn size="small" variant="outlined" :disabled="!resetAllowed || actionBusy" :loading="actionBusy && activeDialog === 'reset'" data-testid="action-reset-password" @click="activeDialog = 'reset'">임시 비밀번호 발급</v-btn>
            <v-btn size="small" variant="outlined" color="error" :disabled="withdrawn || actionBusy" :loading="actionBusy && activeDialog === 'withdraw'" data-testid="action-withdraw" @click="activeDialog = 'withdraw'">탈퇴 처리</v-btn>
          </div>
        </v-card-title>
        <v-card-text class="px-5 pb-5">
          <v-row dense>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">이름</div><div class="text-body-2" data-testid="member-name">{{ detail.name ?? '—' }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">이메일</div><div class="text-body-2" data-testid="member-email">{{ detail.email ?? '—' }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">연락처</div><div class="text-body-2" data-testid="member-phone">{{ formatPhone(detail.phone ?? '—') }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">가입일</div><div class="text-body-2">{{ formatDateTime(detail.createdAt) }}</div></v-col>
            <v-col v-if="detail.withdrawnAt" cols="6" md="3"><div class="text-caption text-medium-emphasis">탈퇴일</div><div class="text-body-2" data-testid="member-withdrawn-at">{{ formatDateTime(detail.withdrawnAt) }}</div></v-col>
            <v-col cols="12" md="3"><div class="text-caption text-medium-emphasis">회원 ID</div><div class="adm-product-id">{{ detail.publicId }}</div></v-col>
          </v-row>
          <div class="d-flex align-center flex-wrap ga-2 mt-3">
            <v-chip v-if="detail.passwordChangeRequired" color="warning" size="small" variant="flat" data-testid="member-password-change-required">비밀번호 변경 필요</v-chip>
            <span v-if="!resetAllowed && !withdrawn" class="text-caption text-medium-emphasis" data-testid="member-phone-missing">연락처가 없어 임시 비밀번호를 발급할 수 없습니다.</span>
          </div>
        </v-card-text>
      </v-card>

      <v-row dense class="mb-1">
        <!-- 등급 -->
        <v-col cols="12" md="4">
          <v-card class="mb-4 h-100" data-testid="member-grade">
            <v-card-title class="d-flex align-center justify-space-between pt-4 px-5">
              <span class="text-subtitle-2 font-weight-bold">등급</span>
              <v-btn size="small" variant="outlined" color="primary" :disabled="withdrawn || actionBusy" data-testid="action-grade" @click="activeDialog = 'grade'">등급 변경</v-btn>
            </v-card-title>
            <v-card-text class="px-5 pb-5">
              <div class="text-body-1 font-weight-medium" data-testid="member-grade-code">{{ gradeLabel(detail.grade) }}</div>
              <div class="text-body-2">부여 방식: <span data-testid="member-grade-source">{{ gradeSourceLabel(detail.grade) }}</span></div>
              <div class="text-body-2">유지 기한: <span data-testid="member-grade-locked-until">{{ detail.grade?.lockedUntil ? formatDateTime(detail.grade.lockedUntil) : '—' }}</span></div>
            </v-card-text>
          </v-card>
        </v-col>
        <!-- 배송지 -->
        <v-col cols="12" md="8">
          <v-card class="mb-4 h-100" data-testid="member-addresses">
            <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">배송지 ({{ detail.addresses.length }})</v-card-title>
            <v-card-text class="px-5 pb-5">
              <v-table v-if="detail.addresses.length > 0" density="compact" class="adm-table">
                <thead><tr><th>별칭</th><th>수령인</th><th>연락처</th><th>주소</th></tr></thead>
                <tbody>
                  <tr v-for="address in detail.addresses" :key="address.id" data-testid="member-address-row">
                    <td>{{ address.addressLabel ?? '—' }}<v-chip v-if="address.isDefault" size="x-small" class="ml-1" variant="tonal" color="primary">기본</v-chip></td>
                    <td>{{ address.recipientName }}</td>
                    <td>{{ formatPhone(address.recipientPhone) }}</td>
                    <td>[{{ address.zonecode }}] {{ address.addressRoad }}<span v-if="address.addressDetail"> {{ address.addressDetail }}</span></td>
                  </tr>
                </tbody>
              </v-table>
              <p v-else class="text-body-2 text-medium-emphasis" data-testid="member-addresses-empty">등록된 배송지가 없습니다.</p>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>

      <!-- 주문정보 탭 -->
      <v-card data-testid="member-activity">
        <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">주문정보</v-card-title>
        <v-tabs :model-value="activeTab" color="primary" density="comfortable" class="px-2" data-testid="member-activity-tabs" @update:model-value="(value) => applyActivityQuery({ tab: value as AdminMemberActivityTab })">
          <v-tab v-for="tab in ADMIN_MEMBER_ACTIVITY_TABS" :key="tab.value" :value="tab.value" :data-testid="`member-tab-${tab.value}`">{{ tab.label }}</v-tab>
        </v-tabs>
        <AdminMemberActivityTable
          :rows="activityRows"
          :total-count="activityTotal"
          :page="activityPage"
          :size="activitySize"
          :loading="activityLoading"
          :load-error="activityError"
          :empty-message="activityEmptyMessage"
          @update:page="(page) => applyActivityQuery({ page })"
          @update:size="(size) => applyActivityQuery({ size })"
          @open="openOrder"
          @retry="loadActivity"
        />
      </v-card>
    </template>

    <AdminMemberEditDialog :open="activeDialog === 'edit'" :detail="detail" @done="closeDialog(true)" @stale="closeDialog(true)" @cancel="closeDialog(false)" />
    <AdminMemberGradeDialog :open="activeDialog === 'grade'" :detail="detail" @done="closeDialog(true)" @stale="closeDialog(true)" @cancel="closeDialog(false)" />
    <AdminConfirmDialog
      :open="activeDialog === 'withdraw'"
      title="회원 탈퇴 처리"
      :message="memberWithdrawMessage(detail?.name ?? '—', detail?.email ?? '—')"
      confirm-label="탈퇴 처리"
      risk
      :loading="actionBusy"
      :warning-lines="sellerWarning?.lines"
      :warning-emphasis="sellerWarning?.emphasis"
      test-id="member-withdraw-dialog"
      @confirm="runWithdraw"
      @cancel="activeDialog = null"
    />
    <AdminConfirmDialog
      :open="activeDialog === 'reset'"
      title="임시 비밀번호 발급"
      :message="temporaryPasswordMessage(formatPhone(detail?.phone ?? '—'))"
      confirm-label="발급"
      risk
      :loading="actionBusy"
      test-id="member-reset-dialog"
      @confirm="runResetPassword"
      @cancel="activeDialog = null"
    />
    <AdminTemporaryPasswordDialog
      :open="activeDialog === 'reset-result'"
      :temporary-password="temporaryPassword"
      :recipient-label="`${detail?.name ?? '—'}(${detail?.email ?? '—'}) 회원의 임시 비밀번호입니다.`"
      test-id="member-reset-result"
      @closed="closeResetResult"
    />
  </div>
</template>
