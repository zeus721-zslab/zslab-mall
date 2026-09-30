"""시더 대상 가드 테스트(D-242 · 운영 오실행 경로 1~5). 실제 DNS·HTTP·DB·입력은 쓰지 않는다.

실행: python -m unittest discover -s scripts/demo-seed -p "test_*.py"

main()을 argv로 끝까지 태우고 DNS(socket.getaddrinfo 전역)·입력(input)·HTTP(ApiClient)·DB(connect_db)를 가짜로 바꾼다.
단계 함수는 전부 mock이라 "단계가 호출됐는가" = "쓰기가 시작됐는가"로 본다. 거부는 반환 코드와 함께 로그 문구로 판정한다
(가드 없는 코드가 모르는 인자로 argparse 종료하는 것을 거부로 오인하지 않기 위해).
"""

import io
import json
import os
import socket
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import seed

PROD_DOMAIN = "zslab-mall.duckdns.org"
ADMIN_EMAIL = "admin@example.com"
ADMIN_PUBLIC_ID = "usr_admin"
STEP_FUNCTIONS = ["step_master", "step_orders", "step_timeshift", "step_settlement", "step_reviews",
                  "step_delivered", "step_inquiries", "step_qna", "step_verify"]
WRITE_SQL_PREFIXES = ("INSERT", "UPDATE", "DELETE", "REPLACE", "TRUNCATE", "DROP", "ALTER", "CREATE")


def base_env(api_host: str, db_host: str = "127.0.0.1", db_name: str = "zslab_mall") -> dict:
    # 106 데이터 단계 계정 env 포함(--step all·dry-run 기본 all이 요구 · D-243)
    return {"API_BASE_URL": f"https://{api_host}", "ADMIN_EMAIL": ADMIN_EMAIL, "ADMIN_PASSWORD": "pw",
            "DB_HOST": db_host, "DB_PORT": "3306", "DB_NAME": db_name, "DB_USER": "user", "DB_PASSWORD": "pw",
            "DEMO_BUYER_EMAIL": "buyer@example.com", "DEMO_BUYER_PASSWORD": "pw",
            "DEMO_SELLER_EMAIL": "seller@example.com", "DEMO_SELLER_PASSWORD": "pw"}


class FakeResolver:
    """호스트 → IP 목록. 등록되지 않은 호스트는 해석 실패(gaierror)."""

    def __init__(self, table: dict):
        self.table = table

    def __call__(self, host, *args, **kwargs):
        if host not in self.table:
            raise socket.gaierror(f"unknown host {host}")
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, 0)) for address in self.table[host]]


class FakeCursor:
    def __init__(self, conn):
        self.conn = conn
        self.last_sql = ""

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql, params=None):
        self.last_sql = sql
        self.conn.executed.append(sql)
        return 0

    def fetchone(self):
        # 관리자 본인 행 조회(교차 검증)만 admin_present로 답하고, 그 밖 COUNT(데모 존재 가드)는 0
        if "public_id" in self.last_sql and "`user`" in self.last_sql:
            return {"c": 1 if self.conn.admin_present else 0}
        return {"c": 0}

    def fetchall(self):
        return []


class FakeConn:
    def __init__(self, admin_present: bool = True):
        self.admin_present = admin_present
        self.executed: list[str] = []

    def cursor(self):
        return FakeCursor(self)

    def writes(self) -> list[str]:
        return [sql for sql in self.executed if sql.lstrip().upper().startswith(WRITE_SQL_PREFIXES)]

    def begin(self):
        pass

    def commit(self):
        pass

    def rollback(self):
        pass

    def close(self):
        pass


class FakeApi:
    def __init__(self, base_url: str):
        self.base_url = base_url
        self.call_count = 0
        self.calls: list[tuple[str, str]] = []

    def login(self, email, password, role):
        self.calls.append(("LOGIN", role))
        return seed.Credential("__Secure-admin_at", "token", "xsrf")

    def json(self, method, path, credential=None, expect=(200, 201), **kwargs):
        self.calls.append((method, path))
        if path == "/api/v1/admin/me":
            return {"userPublicId": ADMIN_PUBLIC_ID, "name": "관리자", "email": ADMIN_EMAIL, "roles": ["SUPER_ADMIN"], "superAdmin": True}
        return {}

    def request(self, method, path, credential=None, expect=(200, 201), **kwargs):
        self.calls.append((method, path))
        return mock.Mock(status_code=200)


class TargetGuardTest(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state_dir = Path(self.temp.name)
        self.conn = FakeConn()
        self.apis: list[FakeApi] = []

    # -- 실행 헬퍼 ---------------------------------------------------------
    def run_seed(self, args: list, env: dict, dns: dict, typed: str | None = None, tty: bool = True):
        """(반환 코드, 단계 mock dict, 로그 문자열). argparse 종료(SystemExit)는 그 코드로 돌려준다."""
        def make_api(base_url):
            api = FakeApi(base_url)
            self.apis.append(api)
            return api

        step_mocks = {name: mock.MagicMock(name=name) for name in STEP_FUNCTIONS}
        input_mock = mock.MagicMock(return_value=typed if typed is not None else "")
        self.input_mock = input_mock
        patches = [
            mock.patch.object(sys, "argv", ["seed.py", *args]),
            mock.patch.dict(os.environ, env, clear=True),
            mock.patch("socket.getaddrinfo", FakeResolver(dns)),
            mock.patch("builtins.input", input_mock),
            mock.patch.object(sys, "stdin", mock.Mock(isatty=mock.Mock(return_value=tty))),
            mock.patch.object(seed, "ApiClient", side_effect=make_api),
            mock.patch.object(seed, "connect_db", return_value=self.conn),
            mock.patch.object(seed, "STATE_DIR", self.state_dir, create=True),
            # 가드 없는 코드의 단일 state 경로도 임시 폴더로 돌린다(실 state 파일을 건드리지 않게)
            mock.patch.object(seed, "STATE_PATH", self.state_dir / "seed-state.json", create=True),
        ] + [mock.patch.object(seed, name, step_mock) for name, step_mock in step_mocks.items()]
        for patcher in patches:
            patcher.start()
        try:
            with self.assertLogs("demo-seed", level="INFO") as captured:
                seed.log.info("테스트 시작")
                try:
                    code = seed.main()
                except SystemExit as exit_error:
                    code = exit_error.code
        finally:
            for patcher in reversed(patches):
                patcher.stop()
        return code, step_mocks, "\n".join(captured.output)

    def assert_no_write(self, step_mocks: dict):
        for name, step_mock in step_mocks.items():
            self.assertFalse(step_mock.called, f"{name} 호출됨(쓰기 시작)")
        self.assertEqual([], self.conn.writes())
        self.assertEqual([], sorted(self.state_dir.glob("seed-state.*.json")), "state 파일이 기록됨")

    def assert_rejected(self, result, message: str):
        code, step_mocks, logs = result
        self.assertNotEqual(0, code)
        self.assertIn(message, logs)
        self.assert_no_write(step_mocks)

    def write_state(self, name: str, content: dict):
        (self.state_dir / name).write_text(json.dumps(content, ensure_ascii=False), encoding="utf-8")

    def read_state(self, name: str) -> dict:
        return json.loads((self.state_dir / name).read_text(encoding="utf-8"))

    LOCAL_DNS = {PROD_DOMAIN: ["127.0.0.1"], "127.0.0.1": ["127.0.0.1"]}
    PROD_DNS = {PROD_DOMAIN: ["203.0.113.10"], "127.0.0.1": ["127.0.0.1"]}

    # -- 대상 명시(경로 3) ---------------------------------------------------
    def test_target_missing_rejected(self):
        with mock.patch.object(sys, "stderr", new_callable=io.StringIO) as stderr:
            code, step_mocks, _ = self.run_seed(["--step", "verify"], base_env(PROD_DOMAIN), self.LOCAL_DNS)
        self.assertEqual(2, code)  # argparse 필수 인자 누락 종료 — 다른 가드의 거부(1)와 구분
        self.assertIn("--target", stderr.getvalue())
        self.assert_no_write(step_mocks)

    # -- local 호스트 판별(경로 1) -------------------------------------------
    def test_local_rejects_public_api_host(self):
        result = self.run_seed(["--target", "local", "--step", "verify"], base_env(PROD_DOMAIN), self.PROD_DNS)
        self.assert_rejected(result, "local인데 API 호스트")
        self.assertEqual([], self.apis)  # 호스트 거부는 API 클라이언트 생성(로그인 POST) 전

    def test_local_rejects_public_db_host(self):
        dns = {PROD_DOMAIN: ["127.0.0.1"], "db.example.com": ["203.0.113.20"]}
        result = self.run_seed(["--target", "local", "--step", "verify"], base_env(PROD_DOMAIN, db_host="db.example.com"), dns)
        self.assert_rejected(result, "local인데 DB_HOST")
        self.assertEqual([], self.apis)

    def test_local_accepts_loopback_and_private_and_records_target(self):
        dns = {PROD_DOMAIN: ["127.0.0.1"], "192.168.0.5": ["192.168.0.5"]}
        code, step_mocks, _ = self.run_seed(["--target", "local", "--step", "verify"], base_env(PROD_DOMAIN, db_host="192.168.0.5"), dns)
        self.assertEqual(0, code)
        self.assertTrue(step_mocks["step_verify"].called)
        self.assertEqual({"name": "local", "apiHost": PROD_DOMAIN, "dbHost": "192.168.0.5", "dbName": "zslab_mall"},
                         self.read_state("seed-state.local.json")["target"])

    # -- prod 호스트 판별(경로 1) · 도메인 확인(경로 3) ------------------------
    def test_prod_rejects_loopback_api_host(self):
        result = self.run_seed(["--target", "prod", "--step", "master"], base_env(PROD_DOMAIN), self.LOCAL_DNS, typed=PROD_DOMAIN)
        self.assert_rejected(result, "prod인데 API 호스트")
        self.assertEqual([], self.apis)

    def test_prod_rejects_when_any_address_is_private(self):
        dns = {PROD_DOMAIN: ["203.0.113.10", "10.0.0.7"], "127.0.0.1": ["127.0.0.1"]}
        result = self.run_seed(["--target", "prod", "--step", "master"], base_env(PROD_DOMAIN), dns, typed=PROD_DOMAIN)
        self.assert_rejected(result, "prod인데 API 호스트")
        self.assertEqual([], self.apis)

    def test_prod_rejects_ipv4_mapped_loopback(self):
        dns = {PROD_DOMAIN: ["::ffff:127.0.0.1"], "127.0.0.1": ["127.0.0.1"]}
        result = self.run_seed(["--target", "prod", "--step", "master"], base_env(PROD_DOMAIN), dns, typed=PROD_DOMAIN)
        self.assert_rejected(result, "prod인데 API 호스트")

    def test_prod_rejects_tls_verify_off(self):
        env = {**base_env(PROD_DOMAIN), "API_TLS_VERIFY": "false"}
        result = self.run_seed(["--target", "prod", "--step", "master"], env, self.PROD_DNS, typed=PROD_DOMAIN)
        self.assert_rejected(result, "API_TLS_VERIFY")

    def test_prod_rejects_piped_input(self):
        result = self.run_seed(["--target", "prod", "--step", "verify"], base_env(PROD_DOMAIN), self.PROD_DNS, typed=PROD_DOMAIN, tty=False)
        self.assert_rejected(result, "터미널에서 직접 입력")
        self.assertFalse(self.input_mock.called)

    def test_api_client_ignores_proxy_env(self):
        # 프록시가 도메인을 따로 해석하면 DNS 판정과 실제 접속 대상이 어긋난다 — 세션이 환경 프록시를 읽지 않아야 한다
        self.assertFalse(seed.ApiClient("https://" + PROD_DOMAIN).session.trust_env)

    def test_prod_reports_host_before_step_checks(self):
        # 판정 순서 고정: 대상 어긋남(호스트)이 허용 단계 검사(--step all 기본값)보다 먼저 보고된다
        code, step_mocks, logs = self.run_seed(["--target", "prod", "--dry-run"], base_env(PROD_DOMAIN), self.LOCAL_DNS)
        self.assertNotEqual(0, code)
        self.assertIn("prod인데 API 호스트", logs)
        self.assertNotIn("prod 허용 단계", logs)
        self.assert_no_write(step_mocks)

    def test_prod_rejects_domain_typo(self):
        result = self.run_seed(["--target", "prod", "--step", "verify"], base_env(PROD_DOMAIN), self.PROD_DNS, typed="zslab-mall.duckdns.or")
        self.assert_rejected(result, "입력한 도메인")

    def test_prod_accepts_typed_domain(self):
        code, step_mocks, _ = self.run_seed(["--target", "prod", "--step", "verify"], base_env(PROD_DOMAIN), self.PROD_DNS, typed=PROD_DOMAIN)
        self.assertEqual(0, code)
        self.assertTrue(step_mocks["step_verify"].called)
        self.assertTrue(self.input_mock.called)
        self.assertEqual("prod", self.read_state("seed-state.prod.json")["target"]["name"])

    # -- prod 금지 인자·단계(경로 5) -----------------------------------------
    def test_prod_rejects_force(self):
        result = self.run_seed(["--target", "prod", "--step", "master", "--force"], base_env(PROD_DOMAIN), self.PROD_DNS, typed=PROD_DOMAIN)
        self.assert_rejected(result, "--force")

    def test_prod_rejects_steps_outside_allow_list(self):
        # 허용 목록 방식: 기존 쓰기 단계와 all은 전부 거부(신규 단계도 목록에 넣기 전까지 거부)
        for step in ["master", "orders", "timeshift", "settlement", "reviews", "all"]:
            with self.subTest(step=step):
                result = self.run_seed(["--target", "prod", "--step", step], base_env(PROD_DOMAIN), self.PROD_DNS, typed=PROD_DOMAIN)
                self.assert_rejected(result, "prod 허용 단계: verify")

    def test_prod_allows_verify_step(self):
        code, step_mocks, logs = self.run_seed(["--target", "prod", "--step", "verify"], base_env(PROD_DOMAIN), self.PROD_DNS, typed=PROD_DOMAIN)
        self.assertEqual(0, code)
        self.assertTrue(step_mocks["step_verify"].called)
        self.assertNotIn("prod 허용 단계", logs)

    def test_prod_allows_106_data_steps(self):
        # 106 데이터 단계(D-243)는 부족분만 API로 추가하므로 prod 허용 목록에 있다
        for step in ["delivered", "inquiries", "qna"]:
            with self.subTest(step=step):
                code, step_mocks, logs = self.run_seed(["--target", "prod", "--step", step], base_env(PROD_DOMAIN), self.PROD_DNS,
                                                       typed=PROD_DOMAIN)
                self.assertEqual(0, code)
                self.assertTrue(step_mocks[f"step_{step}"].called)
                self.assertNotIn("prod 허용 단계", logs)

    def test_qna_requires_seller_env(self):
        env = {key: value for key, value in base_env(PROD_DOMAIN).items() if key != "DEMO_SELLER_PASSWORD"}
        code, step_mocks, logs = self.run_seed(["--target", "local", "--step", "qna"], env, self.LOCAL_DNS)
        self.assertEqual(2, code)
        self.assertIn("DEMO_SELLER_PASSWORD", logs)
        self.assert_no_write(step_mocks)

    def test_inquiries_does_not_require_seller_env(self):
        env = {key: value for key, value in base_env(PROD_DOMAIN).items() if not key.startswith("DEMO_SELLER_")}
        code, step_mocks, _ = self.run_seed(["--target", "local", "--step", "inquiries"], env, self.LOCAL_DNS)
        self.assertEqual(0, code)
        self.assertTrue(step_mocks["step_inquiries"].called)

    def test_prod_rejects_plain_http(self):
        env = {**base_env(PROD_DOMAIN), "API_BASE_URL": f"http://{PROD_DOMAIN}"}
        result = self.run_seed(["--target", "prod", "--step", "verify"], env, self.PROD_DNS, typed=PROD_DOMAIN)
        self.assert_rejected(result, "https여야")

    def test_prod_rejects_unique_local_ipv6(self):
        dns = {PROD_DOMAIN: ["fd12:3456::1"], "127.0.0.1": ["127.0.0.1"]}
        result = self.run_seed(["--target", "prod", "--step", "verify"], base_env(PROD_DOMAIN), dns, typed=PROD_DOMAIN)
        self.assert_rejected(result, "prod인데 API 호스트")

    def test_local_accepts_unique_local_ipv6(self):
        dns = {PROD_DOMAIN: ["fd12:3456::1"], "127.0.0.1": ["127.0.0.1"]}
        code, step_mocks, _ = self.run_seed(["--target", "local", "--step", "verify"], base_env(PROD_DOMAIN), dns)
        self.assertEqual(0, code)
        self.assertTrue(step_mocks["step_verify"].called)

    # -- API·DB 교차 검증(경로 2) --------------------------------------------
    def test_cross_check_mismatch_blocks_writes(self):
        self.conn.admin_present = False
        result = self.run_seed(["--target", "local", "--step", "master"], base_env(PROD_DOMAIN), self.LOCAL_DNS)
        self.assert_rejected(result, "교차 검증")
        self.assertIn(("GET", "/api/v1/admin/me"), self.apis[0].calls)

    def test_cross_check_runs_in_dry_run(self):
        self.conn.admin_present = False
        result = self.run_seed(["--target", "local", "--dry-run"], base_env(PROD_DOMAIN), self.LOCAL_DNS)
        self.assert_rejected(result, "교차 검증")

    def test_dry_run_passes_cross_check_without_writing_state(self):
        code, step_mocks, _ = self.run_seed(["--target", "local", "--dry-run"], base_env(PROD_DOMAIN), self.LOCAL_DNS)
        self.assertEqual(0, code)
        self.assertIn(("GET", "/api/v1/admin/me"), self.apis[0].calls)
        self.assert_no_write(step_mocks)

    # -- state 대상 분리(경로 4) ---------------------------------------------
    def test_state_target_mismatch_rejected(self):
        recorded = {"name": "local", "apiHost": PROD_DOMAIN, "dbHost": "127.0.0.1", "dbName": "other_db"}
        self.write_state("seed-state.local.json", {"target": recorded, "time_shifted": True})
        result = self.run_seed(["--target", "local", "--step", "verify"], base_env(PROD_DOMAIN), self.LOCAL_DNS)
        code, step_mocks, logs = result
        self.assertNotEqual(0, code)
        self.assertIn("[state]", logs)
        self.assertFalse(any(step_mock.called for step_mock in step_mocks.values()))
        self.assertEqual(recorded, self.read_state("seed-state.local.json")["target"])

    def test_state_file_of_other_target_is_not_used(self):
        self.write_state("seed-state.local.json", {"time_shifted": True, "orders": [{"orderId": "ord_local"}]})
        code, step_mocks, _ = self.run_seed(["--target", "prod", "--step", "verify"], base_env(PROD_DOMAIN), self.PROD_DNS, typed=PROD_DOMAIN)
        self.assertEqual(0, code)
        state_passed = step_mocks["step_verify"].call_args.args[1]
        self.assertNotIn("orders", state_passed)
        self.assertNotIn("time_shifted", state_passed)

    def test_state_without_target_rejected_for_prod(self):
        self.write_state("seed-state.prod.json", {"time_shifted": True})
        code, step_mocks, logs = self.run_seed(["--target", "prod", "--step", "verify"], base_env(PROD_DOMAIN), self.PROD_DNS, typed=PROD_DOMAIN)
        self.assertNotEqual(0, code)
        self.assertIn("[state]", logs)
        self.assertFalse(any(step_mock.called for step_mock in step_mocks.values()))

    def test_state_without_target_adopted_for_local(self):
        self.write_state("seed-state.local.json", {"time_shifted": True})
        code, step_mocks, _ = self.run_seed(["--target", "local", "--step", "verify"], base_env(PROD_DOMAIN), self.LOCAL_DNS)
        self.assertEqual(0, code)
        self.assertTrue(step_mocks["step_verify"].called)
        state = self.read_state("seed-state.local.json")
        self.assertTrue(state["time_shifted"])
        self.assertEqual({"name": "local", "apiHost": PROD_DOMAIN, "dbHost": "127.0.0.1", "dbName": "zslab_mall"}, state["target"])

    def test_legacy_state_file_rejected_with_guidance(self):
        self.write_state("seed-state.json", {"time_shifted": True})
        result = self.run_seed(["--target", "local", "--step", "verify"], base_env(PROD_DOMAIN), self.LOCAL_DNS)
        self.assert_rejected(result, "seed-state.local.json")
        self.assertTrue((self.state_dir / "seed-state.json").exists())


if __name__ == "__main__":
    unittest.main()
