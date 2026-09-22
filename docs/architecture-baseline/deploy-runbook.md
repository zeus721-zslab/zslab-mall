# 배포 런북 — 방식 A (Actions 빌드 → ghcr.io → 서버 pull)

Track 100(D-211)에서 서버 빌드를 걷어낸 뒤의 배포 절차다. 대상 독자는 운영자(zslab) 1인.

- 워크플로: `.github/workflows/deploy.yml`
- 이미지: `ghcr.io/zeus721-zslab/zslab-mall-backend`, `ghcr.io/zeus721-zslab/zslab-mall-frontend`
- 태그: `latest`(항상 최신 배포본) + `sha-<7자>`(커밋 고정용)
- 서버는 이미지를 빌드하지 않는다. `git pull`은 `docker-compose.mall.yml`·`docker/filebeat/filebeat.yml` 동기화용으로만 남아 있다.

## 1. 평상시 배포

`main`에 `backend/**`·`frontend/**`·`docker/**`·`docker-compose.mall.yml`·`deploy.yml` 변경이 머지되면 자동 실행된다.

1. `build` — 변경된 쪽만 빌드해 두 태그로 push
2. `deploy` — SSH로 서버에서 `git pull` → `compose pull` → `up -d --no-build --wait`
3. `cleanup` — 패키지별 최근 5개 버전만 유지

**이미지 빌드는 테스트를 실행하지 않는다.** 테스트 게이트는 PR 단계의 `backend-ci.yml`·`frontend-ci.yml`이다.

수동 배포는 Actions 화면의 `Run workflow`(workflow_dispatch). 이 경우 변경 판정을 건너뛰고 양쪽을 모두 빌드한다.

## 2. 최초 전환 (완료 · 2026-09-23 실측)

1. PR #256 머지 → `deploy.yml` 첫 실행.
2. `build` job이 두 패키지를 생성했고 **`deploy` job이 그대로 성공했다** — 서버가 인증 없이 pull 할 수 있었다. 사전 예상과 달리 **패키지 가시성을 public으로 바꾸는 작업은 필요 없었다**(패키지가 처음부터 pull 가능한 상태로 만들어졌다).
3. 서버에서 실제로 뜬 이미지를 `deploy` 로그의 digest 줄(`zslab_mall_backend -> ghcr.io/... @ sha256:...`)로 확인했다.

앞으로 `deploy`가 `denied`/`unauthorized`로 실패한다면 패키지가 private으로 만들어진 경우다. 둘 중 하나로 처치한다.

- GitHub → Packages → `zslab-mall-backend`·`zslab-mall-frontend` → Package settings → Change visibility → **Public** → 실패한 run에서 `deploy` job만 **Re-run failed jobs**
- private을 유지하려면 서버에서 `docker login ghcr.io`(PAT·`read:packages`)를 1회 수행한다.

## 3. 롤백

되돌릴 커밋의 짧은 sha를 확인한 뒤(=`sha-<7자>` 태그), 서버에서:

1. `DEPLOY_PATH`의 `.env`에 태그를 지정한다.

   ```
   BACKEND_IMAGE_TAG=sha-1a2b3c4
   FRONTEND_IMAGE_TAG=sha-1a2b3c4
   ```

   한쪽만 되돌릴 때는 해당 변수만 지정한다(미지정 변수는 `latest`).

2. 이미지를 받고 재기동한다.

   ```
   docker compose -f docker-compose.mall.yml pull zslab_mall_backend zslab_mall_frontend
   docker compose -f docker-compose.mall.yml up -d --no-build --wait --wait-timeout 180
   ```

3. 복귀할 때는 `.env`의 두 줄을 지우거나 `latest`로 되돌린 뒤 같은 두 명령을 다시 실행한다.

**DB 마이그레이션이 포함된 커밋은 이미지 롤백만으로 되돌아가지 않는다.** Flyway는 `validate`로 기동하므로, 구 이미지가 신 스키마를 만나면 기동에 실패할 수 있다. 이 경우 보상 마이그레이션을 포함한 새 커밋으로 앞으로 굴리는 쪽이 안전하다.

## 4. 확인·정리

- 실행 중인 이미지: `docker compose -f docker-compose.mall.yml ps` / `docker inspect zslab_mall_backend --format '{{.Config.Image}}'`
- 서버 이미지 정리: `deploy` job 끝에서 이 저장소 라벨(`org.opencontainers.image.source`)이 붙은 dangling 이미지만 자동 정리한다. 서버의 다른 프로젝트 이미지는 건드리지 않는다.
- 레지스트리 정리: `cleanup` job이 패키지별 최근 5개만 남긴다. **6번째 이전 버전으로는 롤백할 수 없다.**

## 5. 자주 나오는 실패

| 증상 | 원인 | 처치 |
|---|---|---|
| 배포는 성공인데 코드가 안 바뀜 | `compose pull`이 대상 없이 no-op | `deploy` 로그의 digest 줄 확인 → `docker-compose.mall.yml`에 `image:` 키가 있는지 확인 |
| `deploy`가 `denied`로 실패 | 패키지가 private | §2 후반부(public 전환 후 job re-run 또는 서버 `docker login`) |
| `cleanup`이 실패 | 패키지 미존재(최초 실행) | 무시해도 된다(`continue-on-error`) |
| 구 이미지로 내렸더니 기동 실패 | Flyway `validate` 불일치 | §3 마지막 문단 |
