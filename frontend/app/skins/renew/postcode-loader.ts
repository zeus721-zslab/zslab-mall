/**
 * 카카오 우편번호 서비스 로더(FE-78). 처음 쓸 때 1회만 script를 넣고 로드 Promise를 공유한다. 클라이언트 전용.
 * 실패·타임아웃이면 넣은 script를 지우고 캐시를 비워 "다시 시도"가 새로 넣게 한다. renew 내부 헬퍼라 app composables는 import하지 않는다(skin-guard).
 */
const POSTCODE_SCRIPT_URL = 'https://t1.kakaocdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js'
const LOAD_TIMEOUT_MS = 8000

/** oncomplete가 넘기는 선택 결과 중 폼에 쓰는 값. */
export interface PostcodeData {
  zonecode: string
  roadAddress: string
  autoRoadAddress: string
  jibunAddress: string
  autoJibunAddress: string
  bname: string
  buildingName: string
  apartment: 'Y' | 'N'
}

/** 색은 '#RRGGBB'만 받는다(임베드 iframe 안이라 CSS 변수를 읽지 못한다). */
export interface PostcodeTheme {
  bgColor: string
  searchBgColor: string
  contentBgColor: string
  pageBgColor: string
  textColor: string
  queryTextColor: string
  postcodeTextColor: string
  emphTextColor: string
  outlineColor: string
}

export interface PostcodeOptions {
  oncomplete: (data: PostcodeData) => void
  width: string
  height: string
  // 로드되면 검색창에 포커스한다.
  focusInput: boolean
  theme: PostcodeTheme
}

export interface PostcodeInstance {
  embed: (element: HTMLElement, options?: { autoClose?: boolean }) => void
}

export type PostcodeConstructor = new (options: PostcodeOptions) => PostcodeInstance

declare global {
  interface Window {
    kakao?: { Postcode?: PostcodeConstructor }
    daum?: { Postcode?: PostcodeConstructor }
  }
}

/** 폼 3칸에 채울 값. */
export interface PostcodeAddressFields {
  zonecode: string
  addressRoad: string
  addressJibun: string
}

// 참고항목 규칙(카카오 안내 예제): 법정동은 끝 글자가 동·로·가일 때만(리 제외), 건물명은 공동주택일 때만 넣는다.
const LEGAL_DONG_PATTERN = /[동로가]$/

/**
 * 선택 결과를 폼 값으로 바꾼다. 도로명은 roadAddress, 비면 autoRoadAddress(지번 선택 시 매핑된 도로명)를 쓴다.
 * 둘 다 비면 도로명 주소(필수)를 채울 수 없어 null — 호출부가 선택을 받지 않는다.
 */
export function toAddressFields(data: PostcodeData): PostcodeAddressFields | null {
  const roadAddress = data.roadAddress || data.autoRoadAddress
  if (roadAddress === '') return null
  const references: string[] = []
  if (LEGAL_DONG_PATTERN.test(data.bname)) references.push(data.bname)
  if (data.buildingName !== '' && data.apartment === 'Y') references.push(data.buildingName)
  const referenceText = references.length > 0 ? ` (${references.join(', ')})` : ''
  return {
    zonecode: data.zonecode,
    addressRoad: roadAddress + referenceText,
    addressJibun: data.jibunAddress || data.autoJibunAddress,
  }
}

// 서비스 도메인·네임스페이스가 daum → kakao로 옮겨 가는 중이라 둘 다 본다.
function findConstructor(): PostcodeConstructor | undefined {
  return window.kakao?.Postcode ?? window.daum?.Postcode
}

let loading: Promise<PostcodeConstructor> | null = null

export function loadPostcode(): Promise<PostcodeConstructor> {
  if (import.meta.server) return Promise.reject(new Error('우편번호 서비스는 브라우저에서만 불러온다'))
  const loaded = findConstructor()
  if (loaded) return Promise.resolve(loaded)
  loading ??= insertScript().catch((error: unknown) => {
    loading = null
    throw error
  })
  return loading
}

function insertScript(): Promise<PostcodeConstructor> {
  return new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = POSTCODE_SCRIPT_URL
    script.async = true

    const timer = setTimeout(() => fail(new Error(`우편번호 서비스 로드 시간 초과(${LOAD_TIMEOUT_MS}ms)`)), LOAD_TIMEOUT_MS)
    function fail(error: Error): void {
      clearTimeout(timer)
      script.onload = null
      script.onerror = null
      script.remove()
      reject(error)
    }

    script.onload = () => {
      const constructor = findConstructor()
      if (!constructor) {
        fail(new Error('우편번호 서비스 생성자를 찾지 못했다'))
        return
      }
      clearTimeout(timer)
      resolve(constructor)
    }
    script.onerror = () => fail(new Error('우편번호 서비스 스크립트 로드 실패'))
    document.head.appendChild(script)
  })
}
