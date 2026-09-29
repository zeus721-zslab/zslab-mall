import type { ReviewPhotoUploadItem } from '~/types/review'
import { uploadItemErrorMessage } from '~/lib/utils/claim-attachment'

/**
 * 리뷰 API 실패 → 사용자 문구(Track 106-1 PR2 · 순수 함수). 리뷰 400(키워드·사진 장수 등)에는 전용 code가 없어 BE detail을 그대로 보여 준다.
 * 사진 파일별 사전 검사·코드 문구는 클레임 유틸을 재사용하고, 리뷰 경로가 다른 한도(해상도)만 서버 문구를 우선한다.
 */
interface ReviewApiErrorLike {
  statusCode?: number
  data?: { code?: unknown; detail?: unknown }
}

export interface ReviewWriteFailure {
  /** 401 — 호출부가 로그인으로 보낸다. */
  login: boolean
  message: string
}

function asFailure(error: unknown): ReviewApiErrorLike {
  return (error ?? {}) as ReviewApiErrorLike
}

function detailOf(failure: ReviewApiErrorLike): string {
  return typeof failure.data?.detail === 'string' ? failure.data.detail : ''
}

/** 작성·수정 실패. 401 로그인 · 404 대상 없음 · 409 이미 작성 · 422 자격 없음/숨김 · 400 서버 문구. */
export function reviewWriteFailure(error: unknown): ReviewWriteFailure {
  const failure = asFailure(error)
  const code = failure.data?.code
  switch (failure.statusCode) {
    case 401:
      return { login: true, message: '로그인이 필요합니다.' }
    case 404:
      return { login: false, message: code === 'REVIEW_NOT_FOUND' ? '리뷰를 찾을 수 없습니다.' : '주문 품목을 찾을 수 없습니다.' }
    case 409:
      return { login: false, message: '이미 리뷰를 작성한 상품입니다.' }
    case 422:
      return {
        login: false,
        message: code === 'REVIEW_INVALID_STATE'
          ? '비공개 처리된 리뷰는 수정할 수 없습니다.'
          : '리뷰를 작성할 수 없는 주문 품목입니다(구매확정 후 작성할 수 있어요).',
      }
    case 400:
      return { login: false, message: detailOf(failure) || '입력 내용을 확인해 주세요.' }
    default:
      return { login: false, message: '리뷰를 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.' }
  }
}

/** 사진 파일별 실패 문구. 리뷰 경로의 해상도 한도는 클레임과 달라(D-237 결정 3) IMAGE_TOO_LARGE만 서버 문구를 쓴다. */
export function reviewPhotoItemErrorMessage(item: ReviewPhotoUploadItem): string {
  if (item.code === 'IMAGE_TOO_LARGE' && item.message) return item.message
  return uploadItemErrorMessage(item)
}

/** 사진 업로드 요청 자체의 실패(413 · 400 미연결 상한 · 503 · 네트워크). */
export function reviewPhotoRequestErrorMessage(error: unknown): string {
  const failure = asFailure(error)
  if (failure.statusCode === 401) return '로그인이 필요합니다.'
  if (failure.statusCode === 413) return '파일이 너무 큽니다(파일당 5MB).'
  if (failure.statusCode === 400) return detailOf(failure) || '사진 업로드 요청이 잘못되었습니다.'
  if (failure.data?.code === 'UPLOAD_BUSY') return '이미지 처리 요청이 많습니다. 잠시 후 다시 시도해 주세요.'
  return '사진을 올리지 못했습니다. 잠시 후 다시 시도해 주세요.'
}
