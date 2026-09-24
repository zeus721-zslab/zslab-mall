/** 구매자 스킨 선택 상수(FE-67). 스킨 목록 SoT는 app/skins/registry.ts SKIN_NAMES. */

/** 미리보기로 고른 스킨을 기억하는 쿠키. env 기본 스킨보다 우선한다. */
export const SKIN_COOKIE = 'zslab_skin'
/** 미리보기 쿼리 키(?skin=<이름>). */
export const SKIN_QUERY = 'skin'
/** ?skin=reset 이면 미리보기 쿠키를 지운다. */
export const SKIN_RESET_VALUE = 'reset'
/** 요청 단위로 1회 결정한 스킨명을 SSR → CSR로 넘기는 useState 키. */
export const SKIN_STATE_KEY = 'skin'
