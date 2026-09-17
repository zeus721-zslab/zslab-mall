/**
 * 클레임 요청/응답 타입(FE-14 유스케이스 A·BE ClaimRequestRequest·ClaimResponse 대응).
 * 라벨·유니온은 lib/constants/claim.ts 단일 소스를 재사용한다(매직 문자열 금지).
 */

import type {
  ClaimInspectionResult,
  ClaimReasonCode,
  ClaimRejectReasonCode,
  ClaimStatus,
  ClaimType,
  RefundStatus,
} from '~/lib/constants/claim'
import type { DeliveryCarrier, DeliveryDirection, DeliveryStatus } from '~/lib/constants/delivery'

/**
 * 클레임 요청 body(BE ClaimRequestRequest 대응). orderItemPublicId는 oit_ + ULID 26자(서버 정규식 검증),
 * reasonDetail은 선택값(max 500·서버 @Size).
 */
export interface ClaimRequestBody {
  orderItemPublicId: string
  claimType: ClaimType
  reasonCode: ClaimReasonCode
  reasonDetail?: string
  /** 반품·교환 사진 첨부 id(att_·최대 5·FE-29·FE-30). 상품불량/오배송에서만 보낸다(그 외 BE 400). */
  attachmentIds?: string[]
  /** 교환 옵션 variant public id(var_·FE-30·D-177). EXCHANGE에서만 보낸다(필수·그 외 유형 지정 시 BE 400). */
  exchangeVariantId?: string
}

/** 반품 사진 업로드 응답(BE ClaimAttachmentUploadResponse·FE-29). 파일별 부분 실패이며 성공 항목만 attachmentId를 가진다. */
export interface ClaimAttachmentUploadResponse {
  results: ClaimAttachmentUploadItem[]
  successCount: number
  failureCount: number
}

export interface ClaimAttachmentUploadItem {
  fileName?: string
  success: boolean
  attachmentId?: string
  url?: string
  thumbnailUrl?: string
  code?: string
  message?: string
}

/** 클레임 연결 배송 요약(BE ReturnShipmentResponse·Track 81-A). 회수(RETURN)·검수 불합격 재발송(OUTBOUND) 공용. */
export interface ClaimShipment {
  deliveryPublicId: string
  direction: DeliveryDirection
  carrier: DeliveryCarrier
  trackingNo: string
  status: DeliveryStatus
  shippedAt: string | null
  deliveredAt: string | null
}

/** 회수 송장 등록 body(BE ReturnShipmentRequest·FE-29). */
export interface ReturnShipmentBody {
  carrier: DeliveryCarrier
  trackingNo: string
}

/**
 * 클레임 단건 응답(BE ClaimResponse 대응). status는 StatusView가 아닌 ClaimStatus enum name 문자열이며,
 * reasonDetail·processedAt은 미처리/미입력 시 null. 본 유스케이스는 Location 헤더의 clm id만 소비하나,
 * $fetch.raw 응답 본문 타입으로 사용한다.
 */
export interface ClaimResponse {
  publicId: string
  orderItemPublicId: string
  claimType: ClaimType
  status: string
  reasonCode: string
  reasonDetail: string | null
  requestedAt: string
  processedAt: string | null
}

/**
 * 클레임 목록 항목(FE-14 유스케이스 B·BE ClaimSummaryResponse 대응). 페이로드 절감을 위해 목록엔
 * reasonDetail·processedAt·orderItemPublicId를 담지 않는다(상세에서만 노출). status는 ClaimStatus enum name,
 * reasonCode는 ClaimReasonCode name 문자열.
 */
export interface ClaimSummary {
  publicId: string
  claimType: ClaimType
  status: ClaimStatus
  reasonCode: ClaimReasonCode
  requestedAt: string
  /** 거부 사유 코드(FE-28·Track 80 D-169). 거부 전 null. */
  rejectReasonCode: ClaimRejectReasonCode | null
  /** 최신 환불 상태(FE-28). 환불 미생성 시 null. */
  refundStatus: RefundStatus | null
}

/**
 * 클레임 단건 상세(FE-14 유스케이스 B·BE ClaimResponse 대응). 처리 전(REQUESTED)엔 processedAt이 null,
 * reasonDetail 미입력 시 null. status·reasonCode는 enum name 문자열.
 */
export interface ClaimDetail {
  publicId: string
  orderItemPublicId: string
  claimType: ClaimType
  status: ClaimStatus
  reasonCode: ClaimReasonCode
  reasonDetail: string | null
  requestedAt: string
  processedAt: string | null
  /** 거부 사유 코드·메모(FE-28·Track 80 D-169). 거부 전 null. */
  rejectReasonCode: ClaimRejectReasonCode | null
  rejectMemo: string | null
  /** 최신 환불 상태(FE-28). 환불 미생성 시 null. */
  refundStatus: RefundStatus | null
  /** 구매자가 회수 송장을 등록해야 하는 단계인지(RETURN·APPROVED·송장 없음·미회수·Track 81-A). */
  returnShipmentRequired: boolean
  /** 회수 송장(구매자 등록·없으면 생략/null). */
  returnShipment?: ClaimShipment | null
  /** 회수 확인 시각(RETURN·EXCHANGE·없으면 생략/null). */
  pickedUpAt?: string | null
  /** 검수 결과(PASS|FAIL·미검수 생략/null). */
  inspectionResult?: ClaimInspectionResult | null
  /** 반품 사진 URL(순서 보존·없으면 빈 목록·Track 81-B). */
  attachmentUrls: string[]
  /** 검수 불합격 재발송 또는 교환품 발송 배송(OUTBOUND·없으면 생략/null·FE-29·FE-30). */
  reshipment?: ClaimShipment | null
  /** 교환 요청 옵션 라벨(EXCHANGE·FE-30·D-177·미해소 null). */
  exchangeOptionLabel?: string | null
  /** 교환 전 원 옵션 라벨(EXCHANGE·승인 스냅샷 우선·FE-30·D-177). */
  originalOptionLabel?: string | null
}
