import type { JwtPayload } from '~/types/auth'

/**
 * JWT payload base64url 수동 디코드(셀러 세션 표시·만료 UX 전용). 실인가는 서버 응답이 SoT.
 * app/stores/auth.ts의 동명 함수는 모듈 비공개이고 관리자 복제본은 layers/admin에 있어(격리 원칙·admin import 0) 셀러 레이어에 다시 복제한다.
 * 형식 오류 토큰은 null.
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
