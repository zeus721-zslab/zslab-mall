"""워크스루 기준 상태 덤프(Track 98 STEP 1). 로컬 DB를 gitignored 경로에 저장한다.

    python scripts/walkthrough/dump.py

산출물: frontend/playwright-report/walkthrough-db/baseline.sql
"""
from __future__ import annotations

import sys

from common import (DUMP_DIR, DUMP_PATH, WalkthroughError, db_exec, fail, load_env, log, require_local,
                    require_local_api_host)


def main() -> int:
    env = load_env()
    try:
        require_local_api_host()
        require_local(env)
        DUMP_DIR.mkdir(parents=True, exist_ok=True)
        # --single-transaction: InnoDB 일관 스냅샷(락 없이) · --routines/--triggers: 스키마 부속 객체 포함
        db_exec(env, ["mariadb-dump", "--single-transaction", "--quick", "--routines", "--triggers",
                      "--default-character-set=utf8mb4", "-u" + env["DB_USERNAME"], env["DB_NAME"]],
                stdout_path=DUMP_PATH)
        size = DUMP_PATH.stat().st_size
        if size < 10000:
            raise WalkthroughError("덤프 크기가 비정상적으로 작습니다(" + str(size) + " bytes).")
        log("덤프 완료: " + str(DUMP_PATH) + " (" + format(size, ",") + " bytes)")
        log("다음: python scripts/walkthrough/restore.py --yes 로 복원 후 워크스루 실행")
        return 0
    except WalkthroughError as error:
        return fail(str(error))


if __name__ == "__main__":
    sys.exit(main())
