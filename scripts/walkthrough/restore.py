"""워크스루 기준 상태 복원(Track 98 STEP 1). 덤프로 로컬 DB를 되돌리고 백엔드 헬스를 확인한다.

    python scripts/walkthrough/restore.py --yes

[주의] 덤프에는 DROP TABLE/CREATE TABLE이 들어 있어 현재 로컬 DB 내용을 덤프 시점으로 되돌린다.
SPRING_PROFILES_ACTIVE=local 이 아니면 거부하며(common.require_local) --yes 없이는 실행하지 않는다.
운영 서버는 원격 도커 데몬이라 이 스크립트의 docker exec 대상이 아니다.
"""
from __future__ import annotations

import sys
import time

from common import (DUMP_PATH, WalkthroughError, backend_healthy, db_exec, fail, load_env, log, require_local,
                    require_local_api_host)

HEALTH_TIMEOUT_SECONDS = 120
HEALTH_INTERVAL_SECONDS = 5


def wait_healthy() -> None:
    deadline = time.monotonic() + HEALTH_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        if backend_healthy():
            log("백엔드 헬스 UP")
            return
        time.sleep(HEALTH_INTERVAL_SECONDS)
    raise WalkthroughError("복원 후 백엔드 헬스가 " + str(HEALTH_TIMEOUT_SECONDS) + "초 안에 UP이 되지 않았습니다.")


def main() -> int:
    if "--yes" not in sys.argv:
        return fail("복원은 로컬 DB를 덤프 시점으로 되돌립니다. 확인했으면 --yes 를 붙여 실행하세요.")
    env = load_env()
    try:
        require_local_api_host()
        require_local(env)
        if not DUMP_PATH.exists():
            raise WalkthroughError("덤프가 없습니다: " + str(DUMP_PATH) + " (먼저 dump.py 실행)")
        started = time.monotonic()
        db_exec(env, ["mariadb", "--default-character-set=utf8mb4", "-u" + env["DB_USERNAME"], env["DB_NAME"]],
                stdin_path=DUMP_PATH)
        log("복원 완료 (%.1fs)" % (time.monotonic() - started))
        wait_healthy()
        return 0
    except WalkthroughError as error:
        return fail(str(error))


if __name__ == "__main__":
    sys.exit(main())
