<script setup lang="ts">
import { mdiArrowLeft, mdiChevronDown, mdiChevronUp } from '@mdi/js'
import type { AdminOrderClaim, AdminOrderDetail, AdminOrderPayment } from '#layers/admin/app/types/admin-order'
import type { AdminReconciliationIssue } from '#layers/admin/app/types/admin-reconciliation'
import { orderStatusLabel } from '~/lib/constants/order'
import {
  CLAIM_REASON_LABELS,
  claimRejectReasonLabel,
  claimStatusLabel,
  claimTypeLabel,
  orderItemStatusLabel,
  type ClaimReasonCode,
} from '~/lib/constants/claim'
import type { AdminClaimRejectTarget } from '#layers/admin/app/components/admin/AdminClaimRejectDialog.vue'
import { approveConfirmMessage, inspectionChip } from '#layers/admin/app/lib/admin-claim-view'
import { formatDateTime } from '~/lib/utils/datetime'
import {
  ADMIN_CLAIM_STATUS_SEMANTIC,
  ADMIN_DELIVERY_CARRIER_LABEL,
  ADMIN_DELIVERY_STATUS_LABEL,
  ADMIN_DELIVERY_STATUS_SEMANTIC,
  ADMIN_ORDER_ITEM_STATUS_SEMANTIC,
  ADMIN_ORDER_STATUS_SEMANTIC,
  ADMIN_PAYMENT_STATUS_LABEL,
  ADMIN_PAYMENT_STATUS_SEMANTIC,
  paymentMethodLabel,
} from '#layers/admin/app/lib/constants/admin-order'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'
import { formatWon } from '#layers/admin/app/lib/format'
import { claimRefundLabel, isPaymentCancelLost } from '#layers/admin/app/lib/admin-order-view'
import { ADMIN_ORDERS_PATH, resolveBackPath } from '#layers/admin/app/lib/admin-back-path'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminOrders } from '#layers/admin/app/composables/useAdminOrders'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'
import { formatPhone } from '~/lib/format/phone'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '주문 상세 · zslab-mall 관리자' })

// 주문 상세(FE-27). 주문·주문자·배송지·결제·품목(배송·클레임)을 읽기 전용으로 보이고, 취소·송장·배송완료·클레임 승인/거부는
// 여기서만 실행한다. 모든 변경 후에는 상세를 다시 읽는다(응답 조립 대신 서버 상태 재확인). 미존재(404)는 안내 + 목록 이동.
const route = useRoute()
const ordersApi = useAdminOrders()
const toast = useAdminToast()

const orderPublicId = computed<string>(() => String(route.params.id ?? ''))
const backPath = computed(() => resolveBackPath(route.query.back, ADMIN_ORDERS_PATH))

const detail = ref<AdminOrderDetail | null>(null)
const loading = ref(true)
const notFound = ref(false)
const loadError = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  notFound.value = false
  loadError.value = null
  try {
    detail.value = await ordersApi.detail(orderPublicId.value)
  } catch (error) {
    if (extractErrorCode(error) === 'ORDER_NOT_FOUND') {
      notFound.value = true
    } else {
      loadError.value = toAdminErrorMessage(error)
    }
  } finally {
    loading.value = false
  }
}
onMounted(load)

const isExpired = computed(() => detail.value?.status === 'PAYMENT_EXPIRED')

function reasonLabel(code: string): string {
  return CLAIM_REASON_LABELS[code as ClaimReasonCode] ?? code
}

// ---------- 다이얼로그(취소·송장·배송완료) ----------
type DetailDialog = 'cancel' | 'shipment' | 'delivered'
const activeDialog = ref<DetailDialog | null>(null)

function closeDialog(refresh: boolean): void {
  activeDialog.value = null
  if (refresh) void load()
}

// ---------- 수동 결제 취소(FE-36·Track 89-A): PAID 결제 행에만 노출 — BE 전이는 PAID→CANCELLED뿐(그 외 NO-OP·404) ----------
const paymentCancelTarget = ref<AdminOrderPayment | null>(null)

function closePaymentCancel(refresh: boolean): void {
  paymentCancelTarget.value = null
  if (refresh) void load()
}

// ---------- 불일치 해결(Track 104-2 FE-66): 표시만 바꾸는 기록이라 결제·주문은 그대로 — 끝나면 섹션을 다시 읽는다 ----------
const reconciliationTarget = ref<AdminReconciliationIssue | null>(null)

function closeReconciliation(refresh: boolean): void {
  reconciliationTarget.value = null
  if (refresh) void load()
}

// ---------- 클레임 승인(확인 다이얼로그) · 거부(사유 다이얼로그·FE-28 공용) → 기존 단건 API ----------
// 첨부 사진 확대(FE-29·Track 81-B): 클릭한 원본 URL을 v-dialog로 띄운다.
const previewUrl = ref<string | null>(null)

/** 품목 배송이 검수 불합격 재발송인지(BE는 품목 배송으로 최신 발송(OUTBOUND)을 내리므로 FAIL 반품이 있으면 그 송장이 재발송이다·D-170). */
function isReshipment(item: AdminOrderDetail['items'][number]): boolean {
  return item.claims.some((claim) => claim.type === 'RETURN' && claim.inspectionResult === 'FAIL')
}

type ClaimDecision = { claim: AdminOrderClaim; productName: string }
const claimDecision = ref<ClaimDecision | null>(null)
const claimBusy = ref(false)
const rejectTarget = ref<AdminClaimRejectTarget | null>(null)

const claimDecisionMessage = computed<string>(() => {
  const decision = claimDecision.value
  return decision ? approveConfirmMessage(decision.claim.type, decision.productName) : ''
})

async function runClaimDecision(): Promise<void> {
  const decision = claimDecision.value
  if (!decision || claimBusy.value) return
  claimBusy.value = true
  try {
    await ordersApi.approveClaim(decision.claim.claimId)
    toast.info(`${claimTypeLabel(decision.claim.type)} 요청을 승인했습니다.`) // 상태 전환은 중립(취소는 재조회 시 환불 완료로 보임)
    claimDecision.value = null
    await load()
  } catch (error) {
    claimDecision.value = null
    if (extractErrorCode(error) === 'CLAIM_STATE_INVALID') {
      toast.warning(toAdminErrorMessage(error))
      await load()
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    claimBusy.value = false
  }
}

// Track 101-A: 클레임 1건의 처리 이력을 펼친다(한 번에 하나만·목록이 길어지지 않게).
const auditClaimId = ref<string | null>(null)

function toggleClaimAudit(claimId: string): void {
  auditClaimId.value = auditClaimId.value === claimId ? null : claimId
}

/** 펼친 클레임의 첫 페이지 로더. 대상이 바뀌면 참조가 바뀌어 섹션이 다시 읽는다. */
const claimAuditLoader = computed(() => {
  const claimId = auditClaimId.value
  if (!claimId) return null
  return () => ordersApi.claimAuditLogs(claimId)
})

function openReject(claim: AdminOrderClaim, productName: string): void {
  rejectTarget.value = { claimId: claim.claimId, type: claim.type, productName }
}

function closeReject(refresh: boolean): void {
  rejectTarget.value = null
  if (refresh) void load()
}
</script>

<template>
  <div>
    <AdminPageHeader title="주문 상세" :description="detail?.orderNo ?? orderPublicId" guide="품목별로 발송 처리·배송완료·클레임 승인·거부를 하고, 주문 취소·결제 취소를 처리합니다.">
      <template #actions>
        <v-btn variant="text" :prepend-icon="mdiArrowLeft" :to="backPath" data-testid="back-to-list">목록으로</v-btn>
        <v-btn
          v-if="detail?.actions.includes('CANCEL')"
          color="error"
          variant="flat"
          data-testid="open-cancel"
          @click="activeDialog = 'cancel'"
        >주문 취소</v-btn>
        <v-btn
          v-if="detail?.actions.includes('PREPARE_SHIPMENT')"
          color="primary"
          variant="outlined"
          data-testid="open-shipment"
          @click="activeDialog = 'shipment'"
        >발송 처리</v-btn>
        <v-btn
          v-if="detail?.actions.includes('MARK_DELIVERED')"
          color="success"
          variant="outlined"
          data-testid="open-delivered"
          @click="activeDialog = 'delivered'"
        >배송완료 처리</v-btn>
      </template>
    </AdminPageHeader>

    <v-card v-if="loading" class="mb-4"><v-card-text><v-skeleton-loader type="article, table" /></v-card-text></v-card>

    <v-card v-else-if="notFound" data-testid="order-not-found">
      <v-card-text class="d-flex flex-column align-center text-center py-12">
        <p class="text-subtitle-1 font-weight-medium mb-1">주문을 찾을 수 없습니다</p>
        <p class="text-body-2 text-medium-emphasis mb-4">존재하지 않는 주문입니다: {{ orderPublicId }}</p>
        <v-btn color="primary" :to="backPath">목록으로</v-btn>
      </v-card-text>
    </v-card>

    <v-alert v-else-if="loadError" type="error" class="mb-4" data-testid="order-load-error">
      {{ loadError }} <v-btn size="small" variant="outlined" color="error" class="ml-2" @click="load">다시 시도</v-btn>
    </v-alert>

    <template v-else-if="detail">
      <!-- 주문 요약 -->
      <v-card class="mb-4" data-testid="order-summary">
        <v-card-text class="pa-5">
          <div class="d-flex align-center flex-wrap ga-3 mb-4">
            <span class="text-h6 font-weight-bold">{{ detail.orderNo }}</span>
            <v-chip :class="semanticChipClass(ADMIN_ORDER_STATUS_SEMANTIC[detail.status])" size="small" variant="flat" data-testid="order-status-chip">
              {{ orderStatusLabel(detail.status) }}
            </v-chip>
          </div>
          <v-row dense>
            <v-col cols="12" md="3">
              <div class="text-caption text-medium-emphasis">주문 ID</div>
              <div class="adm-product-id">{{ detail.orderId }}</div>
            </v-col>
            <v-col cols="6" md="3">
              <div class="text-caption text-medium-emphasis">주문일시</div>
              <div class="text-body-2">{{ formatDateTime(detail.orderedAt) }}</div>
            </v-col>
            <v-col cols="6" md="3">
              <div class="text-caption text-medium-emphasis">결제일시</div>
              <div class="text-body-2">{{ detail.paidAt ? formatDateTime(detail.paidAt) : '—' }}</div>
            </v-col>
            <v-col cols="12" md="3">
              <div class="text-caption text-medium-emphasis">결제금액</div>
              <div class="text-body-1 font-weight-bold">{{ formatWon(detail.paymentAmount) }}</div>
            </v-col>
          </v-row>

          <!-- 미결제 종료: 관리자 취소면 audit 사유 병기, 없으면 시스템 종료(만료·PG 실패) -->
          <v-alert v-if="isExpired" type="warning" variant="tonal" density="compact" class="mt-4" data-testid="expired-notice">
            <div class="font-weight-medium mb-1">미결제 종료</div>
            <template v-if="detail.cancelReasons.length > 0">
              <div v-for="(reason, index) in detail.cancelReasons" :key="index" class="text-body-2" data-testid="cancel-reason">
                관리자 취소 · {{ reasonLabel(reason.reasonCode) }}<span v-if="reason.reasonDetail"> — {{ reason.reasonDetail }}</span>
                <span class="text-medium-emphasis"> ({{ reason.actorRole ?? 'ADMIN' }} · {{ formatDateTime(reason.recordedAt) }})</span>
              </div>
            </template>
            <div v-else class="text-body-2">결제창 이탈·결제 실패 또는 미결제 만료로 시스템이 종료한 주문입니다.</div>
          </v-alert>
        </v-card-text>
      </v-card>

      <v-row dense class="mb-1">
        <!-- 주문자 -->
        <v-col cols="12" md="6">
          <v-card class="mb-4 h-100" data-testid="order-buyer">
            <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">주문자</v-card-title>
            <v-card-text class="px-5 pb-5">
              <template v-if="detail.buyer">
                <div class="text-body-1 font-weight-medium">{{ detail.buyer.name ?? '—' }}</div>
                <div class="text-body-2">{{ detail.buyer.email ?? '—' }}</div>
                <div class="adm-product-id mt-1">{{ detail.buyer.userId }}</div>
              </template>
              <p v-else class="text-body-2 text-medium-emphasis">주문자 정보가 없습니다(탈퇴 회원).</p>
            </v-card-text>
          </v-card>
        </v-col>
        <!-- 배송지 -->
        <v-col cols="12" md="6">
          <v-card class="mb-4 h-100" data-testid="order-shipping-address">
            <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">배송지</v-card-title>
            <v-card-text class="px-5 pb-5">
              <template v-if="detail.shippingAddress">
                <div class="text-body-1 font-weight-medium">
                  {{ detail.shippingAddress.recipientName }} <span class="text-body-2 text-medium-emphasis">{{ formatPhone(detail.shippingAddress.recipientPhone) }}</span>
                </div>
                <div class="text-body-2">
                  [{{ detail.shippingAddress.zonecode }}] {{ detail.shippingAddress.addressRoad }}
                  <span v-if="detail.shippingAddress.addressDetail"> {{ detail.shippingAddress.addressDetail }}</span>
                </div>
                <div v-if="detail.shippingAddress.addressJibun" class="text-caption text-medium-emphasis">지번: {{ detail.shippingAddress.addressJibun }}</div>
                <div v-if="detail.shippingAddress.deliveryMemo" class="text-body-2 mt-1">메모: {{ detail.shippingAddress.deliveryMemo }}</div>
              </template>
              <p v-else class="text-body-2 text-medium-emphasis">배송지 스냅샷이 없습니다.</p>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>

      <!-- 결제 -->
      <v-card class="mb-4" data-testid="order-payments">
        <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">결제</v-card-title>
        <v-card-text class="px-5 pb-5">
          <div class="d-flex flex-wrap ga-6 mb-4">
            <div><div class="text-caption text-medium-emphasis">상품금액</div><div class="text-body-2">{{ formatWon(detail.totalPrice) }}</div></div>
            <div><div class="text-caption text-medium-emphasis">할인</div><div class="text-body-2">−{{ formatWon(detail.discountAmount) }}</div></div>
            <div><div class="text-caption text-medium-emphasis">배송비</div><div class="text-body-2">{{ formatWon(detail.shippingFee) }}</div></div>
            <div><div class="text-caption text-medium-emphasis">결제금액</div><div class="text-body-2 font-weight-bold">{{ formatWon(detail.paymentAmount) }}</div></div>
          </div>
          <v-table v-if="detail.payments.length > 0" density="compact" class="adm-table">
            <thead>
              <tr><th>결제수단</th><th>상태</th><th class="text-right">금액</th><th>PG</th><th>PG 거래번호</th><th>실패코드</th><th>결제일시</th><th>생성일시</th><th class="text-right">관리</th></tr>
            </thead>
            <tbody>
              <tr v-for="payment in detail.payments" :key="payment.paymentId" data-testid="payment-row">
                <td>{{ paymentMethodLabel(payment.method) }}</td>
                <td>
                  <v-chip :class="semanticChipClass(ADMIN_PAYMENT_STATUS_SEMANTIC[payment.status])" size="small" variant="flat">
                    {{ ADMIN_PAYMENT_STATUS_LABEL[payment.status] }}
                  </v-chip>
                  <!-- Track 104-2 FE-66: C-12 계산형 경고 배지는 저장된 불일치 섹션(아래)으로 대체했다. 수동 취소 버튼은 같은 계산 조건 그대로. -->
                </td>
                <td class="text-right">{{ formatWon(payment.amount) }}</td>
                <td>{{ payment.pgProvider ?? '—' }}</td>
                <td data-testid="payment-pg-tid">{{ payment.pgTid ?? '—' }}</td>
                <td data-testid="payment-failure-code">{{ payment.failureCode ?? '—' }}</td>
                <td>{{ payment.paidAt ? formatDateTime(payment.paidAt) : '—' }}</td>
                <td>{{ formatDateTime(payment.createdAt) }}</td>
                <td class="text-right">
                  <v-btn
                    v-if="isPaymentCancelLost(payment)"
                    size="small"
                    variant="outlined"
                    color="error"
                    data-testid="payment-cancel"
                    @click="paymentCancelTarget = payment"
                  >취소 처리</v-btn>
                  <span v-else class="text-caption text-medium-emphasis">—</span>
                </td>
              </tr>
            </tbody>
          </v-table>
          <p v-else class="text-body-2 text-medium-emphasis">결제 이력이 없습니다.</p>
        </v-card-text>
      </v-card>

      <!-- 불일치(Track 104-2 FE-66·D-216): 이 주문에 기록된 결제·주문·환불 불일치. 없으면 섹션 자체를 그리지 않는다. -->
      <v-card v-if="detail.reconciliationIssues.length > 0" class="mb-4" data-testid="order-reconciliation">
        <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">불일치 ({{ detail.reconciliationIssues.length }})</v-card-title>
        <v-card-text class="px-5 pb-3">
          <AdminReconciliationIssueList :issues="detail.reconciliationIssues" @resolve="(issue) => (reconciliationTarget = issue)" />
        </v-card-text>
      </v-card>

      <!-- 품목 -->
      <v-card class="mb-4" data-testid="order-items">
        <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">품목 ({{ detail.items.length }})</v-card-title>
        <v-card-text class="px-5 pb-5">
          <div v-for="item in detail.items" :key="item.orderItemId" class="adm-order-item py-4" data-testid="order-item">
            <div class="d-flex align-start justify-space-between flex-wrap ga-3">
              <div style="min-width: 0">
                <div class="text-body-1 font-weight-medium">{{ item.productName }}</div>
                <div class="text-caption text-medium-emphasis">
                  {{ item.optionLabel ? `${item.optionLabel} · ` : '' }}{{ formatWon(item.unitPrice) }} × {{ item.quantity }} = {{ formatWon(item.totalPrice) }}
                  · 셀러 {{ item.sellerName ?? '—' }}
                </div>
                <div class="adm-product-id">{{ item.orderItemId }}</div>
              </div>
              <v-chip :class="semanticChipClass(ADMIN_ORDER_ITEM_STATUS_SEMANTIC[item.status])" size="small" variant="flat" data-testid="item-status-chip">
                {{ orderItemStatusLabel(item.status) }}
              </v-chip>
            </div>

            <!-- 배송 -->
            <div class="mt-3 text-body-2" data-testid="item-delivery">
              <span class="text-caption text-medium-emphasis mr-2">배송</span>
              <template v-if="item.delivery">
                <v-chip :class="semanticChipClass(ADMIN_DELIVERY_STATUS_SEMANTIC[item.delivery.status])" size="x-small" variant="flat" class="mr-2">
                  {{ ADMIN_DELIVERY_STATUS_LABEL[item.delivery.status] }}
                </v-chip>
                <v-chip v-if="isReshipment(item)" size="x-small" variant="tonal" class="mr-2" data-testid="item-reshipment-chip">재발송</v-chip>
                {{ ADMIN_DELIVERY_CARRIER_LABEL[item.delivery.carrier] }} {{ item.delivery.trackingNo }}
                <span class="text-medium-emphasis">
                  · 발송 {{ item.delivery.shippedAt ? formatDateTime(item.delivery.shippedAt) : '—' }}
                  · 도착 {{ item.delivery.deliveredAt ? formatDateTime(item.delivery.deliveredAt) : '—' }}
                </span>
              </template>
              <span v-else class="text-medium-emphasis">없음</span>
            </div>

            <!-- 클레임 -->
            <div v-if="item.claims.length > 0" class="mt-2">
              <template v-for="claim in item.claims" :key="claim.claimId">
              <div class="d-flex align-center flex-wrap ga-2 py-1 text-body-2" data-testid="item-claim">
                <span class="text-caption text-medium-emphasis">클레임</span>
                <span class="font-weight-medium">{{ claimTypeLabel(claim.type) }}</span>
                <v-chip :class="semanticChipClass(ADMIN_CLAIM_STATUS_SEMANTIC[claim.status])" size="x-small" variant="flat" data-testid="claim-status-chip">
                  {{ claimStatusLabel(claim.status) }}
                </v-chip>
                <v-chip
                  v-if="claimRefundLabel(claim)"
                  :class="semanticChipClass(claimRefundLabel(claim)!.semantic)"
                  size="x-small"
                  variant="flat"
                  data-testid="claim-refund-chip"
                >
                  {{ claimRefundLabel(claim)!.text }}
                </v-chip>
                <span>{{ reasonLabel(claim.reasonCode) }}<span v-if="claim.reasonDetail" class="text-medium-emphasis"> — {{ claim.reasonDetail }}</span></span>
                <span v-if="claim.type === 'EXCHANGE' && (claim.originalOptionLabel || claim.exchangeOptionLabel)" class="text-medium-emphasis" data-testid="claim-exchange-option">
                  · 교환 {{ claim.originalOptionLabel ?? '—' }} → {{ claim.exchangeOptionLabel ?? '—' }}
                </span>
                <span v-if="claim.rejectReasonCode" class="text-error" data-testid="claim-reject-reason">
                  거부: {{ claimRejectReasonLabel(claim.rejectReasonCode) }}<span v-if="claim.rejectMemo" class="text-medium-emphasis"> — {{ claim.rejectMemo }}</span>
                </span>
                <!-- 반품 회수·검수(FE-29·Track 81-A): 값이 있을 때만 -->
                <span v-if="claim.returnCarrier && claim.returnTrackingNo" class="text-medium-emphasis" data-testid="claim-return-shipment">
                  · 회수 {{ ADMIN_DELIVERY_CARRIER_LABEL[claim.returnCarrier] }} {{ claim.returnTrackingNo }}
                </span>
                <span v-if="claim.pickedUpAt" class="text-medium-emphasis" data-testid="claim-picked-up-at">· 회수 확인 {{ formatDateTime(claim.pickedUpAt) }}</span>
                <v-chip
                  v-if="inspectionChip(claim.inspectionResult, claim.restock)"
                  :class="semanticChipClass(inspectionChip(claim.inspectionResult, claim.restock)!.semantic)"
                  size="x-small"
                  variant="flat"
                  data-testid="claim-inspection-chip"
                >
                  {{ inspectionChip(claim.inspectionResult, claim.restock)!.text }}
                </v-chip>
                <!-- 첨부 사진(Track 81-B): 썸네일·클릭 확대 -->
                <span v-if="claim.attachmentUrls && claim.attachmentUrls.length > 0" class="d-flex align-center ga-1" data-testid="claim-attachments">
                  <button
                    v-for="(url, index) in claim.attachmentUrls"
                    :key="url"
                    type="button"
                    class="adm-claim-thumb"
                    :title="`첨부 사진 ${index + 1}`"
                    data-testid="claim-attachment-thumb"
                    @click="previewUrl = url"
                  >
                    <img :src="url" :alt="`첨부 사진 ${index + 1}`">
                  </button>
                </span>
                <span class="text-medium-emphasis">
                  · 요청 {{ formatDateTime(claim.requestedAt) }}<template v-if="claim.processedAt"> · 처리 {{ formatDateTime(claim.processedAt) }}</template>
                </span>
                <template v-if="claim.approvable">
                  <v-btn size="x-small" variant="flat" class="op-risk-action" data-testid="claim-approve" @click="claimDecision = { claim, productName: item.productName }">승인</v-btn>
                  <v-btn size="x-small" variant="flat" class="op-risk-action" data-testid="claim-reject" @click="openReject(claim, item.productName)">거부</v-btn>
                </template>
                <!-- Track 101-A: 승인·거부·회수 확인·검수가 누구 손에서 이뤄졌는지 이 자리에서 펼쳐 본다.
                     Track 103: 텍스트 버튼은 주변 메타 문구와 섞여 보여 외곽선 + 펼침 아이콘 + aria-expanded로 버튼임을 드러낸다. -->
                <v-btn
                  size="x-small"
                  variant="outlined"
                  :append-icon="auditClaimId === claim.claimId ? mdiChevronUp : mdiChevronDown"
                  :aria-expanded="auditClaimId === claim.claimId"
                  data-testid="claim-audit-toggle"
                  @click="toggleClaimAudit(claim.claimId)"
                >{{ auditClaimId === claim.claimId ? '처리 이력 닫기' : '처리 이력' }}</v-btn>
              </div>
              <AdminAuditLogSection
                v-if="auditClaimId === claim.claimId"
                :loader="claimAuditLoader"
              />
              </template>
            </div>
          </div>
        </v-card-text>
      </v-card>
    </template>

    <AdminOrderCancelDialog :open="activeDialog === 'cancel'" :detail="detail" @done="closeDialog(true)" @stale="closeDialog(true)" @cancel="closeDialog(false)" />
    <AdminShipmentDialog :open="activeDialog === 'shipment'" :detail="detail" @done="closeDialog(true)" @stale="closeDialog(true)" @cancel="closeDialog(false)" />
    <AdminMarkDeliveredDialog :open="activeDialog === 'delivered'" :detail="detail" @done="closeDialog(true)" @stale="closeDialog(true)" @cancel="closeDialog(false)" />
    <AdminPaymentCancelDialog :open="paymentCancelTarget !== null" :payment="paymentCancelTarget" @done="closePaymentCancel(true)" @cancel="closePaymentCancel(false)" />
    <AdminReconciliationResolveDialog
      :open="reconciliationTarget !== null"
      :issue="reconciliationTarget"
      @done="closeReconciliation(true)"
      @stale="closeReconciliation(true)"
      @cancel="closeReconciliation(false)"
    />

    <AdminConfirmDialog
      :open="claimDecision !== null"
      test-id="admin-claim-decision-dialog"
      title="클레임 승인"
      :message="claimDecisionMessage"
      confirm-label="승인"
      risk
      :loading="claimBusy"
      @confirm="runClaimDecision"
      @cancel="claimDecision = null"
    />
    <AdminClaimRejectDialog :open="rejectTarget !== null" :target="rejectTarget" @done="closeReject(true)" @stale="closeReject(true)" @cancel="closeReject(false)" />

    <v-dialog :model-value="previewUrl !== null" max-width="720" @update:model-value="(value: boolean) => !value && (previewUrl = null)">
      <v-card v-if="previewUrl" data-testid="claim-attachment-preview">
        <v-img :src="previewUrl" max-height="80vh" contain />
        <v-card-actions class="px-5 pb-4">
          <v-spacer />
          <v-btn variant="text" @click="previewUrl = null">닫기</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>

<style scoped>
.adm-claim-thumb {
  width: 40px;
  height: 40px;
  padding: 0;
  border: 1px solid rgba(var(--v-border-color), var(--v-border-opacity));
  border-radius: 4px;
  overflow: hidden;
  background: transparent;
  cursor: zoom-in;
}
.adm-claim-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
</style>
