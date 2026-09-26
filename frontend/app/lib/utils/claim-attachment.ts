import { CLAIM_ATTACHMENT_MAX } from '~/lib/constants/claim'
import type { ClaimAttachmentUploadItem } from '~/types/claim'

/**
 * 반품 사진 업로드 클라이언트 사전 검증(FE-29·순수 함수). BE 정책(D-166·D-171·D-174): jpg·png(webp 업로드 중단·D-230) / 파일당 5MB / 클레임당 5장 /
 * 해상도 한 변 8,000px·총 2,500만 픽셀(D-230)·미연결 사진 20장. 서버 검증(매직 바이트·해상도·디코딩)은 그대로 두고 왕복만 줄인다.
 */
export const CLAIM_ATTACHMENT_MAX_MB = 5
export const CLAIM_ATTACHMENT_MAX_BYTES = CLAIM_ATTACHMENT_MAX_MB * 1024 * 1024
const CLAIM_ATTACHMENT_MAX_UNLINKED = 20
const FILE_TOO_LARGE_MESSAGE = `파일당 ${CLAIM_ATTACHMENT_MAX_MB}MB를 초과합니다.`
export const CLAIM_ATTACHMENT_ACCEPTED_TYPES = ['image/jpeg', 'image/png']
export const CLAIM_ATTACHMENT_ACCEPT_ATTRIBUTE = CLAIM_ATTACHMENT_ACCEPTED_TYPES.join(',')

export interface ClaimAttachmentPrecheck {
  accepted: File[]
  rejected: { file: File; reason: string }[]
}

/** 형식·크기·장수(현재 + 추가 ≤ 5)를 검사해 수락/거절로 나눈다. */
export function precheckClaimAttachments(files: File[], currentCount: number): ClaimAttachmentPrecheck {
  const accepted: File[] = []
  const rejected: { file: File; reason: string }[] = []
  for (const file of files) {
    if (!CLAIM_ATTACHMENT_ACCEPTED_TYPES.includes(file.type)) {
      rejected.push({ file, reason: 'jpg·png만 첨부할 수 있습니다.' })
      continue
    }
    if (file.size > CLAIM_ATTACHMENT_MAX_BYTES) {
      rejected.push({ file, reason: FILE_TOO_LARGE_MESSAGE })
      continue
    }
    if (currentCount + accepted.length >= CLAIM_ATTACHMENT_MAX) {
      rejected.push({ file, reason: `사진은 최대 ${CLAIM_ATTACHMENT_MAX}장까지 첨부할 수 있습니다.` })
      continue
    }
    accepted.push(file)
  }
  return { accepted, rejected }
}

const UPLOAD_ITEM_MESSAGES: Record<string, string> = {
  EMPTY_FILE: '빈 파일입니다.',
  FILE_TOO_LARGE: FILE_TOO_LARGE_MESSAGE,
  UNSUPPORTED_FORMAT: 'jpg·png만 첨부할 수 있습니다(형식 불일치).',
  INVALID_IMAGE: '이미지를 읽을 수 없습니다.',
  // D-230: BE 총 픽셀 25,000,000·한 변 8,000px·디코딩 바이트 예산 상한과 같은 문구(BE IMAGE_TOO_LARGE_MESSAGE).
  IMAGE_TOO_LARGE: '이미지가 너무 큽니다(최대 약 5000×5000 픽셀, 8비트 색상).',
}

/** 서버 파일별 실패 항목 → 사용자 문구(코드 우선·없으면 서버 message·일반 문구). */
export function uploadItemErrorMessage(item: ClaimAttachmentUploadItem): string {
  return (item.code && UPLOAD_ITEM_MESSAGES[item.code]) || item.message || '업로드에 실패했습니다.'
}

/** 미연결 사진 보유 상한(D-174) 초과 400 안내. */
export const UNLINKED_LIMIT_MESSAGE
  = `연결되지 않은 사진이 너무 많습니다(최대 ${CLAIM_ATTACHMENT_MAX_UNLINKED}장). 반품 요청에 연결하거나 24시간 후 다시 시도해 주세요.`

interface UploadRequestError {
  statusCode?: number
  data?: { detail?: unknown; code?: unknown }
}

/**
 * 업로드 요청 자체가 실패했을 때(413·400·네트워크) 묶음 문구. 400은 BE MALFORMED_REQUEST detail로 미연결 상한(D-174)과 장수 초과를
 * 구분한다(BE 문구 "연결되지 않은 첨부" 부분 일치·전용 코드 없음).
 */
export function uploadRequestErrorMessage(error: unknown): string {
  const failure = (error ?? {}) as UploadRequestError
  if (failure.statusCode === 413) return FILE_TOO_LARGE_MESSAGE
  if (failure.statusCode === 400) {
    const detail = typeof failure.data?.detail === 'string' ? failure.data.detail : ''
    if (detail.includes('연결되지 않은 첨부')) return UNLINKED_LIMIT_MESSAGE
    return `사진 업로드 요청이 잘못되었습니다(최대 ${CLAIM_ATTACHMENT_MAX}장).`
  }
  // D-230: 503 UPLOAD_BUSY(파일 처리 입장권 소진·디코딩 대기 초과) — 일반 실패 문구 대신 재시도 안내.
  if (failure.data?.code === 'UPLOAD_BUSY') return '이미지 처리 요청이 많습니다. 잠시 후 다시 시도해 주세요.'
  return '사진 업로드에 실패했습니다. 잠시 후 다시 시도하세요.'
}
