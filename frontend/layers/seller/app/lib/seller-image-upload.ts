import type { SellerImageUploadResponse } from '#layers/seller/app/types/seller-product'
import { MAX_IMAGES } from '#layers/seller/app/lib/seller-product-form'

/**
 * 셀러 이미지 업로드 클라이언트 사전 검증·에러 메시지(Track 90-C-4·관리자 admin-image-upload 복제·순수 함수). 엔드포인트만 셀러 경로
 * (POST seller files images)이며 BE 정책은 관리자와 같다(D-166·D-174): jpg·png·webp / 파일당 10MB / 요청당 20장 / 한 변 8,000px.
 * 폼에는 업로드 응답의 url만 들어간다(서버 발급 URL 강제·직접 입력 없음).
 */
export const MAX_IMAGE_BYTES = 10 * 1024 * 1024
const MAX_IMAGE_SIDE_PX = 8000
export const ACCEPTED_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp']
export const ACCEPT_ATTRIBUTE = 'image/jpeg,image/png,image/webp'

export interface UploadPrecheck {
  accepted: File[]
  rejected: { file: File; reason: string }[]
}

/** 형식·크기·장수(현재 + 추가 ≤ 20)를 검사해 수락/거절로 나눈다. */
export function precheckFiles(files: File[], currentCount: number): UploadPrecheck {
  const accepted: File[] = []
  const rejected: { file: File; reason: string }[] = []
  for (const file of files) {
    if (!ACCEPTED_IMAGE_TYPES.includes(file.type)) {
      rejected.push({ file, reason: 'jpg·png·webp만 업로드할 수 있습니다.' })
      continue
    }
    if (file.size > MAX_IMAGE_BYTES) {
      rejected.push({ file, reason: '파일당 10MB를 초과합니다.' })
      continue
    }
    if (currentCount + accepted.length >= MAX_IMAGES) {
      rejected.push({ file, reason: `이미지는 최대 ${MAX_IMAGES}장입니다.` })
      continue
    }
    accepted.push(file)
  }
  return { accepted, rejected }
}

const UPLOAD_ITEM_MESSAGES: Record<string, string> = {
  EMPTY_FILE: '빈 파일입니다.',
  FILE_TOO_LARGE: '파일당 10MB를 초과합니다.',
  UNSUPPORTED_FORMAT: 'jpg·png·webp만 업로드할 수 있습니다(형식 불일치).',
  INVALID_IMAGE: '이미지를 읽을 수 없습니다.',
  IMAGE_TOO_LARGE: `이미지 해상도가 너무 큽니다. 한 변 ${MAX_IMAGE_SIDE_PX.toLocaleString('ko-KR')}px 이하로 줄여 주세요.`,
}

/** 파일별 실패 항목 code → 문구. */
export function uploadItemFailureMessage(code: string | undefined, message: string | undefined): string {
  if (code && UPLOAD_ITEM_MESSAGES[code]) return UPLOAD_ITEM_MESSAGES[code]
  return message && message !== '' ? message : '업로드에 실패했습니다.'
}

/** 요청 자체가 실패했을 때(413·400·403·네트워크) 문구. 403 SELLER_SUSPENDED는 정지 안내. */
export function uploadRequestFailureMessage(error: unknown): string {
  const status = (error as { status?: number; statusCode?: number } | null)?.status
    ?? (error as { statusCode?: number } | null)?.statusCode
  if (status === 413) return '용량 초과 — 파일당 10MB, 요청당 20장까지 업로드할 수 있습니다.'
  const code = (error as { data?: { code?: string } } | null)?.data?.code
  if (code === 'SELLER_SUSPENDED') return '정지 상태의 셀러는 이미지를 업로드할 수 없습니다.'
  if (code === 'MALFORMED_REQUEST') return '업로드 요청이 잘못되었습니다(장수 초과 또는 파일 없음).'
  if (code === 'FORBIDDEN' || status === 403) return '업로드 권한이 없습니다.'
  return '업로드에 실패했습니다. 잠시 후 다시 시도해 주세요.'
}

/** 단일 파일 업로드 응답에서 결과 1건을 꺼낸다(요청당 1파일 전제). */
export function firstUploadResult(response: SellerImageUploadResponse): SellerImageUploadResponse['results'][number] | null {
  return response.results[0] ?? null
}
