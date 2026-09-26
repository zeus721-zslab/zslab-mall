import { describe, it, expect } from 'vitest'
import { ACCEPT_ATTRIBUTE, precheckFiles, uploadItemFailureMessage, uploadRequestFailureMessage } from '#layers/seller/app/lib/seller-image-upload'

// D-230: 셀러 이미지 업로드 문구 — 파일별 IMAGE_TOO_LARGE(디코딩 바이트 예산 포함)와 요청 단위 503 UPLOAD_BUSY(동시 디코딩 대기 초과).
describe('seller-image-upload 문구', () => {
  it('IMAGE_TOO_LARGE → BE와 같은 문구 · 503 UPLOAD_BUSY → 재시도 안내(일반 실패 문구 아님)', () => {
    expect(uploadItemFailureMessage('IMAGE_TOO_LARGE', 'x')).toBe('이미지가 너무 큽니다(최대 약 5000×5000 픽셀, 8비트 색상).')
    expect(uploadRequestFailureMessage({ status: 503, data: { code: 'UPLOAD_BUSY' } })).toBe('이미지 처리 요청이 많습니다. 잠시 후 다시 시도해 주세요.')
    expect(uploadRequestFailureMessage({ status: 500, data: { code: 'INTERNAL_ERROR' } })).toBe('업로드에 실패했습니다. 잠시 후 다시 시도해 주세요.')
  })

  it('webp 업로드 중단(D-230): accept에서 빠지고 사전 검증이 거절 · 서버 UNSUPPORTED_FORMAT 문구도 jpg·png', () => {
    expect(ACCEPT_ATTRIBUTE).toBe('image/jpeg,image/png')
    const webp = new File([new Uint8Array(10)], 'a.webp', { type: 'image/webp' })
    expect(precheckFiles([webp], 0).rejected[0]?.reason).toBe('jpg·png만 업로드할 수 있습니다.')
    expect(uploadItemFailureMessage('UNSUPPORTED_FORMAT', 'x')).toBe('jpg·png만 업로드할 수 있습니다(형식 불일치).')
  })
})
