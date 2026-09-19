"""
데모 시드 스크립트 (docs/infra/demo-seed/plan.md 기반).

단계: master(카테고리·셀러·계좌·구매자·상품·이미지) → orders(3~8월 주문·클레임·9월 진행분)
     → timeshift(시각 보정 SQL·order_no) → settlement(정산 생성·확정·지급·paid_at 보정) → verify(검증)

접속 정보는 환경변수로만 받는다(README 참조). 재실행 가드는 데모 마커(이메일 도메인·variantCode prefix)로 판정하며
--force 없이는 기존 데모 데이터 위에 진행하지 않는다.
"""

import argparse
import io
import json
import logging
import os
import random
import secrets
import string
import sys
import time
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path

import pymysql
import requests
from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------------------
# 상수
# ---------------------------------------------------------------------------
DEMO_EMAIL_DOMAIN = "demo.zslab-mall.com"  # 데모 마커 1: 데모 계정 이메일 도메인
DEMO_VARIANT_PREFIX = "DEMO-"  # 데모 마커 2: 데모 상품 variantCode prefix
DEMO_SELLER_PREFIX = "데모 "  # 데모 마커 3: 셀러 상호 prefix
SEED_YEAR = 2026
STATE_PATH = Path(__file__).resolve().parent / "state" / "seed-state.json"
IMAGE_DIR = Path(__file__).resolve().parent / "state" / "images"

REQUIRED_ENV = ["API_BASE_URL", "ADMIN_EMAIL", "ADMIN_PASSWORD",
                "DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD"]
RETRY_MAX = 3
RETRY_BASE_SECONDS = 1.0
RETRY_STATUSES = {429, 500, 502, 503, 504}
MULTIPART_MAX_FILES = 20  # AdminFileController 요청당 20장
ORDER_NO_DATE_LENGTH = 8  # order_no 'yyyyMMdd-XXXXXX'(OrderService QB-9)의 날짜부 길이

MONTH_ORDER_COUNTS = {3: 15, 4: 18, 5: 20, 6: 22, 7: 22, 8: 23}  # 합 120
# 3~8월 완결 클레임 15건: (월, 유형)
COMPLETED_CLAIMS = [
    (3, "CANCEL"), (4, "CANCEL"), (4, "RETURN"), (5, "CANCEL"), (5, "RETURN"), (5, "EXCHANGE"),
    (6, "CANCEL"), (6, "RETURN"), (6, "EXCHANGE"), (7, "CANCEL"), (7, "RETURN"), (7, "RETURN"),
    (8, "CANCEL"), (8, "RETURN"), (8, "EXCHANGE"),
]
SEPTEMBER_ORDERS = {"PAID": 8, "SHIPPING": 8, "DELIVERED": 9}  # 합 25
# 9월 진행 중 클레임 3건: (품목 최종 상태, 유형, 클레임 상태)
IN_PROGRESS_CLAIMS = [("PAID", "CANCEL", "REQUESTED"), ("DELIVERED", "RETURN", "APPROVED"),
                      ("DELIVERED", "EXCHANGE", "REQUESTED")]
TWO_ITEM_ORDER_RATIO = 0.2
DEMO_BUYER_WEIGHT = 3  # buyer01(공개 데모 계정) 주문 가중치
CANCEL_REASONS = ["BUYER_CHANGED_MIND", "ORDER_MISTAKE", "DUPLICATE_ORDER"]
RETURN_REASONS = ["PRODUCT_DEFECT", "WRONG_PRODUCT", "BUYER_CHANGED_MIND"]
CARRIERS = ["CJ", "HANJIN", "POST", "LOGEN"]
INITIAL_STOCK = 200
IMAGE_SIZE = 600
PAYOUT_DELAY_MAX_DAYS = 3
SETTLEMENT_MONTHS = [3, 4, 5, 6, 7, 8]
SETTLEMENT_PAID_MONTHS = [3, 4, 5, 6]
SETTLEMENT_CONFIRMED_MONTHS = [3, 4, 5, 6, 7]

log = logging.getLogger("demo-seed")


# ---------------------------------------------------------------------------
# 마스터 데이터 정의
# ---------------------------------------------------------------------------
CATEGORIES = ["리빙·주방", "의류", "잡화", "디지털", "문구"]  # + 기존 '데모' 활용 → 총 6

SELLERS = [
    {"key": "living", "companyName": "데모 리빙샵", "businessNo": "101-81-00001", "ceoName": "김리빙",
     "contactEmail": f"seller01@{DEMO_EMAIL_DOMAIN}", "contactPhone": "02-1000-0001",
     "bank": ("KB", "110-000-000001", "김리빙")},
    {"key": "fashion", "companyName": "데모 패션랩", "businessNo": "102-81-00002", "ceoName": "이패션",
     "contactEmail": f"seller02@{DEMO_EMAIL_DOMAIN}", "contactPhone": "02-1000-0002",
     "bank": ("SHINHAN", "110-000-000002", "이패션")},
    {"key": "tech", "companyName": "데모 테크스토어", "businessNo": "103-81-00003", "ceoName": "박테크",
     "contactEmail": f"seller03@{DEMO_EMAIL_DOMAIN}", "contactPhone": "02-1000-0003",
     "bank": ("WOORI", "110-000-000003", "박테크")},
]

# (카테고리, 셀러키, 상품명, 기본가, 옵션그룹 또는 None)
COLOR_SIZE = [("색상", ["블랙", "화이트"]), ("사이즈", ["M", "L"])]
COLOR_ONLY = [("색상", ["블랙", "실버"])]
PRODUCTS = [
    ("리빙·주방", "living", "스테인리스 3중 냄비 세트", 89000, None),
    ("리빙·주방", "living", "세라믹 코팅 프라이팬 28cm", 39900, None),
    ("리빙·주방", "living", "원목 도마 대형", 24900, None),
    ("리빙·주방", "living", "밀폐 유리 반찬통 6종", 32000, None),
    ("리빙·주방", "living", "전기 주전자 1.7L", 45000, COLOR_ONLY),
    ("의류", "fashion", "코튼 베이직 티셔츠", 19900, COLOR_SIZE),
    ("의류", "fashion", "오버핏 후드 집업", 59000, COLOR_SIZE),
    ("의류", "fashion", "린넨 셔츠", 49000, COLOR_SIZE),
    ("의류", "fashion", "스트레이트 데님 팬츠", 69000, COLOR_SIZE),
    ("의류", "fashion", "경량 패딩 조끼", 79000, COLOR_SIZE),
    ("잡화", "fashion", "가죽 카드지갑", 35000, COLOR_ONLY),
    ("잡화", "fashion", "캔버스 에코백", 15900, None),
    ("잡화", "fashion", "울 머플러", 29000, COLOR_ONLY),
    ("잡화", "fashion", "볼캡", 25000, COLOR_ONLY),
    ("잡화", "fashion", "여행용 파우치 세트", 21000, None),
    ("디지털", "tech", "무선 블루투스 이어폰", 129000, COLOR_ONLY),
    ("디지털", "tech", "USB-C 65W 충전기", 39000, None),
    ("디지털", "tech", "기계식 키보드 텐키리스", 119000, COLOR_ONLY),
    ("디지털", "tech", "무선 마우스", 45000, COLOR_ONLY),
    ("디지털", "tech", "보조배터리 20000mAh", 49000, None),
    ("디지털", "tech", "스마트 체중계", 55000, None),
    ("디지털", "tech", "27인치 모니터 거치대", 189000, None),
    ("문구", "tech", "만년필 스타터 세트", 42000, None),
    ("문구", "tech", "데스크 매트 대형", 18000, COLOR_ONLY),
    ("문구", "living", "하드커버 노트 3권", 14900, None),
    ("문구", "living", "젤펜 12색 세트", 9900, None),
    ("문구", "living", "탁상 달력 2027", 12000, None),
    ("데모", "living", "데모 아로마 캔들", 22000, None),
    ("데모", "fashion", "데모 니트 비니", 19000, COLOR_ONLY),
    ("데모", "tech", "데모 탁상 시계", 33000, None),
]

BUYER_NAMES = ["김데모", "이서연", "박지훈", "최민서", "정하은", "강도윤", "조수아", "윤지호", "임채원", "한시우"]
ADDRESSES = [
    ("06236", "서울 강남구 테헤란로 152", "역삼동 737", "101동 1001호"),
    ("04524", "서울 중구 세종대로 110", "태평로1가 31", "5층"),
    ("48058", "부산 해운대구 센텀중앙로 79", "재송동 1116", "302호"),
    ("41911", "대구 중구 동성로 30", "동성로2가 12", "201호"),
    ("34126", "대전 유성구 대학로 99", "궁동 220", "3동 305호"),
    ("13529", "경기 성남시 분당구 판교역로 166", "백현동 532", "B동 702호"),
    ("21999", "인천 연수구 송도과학로 32", "송도동 30", "1502호"),
    ("61475", "광주 동구 금남로 50", "금남로2가 5", "4층"),
    ("44677", "울산 남구 삼산로 250", "삼산동 1552", "1203호"),
    ("16489", "경기 수원시 영통구 광교로 145", "이의동 1330", "A동 501호"),
]


# ---------------------------------------------------------------------------
# 유틸
# ---------------------------------------------------------------------------
class SeedError(Exception):
    pass


def env(name: str) -> str:
    value = os.environ.get(name)
    if not value:
        raise SeedError(f"환경변수 {name} 미설정")
    return value


def iso(value: datetime) -> str:
    return value.strftime("%Y-%m-%dT%H:%M:%S")


def random_password() -> str:
    alphabet = string.ascii_letters + string.digits
    return "Demo!" + "".join(secrets.choice(alphabet) for _ in range(10))


def load_state() -> dict:
    if STATE_PATH.exists():
        return json.loads(STATE_PATH.read_text(encoding="utf-8"))
    return {}


def save_state(state: dict) -> None:
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    STATE_PATH.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


class StepTimer:
    def __init__(self, name: str):
        self.name = name

    def __enter__(self):
        self.start = time.monotonic()
        log.info("===== [%s] 시작", self.name)
        return self

    def __exit__(self, exc_type, exc, tb):
        elapsed = time.monotonic() - self.start
        if exc_type is None:
            log.info("===== [%s] 완료 (%.1fs)", self.name, elapsed)
        else:
            log.error("===== [%s] 실패 (%.1fs): %s", self.name, elapsed, exc)
        return False


# ---------------------------------------------------------------------------
# API 클라이언트
# ---------------------------------------------------------------------------
class ApiClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        # 로컬 게이트웨이는 자체 서명 인증서라 API_TLS_VERIFY=false 로만 검증을 끈다(기본 검증 on)
        self.session.verify = os.environ.get("API_TLS_VERIFY", "true").lower() != "false"
        if not self.session.verify:
            requests.packages.urllib3.disable_warnings()
        self.call_count = 0

    def request(self, method: str, path: str, token: str | None = None, expect=(200, 201), **kwargs):
        headers = kwargs.pop("headers", {})
        if token:
            headers["Authorization"] = f"Bearer {token}"
        url = self.base_url + path
        for attempt in range(1, RETRY_MAX + 1):
            self.call_count += 1
            response = self.session.request(method, url, headers=headers, timeout=60, **kwargs)
            if response.status_code in RETRY_STATUSES and attempt < RETRY_MAX:
                wait = RETRY_BASE_SECONDS * (2 ** (attempt - 1))
                log.warning("%s %s → %s, %.1fs 후 재시도(%d/%d)", method, path, response.status_code, wait, attempt, RETRY_MAX)
                time.sleep(wait)
                continue
            break
        if response.status_code not in expect:
            raise SeedError(f"{method} {path} → {response.status_code} {response.text[:500]}")
        return response

    def json(self, method: str, path: str, token: str | None = None, expect=(200, 201), **kwargs):
        response = self.request(method, path, token, expect, **kwargs)
        return response.json() if response.content else None

    def login(self, email: str, password: str, role: str) -> str:
        body = self.json("POST", "/api/v1/auth/login", json={"email": email, "password": password, "role": role})
        return body["token"]


# ---------------------------------------------------------------------------
# DB
# ---------------------------------------------------------------------------
def connect_db():
    return pymysql.connect(
        host=env("DB_HOST"), port=int(env("DB_PORT")), user=env("DB_USER"), password=env("DB_PASSWORD"),
        # autocommit: API가 커밋한 행을 REPEATABLE READ 스냅샷 없이 바로 읽기 위함. timeshift만 명시 트랜잭션(begin/commit)
        database=env("DB_NAME"), charset="utf8mb4", autocommit=True, cursorclass=pymysql.cursors.DictCursor)


def query_one(conn, sql: str, params=None):
    with conn.cursor() as cursor:
        cursor.execute(sql, params or ())
        return cursor.fetchone()


def query_all(conn, sql: str, params=None):
    with conn.cursor() as cursor:
        cursor.execute(sql, params or ())
        return cursor.fetchall()


def execute(conn, sql: str, params=None) -> int:
    with conn.cursor() as cursor:
        return cursor.execute(sql, params or ())


# ---------------------------------------------------------------------------
# 재실행 가드
# ---------------------------------------------------------------------------
def demo_user_count(conn) -> int:
    # 모든 변수는 %s 바인딩 사용, SQL injection 위험 없음
    return query_one(conn, "SELECT COUNT(*) AS c FROM `user` WHERE email LIKE %s", (f"%@{DEMO_EMAIL_DOMAIN}",))["c"]


def demo_order_count(conn) -> int:
    return query_one(conn, "SELECT COUNT(*) AS c FROM `order` o JOIN `user` u ON u.id = o.buyer_id WHERE u.email LIKE %s",
                     (f"%@{DEMO_EMAIL_DOMAIN}",))["c"]


def demo_settlement_count(conn) -> int:
    return query_one(conn, "SELECT COUNT(*) AS c FROM settlement s JOIN seller sl ON sl.id = s.seller_id WHERE sl.company_name LIKE %s",
                     (f"{DEMO_SELLER_PREFIX}%",))["c"]


def guard(step: str, conn, state: dict, force: bool) -> None:
    reason = None
    if step == "master" and demo_user_count(conn) > 0:
        reason = f"데모 계정(@{DEMO_EMAIL_DOMAIN})이 이미 존재"
    elif step == "orders" and demo_order_count(conn) > 0:
        reason = "데모 구매자의 주문이 이미 존재"
    elif step == "timeshift" and state.get("time_shifted"):
        reason = "state 파일에 time_shifted=true (이중 보정 방지)"
    elif step == "settlement" and demo_settlement_count(conn) > 0:
        reason = "데모 셀러 정산이 이미 존재"
    if reason is None:
        return
    if force:
        log.warning("[가드] %s — --force로 계속 진행", reason)
        return
    raise SeedError(f"[가드] {reason}. 계속하려면 --force 를 지정하세요.")


# ---------------------------------------------------------------------------
# STEP master
# ---------------------------------------------------------------------------
def find_font(size: int):
    candidates = ["C:/Windows/Fonts/malgun.ttf", "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
                  "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]
    for path in candidates:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    log.warning("한글 폰트를 찾지 못해 기본 폰트 사용(한글이 깨질 수 있음)")
    return ImageFont.load_default()


def make_image(product_name: str, category_name: str, variant_index: int) -> bytes:
    palette = [(52, 101, 164), (198, 73, 73), (60, 140, 90), (180, 120, 40), (110, 70, 160), (40, 130, 150)]
    color = palette[(sum(map(ord, product_name)) + variant_index) % len(palette)]
    image = Image.new("RGB", (IMAGE_SIZE, IMAGE_SIZE), color)
    draw = ImageDraw.Draw(image)
    title_font, sub_font = find_font(40), find_font(28)
    draw.text((40, 220), product_name, fill=(255, 255, 255), font=title_font)
    draw.text((40, 290), f"{category_name} · 이미지 {variant_index + 1}", fill=(230, 230, 230), font=sub_font)
    draw.text((40, 540), "DEMO", fill=(255, 255, 255), font=sub_font)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def upload_images(api: ApiClient, admin_token: str, files: list[tuple[str, bytes]]) -> list[str]:
    urls = []
    for start in range(0, len(files), MULTIPART_MAX_FILES):
        chunk = files[start:start + MULTIPART_MAX_FILES]
        multipart = [("files", (name, data, "image/png")) for name, data in chunk]
        body = api.json("POST", "/api/v1/admin/files/images", admin_token, files=multipart)
        for item in body["results"]:
            if not item["success"]:
                raise SeedError(f"이미지 업로드 실패 {item['fileName']}: {item['code']} {item['message']}")
            urls.append(item["url"])
    return urls


def build_product_body(seller_public_id: str, category_id: int, index: int, name: str, base_price: int, options):
    code = f"{DEMO_VARIANT_PREFIX}{index:02d}"
    if options is None:
        return {"sellerPublicId": seller_public_id, "categoryId": category_id, "name": name,
                "description": f"{name} — 데모 시드 상품입니다.", "basePrice": base_price, "optionGroups": [],
                "variants": [{"variantCode": code, "additionalPrice": 0, "displayOrder": 0,
                              "initialStock": INITIAL_STOCK, "optionKeys": []}]}
    option_groups = []
    combos = [[]]
    for group_index, (group_name, values) in enumerate(options):
        group_values = []
        for value_index, value in enumerate(values):
            key = f"g{group_index}v{value_index}"
            group_values.append({"key": key, "value": value, "displayOrder": value_index})
        option_groups.append({"name": group_name, "displayOrder": group_index, "values": group_values})
        combos = [combo + [value["key"]] for combo in combos for value in group_values]
    variants = [{"variantCode": f"{code}-{i}", "additionalPrice": 0, "displayOrder": i,
                 "initialStock": INITIAL_STOCK, "optionKeys": combo} for i, combo in enumerate(combos)]
    return {"sellerPublicId": seller_public_id, "categoryId": category_id, "name": name,
            "description": f"{name} — 데모 시드 상품입니다(옵션 상품).", "basePrice": base_price,
            "optionGroups": option_groups, "variants": variants}


def step_master(api: ApiClient, conn, state: dict, admin_token: str) -> None:
    counts = {}
    # 1) 카테고리 (기존 '데모' 활용)
    existing = {c["displayName"]: c["categoryId"] for c in api.json("GET", "/api/v1/categories")}
    categories = dict(existing)
    created = 0
    for sort_order, name in enumerate(CATEGORIES, start=1):
        if name in categories:
            continue
        body = api.json("POST", "/api/v1/admin/categories", admin_token, json={"displayName": name, "sortOrder": sort_order})
        categories[name] = body["categoryId"]
        created += 1
    if "데모" not in categories:
        body = api.json("POST", "/api/v1/admin/categories", admin_token, json={"displayName": "데모", "sortOrder": 0})
        categories["데모"] = body["categoryId"]
        created += 1
    counts["category"] = created
    state["categories"] = categories
    log.info("카테고리 신규 %d (총 %d)", created, len(categories))

    # 2) 구매자 10 + 셀러 owner 3 가입
    demo_buyer_password = os.environ.get("DEMO_BUYER_PASSWORD") or random_password()
    buyers = []
    for index, name in enumerate(BUYER_NAMES, start=1):
        email = f"buyer{index:02d}@{DEMO_EMAIL_DOMAIN}"
        password = demo_buyer_password if index == 1 else random_password()
        api.json("POST", "/api/v1/users", json={"email": email, "name": name, "phone": f"010-2000-{index:04d}", "password": password})
        buyers.append({"email": email, "name": name, "password": password, "address": ADDRESSES[index - 1]})
    state["buyers"] = buyers
    counts["buyer"] = len(buyers)

    sellers = []
    for index, seller in enumerate(SELLERS, start=1):
        email = f"seller{index:02d}@{DEMO_EMAIL_DOMAIN}"
        password = random_password()
        api.json("POST", "/api/v1/users", json={"email": email, "name": seller["ceoName"], "phone": f"010-3000-{index:04d}", "password": password})
        # ownerUserId는 내부 Long id(SellerProvisioningRequest:19) — API 미노출이라 DB 조회. %s 바인딩·injection 위험 없음
        owner_id = query_one(conn, "SELECT id FROM `user` WHERE email = %s", (email,))["id"]
        body = api.json("POST", "/api/v1/admin/sellers", admin_token, json={
            "companyName": seller["companyName"], "businessNo": seller["businessNo"], "ceoName": seller["ceoName"],
            "contactEmail": seller["contactEmail"], "contactPhone": seller["contactPhone"], "status": "ACTIVE",
            "ownerUserId": owner_id})
        seller_public_id = body["sellerPublicId"]
        seller_id = query_one(conn, "SELECT id FROM seller WHERE public_id = %s", (seller_public_id,))["id"]
        bank_code, account_number, holder = seller["bank"]
        # Track 89-F: 계좌번호는 앱 Converter가 AES 암호화해 저장하므로 raw INSERT(평문) 대신 등록 API를 쓴다 — 평문 행은 이후 조회에서
        # strict 복호 예외가 난다. 첫 계좌는 자동 주 계좌·VERIFIED(D-188). 응답에는 끝 4자리만 오며 실값은 로그에 남기지 않는다.
        api.json("POST", f"/api/v1/admin/sellers/{seller_public_id}/bank-accounts", admin_token, json={
            "bankCode": bank_code, "accountNumber": account_number, "accountHolder": holder})
        sellers.append({"key": seller["key"], "email": email, "password": password, "publicId": seller_public_id,
                        "id": seller_id, "companyName": seller["companyName"]})
    state["sellers"] = sellers
    counts["seller"] = len(sellers)
    counts["seller_bank_account"] = len(sellers)
    save_state(state)

    # 3) 상품 30 + 이미지
    seller_by_key = {s["key"]: s for s in sellers}
    products = []
    image_files = []
    image_plan = []  # (product index, image count)
    for index, (category_name, seller_key, name, base_price, options) in enumerate(PRODUCTS, start=1):
        image_count = 2 if index % 3 == 0 else 1
        image_plan.append(image_count)
        for image_index in range(image_count):
            image_files.append((f"demo-{index:02d}-{image_index}.png", make_image(name, category_name, image_index)))
    urls = upload_images(api, admin_token, image_files)
    counts["image_upload"] = len(urls)
    url_cursor = 0
    for index, (category_name, seller_key, name, base_price, options) in enumerate(PRODUCTS, start=1):
        seller = seller_by_key[seller_key]
        body = build_product_body(seller["publicId"], categories[category_name], index, name, base_price, options)
        created_product = api.json("POST", "/api/v1/admin/products", admin_token, json=body)
        product_public_id = created_product["productPublicId"]
        image_count = image_plan[index - 1]
        images = []
        for image_index in range(image_count):
            images.append({"imageId": None, "imageUrl": urls[url_cursor], "imageType": "GALLERY", "main": image_index == 0})
            url_cursor += 1
        api.json("PUT", f"/api/v1/admin/products/{product_public_id}/images", admin_token, json={"images": images})
        api.json("POST", f"/api/v1/admin/products/{product_public_id}/approve", admin_token)
        unit_price = base_price  # additionalPrice 0
        products.append({"publicId": product_public_id, "name": name, "sellerKey": seller_key, "category": category_name,
                         "unitPrice": unit_price, "variantPublicIds": created_product["variantPublicIds"],
                         "hasOptions": options is not None})
    state["products"] = products
    counts["product"] = len(products)
    counts["product_variant"] = sum(len(p["variantPublicIds"]) for p in products)
    save_state(state)
    log.info("master 생성 건수: %s", counts)
    state.setdefault("counts", {}).update(counts)
    save_state(state)


# ---------------------------------------------------------------------------
# STEP orders
# ---------------------------------------------------------------------------
@dataclass
class OrderPlan:
    month: int
    day: int
    hour: int
    minute: int
    buyer_index: int
    product_indexes: list[int]
    final: str  # CONFIRMED | PAID | SHIPPING | DELIVERED
    claim_type: str | None = None  # CANCEL | RETURN | EXCHANGE
    claim_end: str | None = None  # COMPLETED | REQUESTED | APPROVED

    @property
    def ordered_at(self) -> datetime:
        return datetime(SEED_YEAR, self.month, self.day, self.hour, self.minute, random.randint(0, 59))


def build_order_plans(rng: random.Random, product_count: int, buyer_count: int, option_product_indexes: list[int]) -> list[OrderPlan]:
    buyer_weights = [DEMO_BUYER_WEIGHT] + [1] * (buyer_count - 1)
    plans: list[OrderPlan] = []
    claim_pool = list(COMPLETED_CLAIMS)
    for month, count in MONTH_ORDER_COUNTS.items():
        month_claims = [c for c in claim_pool if c[0] == month]
        for order_index in range(count):
            claim_type = month_claims.pop(0)[1] if month_claims else None
            # 반품·교환 체인(최대 ~15일)이 월 내에 끝나도록 앞쪽 날짜, 그 외는 1~20일 분산
            day = rng.randint(1, 12) if claim_type in ("RETURN", "EXCHANGE") else rng.randint(1, 20)
            buyer_index = rng.choices(range(buyer_count), weights=buyer_weights)[0]
            if claim_type == "EXCHANGE":
                product_indexes = [rng.choice(option_product_indexes)]
            elif claim_type is not None or rng.random() >= TWO_ITEM_ORDER_RATIO:
                product_indexes = [rng.randrange(product_count)]
            else:
                product_indexes = rng.sample(range(product_count), 2)
            plans.append(OrderPlan(month, day, rng.randint(9, 21), rng.randint(0, 59), buyer_index, product_indexes,
                                   "CONFIRMED", claim_type, "COMPLETED" if claim_type else None))
    # 9월 진행분(오늘 이전으로만)
    today = date.today()
    in_progress = list(IN_PROGRESS_CLAIMS)
    for final, count in SEPTEMBER_ORDERS.items():
        for _ in range(count):
            claim = None
            for candidate in in_progress:
                if candidate[0] == final:
                    claim = candidate
                    in_progress.remove(candidate)
                    break
            if final == "PAID":
                day = rng.randint(max(1, today.day - 4), max(1, today.day - 1))
            elif final == "SHIPPING":
                day = rng.randint(max(1, today.day - 8), max(1, today.day - 3))
            else:  # DELIVERED: delivered_at이 최근 7일 안에 들어오도록(자동확정 회피)
                day = rng.randint(max(1, today.day - 6), max(1, today.day - 4))
            buyer_index = rng.choices(range(buyer_count), weights=buyer_weights)[0]
            product_indexes = [rng.choice(option_product_indexes)] if claim and claim[1] == "EXCHANGE" else [rng.randrange(product_count)]
            plans.append(OrderPlan(9, day, rng.randint(9, 21), rng.randint(0, 59), buyer_index, product_indexes, final,
                                   claim[1] if claim else None, claim[2] if claim else None))
    return plans


class OrderRunner:
    def __init__(self, api: ApiClient, conn, state: dict, admin_token: str, rng: random.Random):
        self.api = api
        self.conn = conn
        self.state = state
        self.admin_token = admin_token
        self.rng = rng
        self.buyer_tokens: dict[int, str] = {}
        self.tracking_seq = 1000

    def buyer_token(self, buyer_index: int) -> str:
        if buyer_index not in self.buyer_tokens:
            buyer = self.state["buyers"][buyer_index]
            self.buyer_tokens[buyer_index] = self.api.login(buyer["email"], buyer["password"], "BUYER")
        return self.buyer_tokens[buyer_index]

    def next_tracking(self) -> str:
        self.tracking_seq += 1
        return f"DEMO{self.tracking_seq:08d}"

    def run(self, plan: OrderPlan) -> dict:
        buyer = self.state["buyers"][plan.buyer_index]
        token = self.buyer_token(plan.buyer_index)
        products = self.state["products"]
        items = []
        for product_index in plan.product_indexes:
            product = products[product_index]
            variant = product["variantPublicIds"][0]
            items.append({"productId": product["publicId"], "variantId": variant, "quantity": self.rng.choice([1, 1, 1, 2])})
        zonecode, road, jibun, detail = buyer["address"]
        body = {"items": items, "method": self.rng.choice(["CARD", "CARD", "KAKAO", "BANK"]),
                "shippingAddress": {"recipientName": buyer["name"], "recipientPhone": "010-2000-0000", "zonecode": zonecode,
                                    "addressRoad": road, "addressJibun": jibun, "addressDetail": detail, "deliveryMemo": "문 앞에 놓아주세요"}}
        checkout = self.api.json("POST", "/api/v1/orders", token, json=body)
        redirect_url = checkout["payment"]["redirectUrl"]
        attempt_key = redirect_url.split("attemptKey=")[1].split("&")[0]
        ordered_at = plan.ordered_at
        paid_at = ordered_at + timedelta(minutes=self.rng.randint(1, 3))
        # 결제 승인 = Mock 웹훅 SUCCESS. occurredAt이 payment.paid_at·order.paid_at이 된다(PaymentService:273)
        self.api.json("POST", "/api/webhooks/payments", json={
            "provider": "MOCK_PG", "callbackType": "SUCCESS", "paymentAttemptKey": attempt_key,
            "pgTid": f"mock_tid_{attempt_key[-8:]}", "occurredAt": iso(paid_at), "metadata": {}})
        latest = self.api.json("GET", "/api/v1/orders?page=0&size=1", token)["items"][0]
        order_public_id = latest["orderId"]
        detail_body = self.api.json("GET", f"/api/v1/orders/{order_public_id}", token)
        # 응답은 셀러별 그룹(OrderResponse.sellers)이라 요청 순서와 다를 수 있음 → productId로 대응
        item_id_by_product = {item["productId"]: item["orderItemId"]
                              for group in detail_body["sellers"] for item in group["items"]}
        record = {"orderId": order_public_id, "buyerIndex": plan.buyer_index, "orderedAt": iso(ordered_at), "paidAt": iso(paid_at),
                  "final": plan.final, "claimType": plan.claim_type, "claimEnd": plan.claim_end, "month": plan.month,
                  "items": [{"orderItemId": item_id_by_product[products[pi]["publicId"]], "productIndex": pi}
                            for pi in plan.product_indexes]}

        if plan.claim_type == "CANCEL":
            self._cancel(token, record)
            return record
        if plan.final == "PAID":
            return record
        # 배송 등록·완료(ADMIN)
        for item in record["items"]:
            shipped = self.api.json("POST", f"/api/v1/admin/orders/items/{item['orderItemId']}/prepare-shipment", self.admin_token,
                                    json={"carrier": self.rng.choice(CARRIERS), "trackingNo": self.next_tracking()})
            item["deliveryId"] = shipped["deliveryPublicId"]
        if plan.final == "SHIPPING":
            return record
        for item in record["items"]:
            self.api.json("POST", f"/api/v1/admin/deliveries/{item['deliveryId']}/mark-delivered", self.admin_token)
        if plan.claim_type in ("RETURN", "EXCHANGE"):
            self._return_or_exchange(token, record, plan)
            if plan.claim_type == "EXCHANGE" and plan.claim_end == "COMPLETED":
                self._confirm(token, record)
            return record
        if plan.final == "CONFIRMED":
            self._confirm(token, record)
        return record

    def _confirm(self, token: str, record: dict) -> None:
        for item in record["items"]:
            self.api.json("POST", f"/api/v1/orders/{record['orderId']}/items/{item['orderItemId']}/confirm", token)
        record["confirmed"] = True

    def _cancel(self, token: str, record: dict) -> None:
        item = record["items"][0]
        claim = self.api.json("POST", "/api/v1/claims", token, json={
            "orderItemPublicId": item["orderItemId"], "claimType": "CANCEL",
            "reasonCode": self.rng.choice(CANCEL_REASONS), "reasonDetail": "데모 취소 요청"})
        item["claimId"] = claim["publicId"]
        if record["claimEnd"] == "REQUESTED":
            return
        # 승인 커밋 후 환불 개시→Mock 콜백→COMPLETED→품목 CANCELLED까지 동기 자동(ClaimApprovedHandler·MockRefundAutoCallbackListener)
        self.api.json("POST", f"/api/v1/admin/claims/{claim['publicId']}/approve", self.admin_token, json={})
        final = self.api.json("GET", f"/api/v1/claims/{claim['publicId']}", token)
        if final["status"] != "COMPLETED":
            raise SeedError(f"취소 클레임 미완결: {claim['publicId']} status={final['status']}")

    def _return_or_exchange(self, token: str, record: dict, plan: OrderPlan) -> None:
        item = record["items"][0]
        product = self.state["products"][item["productIndex"]]
        body = {"orderItemPublicId": item["orderItemId"], "claimType": plan.claim_type,
                "reasonCode": self.rng.choice(RETURN_REASONS), "reasonDetail": f"데모 {plan.claim_type} 요청"}
        if plan.claim_type == "EXCHANGE":
            body["exchangeVariantId"] = product["variantPublicIds"][1]  # 같은 상품·같은 가격·다른 옵션
        claim = self.api.json("POST", "/api/v1/claims", token, json=body)
        claim_id = claim["publicId"]
        item["claimId"] = claim_id
        if plan.claim_end == "REQUESTED":
            return
        self.api.json("POST", f"/api/v1/admin/claims/{claim_id}/approve", self.admin_token, json={})
        if plan.claim_end == "APPROVED":
            return
        self.api.json("POST", f"/api/v1/claims/{claim_id}/return-shipment", token,
                      json={"carrier": self.rng.choice(CARRIERS), "trackingNo": self.next_tracking()})
        self.api.json("POST", f"/api/v1/admin/claims/{claim_id}/confirm-pickup", self.admin_token)
        self.api.json("POST", f"/api/v1/admin/claims/{claim_id}/inspect", self.admin_token, json={"result": "PASS", "restock": True})
        if plan.claim_type == "EXCHANGE":
            shipped = self.api.json("POST", f"/api/v1/admin/claims/{claim_id}/register-exchange-shipment", self.admin_token,
                                    json={"carrier": self.rng.choice(CARRIERS), "trackingNo": self.next_tracking()})
            item["exchangeDeliveryId"] = shipped["deliveryPublicId"]
            self.api.json("POST", f"/api/v1/admin/deliveries/{item['exchangeDeliveryId']}/mark-delivered", self.admin_token)
        final = self.api.json("GET", f"/api/v1/claims/{claim_id}", token)
        if final["status"] != "COMPLETED":
            raise SeedError(f"{plan.claim_type} 클레임 미완결: {claim_id} status={final['status']}")


def step_orders(api: ApiClient, conn, state: dict, admin_token: str, rng: random.Random) -> None:
    if "products" not in state or "buyers" not in state:
        raise SeedError("state에 master 결과가 없습니다. --step master 먼저 실행")
    option_product_indexes = [i for i, p in enumerate(state["products"]) if p["hasOptions"]]
    plans = build_order_plans(rng, len(state["products"]), len(state["buyers"]), option_product_indexes)
    runner = OrderRunner(api, conn, state, admin_token, rng)
    records = state.setdefault("orders", [])
    done = len(records)
    log.info("주문 계획 %d건 (완료 %d건부터 재개)", len(plans), done)
    for index, plan in enumerate(plans):
        if index < done:
            continue
        record = runner.run(plan)
        records.append(record)
        save_state(state)
        if (index + 1) % 10 == 0:
            log.info("주문 진행 %d/%d (API 호출 누계 %d)", index + 1, len(plans), api.call_count)
    counts = state.setdefault("counts", {})
    counts["order"] = len(records)
    counts["order_item"] = sum(len(r["items"]) for r in records)
    counts["claim"] = sum(1 for r in records if r["claimType"])
    counts["claim_completed"] = sum(1 for r in records if r["claimEnd"] == "COMPLETED")
    counts["confirmed_orders"] = sum(1 for r in records if r.get("confirmed"))
    save_state(state)
    log.info("orders 생성 건수: order %d · order_item %d · claim %d(완결 %d) · 구매확정 주문 %d",
             counts["order"], counts["order_item"], counts["claim"], counts["claim_completed"], counts["confirmed_orders"])


# ---------------------------------------------------------------------------
# STEP timeshift
# ---------------------------------------------------------------------------
@dataclass
class Timeline:
    ordered_at: datetime
    paid_at: datetime
    shipped_at: datetime | None = None
    delivered_at: datetime | None = None
    confirmed_at: datetime | None = None
    claim_requested_at: datetime | None = None
    claim_processed_at: datetime | None = None
    return_shipped_at: datetime | None = None
    return_delivered_at: datetime | None = None  # = picked_up_at
    inspected_at: datetime | None = None
    refunded_at: datetime | None = None
    exchange_shipped_at: datetime | None = None
    exchange_delivered_at: datetime | None = None

    def last(self) -> datetime:
        values = [v for v in vars(self).values() if isinstance(v, datetime)]
        return max(values)


def build_timeline(record: dict, rng: random.Random, now: datetime) -> Timeline:
    ordered_at = datetime.fromisoformat(record["orderedAt"])
    paid_at = datetime.fromisoformat(record["paidAt"])
    t = Timeline(ordered_at, paid_at)
    hours = lambda a, b: timedelta(hours=rng.randint(a, b))
    days = lambda a, b: timedelta(days=rng.randint(a, b), hours=rng.randint(0, 8))
    september = record["month"] == 9
    if record["claimType"] == "CANCEL":
        t.claim_requested_at = paid_at + hours(1, 12)
        if record["claimEnd"] != "REQUESTED":
            t.claim_processed_at = t.claim_requested_at + hours(1, 6)
            t.refunded_at = t.claim_processed_at + timedelta(minutes=1)
        return clamp(t, now)
    if record["final"] == "PAID":
        return clamp(t, now)
    t.shipped_at = paid_at + (days(1, 1) if september else days(1, 2))
    if record["final"] == "SHIPPING":
        return clamp(t, now)
    t.delivered_at = t.shipped_at + (days(1, 2) if september else days(1, 3))
    claim_type = record["claimType"]
    if claim_type in ("RETURN", "EXCHANGE"):
        t.claim_requested_at = t.delivered_at + days(1, 5)
        if record["claimEnd"] != "REQUESTED":
            t.claim_processed_at = t.claim_requested_at + days(1, 1)
        if record["claimEnd"] == "COMPLETED":
            t.return_shipped_at = t.claim_processed_at + days(1, 1)
            t.return_delivered_at = t.return_shipped_at + days(2, 2)
            t.inspected_at = t.return_delivered_at + days(1, 1)
            if claim_type == "RETURN":
                t.refunded_at = t.inspected_at + timedelta(minutes=1)
            else:
                t.exchange_shipped_at = t.inspected_at + days(1, 1)
                t.exchange_delivered_at = t.exchange_shipped_at + days(2, 2)
                if record.get("confirmed"):
                    t.confirmed_at = t.exchange_delivered_at + days(2, 2)
        return clamp(t, now)
    if record.get("confirmed"):
        t.confirmed_at = t.delivered_at + days(1, 6)
    return clamp(t, now)


def clamp(t: Timeline, now: datetime) -> Timeline:
    # 9월 진행분은 실행 시각을 넘지 않도록(미래 시각 방지)
    limit = now - timedelta(minutes=5)
    for name, value in list(vars(t).items()):
        if isinstance(value, datetime) and value > limit:
            setattr(t, name, limit)
    return t


def shift_order(conn, record: dict, t: Timeline) -> None:
    # 모든 변수는 %s 바인딩 사용, SQL injection 위험 없음. 대상 행은 데모 구매자 소유 주문으로 한정(데모 마커 WHERE 포함)
    order = query_one(conn, "SELECT o.id, o.order_no FROM `order` o JOIN `user` u ON u.id = o.buyer_id "
                            "WHERE o.public_id = %s AND u.email LIKE %s", (record["orderId"], f"%@{DEMO_EMAIL_DOMAIN}"))
    if order is None:
        raise SeedError(f"데모 주문을 찾을 수 없음: {record['orderId']}")
    order_id = order["id"]
    last = t.last()
    new_order_no = t.ordered_at.strftime("%Y%m%d") + order["order_no"][ORDER_NO_DATE_LENGTH:]
    if query_one(conn, "SELECT COUNT(*) AS c FROM `order` WHERE order_no = %s AND id <> %s", (new_order_no, order_id))["c"]:
        raise SeedError(f"order_no 충돌: {new_order_no}")
    execute(conn, "UPDATE `order` SET created_at=%s, ordered_at=%s, paid_at=%s, updated_at=%s, order_no=%s WHERE id=%s",
            (t.ordered_at, t.ordered_at, t.paid_at, last, new_order_no, order_id))
    execute(conn, "UPDATE order_shipping_snapshot SET created_at=%s, updated_at=%s WHERE order_id=%s", (t.ordered_at, t.ordered_at, order_id))
    execute(conn, "UPDATE payment SET created_at=%s, paid_at=%s, expires_at=%s, updated_at=%s WHERE order_id=%s",
            (t.ordered_at, t.paid_at, t.ordered_at + timedelta(minutes=30), t.paid_at, order_id))
    execute(conn, "UPDATE order_item SET created_at=%s, updated_at=%s, confirmed_at=%s WHERE order_id=%s",
            (t.ordered_at, last, t.confirmed_at, order_id))
    items = query_all(conn, "SELECT id FROM order_item WHERE order_id=%s", (order_id,))
    item_ids = [row["id"] for row in items]
    for item_id in item_ids:
        if t.shipped_at:
            execute(conn, "UPDATE delivery SET created_at=%s, shipped_at=%s, delivered_at=%s, updated_at=%s "
                          "WHERE order_item_id=%s AND direction='OUTBOUND' AND claim_id IS NULL",
                    (t.shipped_at, t.shipped_at, t.delivered_at, t.delivered_at or t.shipped_at, item_id))
        claim = query_one(conn, "SELECT id FROM claim WHERE order_item_id=%s ORDER BY id DESC LIMIT 1", (item_id,))
        if claim is None:
            continue
        claim_id = claim["id"]
        claim_last = max(v for v in [t.claim_requested_at, t.claim_processed_at, t.return_delivered_at, t.inspected_at,
                                     t.exchange_delivered_at] if v)
        execute(conn, "UPDATE claim SET created_at=%s, requested_at=%s, processed_at=%s, picked_up_at=%s, inspected_at=%s, "
                      "exchange_reserved_at=%s, updated_at=%s WHERE id=%s",
                (t.claim_requested_at, t.claim_requested_at, t.claim_processed_at, t.return_delivered_at, t.inspected_at,
                 t.claim_processed_at if record["claimType"] == "EXCHANGE" else None, claim_last, claim_id))
        if t.return_shipped_at:
            execute(conn, "UPDATE delivery SET created_at=%s, shipped_at=%s, delivered_at=%s, updated_at=%s "
                          "WHERE claim_id=%s AND direction='RETURN'",
                    (t.return_shipped_at, t.return_shipped_at, t.return_delivered_at, t.return_delivered_at, claim_id))
        if t.exchange_shipped_at:
            execute(conn, "UPDATE delivery SET created_at=%s, shipped_at=%s, delivered_at=%s, updated_at=%s "
                          "WHERE claim_id=%s AND direction='OUTBOUND'",
                    (t.exchange_shipped_at, t.exchange_shipped_at, t.exchange_delivered_at, t.exchange_delivered_at, claim_id))
        if t.refunded_at:
            execute(conn, "UPDATE refund SET created_at=%s, refunded_at=%s, updated_at=%s WHERE claim_id=%s AND status='COMPLETED'",
                    (t.claim_processed_at if record["claimType"] == "CANCEL" else t.inspected_at, t.refunded_at, t.refunded_at, claim_id))


def shift_master_rows(conn, state: dict, rng: random.Random) -> None:
    # 가입·입점·상품 등록 시각은 첫 주문(3월)보다 앞선 2월로(cosmetic). 데모 마커 WHERE 포함
    for buyer in state["buyers"]:
        created = datetime(SEED_YEAR, 2, rng.randint(1, 28), rng.randint(9, 21), rng.randint(0, 59))
        user = query_one(conn, "SELECT id FROM `user` WHERE email=%s", (buyer["email"],))
        execute(conn, "UPDATE `user` SET created_at=%s, updated_at=%s WHERE id=%s AND email LIKE %s", (created, created, user["id"], f"%@{DEMO_EMAIL_DOMAIN}"))
        execute(conn, "UPDATE user_role SET created_at=%s WHERE user_id=%s", (created, user["id"]))
        execute(conn, "UPDATE buyer_profile SET created_at=%s, updated_at=%s WHERE user_id=%s", (created, created, user["id"]))
    for seller in state["sellers"]:
        created = datetime(SEED_YEAR, 2, rng.randint(1, 10), 10, 0)
        user = query_one(conn, "SELECT id FROM `user` WHERE email=%s", (seller["email"],))
        execute(conn, "UPDATE `user` SET created_at=%s, updated_at=%s WHERE id=%s", (created, created, user["id"]))
        execute(conn, "UPDATE user_role SET created_at=%s WHERE user_id=%s", (created, user["id"]))
        execute(conn, "UPDATE buyer_profile SET created_at=%s, updated_at=%s WHERE user_id=%s", (created, created, user["id"]))
        execute(conn, "UPDATE seller SET created_at=%s, updated_at=%s WHERE id=%s AND company_name LIKE %s", (created, created, seller["id"], f"{DEMO_SELLER_PREFIX}%"))
        execute(conn, "UPDATE seller_user SET created_at=%s, updated_at=%s WHERE seller_id=%s", (created, created, seller["id"]))
        execute(conn, "UPDATE seller_bank_account SET created_at=%s, updated_at=%s, verified_at=%s WHERE seller_id=%s",
                (created, created, created + timedelta(days=1), seller["id"]))
    for product in state["products"]:
        created = datetime(SEED_YEAR, 2, rng.randint(11, 28), rng.randint(9, 18), rng.randint(0, 59))
        row = query_one(conn, "SELECT id FROM product WHERE public_id=%s", (product["publicId"],))
        product_id = row["id"]
        execute(conn, "UPDATE product SET created_at=%s, updated_at=%s WHERE id=%s", (created, created, product_id))
        execute(conn, "UPDATE product_image SET created_at=%s, updated_at=%s WHERE product_id=%s", (created, created, product_id))
        execute(conn, "UPDATE product_option_group SET created_at=%s, updated_at=%s WHERE product_id=%s", (created, created, product_id))
        execute(conn, "UPDATE product_option_value SET created_at=%s, updated_at=%s "
                      "WHERE option_group_id IN (SELECT id FROM product_option_group WHERE product_id=%s)", (created, created, product_id))
        execute(conn, "UPDATE product_variant SET created_at=%s, updated_at=%s WHERE product_id=%s AND variant_code LIKE %s",
                (created, created, product_id, f"{DEMO_VARIANT_PREFIX}%"))
        execute(conn, "UPDATE inventory SET created_at=%s, updated_at=%s "
                      "WHERE variant_id IN (SELECT id FROM product_variant WHERE product_id=%s)", (created, created, product_id))


def step_timeshift(conn, state: dict, rng: random.Random) -> None:
    if "orders" not in state:
        raise SeedError("state에 orders 결과가 없습니다. --step orders 먼저 실행")
    now = datetime.now()
    conn.begin()
    try:
        shift_master_rows(conn, state, rng)
        for record in state["orders"]:
            shift_order(conn, record, build_timeline(record, rng, now))
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    state["time_shifted"] = True
    save_state(state)
    log.info("시각 보정 완료: 주문 %d건 + 마스터 행", len(state["orders"]))
    verify_timeshift(conn)


def verify_timeshift(conn) -> None:
    # 데모 주문 한정 순서 불변식·order_no 날짜부 검증(SELECT만)
    demo = f"%@{DEMO_EMAIL_DOMAIN}"
    checks = {
        "order.created_at ≤ paid_at": ("SELECT COUNT(*) AS c FROM `order` o JOIN `user` u ON u.id=o.buyer_id "
                                       "WHERE u.email LIKE %s AND o.paid_at IS NOT NULL AND o.paid_at < o.created_at", (demo,)),
        "order_no 날짜부 = ordered_at": ("SELECT COUNT(*) AS c FROM `order` o JOIN `user` u ON u.id=o.buyer_id "
                                        "WHERE u.email LIKE %s AND LEFT(o.order_no, 8) <> DATE_FORMAT(o.ordered_at, '%%Y%%m%%d')", (demo,)),
        "payment.paid_at = order.paid_at": ("SELECT COUNT(*) AS c FROM payment p JOIN `order` o ON o.id=p.order_id JOIN `user` u ON u.id=o.buyer_id "
                                            "WHERE u.email LIKE %s AND p.status='PAID' AND p.paid_at <> o.paid_at", (demo,)),
        "paid_at < 원발송 shipped_at": ("SELECT COUNT(*) AS c FROM delivery d JOIN order_item oi ON oi.id=d.order_item_id "
                                        "JOIN `order` o ON o.id=oi.order_id JOIN `user` u ON u.id=o.buyer_id "
                                        "WHERE u.email LIKE %s AND d.claim_id IS NULL AND d.shipped_at <= o.paid_at", (demo,)),
        "shipped_at < delivered_at": ("SELECT COUNT(*) AS c FROM delivery d JOIN order_item oi ON oi.id=d.order_item_id "
                                      "JOIN `order` o ON o.id=oi.order_id JOIN `user` u ON u.id=o.buyer_id "
                                      "WHERE u.email LIKE %s AND d.delivered_at IS NOT NULL AND d.delivered_at <= d.shipped_at", (demo,)),
        "delivered_at < confirmed_at": ("SELECT COUNT(*) AS c FROM order_item oi JOIN delivery d ON d.order_item_id=oi.id AND d.claim_id IS NULL "
                                        "JOIN `order` o ON o.id=oi.order_id JOIN `user` u ON u.id=o.buyer_id "
                                        "WHERE u.email LIKE %s AND oi.confirmed_at IS NOT NULL AND oi.confirmed_at <= d.delivered_at", (demo,)),
        "claim.requested_at ≥ paid_at": ("SELECT COUNT(*) AS c FROM claim c JOIN order_item oi ON oi.id=c.order_item_id "
                                         "JOIN `order` o ON o.id=oi.order_id JOIN `user` u ON u.id=o.buyer_id "
                                         "WHERE u.email LIKE %s AND c.requested_at < o.paid_at", (demo,)),
        "claim.processed_at ≥ requested_at": ("SELECT COUNT(*) AS c FROM claim c JOIN order_item oi ON oi.id=c.order_item_id "
                                              "JOIN `order` o ON o.id=oi.order_id JOIN `user` u ON u.id=o.buyer_id "
                                              "WHERE u.email LIKE %s AND c.processed_at IS NOT NULL AND c.processed_at < c.requested_at", (demo,)),
        "refund.refunded_at ≥ claim.processed_at": ("SELECT COUNT(*) AS c FROM refund r JOIN claim c ON c.id=r.claim_id JOIN order_item oi ON oi.id=c.order_item_id "
                                                    "JOIN `order` o ON o.id=oi.order_id JOIN `user` u ON u.id=o.buyer_id "
                                                    "WHERE u.email LIKE %s AND r.status='COMPLETED' AND r.refunded_at < c.processed_at", (demo,)),
        "미래 시각 없음": ("SELECT COUNT(*) AS c FROM `order` o JOIN `user` u ON u.id=o.buyer_id WHERE u.email LIKE %s AND o.ordered_at > NOW()", (demo,)),
    }
    failures = 0
    for name, (sql, params) in checks.items():
        count = query_one(conn, sql, params)["c"]
        log.info("[검증] %s → 위반 %d건", name, count)
        failures += count
    if failures:
        raise SeedError(f"시각 보정 검증 실패: 위반 합계 {failures}")


# ---------------------------------------------------------------------------
# STEP settlement
# ---------------------------------------------------------------------------
def step_settlement(api: ApiClient, conn, state: dict, admin_token: str, rng: random.Random) -> None:
    if not state.get("time_shifted"):
        raise SeedError("시각 보정(timeshift) 전에는 정산을 생성하지 않습니다(settlement_item.occurred_at 스냅샷)")
    demo_seller_ids = {s["id"] for s in state["sellers"]}
    created_ids: dict[int, list[int]] = {}
    for month in SETTLEMENT_MONTHS:
        body = api.json("POST", "/api/v1/admin/settlements", admin_token, json={"year": SEED_YEAR, "month": month})
        ids = [line["settlementId"] for line in body["settlements"] if line["sellerId"] in demo_seller_ids]
        created_ids[month] = ids
        log.info("정산 생성 %d월: createdCount=%d (데모 셀러 %d)", month, body["createdCount"], len(ids))
    paid_ids = []
    for month, ids in created_ids.items():
        for settlement_id in ids:
            if month in SETTLEMENT_CONFIRMED_MONTHS:
                api.json("POST", f"/api/v1/admin/settlements/{settlement_id}/confirm", admin_token)
            if month in SETTLEMENT_PAID_MONTHS:
                api.json("POST", f"/api/v1/admin/settlements/{settlement_id}/pay", admin_token)
                paid_ids.append(settlement_id)
    # 지급 정산 paid_at = 지급예정일 + 0~3일 10:00 (SQL·데모 셀러 한정). 모든 변수는 %s 바인딩, SQL injection 위험 없음
    for settlement_id in paid_ids:
        delay = rng.randint(0, PAYOUT_DELAY_MAX_DAYS)
        execute(conn, "UPDATE settlement s JOIN seller sl ON sl.id=s.seller_id "
                      "SET s.paid_at = TIMESTAMP(DATE_ADD(s.scheduled_pay_date, INTERVAL %s DAY), '10:00:00'), s.updated_at = s.paid_at "
                      "WHERE s.id=%s AND s.status='PAID' AND sl.company_name LIKE %s", (delay, settlement_id, f"{DEMO_SELLER_PREFIX}%"))
    state["settlements"] = {str(m): ids for m, ids in created_ids.items()}
    state.setdefault("counts", {})["settlement"] = sum(len(v) for v in created_ids.values())
    save_state(state)
    log.info("정산 완료: 생성 %d · 확정 %d · 지급 %d",
             state["counts"]["settlement"], sum(len(created_ids[m]) for m in SETTLEMENT_CONFIRMED_MONTHS), len(paid_ids))


# ---------------------------------------------------------------------------
# STEP verify
# ---------------------------------------------------------------------------
def step_verify(conn, state: dict) -> None:
    demo_seller = f"{DEMO_SELLER_PREFIX}%"
    rows = query_all(conn, """
        SELECT s.id, sl.company_name, DATE_FORMAT(s.period_start, '%%Y-%%m') AS ym, s.status, s.gross_amount, s.fee_amount,
               s.refund_amount, s.net_amount, s.paid_at, s.scheduled_pay_date,
               (SELECT COALESCE(SUM(si.amount),0) FROM settlement_item si WHERE si.settlement_id=s.id AND si.item_type='SALE') AS sale_sum,
               (SELECT COALESCE(SUM(si.fee_amount),0) FROM settlement_item si WHERE si.settlement_id=s.id AND si.item_type='SALE') AS fee_sum,
               (SELECT COALESCE(SUM(si.amount),0) FROM settlement_item si WHERE si.settlement_id=s.id AND si.item_type='REFUND') AS refund_sum,
               (SELECT COUNT(*) FROM settlement_item si JOIN order_item oi ON oi.id=si.order_item_id
                 WHERE si.settlement_id=s.id AND si.item_type='SALE' AND si.occurred_at <> oi.confirmed_at) AS occurred_mismatch
        FROM settlement s JOIN seller sl ON sl.id=s.seller_id WHERE sl.company_name LIKE %s ORDER BY s.period_start, sl.id""", (demo_seller,))
    mismatch = 0
    for row in rows:
        ok = row["sale_sum"] == row["gross_amount"] and row["fee_sum"] == row["fee_amount"] and row["refund_sum"] == row["refund_amount"] \
            and row["occurred_mismatch"] == 0
        mismatch += 0 if ok else 1
        log.info("[정산] %s %s %-9s gross=%d(items %d) fee=%d(items %d) refund=%d net=%d paid_at=%s sched=%s occurred_mismatch=%d %s",
                 row["ym"], row["company_name"], row["status"], row["gross_amount"], row["sale_sum"], row["fee_amount"], row["fee_sum"],
                 row["refund_amount"], row["net_amount"], row["paid_at"], row["scheduled_pay_date"], row["occurred_mismatch"], "OK" if ok else "MISMATCH")
    summary = query_all(conn, """
        SELECT DATE_FORMAT(o.ordered_at, '%%Y-%%m') AS ym, COUNT(*) AS orders,
               SUM(CASE WHEN o.status='CONFIRMED' THEN 1 ELSE 0 END) AS confirmed
        FROM `order` o JOIN `user` u ON u.id=o.buyer_id WHERE u.email LIKE %s GROUP BY ym ORDER BY ym""", (f"%@{DEMO_EMAIL_DOMAIN}",))
    for row in summary:
        log.info("[주문] %s 주문 %d · 확정 %d", row["ym"], row["orders"], row["confirmed"])
    claims = query_all(conn, """
        SELECT c.type, c.status, COUNT(*) AS cnt FROM claim c JOIN order_item oi ON oi.id=c.order_item_id JOIN `order` o ON o.id=oi.order_id
        JOIN `user` u ON u.id=o.buyer_id WHERE u.email LIKE %s GROUP BY c.type, c.status ORDER BY c.type, c.status""", (f"%@{DEMO_EMAIL_DOMAIN}",))
    for row in claims:
        log.info("[클레임] %s %s %d", row["type"], row["status"], row["cnt"])
    verify_timeshift(conn)
    if mismatch:
        raise SeedError(f"정산 금액 대조 불일치 {mismatch}건")
    log.info("verify 완료: 정산 %d건 금액 일치", len(rows))


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
def print_dry_run(state: dict) -> None:
    rng = random.Random(42)
    option_indexes = [i for i, p in enumerate(PRODUCTS) if p[4] is not None]
    plans = build_order_plans(rng, len(PRODUCTS), len(BUYER_NAMES), option_indexes)
    by_month: dict[int, int] = {}
    for plan in plans:
        by_month[plan.month] = by_month.get(plan.month, 0) + 1
    log.info("[dry-run] 카테고리 신규 %d(+기존 데모) · 셀러 %d · 구매자 %d · 상품 %d(옵션 %d) · 이미지 %d",
             len(CATEGORIES), len(SELLERS), len(BUYER_NAMES), len(PRODUCTS), len(option_indexes),
             sum(2 if i % 3 == 0 else 1 for i in range(1, len(PRODUCTS) + 1)))
    log.info("[dry-run] 주문 월별 %s (합 %d) · 완결 클레임 %d · 진행 클레임 %d", by_month, len(plans), len(COMPLETED_CLAIMS), len(IN_PROGRESS_CLAIMS))
    log.info("[dry-run] 정산 월 %s · 확정 %s · 지급 %s", SETTLEMENT_MONTHS, SETTLEMENT_CONFIRMED_MONTHS, SETTLEMENT_PAID_MONTHS)
    log.info("[dry-run] 호출 엔드포인트: POST /users, /admin/sellers, /admin/categories, /admin/files/images, /admin/products(+images/approve), "
             "/orders, /api/webhooks/payments, /admin/orders/items/{oit}/prepare-shipment, /admin/deliveries/{dlv}/mark-delivered, "
             "/orders/{ord}/items/{oit}/confirm, /claims(+approve/return-shipment/confirm-pickup/inspect/register-exchange-shipment), "
             "/admin/settlements(+confirm/pay) · /admin/sellers/{slr}/bank-accounts(Track 89-F) · SQL: 시각 UPDATE · settlement.paid_at UPDATE")
    log.info("[dry-run] state 파일: %s (존재: %s)", STATE_PATH, STATE_PATH.exists())


def main() -> int:
    parser = argparse.ArgumentParser(description="zslab-mall 데모 시드")
    parser.add_argument("--step", choices=["master", "orders", "timeshift", "settlement", "verify", "all"], default="all")
    parser.add_argument("--dry-run", action="store_true", help="실행 없이 계획만 출력")
    parser.add_argument("--force", action="store_true", help="재실행 가드 무시")
    parser.add_argument("--seed", type=int, default=20260918, help="난수 시드(재현용)")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949) 한글 로그 깨짐 방지
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)

    state = load_state()
    if args.dry_run:
        print_dry_run(state)
        return 0
    missing = [name for name in REQUIRED_ENV if not os.environ.get(name)]
    if missing:
        log.error("환경변수 미설정: %s", ", ".join(missing))
        return 2

    steps = ["master", "orders", "timeshift", "settlement", "verify"] if args.step == "all" else [args.step]
    api = ApiClient(env("API_BASE_URL"))
    conn = connect_db()
    rng = random.Random(args.seed)
    total_start = time.monotonic()
    try:
        admin_token = None
        for step in steps:
            if step != "verify":
                guard(step, conn, state, args.force)
            if step in ("master", "orders", "settlement"):
                admin_token = api.login(env("ADMIN_EMAIL"), env("ADMIN_PASSWORD"), "ADMIN")  # JWT 1시간 → 단계마다 재발급
            with StepTimer(step):
                if step == "master":
                    step_master(api, conn, state, admin_token)
                elif step == "orders":
                    step_orders(api, conn, state, admin_token, rng)
                elif step == "timeshift":
                    step_timeshift(conn, state, rng)
                elif step == "settlement":
                    step_settlement(api, conn, state, admin_token, rng)
                elif step == "verify":
                    step_verify(conn, state)
    except SeedError as error:
        log.error("중단: %s", error)
        return 1
    finally:
        conn.close()
        log.info("총 소요 %.1fs · API 호출 %d · 생성 건수 %s", time.monotonic() - total_start, api.call_count, state.get("counts"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
