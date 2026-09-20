import type { ClaimStatus, ClaimType, RefundStatus } from '~/lib/constants/claim'
import type { SellerDeliveryStatus } from '#layers/seller/app/lib/constants/seller-order'

/**
 * 셀러 클레임 API 타입(Track 90-D-1·BE claim/controller/response/SellerClaim* 실측 1:1·조회 전용). nullable(processedAt·optionLabel·reasonDetail·
 * refundStatus·exchangeDeliveryStatus)은 BE NON_NULL 직렬화로 생략될 수 있어 optional. 시각 문자열은 KST 오프셋 ISO(KstOffsetSerializer)라
 * formatDateTime으로만 표시한다. 구매자·거부 메모·환불 금액·처리 액션·첨부 원본 파일명은 BE가 싣지 않는다(노출 판정 표).
 */

/** 목록 행(BE SellerClaimSummaryResponse·12필드). */
export interface SellerClaimSummary {
  claimId: string
  type: ClaimType
  status: ClaimStatus
  requestedAt: string
  processedAt?: string
  orderNo: string
  productName: string
  optionLabel?: string
  reasonCode: string
  reasonDetail?: string
  /** 최신 환불 상태(환불 미생성이면 생략·금액 없음). */
  refundStatus?: RefundStatus
  /** 반품 사진 첨부 개수(URL은 상세). */
  attachmentCount: number
}

/** 첨부 1건(BE SellerClaimDetailResponse.AttachmentRow·public_id·서빙 URL만). */
export interface SellerClaimAttachment {
  attachmentId: string
  url: string
}

/** 상세(BE SellerClaimDetailResponse = 목록 행 + attachments + exchangeDeliveryStatus). */
export interface SellerClaimDetail extends SellerClaimSummary {
  attachments: SellerClaimAttachment[]
  /** 교환품 발송 최신 배송 상태(EXCHANGE·미발송이면 생략). */
  exchangeDeliveryStatus?: SellerDeliveryStatus
}

/** 페이징 봉투(BE PagedResponse·필드 5개). */
export interface SellerClaimListResponse {
  items: SellerClaimSummary[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 목록 화면 상태 = URL query 단일 소스. from/to는 yyyy-MM-dd(요청일·API 전송 시 시각 부착). 정렬은 요청일 최신순 고정(BE·sort 파라미터 없음). */
export interface SellerClaimListQuery {
  keyword: string
  type: ClaimType | null
  status: ClaimStatus | null
  from: string | null
  to: string | null
  page: number
  size: number
}

/** BE GET /seller/claims 쿼리 파라미터(null·빈 값은 제외). */
export type SellerClaimApiParams = Record<string, string | number>
