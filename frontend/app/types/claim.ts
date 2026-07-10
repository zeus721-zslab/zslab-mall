/**
 * 클레임 요청/응답 타입(FE-14 유스케이스 A·BE ClaimRequestRequest·ClaimResponse 대응).
 * 라벨·유니온은 lib/constants/claim.ts 단일 소스를 재사용한다(매직 문자열 금지).
 */

import type { ClaimReasonCode, ClaimStatus, ClaimType } from '~/lib/constants/claim'

/**
 * 클레임 요청 body(BE ClaimRequestRequest 대응). orderItemPublicId는 oit_ + ULID 26자(서버 정규식 검증),
 * reasonDetail은 선택값(max 500·서버 @Size).
 */
export interface ClaimRequestBody {
  orderItemPublicId: string
  claimType: ClaimType
  reasonCode: ClaimReasonCode
  reasonDetail?: string
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
}
