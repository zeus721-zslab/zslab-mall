# zslab-mall

Java 기반 멀티벤더 입점형 쇼핑몰.

## 기술 스택
- Backend: Spring Boot 3.x · Java 21 LTS · Gradle
- Frontend: Nuxt 3 (Vue 3 + SSR)
- DB: MariaDB (공유 인프라 `zslab_mariadb` / 스키마 `zslab_mall`)
- Cache: Redis (공유 인프라 `zslab_redis`)
- Infra: Docker · GitHub Actions · gateway_nginx

## 도메인
https://zslab-mall.duckdns.org

## 현재 단계
**architecture-baseline** — 설계 기준선 확정 단계 (ERD 진입 전).
상세 트랙 로드맵은 [docs/TODO.md](./docs/TODO.md) 참조.

## 문서
- 개발 룰: [CLAUDE-DEV.md](./CLAUDE-DEV.md)
- 설계 문서: [docs/design/](./docs/design/)
- 트랙 로드맵: [docs/TODO.md](./docs/TODO.md)
- 완료 트랙: [docs/TODO-complete.md](./docs/TODO-complete.md)

## 라이선스
Private project (zeus721-zslab).
