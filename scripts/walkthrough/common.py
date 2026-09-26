"""워크스루 도구 공용 모듈(Track 98). 로컬 전용 — .env와 로컬 DB 컨테이너만 다룬다.

자격증명은 .env에서 읽어 subprocess 환경변수로만 전달하며 argv·로그에 남기지 않는다.
"""
from __future__ import annotations

import io
import ipaddress
import json
import os
import socket
import ssl
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ENV_PATH = REPO_ROOT / ".env"

# 로컬 DB 컨테이너 이름(고정). 운영 DB는 원격 호스트의 다른 도커 데몬에 있어 로컬 docker exec으로는 닿지 않는다.
DB_CONTAINER = "zslab_mariadb"
# 백엔드 컨테이너. /actuator/**는 gateway가 backend로 넘기지 않으므로(05-ssl-domain.md: /api/만 프록시)
# 헬스 확인은 컨테이너 내부에서 한다.
BACKEND_CONTAINER = "zslab_mall_backend"
BACKEND_HEALTH_URL = "http://localhost:8080/actuator/health"
# 덤프 산출물은 gitignored 경로에 둔다(frontend/.gitignore: playwright-report/).
DUMP_DIR = REPO_ROOT / "frontend" / "playwright-report" / "walkthrough-db"
DUMP_PATH = DUMP_DIR / "baseline.sql"

API_BASE_URL = "https://zslab-mall.duckdns.org"
# 로컬은 hosts로 운영과 같은 도메인을 127.0.0.1에 두므로 도메인 이름으로는 대상을 가릴 수 없다.
# hosts가 운영 IP를 가리키면 운영에 쓰기가 나가므로, 실행 시점 DNS 해석 결과가 전부 아래 대역일 때만 진행한다.
LOCAL_NETWORKS = (
    ipaddress.ip_network("127.0.0.0/8"),
    ipaddress.ip_network("::1/128"),
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
)


class WalkthroughError(RuntimeError):
    pass


def load_env() -> dict:
    """.env를 dict로 읽는다(값은 출력하지 않는다)."""
    if not ENV_PATH.exists():
        raise WalkthroughError(".env 파일이 없습니다: " + str(ENV_PATH))
    env = {}
    with io.open(ENV_PATH, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                env[key] = value
    return env


def container_running(name: str) -> bool:
    result = subprocess.run(["docker", "ps", "--format", "{{.Names}}"], capture_output=True, text=True)
    return name in result.stdout.split()


def require_local(env: dict) -> None:
    """운영 데이터 보호 가드(CLAUDE.md 운영 데이터 보호 규칙).

    복원은 덤프에 포함된 DROP TABLE/CREATE TABLE을 실행하므로 local 프로파일에서만 허용한다.
    """
    profile = env.get("SPRING_PROFILES_ACTIVE", "")
    if profile != "local":
        raise WalkthroughError("SPRING_PROFILES_ACTIVE=" + repr(profile) + " — 워크스루 DB 조작은 local 전용입니다.")
    if not container_running(DB_CONTAINER):
        raise WalkthroughError("로컬 DB 컨테이너 " + DB_CONTAINER + " 가 실행 중이 아닙니다.")


def require_local_api_host() -> None:
    """API_BASE_URL 호스트가 로컬(loopback·사설 대역)로만 해석될 때 진행한다. 우회 수단은 두지 않는다.

    해석된 IP는 운영 주소일 수 있어 오류 문구에 넣지 않는다.
    """
    host = urllib.parse.urlsplit(API_BASE_URL).hostname
    try:
        resolved = {info[4][0] for info in socket.getaddrinfo(host, None)}
    except socket.gaierror:
        raise WalkthroughError("워크스루 스크립트는 로컬 전용입니다 — " + host + "를 해석할 수 없습니다") from None
    if not resolved or not all(_is_local_address(address) for address in resolved):
        raise WalkthroughError("워크스루 스크립트는 로컬 전용입니다 — " + host + "가 외부 주소로 해석됩니다")


def _is_local_address(address: str) -> bool:
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        # zone id가 붙은 IPv6(fe80::1%eth0) 등 해석할 수 없는 형태는 로컬로 인정하지 않는다.
        return False
    return any(ip in network for network in LOCAL_NETWORKS)


def db_exec(env: dict, args: list, stdin_path=None, stdout_path=None) -> None:
    """DB 컨테이너에서 mariadb 계열 명령을 실행한다. 비밀번호는 MYSQL_PWD 환경변수로만 넘긴다(argv 노출 없음)."""
    child_env = dict(os.environ)
    child_env["MYSQL_PWD"] = env["DB_PASSWORD"]
    docker_args = ["docker", "exec", "-e", "MYSQL_PWD"]
    if stdin_path is not None:
        docker_args.append("-i")
    docker_args.append(DB_CONTAINER)
    stdin_handle = io.open(stdin_path, "rb") if stdin_path else None
    stdout_handle = io.open(stdout_path, "wb") if stdout_path else None
    try:
        result = subprocess.run(docker_args + args, stdin=stdin_handle, stdout=stdout_handle,
                                stderr=subprocess.PIPE, env=child_env)
    finally:
        if stdin_handle:
            stdin_handle.close()
        if stdout_handle:
            stdout_handle.close()
    if result.returncode != 0:
        message = result.stderr.decode("utf-8", "replace").strip()
        raise WalkthroughError("DB 명령 실패(exit " + str(result.returncode) + "): " + message[:300])


def backend_healthy() -> bool:
    """백엔드 컨테이너 안에서 /actuator/health 를 호출해 UP 여부를 본다."""
    if not container_running(BACKEND_CONTAINER):
        return False
    result = subprocess.run(["docker", "exec", BACKEND_CONTAINER, "curl", "-fsS", BACKEND_HEALTH_URL],
                            capture_output=True, text=True)
    return result.returncode == 0 and '"status":"UP"' in result.stdout.replace(" ", "")


class ApiClient:
    """준비 스크립트용 최소 API 클라이언트(로컬 자체 서명 인증서라 TLS 검증을 생략한다)."""

    def __init__(self, base_url: str = API_BASE_URL) -> None:
        self.base_url = base_url
        self.context = ssl.create_default_context()
        self.context.check_hostname = False
        self.context.verify_mode = ssl.CERT_NONE

    def request(self, method: str, path: str, token=None, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base_url + path, data=data, method=method)
        request.add_header("Content-Type", "application/json")
        if token:
            request.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(request, context=self.context) as response:
                raw = response.read()
                return response.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode("utf-8", "replace")[:300]

    def json(self, method: str, path: str, token=None, body=None):
        status, payload = self.request(method, path, token, body)
        if status >= 400:
            raise WalkthroughError(method + " " + path + " -> " + str(status) + ": " + str(payload)[:200])
        # 204·본문 없는 200(mock 결제 콜백 등)은 빈 dict로 돌려준다.
        return payload if payload is not None else {}

    def login(self, email: str, password: str, role: str) -> str:
        return self.json("POST", "/api/v1/auth/login", body={"email": email, "password": password, "role": role})["token"]


def log(message: str) -> None:
    _ensure_utf8_stdout()
    print(message, flush=True)


def fail(message: str) -> int:
    _ensure_utf8_stdout()
    print("ERROR: " + message, file=sys.stderr, flush=True)
    return 1


def _ensure_utf8_stdout() -> None:
    """Windows 콘솔 기본 코드페이지(cp949)에서 한글 로그가 깨지지 않게 한다(demo-seed/seed.py 관례·LT-35)."""
    for stream in (sys.stdout, sys.stderr):
        if getattr(stream, "encoding", "").lower() not in ("utf-8", "utf8"):
            stream.reconfigure(encoding="utf-8")
