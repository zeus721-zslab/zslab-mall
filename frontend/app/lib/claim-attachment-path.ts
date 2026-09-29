/** BE가 내려주는 클레임 첨부 경로 접두사(ClaimAttachmentService · 옛 서빙 경로 GET /api/v1/files/claims/**). */
const CLAIM_ATTACHMENT_PATH_PREFIX = '/api/v1/files/claims/'

/**
 * 클레임 첨부 경로를 셀러·관리자 접두사 별칭(GET /api/v1/{seller|admin}/files/claims/**)으로 바꾼다(D-235 S2 · 역할 쿠키는 자기 접두사에만 실린다).
 * 구매자는 옛 경로를 그대로 쓴다(개정 2 구매자 쿠키 후보). 접두사가 다른 경로는 바꾸지 않는다.
 */
export function toRoleClaimAttachmentPath(path: string, role: 'seller' | 'admin'): string {
  if (!path.startsWith(CLAIM_ATTACHMENT_PATH_PREFIX)) return path
  return `/api/v1/${role}/files/claims/${path.slice(CLAIM_ATTACHMENT_PATH_PREFIX.length)}`
}
