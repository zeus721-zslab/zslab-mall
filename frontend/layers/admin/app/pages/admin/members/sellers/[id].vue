<script setup lang="ts">
import { mdiAlertOutline, mdiArrowLeft, mdiOpenInNew } from '@mdi/js'
import type { AdminSellerDetail } from '#layers/admin/app/types/admin-seller'
import {
  ADMIN_SELLER_TRANSITION_LABEL,
  type AdminSellerStatus,
} from '#layers/admin/app/lib/constants/admin-seller'
import { ADMIN_PRODUCT_STATUS_LABEL, type AdminProductStatus } from '#layers/admin/app/lib/constants/product'
import { ADMIN_SETTLEMENT_STATUS_LABEL } from '#layers/admin/app/lib/constants/admin-settlement'
import {
  availableTransitions,
  formatTerminationBlocks,
  loginableMemberCount,
  memberDisplayName,
  sellerStatusChipClass,
  sellerStatusLabel,
  terminateBlockedReason,
  toSellerProductListPath,
  toSellerSettlementsPath,
} from '#layers/admin/app/lib/admin-seller-view'
import { formatPercent } from '#layers/admin/app/lib/admin-category-view'
import { formatWon } from '#layers/admin/app/lib/format'
import { ADMIN_SELLERS_PATH, resolveBackPath } from '#layers/admin/app/lib/admin-back-path'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { formatDateTime } from '~/lib/utils/datetime'
import { useAdminSellers } from '#layers/admin/app/composables/useAdminSellers'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '셀러 상세 · zslab-mall 관리자' })

/**
 * 셀러 상세(FE-40·Track 89-D D-187·회원 상세 패턴·별도 라우트). 기본 정보·구성원(탈퇴 표기·로그인 가능 0명 경고)·주 계좌(끝 4자리·미등록 경고)·
 * 집계(상태별 상품·주문·구매확정 매출·정산 상태별)와 상태 전이 버튼(현재 상태에서 가능한 목표만·종료는 terminable=false면 비활성 + 차단 사유 툴팁)·
 * 정보 수정을 담당한다. 전이 응답(200)은 전이 후 상세라 재조회 없이 즉시 반영하고, 수정(204)은 다시 읽는다.
 */
const route = useRoute()
const sellersApi = useAdminSellers()

const publicId = computed<string>(() => String(route.params.id ?? ''))
const backPath = computed(() => resolveBackPath(route.query.back, ADMIN_SELLERS_PATH))

const detail = ref<AdminSellerDetail | null>(null)
const loading = ref(true)
const notFound = ref(false)
const loadError = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  notFound.value = false
  loadError.value = null
  try {
    detail.value = await sellersApi.get(publicId.value)
  } catch (error) {
    if (extractErrorCode(error) === 'SELLER_NOT_FOUND') {
      notFound.value = true
    } else {
      loadError.value = toAdminErrorMessage(error)
    }
  } finally {
    loading.value = false
  }
}
onMounted(load)

// ---------- 파생 표시 ----------
const transitions = computed(() => (detail.value ? availableTransitions(detail.value.status) : []))
const terminateBlocked = computed(() => (detail.value ? terminateBlockedReason(detail.value) : null))
const loginableMembers = computed(() => (detail.value ? loginableMemberCount(detail.value.members) : 0))
const productRows = computed(() => {
  const counts = detail.value?.productCountByStatus ?? {}
  return (Object.keys(counts) as AdminProductStatus[])
    .filter((status) => (counts[status] ?? 0) > 0)
    .map((status) => ({ status, label: ADMIN_PRODUCT_STATUS_LABEL[status], count: counts[status] ?? 0 }))
})

function transitionDisabled(target: AdminSellerStatus): boolean {
  return target === 'TERMINATED' && terminateBlocked.value !== null
}
function transitionColor(target: AdminSellerStatus): string {
  return target === 'TERMINATED' ? 'error' : target === 'SUSPENDED' ? 'warning' : 'primary'
}

// ---------- 다이얼로그 ----------
const editOpen = ref(false)
const statusTarget = ref<Exclude<AdminSellerStatus, 'PENDING'> | null>(null)

function onStatusDone(updated: AdminSellerDetail): void {
  statusTarget.value = null
  detail.value = updated // 전이 응답 = 전이 후 상세(terminable 갱신 포함)
}
function onStale(): void {
  statusTarget.value = null
  editOpen.value = false
  void load()
}
function onEditDone(): void {
  editOpen.value = false
  void load() // PUT 204 → 재조회
}
</script>

<template>
  <div>
    <AdminPageHeader title="셀러 상세" :description="detail ? `${detail.companyName} · ${detail.ceoName}` : undefined">
      <template #actions>
        <v-btn variant="text" :prepend-icon="mdiArrowLeft" :to="backPath" data-testid="seller-back">목록</v-btn>
      </template>
    </AdminPageHeader>

    <v-card v-if="loading" class="mb-4"><v-card-text><v-skeleton-loader type="article, table" /></v-card-text></v-card>

    <v-card v-else-if="notFound" data-testid="seller-not-found">
      <v-card-text class="d-flex flex-column align-center text-center py-12">
        <p class="text-subtitle-1 font-weight-medium mb-1">셀러를 찾을 수 없습니다</p>
        <p class="text-body-2 text-medium-emphasis mb-4">존재하지 않거나 삭제된 셀러입니다: {{ publicId }}</p>
        <v-btn color="primary" :to="backPath">목록으로</v-btn>
      </v-card-text>
    </v-card>

    <v-alert v-else-if="loadError" type="error" class="mb-4" data-testid="seller-load-error">
      {{ loadError }} <v-btn size="small" variant="outlined" color="error" class="ml-2" @click="load">다시 시도</v-btn>
    </v-alert>

    <template v-else-if="detail">
      <!-- 경고 -->
      <v-alert v-if="detail.status === 'TERMINATED'" type="info" variant="tonal" density="compact" class="mb-4" data-testid="seller-terminated-notice">
        종료된 셀러입니다. 상태를 되돌릴 수 없으며 상품은 카탈로그에 노출되지 않습니다. 남은 확정 매출의 정산은 계속 생성됩니다.
      </v-alert>
      <v-alert v-if="detail.status !== 'TERMINATED' && loginableMembers === 0" type="warning" variant="tonal" density="compact" :icon="mdiAlertOutline" class="mb-4" data-testid="seller-no-login-member">
        로그인 가능한 구성원이 없습니다(구성원 {{ detail.members.length }}명 중 활성 0명). 셀러 계정으로 주문·송장·클레임을 처리할 사람이 없습니다.
      </v-alert>
      <v-alert v-if="detail.warnings.primaryBankAccountMissing && detail.status !== 'TERMINATED'" type="warning" variant="tonal" density="compact" :icon="mdiAlertOutline" class="mb-4" data-testid="seller-no-bank-account">
        주 정산계좌가 등록되지 않았습니다. 정산은 생성되지만 지급 처리가 차단됩니다(계좌 등록은 별도 트랙).
      </v-alert>

      <!-- 기본 정보 + 액션 -->
      <v-card class="mb-4" data-testid="seller-info">
        <v-card-title class="d-flex align-center justify-space-between flex-wrap ga-2 pt-4 px-5">
          <div class="d-flex align-center ga-2">
            <span class="text-subtitle-2 font-weight-bold">기본 정보</span>
            <v-chip size="small" variant="flat" :class="sellerStatusChipClass(detail.status)" data-testid="seller-status">{{ sellerStatusLabel(detail.status) }}</v-chip>
          </div>
          <div class="d-flex align-center flex-wrap ga-2">
            <v-btn size="small" variant="outlined" color="primary" data-testid="action-edit" @click="editOpen = true">정보 수정</v-btn>
            <template v-for="target in transitions" :key="target">
              <!-- 비활성 버튼은 이벤트를 받지 않으므로 툴팁은 감싸는 span에 건다(AdminOperatorTable 패턴). -->
              <v-tooltip :disabled="!transitionDisabled(target)" location="top">
                <template #activator="{ props: tooltipProps }">
                  <span v-bind="tooltipProps" :data-testid="`action-status-${target}-wrapper`">
                    <v-btn
                      size="small"
                      variant="outlined"
                      :color="transitionColor(target)"
                      :disabled="transitionDisabled(target)"
                      :data-testid="`action-status-${target}`"
                      @click="statusTarget = target"
                    >{{ ADMIN_SELLER_TRANSITION_LABEL[target] }}</v-btn>
                  </span>
                </template>
                <span data-testid="action-terminate-blocked">{{ terminateBlocked }}</span>
              </v-tooltip>
            </template>
          </div>
        </v-card-title>
        <v-card-text class="px-5 pb-5">
          <v-row dense>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">상호명</div><div class="text-body-2" data-testid="seller-company">{{ detail.companyName }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">사업자등록번호</div><div class="text-body-2" data-testid="seller-business-no">{{ detail.businessNo ?? '—' }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">대표자</div><div class="text-body-2" data-testid="seller-ceo">{{ detail.ceoName }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">수수료율</div><div class="text-body-2" data-testid="seller-rate">{{ detail.commissionRate !== undefined ? formatPercent(detail.commissionRate) : '미설정 (카테고리율 → 기본율)' }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">담당자 이메일</div><div class="text-body-2" data-testid="seller-email">{{ detail.contactEmail ?? '—' }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">담당자 연락처</div><div class="text-body-2" data-testid="seller-phone">{{ detail.contactPhone ?? '—' }}</div></v-col>
            <v-col cols="6" md="3"><div class="text-caption text-medium-emphasis">등록일</div><div class="text-body-2">{{ formatDateTime(detail.createdAt) }}</div></v-col>
            <v-col cols="12" md="3"><div class="text-caption text-medium-emphasis">셀러 ID</div><div class="adm-product-id">{{ detail.sellerPublicId }}</div></v-col>
          </v-row>
          <p v-if="terminateBlocked && transitions.includes('TERMINATED')" class="text-caption text-medium-emphasis mt-3 mb-0" data-testid="seller-terminate-blocked-text">
            {{ terminateBlocked }} — 정산 지급·주문 처리·클레임 종결 후 종료할 수 있습니다.
          </p>
        </v-card-text>
      </v-card>

      <v-row dense class="mb-1">
        <!-- 구성원 -->
        <v-col cols="12" md="7">
          <v-card class="mb-4 h-100" data-testid="seller-members">
            <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">구성원 ({{ detail.members.length }})</v-card-title>
            <v-card-text class="px-5 pb-5">
              <v-table v-if="detail.members.length > 0" density="compact" class="adm-table">
                <thead><tr><th>이름</th><th>이메일</th><th>역할</th><th>상태</th></tr></thead>
                <tbody>
                  <tr v-for="(member, index) in detail.members" :key="member.userPublicId ?? index" data-testid="seller-member-row">
                    <td>
                      <NuxtLink v-if="member.userPublicId" :to="{ path: `/admin/members/${member.userPublicId}`, query: { back: route.fullPath } }" class="text-primary text-decoration-none" data-testid="seller-member-name">{{ memberDisplayName(member) }}</NuxtLink>
                      <span v-else class="text-medium-emphasis" data-testid="seller-member-name">{{ memberDisplayName(member) }}</span>
                    </td>
                    <td>{{ member.email ?? '—' }}</td>
                    <td>{{ member.roleCode ?? '—' }}</td>
                    <td>
                      <span v-if="member.withdrawnAt" class="text-medium-emphasis" data-testid="seller-member-withdrawn">탈퇴 ({{ formatDateTime(member.withdrawnAt) }})</span>
                      <span v-else-if="!member.userPublicId" class="text-medium-emphasis">삭제됨</span>
                      <span v-else data-testid="seller-member-active">활성</span>
                    </td>
                  </tr>
                </tbody>
              </v-table>
              <p v-else class="text-body-2 text-medium-emphasis" data-testid="seller-members-empty">소속 구성원이 없습니다.</p>
            </v-card-text>
          </v-card>
        </v-col>
        <!-- 계좌 -->
        <v-col cols="12" md="5">
          <v-card class="mb-4 h-100" data-testid="seller-bank-account">
            <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">주 정산계좌</v-card-title>
            <v-card-text class="px-5 pb-5">
              <template v-if="detail.primaryBankAccount">
                <div class="text-body-1 font-weight-medium" data-testid="seller-bank-number">{{ detail.primaryBankAccount.bankCode }} ····{{ detail.primaryBankAccount.accountNumberSuffix }}</div>
                <div class="text-body-2">예금주: {{ detail.primaryBankAccount.accountHolder }}</div>
                <div class="text-body-2">인증: {{ detail.primaryBankAccount.status }}<span v-if="detail.primaryBankAccount.verifiedAt"> ({{ formatDateTime(detail.primaryBankAccount.verifiedAt) }})</span></div>
              </template>
              <p v-else class="text-body-2 text-warning" data-testid="seller-bank-missing">미등록 — 정산 지급이 차단됩니다.</p>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>

      <!-- 집계 -->
      <v-row dense>
        <v-col cols="12" md="4">
          <v-card class="mb-4 h-100" data-testid="seller-products">
            <v-card-title class="d-flex align-center justify-space-between pt-4 px-5">
              <span class="text-subtitle-2 font-weight-bold">상품 ({{ detail.productCount }})</span>
              <v-btn v-if="detail.productCount > 0" size="small" variant="text" color="primary" :append-icon="mdiOpenInNew" :to="toSellerProductListPath(detail.sellerPublicId)" data-testid="seller-products-link">상품 목록</v-btn>
            </v-card-title>
            <v-card-text class="px-5 pb-5">
              <div v-for="row in productRows" :key="row.status" class="d-flex justify-space-between text-body-2" :data-testid="`seller-product-${row.status}`">
                <span>{{ row.label }}</span><span class="font-weight-medium">{{ row.count }}</span>
              </div>
              <p v-if="productRows.length === 0" class="text-body-2 text-medium-emphasis mb-0">등록된 상품이 없습니다.</p>
              <p v-if="detail.warnings.saleProductCount > 0 && detail.status !== 'ACTIVE'" class="text-caption text-warning mt-2 mb-0" data-testid="seller-sale-hidden-note">
                판매중 상품 {{ detail.warnings.saleProductCount }}건은 셀러가 활성이 아니라 카탈로그에 노출되지 않습니다.
              </p>
            </v-card-text>
          </v-card>
        </v-col>
        <v-col cols="12" md="4">
          <v-card class="mb-4 h-100" data-testid="seller-sales">
            <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">거래</v-card-title>
            <v-card-text class="px-5 pb-5">
              <div class="d-flex justify-space-between text-body-2"><span>주문 수(결제 이력)</span><span class="font-weight-medium" data-testid="seller-order-count">{{ detail.orderCount.toLocaleString('ko-KR') }}건</span></div>
              <div class="d-flex justify-space-between text-body-2"><span>누적 매출(구매확정)</span><span class="font-weight-medium" data-testid="seller-sales-amount">{{ formatWon(detail.confirmedSalesAmount) }}</span></div>
            </v-card-text>
          </v-card>
        </v-col>
        <v-col cols="12" md="4">
          <v-card class="mb-4 h-100" data-testid="seller-settlements">
            <v-card-title class="d-flex align-center justify-space-between pt-4 px-5">
              <span class="text-subtitle-2 font-weight-bold">정산</span>
              <v-btn size="small" variant="text" color="primary" :append-icon="mdiOpenInNew" :to="toSellerSettlementsPath(detail.sellerPublicId)" data-testid="seller-settlements-link">정산 이력</v-btn>
            </v-card-title>
            <v-card-text class="px-5 pb-5">
              <div v-for="total in detail.settlements" :key="total.status" class="d-flex justify-space-between text-body-2" :data-testid="`seller-settlement-${total.status}`">
                <span>{{ ADMIN_SETTLEMENT_STATUS_LABEL[total.status] }} {{ total.count }}건</span><span class="font-weight-medium">{{ formatWon(total.netAmount) }}</span>
              </div>
              <p v-if="detail.settlements.length === 0" class="text-body-2 text-medium-emphasis mb-0">정산 이력이 없습니다.</p>
              <p v-if="!detail.terminable" class="text-caption text-medium-emphasis mt-2 mb-0" data-testid="seller-termination-blocks">종료 차단: {{ formatTerminationBlocks(detail.terminationBlocks) }}</p>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>
    </template>

    <AdminSellerEditDialog :open="editOpen" :detail="detail" @done="onEditDone" @stale="onStale" @cancel="editOpen = false" />
    <AdminSellerStatusDialog :open="statusTarget !== null" :detail="detail" :target="statusTarget" @done="onStatusDone" @stale="onStale" @cancel="statusTarget = null" />
  </div>
</template>
