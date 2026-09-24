/**
 * 연락처 표시 형식(FE-79). 저장·전송 값은 바꾸지 않고 화면에 보일 문자열만 만든다(BE는 입력 문자열을 그대로 저장한다).
 * +82로 시작하면 0으로 바꾼 뒤 판단한다. 휴대폰(01X) 11자리 3-4-4 · 10자리 3-3-4 · 서울(02) 2-3/4-4 · 그 밖의 지역번호 3-3/4-4.
 * 숫자·공백·하이픈·괄호·점 밖의 글자가 있거나(마스킹 * 등) 자릿수가 맞지 않아 판별할 수 없으면 원문 그대로 돌려준다.
 */
const INTERNATIONAL_PREFIX = '+82'
const PHONE_CHARACTERS = /^[\d\s().-]+$/
const MOBILE_PREFIX = /^01[016789]/
const SEOUL_PREFIX = '02'
const AREA_PREFIX = /^0[3-9]\d/

function split(digits: string, ...lengths: number[]): string {
  const parts: string[] = []
  let start = 0
  for (const length of lengths) {
    parts.push(digits.slice(start, start + length))
    start += length
  }
  return parts.join('-')
}

export function formatPhone(raw: string): string {
  const trimmed = raw.trim()
  const domestic = trimmed.startsWith(INTERNATIONAL_PREFIX) ? `0${trimmed.slice(INTERNATIONAL_PREFIX.length)}` : trimmed
  if (!PHONE_CHARACTERS.test(domestic)) return raw
  const digits = domestic.replace(/\D/g, '')

  if (MOBILE_PREFIX.test(digits)) {
    if (digits.length === 11) return split(digits, 3, 4, 4)
    if (digits.length === 10) return split(digits, 3, 3, 4)
  } else if (digits.startsWith(SEOUL_PREFIX)) {
    if (digits.length === 9) return split(digits, 2, 3, 4)
    if (digits.length === 10) return split(digits, 2, 4, 4)
  } else if (AREA_PREFIX.test(digits)) {
    if (digits.length === 10) return split(digits, 3, 3, 4)
    if (digits.length === 11) return split(digits, 3, 4, 4)
  }
  return raw
}
