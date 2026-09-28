import { resolveCasePathRedirect } from '~~/server/utils/case-path-redirect'

const MOVED_PERMANENTLY = 301

/**
 * 대소문자 혼합 경로(/Admin/login 등)를 첫 세그먼트 소문자 경로로 301(FE-92). 규칙은 resolveCasePathRedirect 참조.
 */
export default defineEventHandler((event) => {
  const queryStart = event.path.indexOf('?')
  const path = queryStart === -1 ? event.path : event.path.slice(0, queryStart)
  const query = queryStart === -1 ? '' : event.path.slice(queryStart)
  const location = resolveCasePathRedirect(event.method, path, query)
  if (location === null) return
  return sendRedirect(event, location, MOVED_PERMANENTLY)
})
