#!/usr/bin/env bash
# 운영 서버 cron이 15분마다 실행하는 감시 스크립트(D-272 · deploy-runbook.md §6).
# 점검: 공개 메인 페이지 200 · mall 컨테이너(backend·frontend) health · backend 내부 헬스 UP · 직전 실행 이후 backend 로그 ERROR 줄 수.
# 하나라도 이상이면 GitHub repository_dispatch(event_type health-alert)로 요약을 보낸다 → health-alert.yml이 실패해 Actions 실패 메일이 온다.
# 요약에는 항목명·상태 코드·ERROR 건수만 싣는다(로그 원문·시크릿 미포함). 이상이 이어지면 실행마다 알린다(중복 억제 없음).
#
# 설정(스크립트 기본값 없음):
#   HEALTH_ALERT_DEPLOY_DIR  배포 디렉터리. 환경변수로 넘긴다(cron 줄). 이 디렉터리의 .env에서 아래 3개를 읽고 상태 파일을 둔다.
#   HEALTH_ALERT_PUBLIC_URL  공개 URL(예 https://<도메인>)
#   HEALTH_ALERT_REPOSITORY  GitHub 저장소(owner/repo)
#   GH_DISPATCH_TOKEN        repository_dispatch 호출 토큰
#   위 3개는 환경변수가 비어 있을 때만 .env의 KEY=값 줄에서 문자 그대로 읽는다(.env를 셸로 실행하지 않는다).
#   DRY_RUN=1                전송 대신 페이로드를 출력한다(저장소·토큰 불필요 · 상태 파일 미갱신).
# 종료 코드: 0 정상 · 1 이상(알림 전송 또는 DRY_RUN 출력) · 2 설정 오류·전송 실패

set -u
umask 077

readonly EXIT_OK=0
readonly EXIT_ANOMALY=1
readonly EXIT_ERROR=2

readonly BACKEND_CONTAINER=zslab_mall_backend
readonly FRONTEND_CONTAINER=zslab_mall_frontend
readonly BACKEND_INTERNAL_HEALTH_URL=http://localhost:8080/actuator/health
readonly EVENT_TYPE=health-alert
readonly FIRST_RUN_LOOKBACK_SECONDS=900
readonly REQUEST_TIMEOUT_SECONDS=10
readonly STATE_FILE_NAME=.health-alert.state
# 콘솔 로그 한 줄 = "<시각> <레벨> [traceId] ..." — 레벨 칸이 ERROR인 줄만 센다(스택 트레이스 줄 제외).
readonly ERROR_LINE_PATTERN='^[^ ]+ +ERROR '

fail_config() {
  echo "설정 오류: $1" >&2
  exit "$EXIT_ERROR"
}

# 요약에 넣는 값은 상태 코드·상태 단어·숫자뿐이다. 그 밖의 문자는 지워 JSON 이스케이프가 필요 없게 한다.
sanitize() {
  printf '%s' "$1" | tr -cd 'A-Za-z0-9_.:-'
}

# .env에서 KEY=값 한 줄을 문자 그대로 읽는다(마지막 줄 우선 · 감싼 따옴표 1쌍과 CR 제거).
read_env_value() {
  local key=$1
  local value
  value=$(sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1 | tr -d '\r')
  case "$value" in
    \"*\") value=${value#\"}; value=${value%\"} ;;
    \'*\') value=${value#\'}; value=${value%\'} ;;
  esac
  printf '%s' "$value"
}

load_setting() {
  local key=$1
  if [ -z "${!key:-}" ] && [ -f "$ENV_FILE" ]; then
    printf -v "$key" '%s' "$(read_env_value "$key")"
  fi
}

[ -n "${HEALTH_ALERT_DEPLOY_DIR:-}" ] || fail_config "HEALTH_ALERT_DEPLOY_DIR 미설정"
[ -d "$HEALTH_ALERT_DEPLOY_DIR" ] || fail_config "HEALTH_ALERT_DEPLOY_DIR 디렉터리 없음"
ENV_FILE="$HEALTH_ALERT_DEPLOY_DIR/.env"
STATE_FILE="$HEALTH_ALERT_DEPLOY_DIR/$STATE_FILE_NAME"
DRY_RUN=${DRY_RUN:-0}

load_setting HEALTH_ALERT_PUBLIC_URL
load_setting HEALTH_ALERT_REPOSITORY
load_setting GH_DISPATCH_TOKEN

case "${HEALTH_ALERT_PUBLIC_URL:-}" in
  http://*|https://*) ;;
  *) fail_config "HEALTH_ALERT_PUBLIC_URL 미설정 또는 http(s):// 아님" ;;
esac
if [ "$DRY_RUN" != "1" ]; then
  [[ "${HEALTH_ALERT_REPOSITORY:-}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fail_config "HEALTH_ALERT_REPOSITORY 미설정 또는 owner/repo 형식 아님"
  [ -n "${GH_DISPATCH_TOKEN:-}" ] || fail_config "GH_DISPATCH_TOKEN 미설정"
fi

work_dir=$(mktemp -d) || fail_config "임시 디렉터리 생성 실패"
trap 'rm -rf "$work_dir"' EXIT

run_started_at=$(date +%s)
failures=()

check_public_main() {
  local status
  status=$(curl -s -o /dev/null -w '%{http_code}' --max-time "$REQUEST_TIMEOUT_SECONDS" "${HEALTH_ALERT_PUBLIC_URL%/}/" || true)
  status=$(sanitize "$status")
  [ "$status" = "200" ] || failures+=("public_main=${status:-000}")
}

check_container_health() {
  local name=$1
  local label=$2
  local health
  health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name" 2>/dev/null) || health=missing
  health=$(sanitize "$health")
  [ "$health" = "healthy" ] || failures+=("${label}_container=${health:-missing}")
}

check_backend_internal_health() {
  local output status body
  output=$(docker exec "$BACKEND_CONTAINER" curl -s --max-time "$REQUEST_TIMEOUT_SECONDS" -w '\n%{http_code}' "$BACKEND_INTERNAL_HEALTH_URL" 2>/dev/null) || true
  status=$(sanitize "$(printf '%s' "$output" | tail -n 1)")
  body=$(printf '%s' "$output" | sed '$d')
  case "$body" in
    *'"status":"UP"'*) ;;
    *) failures+=("backend_internal_health=${status:-unreachable}") ;;
  esac
}

check_backend_error_lines() {
  local since error_lines
  if [ -f "$STATE_FILE" ]; then
    since=$(tr -cd '0-9' < "$STATE_FILE")
  fi
  [ -n "${since:-}" ] || since=$((run_started_at - FIRST_RUN_LOOKBACK_SECONDS))
  if ! docker logs --since "$since" "$BACKEND_CONTAINER" > "$work_dir/backend.log" 2>&1; then
    failures+=("backend_log=unavailable")
    return
  fi
  error_lines=$(grep -cE "$ERROR_LINE_PATTERN" "$work_dir/backend.log" || true)
  error_lines=$(sanitize "$error_lines")
  [ "${error_lines:-0}" = "0" ] || failures+=("backend_log_error_lines=${error_lines}")
}

save_state() {
  printf '%s\n' "$run_started_at" > "$work_dir/state" && mv "$work_dir/state" "$STATE_FILE"
}

send_dispatch() {
  local payload=$1
  local response_code
  # 토큰은 프로세스 인자에 남지 않도록 표준 입력(curl --config -)으로 넘긴다.
  response_code=$(printf 'header = "Authorization: Bearer %s"\n' "$GH_DISPATCH_TOKEN" | curl -s -o /dev/null -w '%{http_code}' \
    --max-time "$REQUEST_TIMEOUT_SECONDS" --config - -X POST \
    -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28' \
    --data "$payload" "https://api.github.com/repos/${HEALTH_ALERT_REPOSITORY}/dispatches" || true)
  if [ "$response_code" != "204" ]; then
    echo "알림 전송 실패: HTTP $(sanitize "$response_code")" >&2
    return 1
  fi
}

check_public_main
check_container_health "$BACKEND_CONTAINER" backend
check_container_health "$FRONTEND_CONTAINER" frontend
check_backend_internal_health
check_backend_error_lines

[ "$DRY_RUN" = "1" ] || save_state

if [ "${#failures[@]}" -eq 0 ]; then
  exit "$EXIT_OK"
fi

summary="${failures[*]}"
payload=$(printf '{"event_type":"%s","client_payload":{"summary":"%s","checked_at":"%s"}}' \
  "$EVENT_TYPE" "$summary" "$(date -u +%Y-%m-%dT%H:%M:%SZ)")

if [ "$DRY_RUN" = "1" ]; then
  printf '%s\n' "$payload"
  exit "$EXIT_ANOMALY"
fi

echo "이상 감지: $summary"
send_dispatch "$payload" || exit "$EXIT_ERROR"
exit "$EXIT_ANOMALY"
