import type { LocationQueryValue } from 'vue-router'

/** 로그인 후 복귀 기본 경로. */
export const DEFAULT_LOGIN_REDIRECT = '/'

// 내부 절대경로만: '/'로 시작하고 두 번째 글자가 '/'·'\'가 아니다('//evil'·'/\evil'은 브라우저가 외부 호스트로 해석한다).
const INTERNAL_PATH = /^\/(?![/\\])/
// 인코딩된 백슬래시('%5C'·'%5c')는 디코딩 단계에 따라 '/\evil'로 바뀔 수 있어 위치와 무관하게 거부한다(SEC-24).
const ENCODED_BACKSLASH = /%5c/i
// 공백·제어문자: '/\t/evil'처럼 두 번째 글자 검사를 통과해도 URL 파서가 지워 '//evil'(외부 호스트)이 된다.
// Nuxt navigateTo가 외부 이동으로 막지만 예외가 나 로그인 성공 뒤 오류 문구·SSR 오류가 되므로 여기서 기본 경로로 보낸다.
const WHITESPACE_OR_CONTROL = /[\s\u0000-\u001F\u007F]/

/** 한 번 더 디코드한 값(이중 인코딩 '%09' 등 대비). 잘못된 인코딩이면 null — 판정할 수 없으므로 거부한다. */
function decodeOnce(value: string): string | null {
  try {
    return decodeURIComponent(value)
  } catch (decodeError) {
    console.warn('[login] redirect 디코드 실패 → 기본 경로', decodeError)
    return null
  }
}

/**
 * 로그인 복귀 경로(SEC-24 · 오픈 리다이렉트 방지). redirect query가 내부 절대경로일 때만 그대로 쓰고,
 * 외부 URL·protocol-relative·백슬래시 우회·공백·제어문자(디코드 후 포함)·배열 query는 기본 경로로 보낸다.
 */
export function resolveLoginRedirect(redirect: LocationQueryValue | LocationQueryValue[] | undefined): string {
  if (typeof redirect !== 'string' || !INTERNAL_PATH.test(redirect) || ENCODED_BACKSLASH.test(redirect)) {
    return DEFAULT_LOGIN_REDIRECT
  }
  const decoded = decodeOnce(redirect)
  if (decoded === null || WHITESPACE_OR_CONTROL.test(decoded)) {
    return DEFAULT_LOGIN_REDIRECT
  }
  return redirect
}
