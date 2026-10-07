#!/bin/sh
# .env와 .env.example의 활성 키 집합이 같은지 확인한다(D-270). 값은 출력하지 않는다.
# 사용: sh docker/env/check-env-keys.sh <env 파일> <example 파일>
# 종료 코드: 0 일치 · 1 불일치(중복 포함) · 2 파일 없음
# 활성 키 = 줄 맨 앞이 KEY= 인 줄의 키 이름. 주석 줄(# KEY=)은 자리 표시라 비교하지 않는다.
# 같은 동작의 PowerShell 판: docker/env/check-env-keys.ps1

EXIT_MATCH=0
EXIT_MISMATCH=1
EXIT_MISSING_FILE=2

if [ "$#" -ne 2 ]; then
  echo "사용: sh check-env-keys.sh <env 파일> <example 파일>"
  exit "$EXIT_MISSING_FILE"
fi

env_file=$1
example_file=$2

for file in "$env_file" "$example_file"; do
  if [ ! -f "$file" ]; then
    echo "파일 없음: $file"
    exit "$EXIT_MISSING_FILE"
  fi
done

# 정렬·비교 기준을 바이트 순서로 고정한다(로케일마다 순서가 달라 comm이 어긋나지 않도록).
LC_ALL=C
export LC_ALL

work_dir=$(mktemp -d) || exit "$EXIT_MISSING_FILE"
trap 'rm -rf "$work_dir"' EXIT

# 키 이름만 뽑는다 — = 뒤(값)는 버린다.
extract_keys() {
  sed -n 's/^\([A-Za-z_][A-Za-z0-9_]*\)=.*/\1/p' "$1" | sort
}

join_keys() {
  tr '\n' ' ' | sed 's/ $//'
}

extract_keys "$env_file" > "$work_dir/env_all"
extract_keys "$example_file" > "$work_dir/example_all"
sort -u "$work_dir/env_all" > "$work_dir/env_keys"
sort -u "$work_dir/example_all" > "$work_dir/example_keys"

status=$EXIT_MATCH

env_only=$(comm -23 "$work_dir/env_keys" "$work_dir/example_keys" | join_keys)
example_only=$(comm -13 "$work_dir/env_keys" "$work_dir/example_keys" | join_keys)
env_duplicates=$(uniq -d "$work_dir/env_all" | join_keys)
example_duplicates=$(uniq -d "$work_dir/example_all" | join_keys)

if [ -n "$env_only" ]; then
  echo ".env에만 있음: $env_only"
  status=$EXIT_MISMATCH
fi
if [ -n "$example_only" ]; then
  echo "example에만 있음: $example_only"
  status=$EXIT_MISMATCH
fi
if [ -n "$env_duplicates" ]; then
  echo "중복(.env): $env_duplicates"
  status=$EXIT_MISMATCH
fi
if [ -n "$example_duplicates" ]; then
  echo "중복(example): $example_duplicates"
  status=$EXIT_MISMATCH
fi

if [ "$status" -eq "$EXIT_MATCH" ]; then
  echo "키 일치 ($(wc -l < "$work_dir/env_keys" | tr -d ' ')개)"
fi
exit "$status"
