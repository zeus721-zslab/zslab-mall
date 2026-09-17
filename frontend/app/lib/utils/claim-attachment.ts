import { CLAIM_ATTACHMENT_MAX } from '~/lib/constants/claim'
import type { ClaimAttachmentUploadItem } from '~/types/claim'

/**
 * 반품 사진 업로드 클라이언트 사전 검증(FE-29·순수 함수). BE 정책(D-166·D-171): jpg·png·webp / 파일당 10MB / 클레임당 5장.
 * 서버 검증(매직 바이트·디코딩)은 그대로 두고 왕복만 줄인다.
 */
export const CLAIM_ATTACHMENT_MAX_BYTES = 10 * 1024 * 1024
export const CLAIM_ATTACHMENT_ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp']
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
      rejected.push({ file, reason: 'jpg·png·webp만 첨부할 수 있습니다.' })
      continue
    }
    if (file.size > CLAIM_ATTACHMENT_MAX_BYTES) {
      rejected.push({ file, reason: '파일당 10MB를 초과합니다.' })
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
  FILE_TOO_LARGE: '파일당 10MB를 초과합니다.',
  UNSUPPORTED_FORMAT: 'jpg·png·webp만 첨부할 수 있습니다(형식 불일치).',
  INVALID_IMAGE: '이미지를 읽을 수 없습니다.',
}

/** 서버 파일별 실패 항목 → 사용자 문구(코드 우선·없으면 서버 message·일반 문구). */
export function uploadItemErrorMessage(item: ClaimAttachmentUploadItem): string {
  return (item.code && UPLOAD_ITEM_MESSAGES[item.code]) || item.message || '업로드에 실패했습니다.'
}
