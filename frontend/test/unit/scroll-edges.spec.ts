import { describe, it, expect } from 'vitest'
import { readScrollEdges } from '~/skins/renew/scroll-edges'

// FE-77 가로 스크롤 줄 양끝 판정(흐림은 atEnd가 아닐 때만). 폭 300 · 내용 1000 기준, 소수점 오차 1px 허용.
describe('readScrollEdges', () => {
  it('시작·중간·끝(오차 1px 포함)을 판정한다', () => {
    const metrics = { clientWidth: 300, scrollWidth: 1000 }
    expect(readScrollEdges({ ...metrics, scrollLeft: 0 })).toEqual({ atStart: true, atEnd: false })
    expect(readScrollEdges({ ...metrics, scrollLeft: 350 })).toEqual({ atStart: false, atEnd: false })
    expect(readScrollEdges({ ...metrics, scrollLeft: 700 })).toEqual({ atStart: false, atEnd: true })
    expect(readScrollEdges({ ...metrics, scrollLeft: 699.5 })).toEqual({ atStart: false, atEnd: true })
  })
})
