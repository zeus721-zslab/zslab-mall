import { isDemoConfigured } from '~~/server/lib/demo-login'

/**
 * 구매자 데모 로그인 활성 여부(FE-43). /login이 마운트 시 1회 조회해 버튼 표시를 결정한다.
 * 경로는 /api/**(backend 프록시) 밖이라 dev routeRules·운영 nginx(location /api/)와 충돌하지 않는다(_admin-demo와 동일).
 * 계정 값은 절대 응답하지 않고 boolean만 돌려준다.
 */
export default defineEventHandler((event) => {
  const config = useRuntimeConfig(event)
  return { enabled: isDemoConfigured({ email: config.buyerDemoEmail, password: config.buyerDemoPassword }) }
})
