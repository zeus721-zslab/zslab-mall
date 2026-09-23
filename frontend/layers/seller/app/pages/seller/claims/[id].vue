<script setup lang="ts">
import { mdiArrowLeft, mdiCheckCircle, mdiCircleOutline, mdiProgressClock } from '@mdi/js'
import type { SellerClaimDetail } from '#layers/seller/app/types/seller-claim'
import { claimRejectReasonLabel, claimStatusLabel, claimTypeLabel, refundStatusLabel } from '~/lib/constants/claim'
import { formatDateTime } from '~/lib/utils/datetime'
import { SELLER_CLAIM_STATUS_SEMANTIC, SELLER_DELIVERY_STATUS_LABEL, SELLER_DELIVERY_STATUS_SEMANTIC } from '#layers/seller/app/lib/constants/seller-order'
import { semanticChipClass } from '#layers/seller/app/lib/constants/semantic'
import { claimReasonLabel, claimTimeline, type ClaimTimelineStep } from '#layers/seller/app/lib/seller-claim-view'
import { SELLER_CLAIMS_PATH, resolveBackPath } from '#layers/seller/app/lib/seller-back-path'
import { extractErrorCode, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerClaims } from '#layers/seller/app/composables/useSellerClaims'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '클레임 상세 · zslab-mall 셀러' })

// 셀러 클레임 상세(Track 90-D-1·조회 전용·orders/[id].vue 골격 복제). 클레임 정보(사유·상세 사유·환불 상태·교환품 배송 상태) + 상태 타임라인(읽기 전용) +
// 첨부 사진(blob 로더·클릭 확대). 구매자 정보·거부 메모·환불 금액은 BE가 싣지 않는다. 처리 버튼은 없다(관리자 전용). 미존재·타 셀러(404)는 안내 + 목록 이동.
const route = useRoute()
const claimsApi = useSellerClaims()

const claimId = computed<string>(() => String(route.params.id))
const backPath = computed(() => resolveBackPath(route.query.back, SELLER_CLAIMS_PATH))

const detail = ref<SellerClaimDetail | null>(null)
const loading = ref(true)
const notFound = ref(false)
const loadError = ref<string | null>(null)

async function load(): Promise<void> {
  loading.value = true
  notFound.value = false
  loadError.value = null
  try {
    detail.value = await claimsApi.detail(claimId.value)
  } catch (error) {
    if (extractErrorCode(error) === 'CLAIM_NOT_FOUND') {
      notFound.value = true
    } else {
      loadError.value = toSellerErrorMessage(error)
    }
  } finally {
    loading.value = false
  }
}
onMounted(load)

const timeline = computed<ClaimTimelineStep[]>(() => (detail.value ? claimTimeline(detail.value) : []))

function timelineIcon(step: ClaimTimelineStep): string {
  if (step.state === 'done') return mdiCheckCircle
  return step.state === 'current' ? mdiProgressClock : mdiCircleOutline
}

function timelineColor(step: ClaimTimelineStep): string {
  if (step.state === 'done') return 'success'
  return step.state === 'current' ? 'warning' : 'grey-lighten-1'
}

// ---------- 첨부 확대 ----------
// 부모는 어떤 첨부를 확대할지(attachmentId)만 보관한다. object URL은 SellerClaimAttachmentImage(컴포저블)가 소유·해제하므로
// 여기서 URL 문자열을 들거나 revokeObjectURL을 호출하지 않는다(이중 revoke·누수 방지·외부 검토 r4 반영).
const previewAttachmentId = ref<string | null>(null)
const previewAttachment = computed(() => {
  if (!detail.value || previewAttachmentId.value === null) return null
  const index = detail.value.attachments.findIndex((attachment) => attachment.attachmentId === previewAttachmentId.value)
  return index < 0 ? null : { index, attachment: detail.value.attachments[index]! }
})
</script>

<template>
  <div data-testid="seller-claim-detail">
    <SellerPageHeader title="클레임 상세" :description="detail ? `${claimTypeLabel(detail.type)} · ${detail.orderNo} · ${detail.productName}` : undefined">
      <template #actions>
        <v-btn variant="text" :prepend-icon="mdiArrowLeft" :to="backPath" data-testid="claim-detail-back">목록</v-btn>
      </template>
    </SellerPageHeader>

    <v-card v-if="loading" class="mb-4"><v-card-text><v-skeleton-loader type="article, list-item-two-line" /></v-card-text></v-card>

    <v-card v-else-if="notFound" data-testid="claim-detail-not-found">
      <v-card-text class="d-flex flex-column align-center text-center py-12">
        <p class="text-subtitle-1 font-weight-medium mb-1">클레임을 찾을 수 없습니다</p>
        <p class="text-body-2 text-medium-emphasis mb-4">내 품목의 클레임이 아니거나 존재하지 않는 클레임입니다: {{ claimId }}</p>
        <v-btn color="primary" :to="backPath">목록으로</v-btn>
      </v-card-text>
    </v-card>

    <v-alert v-else-if="loadError" type="error" class="mb-4" data-testid="claim-detail-error">
      {{ loadError }} <v-btn size="small" variant="outlined" color="error" class="ml-2" @click="load">다시 시도</v-btn>
    </v-alert>

    <template v-else-if="detail">
      <!-- Track 103: 카드 줄과 아래 카드 사이 간격(h-100 카드의 mb-4는 칸 밖으로 넘쳐 간격이 되지 않는다) -->
      <v-row dense class="mb-4">
        <!-- 클레임 -->
        <v-col cols="12" md="7">
          <v-card class="mb-4 h-100" data-testid="claim-detail-info">
            <v-card-title class="d-flex align-center justify-space-between pt-4 px-5">
              <span class="text-subtitle-2 font-weight-bold">{{ claimTypeLabel(detail.type) }} 요청</span>
              <div class="d-flex align-center ga-1">
                <v-chip :class="semanticChipClass(SELLER_CLAIM_STATUS_SEMANTIC[detail.status])" size="small" variant="flat" data-testid="claim-detail-status">
                  {{ claimStatusLabel(detail.status) }}
                </v-chip>
                <v-chip v-if="detail.refundStatus" size="small" variant="outlined" data-testid="claim-detail-refund-status">
                  {{ refundStatusLabel(detail.refundStatus) }}
                </v-chip>
              </div>
            </v-card-title>
            <v-card-text class="px-5 pb-5">
              <div class="text-body-1 font-weight-medium" data-testid="claim-detail-product">{{ detail.productName }}</div>
              <div class="text-body-2 text-medium-emphasis mb-3">{{ detail.optionLabel ?? '옵션 없음' }}</div>
              <!-- Track 103: 한 row에서 줄바꿈되는 라벨·값 칸 사이 행 간격(dense 4+4px만으로는 붙어 보였다) -->
              <v-row dense class="gr-2">
                <v-col cols="6"><div class="text-caption text-medium-emphasis">주문번호</div><div class="text-body-2" data-testid="claim-detail-order-no">{{ detail.orderNo }}</div></v-col>
                <v-col cols="6"><div class="text-caption text-medium-emphasis">클레임 ID</div><div class="slr-product-id">{{ detail.claimId }}</div></v-col>
                <v-col cols="6"><div class="text-caption text-medium-emphasis">요청일시</div><div class="text-body-2" data-testid="claim-detail-requested-at">{{ formatDateTime(detail.requestedAt) }}</div></v-col>
                <v-col cols="6"><div class="text-caption text-medium-emphasis">처리일시</div><div class="text-body-2" data-testid="claim-detail-processed-at">{{ detail.processedAt ? formatDateTime(detail.processedAt) : '—' }}</div></v-col>
                <v-col cols="12"><div class="text-caption text-medium-emphasis">사유</div><div class="text-body-2" data-testid="claim-detail-reason">{{ claimReasonLabel(detail.reasonCode) }}</div></v-col>
                <v-col cols="12">
                  <div class="text-caption text-medium-emphasis">상세 사유</div>
                  <div class="text-body-2 slr-claim-reason-detail" data-testid="claim-detail-reason-detail">{{ detail.reasonDetail ?? '—' }}</div>
                </v-col>
                <v-col v-if="detail.rejectReasonCode" cols="12">
                  <div class="text-caption text-medium-emphasis">거부 사유</div>
                  <div class="text-body-2" data-testid="claim-detail-reject-reason">{{ claimRejectReasonLabel(detail.rejectReasonCode) }}</div>
                </v-col>
                <v-col v-if="detail.type === 'EXCHANGE'" cols="12">
                  <div class="text-caption text-medium-emphasis">교환품 배송</div>
                  <v-chip
                    v-if="detail.exchangeDeliveryStatus"
                    :class="semanticChipClass(SELLER_DELIVERY_STATUS_SEMANTIC[detail.exchangeDeliveryStatus])"
                    size="small"
                    variant="flat"
                    data-testid="claim-detail-exchange-delivery"
                  >
                    {{ SELLER_DELIVERY_STATUS_LABEL[detail.exchangeDeliveryStatus] }}
                  </v-chip>
                  <span v-else class="text-body-2 text-medium-emphasis" data-testid="claim-detail-exchange-delivery">아직 발송 전</span>
                </v-col>
              </v-row>
            </v-card-text>
          </v-card>
        </v-col>
        <!-- 상태 타임라인(읽기 전용) -->
        <v-col cols="12" md="5">
          <v-card class="mb-4 h-100" data-testid="claim-detail-timeline">
            <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">진행 상태</v-card-title>
            <v-card-text class="px-5 pb-5">
              <v-timeline side="end" density="compact" align="start" truncate-line="both">
                <v-timeline-item
                  v-for="step in timeline"
                  :key="step.key"
                  :dot-color="timelineColor(step)"
                  :icon="timelineIcon(step)"
                  size="small"
                  fill-dot
                  :data-testid="`claim-timeline-${step.key}`"
                >
                  <div class="text-body-2" :class="{ 'font-weight-medium': step.state !== 'pending', 'text-medium-emphasis': step.state === 'pending' }">{{ step.label }}</div>
                  <div v-if="step.at" class="text-caption text-medium-emphasis">{{ formatDateTime(step.at) }}</div>
                </v-timeline-item>
              </v-timeline>
              <p class="text-caption text-medium-emphasis mt-2 mb-0">승인·거부·검수는 관리자가 처리합니다. 이 화면에서는 진행 상태만 확인할 수 있습니다.</p>
            </v-card-text>
          </v-card>
        </v-col>
      </v-row>

      <!-- 첨부 사진 -->
      <v-card data-testid="claim-detail-attachments">
        <v-card-title class="text-subtitle-2 font-weight-bold pt-4 px-5">첨부 사진 <span class="text-medium-emphasis font-weight-regular">{{ detail.attachments.length }}장</span></v-card-title>
        <v-card-text class="px-5 pb-5">
          <div v-if="detail.attachments.length > 0" class="d-flex flex-wrap ga-2">
            <SellerClaimAttachmentImage
              v-for="(attachment, index) in detail.attachments"
              :key="attachment.attachmentId"
              :url="attachment.url"
              :index="index"
              @open="previewAttachmentId = attachment.attachmentId"
            />
          </div>
          <p v-else class="text-body-2 text-medium-emphasis mb-0" data-testid="claim-detail-no-attachments">첨부된 사진이 없습니다. 반품 요청 중 상품 불량·오배송 사유에서만 구매자가 사진을 첨부합니다.</p>
        </v-card-text>
      </v-card>
    </template>

    <v-dialog :model-value="previewAttachment !== null" max-width="900" @update:model-value="(open) => { if (!open) previewAttachmentId = null }">
      <v-card v-if="previewAttachment" data-testid="claim-attachment-preview">
        <v-card-text class="pa-2">
          <SellerClaimAttachmentImage :url="previewAttachment.attachment.url" :index="previewAttachment.index" variant="preview" />
        </v-card-text>
        <v-card-actions class="justify-end">
          <v-btn variant="text" @click="previewAttachmentId = null">닫기</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </div>
</template>
