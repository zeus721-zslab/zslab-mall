import type { JwtPayload } from '~/types/auth'

/**
 * JWT payload base64url 수동 디코드(관리자 세션 표시·만료 UX 전용·FE-22d). 실인가는 서버 응답이 SoT.
 * app/stores/auth.ts의 동명 함수는 모듈 비공개라 레이어에 복제한다(사용자 영역 무수정 원칙). 형식 오류 토큰은 null.
 */
export function decodeJwtPayload(token: string): JwtPayload | null {
  const parts = token.split('.')
  const payloadSegment = parts[1]
  if (parts.length !== 3 || !payloadSegment) return null
  try {
    const base64 = payloadSegment.replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=')
    const binary = atob(padded)
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0))
    return JSON.parse(new TextDecoder().decode(bytes)) as JwtPayload
  } catch {
    // 손상·비표준 토큰은 디코드 불가 → null(미인증 취급). 표시 전용이라 throw하지 않는다.
    return null
  }
}
