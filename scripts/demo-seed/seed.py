"""
데모 시드 스크립트 (docs/infra/demo-seed/plan.md 기반).

단계: master(카테고리·셀러·계좌·구매자·상품·이미지) → orders(3~8월 주문·클레임·9월 진행분)
     → timeshift(시각 보정 SQL·order_no) → settlement(정산 생성·확정·지급·paid_at 보정)
     → reviews(카테고리 키워드·리뷰·사진·도움됐어요·작성 시각 보정)
     → delivered(배송완료 대기 품목) → inquiries(1:1 문의) → qna(상품 Q&A) → verify(검증)
     106 데이터 단계(delivered·inquiries·qna · D-243)는 조건 충족형이라 반복 실행하면 부족한 조건만 만든다.

접속 정보는 환경변수로만 받는다(README 참조). 재실행 가드는 데모 마커(이메일 도메인·variantCode prefix)로 판정하며
--force 없이는 기존 데모 데이터 위에 진행하지 않는다.
대상 가드(D-242): --target local|prod 필수 · 호스트 DNS 판별 · prod 도메인 타이핑 확인 · API·DB 교차 검증 · state 대상별 분리.
"""

import argparse
import http.cookiejar
import io
import ipaddress
import json
import logging
import os
import random
import secrets
import socket
import string
import sys
import time
import re
import urllib.parse
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
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
STATE_DIR = Path(__file__).resolve().parent / "state"
LEGACY_STATE_FILE_NAME = "seed-state.json"  # D-242 이전 단일 state(대상 미기록) — 자동 이전하지 않는다
IMAGE_DIR = Path(__file__).resolve().parent / "state" / "images"
TARGETS = ["local", "prod"]
# local 판정 대역(scripts/walkthrough/common.py LOCAL_NETWORKS와 같은 규칙). 이 PC는 hosts로 운영 도메인을 127.0.0.1에 두므로
# 도메인 이름으로는 대상을 가릴 수 없어 실행 시점 DNS 해석 결과로 판정한다.
LOCAL_NETWORKS = (
    ipaddress.ip_network("127.0.0.0/8"),
    ipaddress.ip_network("::1/128"),
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
    ipaddress.ip_network("fc00::/7"),  # IPv6 ULA(사설 대역 대응)
)
ALL_STEPS = ["master", "orders", "timeshift", "settlement", "reviews", "delivered", "inquiries", "qna", "verify"]
# prod 허용 단계 목록(D-242): 운영은 추가분만 쓴다 — 기존 쓰기 단계는 전부 거부하고 새 단계는 목록에 넣을 때만 열린다.
# settlement는 정산 생성 API가 전 셀러 대상이라 운영에서 비데모 셀러 정산까지 PENDING으로 만든다.
# 106 데이터 단계(D-243)는 부족한 조건만 API로 추가하므로 허용한다.
PROD_ALLOWED_STEPS = ["verify", "delivered", "inquiries", "qna"]
ADMIN_ME_PATH = "/api/v1/admin/me"

REQUIRED_ENV = ["API_BASE_URL", "ADMIN_EMAIL", "ADMIN_PASSWORD",
                "DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD"]
# 106 데이터 단계 계정은 state가 아니라 env에서 받는다(운영에는 state가 없다 · D-243).
# 구매자 = 공개 데모 구매자(프론트 데모 버튼 · .env NUXT_BUYER_DEMO_EMAIL) — 운영은 buyer01이 아니다(D-243 § 정정).
STEP_REQUIRED_ENV = {"delivered": ["DEMO_BUYER_EMAIL", "DEMO_BUYER_PASSWORD"], "inquiries": ["DEMO_BUYER_EMAIL", "DEMO_BUYER_PASSWORD"],
                     "qna": ["DEMO_BUYER_EMAIL", "DEMO_BUYER_PASSWORD", "DEMO_SELLER_EMAIL", "DEMO_SELLER_PASSWORD"]}
RETRY_MAX = 3
RETRY_BASE_SECONDS = 1.0
RETRY_STATUSES = {429, 500, 502, 503, 504}
MULTIPART_MAX_FILES = 20  # AdminFileController 요청당 20장
ORDER_NO_DATE_LENGTH = 8  # order_no 'yyyyMMdd-XXXXXX'(OrderService QB-9)의 날짜부 길이
# 쿠키 인증(D-235 PR3 K9): 역할 로그인 경로·역할 쿠키 이름·CSRF
CSRF_TOKEN_PATH = "/api/v1/auth/csrf"
LOGIN_PATHS = {"ADMIN": "/api/v1/admin/auth/login", "BUYER": "/api/v1/auth/buyer/login", "SELLER": "/api/v1/seller/auth/login"}
ROLE_COOKIES = {"ADMIN": "__Secure-admin_at", "BUYER": "__Secure-buyer_at", "SELLER": "__Secure-seller_at"}
XSRF_COOKIE = "XSRF-TOKEN"
XSRF_HEADER = "X-XSRF-TOKEN"
SAFE_METHODS = {"GET", "HEAD", "OPTIONS", "TRACE"}

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
# 리뷰(Track 106-1 PR2): 클레임 없는 구매확정 품목 중 이 비율만 작성 · 별점 가중치(한쪽으로 쏠리지 않게) · 사진 리뷰 비율 · 사진 장수
REVIEW_RATIO = 0.7
REVIEW_TARGET_SEED = 1061  # 리뷰 대상 품목 선택 전용 난수 시드(재개해도 같은 대상)
REVIEW_RATING_WEIGHTS = {5: 30, 4: 30, 3: 20, 2: 12, 1: 8}
PHOTO_REVIEW_RATIO = 0.35
PHOTO_COUNT_RANGE = (1, 3)
REVIEW_PHOTO_SIZE = 480
HELPFUL_VOTERS_MAX = 3
REVIEW_DELAY_MAX_DAYS = 5  # 리뷰 작성 시각 = 구매확정 + 0~N일(실행 시각 이전)
CATEGORY_KEYWORD_ORDER_BASE = 100  # 기본 세트(1~6) 뒤에 보이도록
# 106 데이터 단계(D-243)
DELIVERED_SELLER_KEY = "fashion"  # delivered 주문 상품의 데모 셀러(seller02 · 데모 패션랩)
RETURN_WINDOW_DAYS = 7  # 백엔드 ReturnWindowPolicy.WINDOW_DAYS와 같은 값(배송완료 후 자동 구매확정까지)
# 백엔드는 KST로 시각을 저장한다(application.yml hibernate.jdbc.time_zone=Asia/Seoul). DB 컨테이너 NOW()는 UTC일 수 있어 쓰지 않는다.
SERVER_ZONE = timezone(timedelta(hours=9))
TRACKING_SEQUENCE_MODULUS = 100_000_000  # 송장 DEMO + 8자리(D-227 형식) — 기존 시드 송장(DEMO00001001~)과 겹치지 않게 시각 기반
CATALOG_PAGE_SIZE = 50
LIST_PAGE_SIZE = 50  # 문의·Q&A 목록 서버 최대 페이지 크기
INQUIRY_CONDITIONS = ["answered_checked", "answered_unread", "unanswered", "order_attached"]
INQUIRY_TEMPLATES = {
    "answered_checked": ("DELIVERY", "주문한 상품은 보통 며칠 안에 받아볼 수 있나요? 평균 배송 기간이 궁금합니다.",
                         "안녕하세요. 결제 후 영업일 기준 1~2일 안에 출고되고, 출고 후 보통 1~3일 안에 받아보실 수 있습니다."),
    "answered_unread": ("ORDER_PAYMENT", "결제를 마친 주문의 결제 수단을 카드에서 계좌이체로 바꿀 수 있을까요?",
                        "결제가 끝난 주문은 결제 수단을 바꿀 수 없습니다. 주문을 취소하신 뒤 원하시는 수단으로 다시 주문해 주세요."),
    "unanswered": ("CLAIM", "받은 상품이 생각보다 작아서 교환하고 싶어요. 교환 신청은 어디에서 하면 되나요?", None),
}
QNA_CONDITIONS = ["multi_sentence_description", "answered", "unanswered", "hidden"]
QNA_PRODUCT_NAME = "코튼 베이직 티셔츠"  # 데모 셀러(seller02) 상품 — 설명 갱신·질문 대상
QNA_PRODUCT_DESCRIPTION = ("면 100% 원단으로 만든 기본 반팔 티셔츠입니다.\n"
                           "두께는 중간 정도라 한 장으로 입거나 셔츠 안에 받쳐 입기 좋습니다.\n"
                           "정사이즈로 나왔으며 여유 있게 입으시려면 한 치수 크게 고르시길 권합니다.\n"
                           "찬물 단독 세탁을 권장하며 건조기를 쓰면 조금 줄어들 수 있습니다.")
QNA_TEMPLATES = {
    "answered": ("세탁기에 돌려도 줄어들지 않나요? 건조기도 써도 되는지 궁금합니다.",
                 "찬물 단독 세탁을 권장드려요. 건조기를 쓰시면 조금 줄어들 수 있어 자연 건조를 추천드립니다."),
    "unanswered": ("평소 95 사이즈를 입는데 어떤 사이즈를 고르면 좋을까요?", None),
    # 숨김이 실패해 공개로 남아도 해가 없도록 상품과 무관한 잡담성 문구(광고·연락처·링크 없음)
    "hidden": ("오늘 날씨가 정말 좋네요. 다들 좋은 하루 보내세요!", None),
}
QNA_HIDDEN_REASON = "상품과 무관한 내용"
# 상품 설명 조각 분리 = 백엔드 KeywordMatcher(\R+|(?<=[.!?。])\s+)와 같은 규칙 — 조각 2개 이상이면 여러 문장 설명
DESCRIPTION_SPLIT = re.compile(r"(?:\r\n|[\n\x0b\x0c\r\x85  ])+|(?<=[.!?。])\s+")

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

# 카테고리별 리뷰 키워드 세트(Track 106-1 · V40 주석: 기본 세트는 Flyway, 카테고리 세트는 데모 시드). (code, label, group_code)
# code는 전역 유일(uk) · group_code는 V40 CHECK 5종 안에서만.
CATEGORY_KEYWORDS = {
    "의류": [("APPAREL_SIZE_SMALL", "사이즈가 작아요", "PRODUCT"), ("APPAREL_SIZE_FIT", "사이즈가 딱 맞아요", "PRODUCT"),
           ("APPAREL_SIZE_LARGE", "사이즈가 커요", "PRODUCT"), ("APPAREL_FABRIC_SOFT", "소재가 부드러워요", "QUALITY"),
           ("APPAREL_COLOR_SAME", "색감이 화면과 같아요", "PRODUCT")],
    "리빙·주방": [("LIVING_EASY_CLEAN", "세척이 쉬워요", "PRODUCT"), ("LIVING_STURDY", "튼튼해요", "QUALITY"),
              ("LIVING_SIZE_GOOD", "크기가 적당해요", "PRODUCT"), ("LIVING_DESIGN_PRETTY", "디자인이 예뻐요", "PRODUCT")],
    "잡화": [("GOODS_FINISH_NEAT", "마감이 깔끔해요", "QUALITY"), ("GOODS_PRACTICAL", "실용적이에요", "PRODUCT"),
           ("GOODS_GIFT_GOOD", "선물용으로 좋아요", "VALUE"), ("GOODS_COLOR_SAME", "색이 사진과 같아요", "PRODUCT")],
    "디지털": [("DIGITAL_EASY_SETUP", "설정이 쉬워요", "PRODUCT"), ("DIGITAL_BATTERY_LONG", "배터리가 오래가요", "PRODUCT"),
            ("DIGITAL_QUIET", "소음이 적어요", "PRODUCT"), ("DIGITAL_SPEC_SAME", "성능이 설명대로예요", "QUALITY"),
            ("DIGITAL_BUILD_SOLID", "만듦새가 좋아요", "QUALITY")],
    "문구": [("STATIONERY_WRITES_WELL", "필기감이 좋아요", "PRODUCT"), ("STATIONERY_PAPER_THICK", "종이가 두꺼워요", "QUALITY"),
           ("STATIONERY_CUTE", "귀여워요", "PRODUCT"), ("STATIONERY_MANY_COLORS", "구성이 알차요", "VALUE")],
    "데모": [("DEMO_MOOD_GOOD", "분위기가 좋아요", "PRODUCT"), ("DEMO_AS_EXPECTED", "기대한 그대로예요", "QUALITY"),
           ("DEMO_GIFT_GOOD", "선물하기 좋아요", "VALUE"), ("DEMO_WORTH_IT", "값어치를 해요", "VALUE")],
}
POSITIVE_BASE_KEYWORDS = ["DELIVERY_FAST", "PACKAGING_NEAT", "QUALITY_GOOD", "SAME_AS_DESCRIPTION", "VALUE_FOR_MONEY", "WILL_REPURCHASE"]
REVIEW_TEMPLATES = {
    5: ["{name} 정말 만족해요. 기대 이상이었어요.", "재구매 의사 있습니다. {name} 강력 추천해요!", "{name} 받자마자 마음에 들었어요.\n가족들도 좋아하네요."],
    4: ["{name} 전체적으로 만족합니다. 조금만 더 저렴하면 좋겠어요.", "품질 괜찮아요. {name} 잘 쓰고 있습니다.", "생각했던 것과 비슷해요. 무난하게 추천합니다."],
    3: ["{name} 가격 생각하면 무난해요.", "나쁘지 않은데 특별히 좋지도 않아요.", "{name} 쓸 만은 한데 기대보다는 평범했어요."],
    2: ["{name} 사진과 조금 달라서 아쉬워요.", "마감이 기대보다 아쉬웠어요. 그래도 쓸 수는 있어요.", "배송은 빨랐지만 {name} 품질은 아쉽네요."],
    1: ["{name} 제 기대와는 많이 달랐어요.", "사용해 보니 불편한 점이 많았어요. 추천하기 어렵습니다."],
}

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


class LoginRejected(SeedError):
    """역할 로그인 자격 증명 거부(401). reviews 단계는 그 구매자를 건너뛰고, 그 밖 단계는 SeedError처럼 중단한다."""


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


active_state_path: Path | None = None  # main이 --target으로 정한다(seed-state.{target}.json)


def state_path(target: str) -> Path:
    return STATE_DIR / f"seed-state.{target}.json"


def load_state(path: Path) -> dict:
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return {}


def save_state(state: dict) -> None:
    active_state_path.parent.mkdir(parents=True, exist_ok=True)
    active_state_path.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


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
@dataclass(frozen=True)
class Credential:
    """역할 로그인 결과(D-235 PR3 K9). 로그인 응답 Set-Cookie에서 읽은 역할 쿠키·XSRF 값을 이후 요청에 Cookie 헤더로 직접 싣는다
    (세션 쿠키 저장소는 쓰지 않는다). 값은 repr·로그에 나오지 않는다."""

    cookie_name: str
    cookie_value: str = field(repr=False)
    xsrf: str = field(repr=False)

    def headers(self, method: str) -> dict:
        headers = {"Cookie": f"{self.cookie_name}={self.cookie_value}; {XSRF_COOKIE}={self.xsrf}"}
        if method.upper() not in SAFE_METHODS:
            headers[XSRF_HEADER] = self.xsrf
        return headers


def set_cookie_value(response: requests.Response, name: str) -> str:
    """응답 Set-Cookie 원문에서 이름이 name인 쿠키 값을 찾는다(없으면 실패)."""
    for header in response.raw.headers.getlist("Set-Cookie"):
        key, _, value = header.split(";", 1)[0].partition("=")
        if key.strip() == name:
            return value.strip()
    raise SeedError(f"{name} Set-Cookie 없음")


class ApiClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        # 인증은 Credential이 Cookie 헤더로 직접 싣는다 — 세션 저장소가 쿠키를 모아 자동 전송하지 않도록 모든 쿠키를 거부한다(D-235 PR3 K9)
        self.session.cookies.set_policy(http.cookiejar.DefaultCookiePolicy(allowed_domains=[]))
        # 환경·시스템 프록시를 쓰지 않는다: 프록시가 도메인을 따로 해석하면 대상 가드의 DNS 판정(hosts 기준)과 실제 접속 대상이 어긋난다(D-242)
        self.session.trust_env = False
        # 로컬 게이트웨이는 자체 서명 인증서라 API_TLS_VERIFY=false 로만 검증을 끈다(기본 검증 on)
        self.session.verify = os.environ.get("API_TLS_VERIFY", "true").lower() != "false"
        if not self.session.verify:
            requests.packages.urllib3.disable_warnings()
        self.call_count = 0

    def request(self, method: str, path: str, credential: Credential | None = None, expect=(200, 201), **kwargs):
        headers = kwargs.pop("headers", {})
        if credential:
            headers.update(credential.headers(method))
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

    def json(self, method: str, path: str, credential: Credential | None = None, expect=(200, 201), **kwargs):
        response = self.request(method, path, credential, expect, **kwargs)
        return response.json() if response.content else None

    def login(self, email: str, password: str, role: str) -> Credential:
        """인증 전 CSRF 토큰(GET /api/v1/auth/csrf) → 역할 로그인(X-XSRF-TOKEN) 순서(D-235 PR3 K7·K9).
        자격 증명 거부(401)는 LoginRejected로 구분한다(SeedError 하위라 잡지 않는 단계는 기존처럼 중단)."""
        xsrf = set_cookie_value(self.request("GET", CSRF_TOKEN_PATH, expect=(204,)), XSRF_COOKIE)
        response = self.request("POST", LOGIN_PATHS[role], expect=(200, 401), json={"email": email, "password": password},
                                headers={"Cookie": f"{XSRF_COOKIE}={xsrf}", XSRF_HEADER: xsrf})
        if response.status_code == 401:
            raise LoginRejected(f"{role} 로그인 거부(401): {email}")
        return Credential(ROLE_COOKIES[role], set_cookie_value(response, ROLE_COOKIES[role]), xsrf)


# ---------------------------------------------------------------------------
# DB
# ---------------------------------------------------------------------------
def connect_db():
    return pymysql.connect(
        host=env("DB_HOST"), port=int(env("DB_PORT")), user=env("DB_USER"), password=env("DB_PASSWORD"),
        # autocommit: API가 커밋한 행을 REPEATABLE READ 스냅샷 없이 바로 읽기 위함. timeshift만 명시 트랜잭션(begin/commit)
        database=env("DB_NAME"), charset="utf8mb4", autocommit=True, cursorclass=pymysql.cursors.DictCursor,
        # D-264: 앱 커넥션과 같이 세션을 KST로 고정(DB 서버 기본 SYSTEM=UTC) — SQL NOW()가 앱 저장값(KST 벽시계)과 맞게
        init_command="SET time_zone = '+09:00'")


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


def demo_review_count(conn) -> int:
    return query_one(conn, "SELECT COUNT(*) AS c FROM review r JOIN `user` u ON u.id = r.buyer_id WHERE u.email LIKE %s",
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
    elif step == "reviews" and demo_review_count(conn) > 0:
        reason = "데모 구매자의 리뷰가 이미 존재"
    if reason is None:
        return
    if force:
        log.warning("[가드] %s — --force로 계속 진행", reason)
        return
    raise SeedError(f"[가드] {reason}. 계속하려면 --force 를 지정하세요.")


# ---------------------------------------------------------------------------
# 대상 가드(D-242) — 모든 쓰기보다 먼저 실행한다
# ---------------------------------------------------------------------------
def api_host_of(base_url: str) -> str:
    host = urllib.parse.urlsplit(base_url).hostname
    if not host:
        raise SeedError("[대상] API_BASE_URL에서 호스트를 읽을 수 없습니다")
    return host


def resolve_addresses(host: str) -> set[str]:
    """해석된 IP는 운영 주소일 수 있어 오류 문구에 넣지 않는다(walkthrough 관례)."""
    try:
        resolved = {info[4][0] for info in socket.getaddrinfo(host, None)}
    except socket.gaierror:
        raise SeedError(f"[대상] {host}를 해석할 수 없습니다") from None
    if not resolved:
        raise SeedError(f"[대상] {host}의 해석 결과가 없습니다")
    return resolved


def is_local_address(address: str) -> bool:
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        # IP 형식이 아닌 값은 로컬로 인정하지 않는다
        return False
    # IPv4-mapped(::ffff:127.0.0.1)는 IPv4로 풀어 판정 · 미지정(0.0.0.0)·링크 로컬도 이 PC 쪽 주소라 로컬로 본다
    if ip.version == 6 and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    return ip.is_unspecified or ip.is_link_local or any(ip in network for network in LOCAL_NETWORKS)


def check_target(target: str, api_scheme: str, api_host: str, db_host: str, steps: list[str], force: bool) -> None:
    """대상과 실제 접속 호스트가 맞는지 본다. 우회 옵션은 두지 않는다.
    prod DB_HOST는 SSH 터널(127.0.0.1)이 정상 경로라 호스트로 판정하지 않고 API·DB 교차 검증에 맡긴다."""
    if target == "local":
        if not all(is_local_address(address) for address in resolve_addresses(api_host)):
            raise SeedError(f"[대상] local인데 API 호스트 {api_host}가 loopback·사설 대역 밖 주소로 해석됩니다")
        if not all(is_local_address(address) for address in resolve_addresses(db_host)):
            raise SeedError(f"[대상] local인데 DB_HOST {db_host}가 loopback·사설 대역 밖 주소로 해석됩니다")
        return
    # 호스트 판별을 인자 검사보다 먼저: 대상이 어긋난 상태가 가장 근본적인 오류라 그 사유를 먼저 알린다(local 분기와 같은 순서).
    # 하나라도 로컬 주소면 거부: hosts가 운영 도메인을 이 PC로 돌려 두면 운영 대상인 척 로컬에 쓰게 된다
    if any(is_local_address(address) for address in resolve_addresses(api_host)):
        raise SeedError(f"[대상] prod인데 API 호스트 {api_host}가 loopback·사설 대역으로 해석됩니다(hosts 확인)")
    # 평문 http는 인증서 검증 자체가 없어 아래 TLS 방어층이 무의미해진다
    if api_scheme != "https":
        raise SeedError("[대상] prod API_BASE_URL은 https여야 합니다")
    # 인증서 검증은 운영 자격 증명 전송의 마지막 방어층(hosts가 운영 도메인을 다른 곳으로 돌려도 인증서가 맞지 않아 끊긴다)
    if os.environ.get("API_TLS_VERIFY", "true").lower() == "false":
        raise SeedError("[대상] prod에서는 API_TLS_VERIFY=false를 쓸 수 없습니다(로컬 자체 서명 인증서 전용)")
    if force:
        raise SeedError("[대상] prod에서는 --force를 쓸 수 없습니다(데모 데이터 존재 가드 무시 금지)")
    denied = [step for step in steps if step not in PROD_ALLOWED_STEPS]
    if denied:
        raise SeedError(f"[대상] prod에서 실행할 수 없는 단계입니다: {', '.join(denied)} — prod 허용 단계: {', '.join(PROD_ALLOWED_STEPS)}"
                        "(--step all은 전 단계를 포함)")


def confirm_prod_domain(api_host: str) -> None:
    """운영 실행 직전 도메인을 직접 타이핑해 확인한다 — 운영 env가 남은 셸에서 바로 실행되는 것을 막는다. 생략 옵션은 두지 않는다."""
    # 파이프(echo 도메인 | seed.py)로 흘려 넣으면 사람 확인이 사라지므로 터미널 입력만 받는다
    if not sys.stdin.isatty():
        raise SeedError("[대상] prod 도메인 확인은 터미널에서 직접 입력해야 합니다(파이프·리다이렉트 입력 거부)")
    try:
        typed = input(f"운영 대상입니다. 실행하려면 API 호스트 도메인을 그대로 입력하세요 ({api_host}): ")
    except EOFError:
        typed = ""
    if typed.strip() != api_host:
        raise SeedError("[대상] 입력한 도메인이 API 호스트와 다릅니다 — 실행하지 않습니다")


def cross_check_api_db(api: ApiClient, conn, admin_token: Credential) -> None:
    """API와 DB가 같은 환경인지 본다. API가 알려준 관리자 본인 행이 지정 DB에 없으면 다른 환경으로 보고 중단한다."""
    me = api.json("GET", ADMIN_ME_PATH, admin_token)
    # 모든 변수는 %s 바인딩 사용, SQL injection 위험 없음
    row = query_one(conn, "SELECT COUNT(*) AS c FROM `user` WHERE public_id = %s AND email = %s", (me["userPublicId"], me["email"]))
    if row["c"] != 1:
        raise SeedError("[교차 검증] API의 관리자 본인이 지정한 DB에 없습니다 — API_BASE_URL과 DB_*가 다른 환경입니다")
    log.info("[교차 검증] API 관리자 %s = DB 행 일치", me["userPublicId"])


def bind_state_target(state: dict, identity: dict) -> None:
    """state를 실행 대상에 묶는다. 기록된 대상과 다르면 거부한다.
    대상 기록이 없는 기존 state는 local일 때만 현재 대상으로 받아들인다(local은 호스트 판별·교차 검증으로 로컬임이 보장됨)."""
    recorded = state.get("target")
    if recorded is None:
        if state and identity["name"] != "local":
            raise SeedError(f"[state] {active_state_path}에 대상 기록이 없습니다 — prod에서는 쓰지 않습니다")
        state["target"] = identity
        return
    if recorded != identity:
        raise SeedError(f"[state] {active_state_path}의 기록 대상 {recorded}와 현재 실행 대상 {identity}가 다릅니다")


def reject_legacy_state() -> None:
    legacy = STATE_DIR / LEGACY_STATE_FILE_NAME
    if legacy.exists():
        raise SeedError(f"[state] 대상 기록이 없는 {legacy}가 있습니다. 자동 이전하지 않습니다 — "
                        f"local 실행분이면 seed-state.local.json으로 이름을 바꾸세요")


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


def upload_images(api: ApiClient, admin_token: Credential, files: list[tuple[str, bytes]]) -> list[str]:
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


def step_master(api: ApiClient, conn, state: dict, admin_token: Credential) -> None:
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
        signup = api.json("POST", "/api/v1/users", json={"email": email, "name": seller["ceoName"], "phone": f"010-3000-{index:04d}", "password": password})
        # Track 89-D: 입점 요청 키는 ownerUserPublicId(usr_·SellerProvisioningRequest) — 가입 응답 userPublicId를 그대로 쓴다(DB 조회 불요)
        body = api.json("POST", "/api/v1/admin/sellers", admin_token, json={
            "companyName": seller["companyName"], "businessNo": seller["businessNo"], "ceoName": seller["ceoName"],
            "contactEmail": seller["contactEmail"], "contactPhone": seller["contactPhone"], "status": "ACTIVE",
            "ownerUserPublicId": signup["userPublicId"]})
        seller_public_id = body["sellerPublicId"]
        seller_id = query_one(conn, "SELECT id FROM seller WHERE public_id = %s", (seller_public_id,))["id"]
        # 구성원 검증(Track 90-A-2b): 요청 키가 API와 어긋나면 Jackson이 미지 필드를 무시해 409 없이 구성원 0 셀러가 조용히 생성된다 → 여기서 즉시 중단.
        member_count = query_one(conn, "SELECT COUNT(*) AS c FROM seller_user WHERE seller_id = %s", (seller_id,))["c"]
        if member_count == 0:
            raise SeedError(f"셀러 {seller['companyName']}({seller_public_id}) 구성원 0명 — 입점 요청 owner 키가 API에서 무시됐을 가능성(ownerUserPublicId 확인)")
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
    def __init__(self, api: ApiClient, conn, state: dict, admin_token: Credential, rng: random.Random):
        self.api = api
        self.conn = conn
        self.state = state
        self.admin_token = admin_token
        self.rng = rng
        self.buyer_tokens: dict[int, Credential] = {}
        self.tracking_seq = 1000

    def buyer_token(self, buyer_index: int) -> Credential:
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
        # 결제 승인 = 구매자 본인 mock 콜백 SUCCESS(Track 93 D-198·Track 96-1 A7). /api/webhooks/**는 운영 gateway가 외부 차단(LT-29)이라
        # 쓰지 않는다. paid_at은 서버 시각(now)으로 기록되며 목표 결제시각은 timeshift 단계의 SQL UPDATE(shift_order)가 덮어쓴다.
        self.api.json("POST", "/api/v1/payments/mock-callback", token, json={"attemptKey": attempt_key, "callbackType": "SUCCESS"})
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

    def _confirm(self, token: Credential, record: dict) -> None:
        for item in record["items"]:
            self.api.json("POST", f"/api/v1/orders/{record['orderId']}/items/{item['orderItemId']}/confirm", token)
        record["confirmed"] = True

    def _cancel(self, token: Credential, record: dict) -> None:
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

    def _return_or_exchange(self, token: Credential, record: dict, plan: OrderPlan) -> None:
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


def step_orders(api: ApiClient, conn, state: dict, admin_token: Credential, rng: random.Random) -> None:
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
        "미래 시각 없음": ("SELECT COUNT(*) AS c FROM `order` o JOIN `user` u ON u.id=o.buyer_id WHERE u.email LIKE %s AND o.ordered_at > %s", (demo, server_now())),
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
def step_settlement(api: ApiClient, conn, state: dict, admin_token: Credential, rng: random.Random) -> None:
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
# STEP reviews (Track 106-1 PR2)
# ---------------------------------------------------------------------------
def insert_category_keywords(conn, state: dict) -> int:
    """카테고리별 키워드 세트를 넣는다(키워드 쓰기 API 없음 → SQL). code가 이미 있으면 건너뛰어 재실행해도 중복이 없다."""
    inserted = 0
    for category_name, keywords in CATEGORY_KEYWORDS.items():
        category_id = state["categories"].get(category_name)
        if category_id is None:
            raise SeedError(f"state에 카테고리가 없습니다: {category_name}")
        for order, (code, label, group_code) in enumerate(keywords, start=1):
            # 모든 변수는 %s 바인딩 사용, SQL injection 위험 없음. 존재 확인 후 INSERT(INSERT IGNORE는 다른 오류까지 삼키므로 쓰지 않는다)
            inserted += execute(conn,
                                "INSERT INTO review_keyword (code, label, group_code, top_category_id, display_order, created_at, updated_at) "
                                "SELECT %s, %s, %s, %s, %s, NOW(6), NOW(6) FROM DUAL "
                                "WHERE NOT EXISTS (SELECT 1 FROM review_keyword WHERE code = %s)",
                                (code, label, group_code, category_id, CATEGORY_KEYWORD_ORDER_BASE + order, code))
    return inserted


def make_review_image(product_name: str, photo_index: int, rng: random.Random) -> bytes:
    """구매자 사진 느낌의 샘플(PIL 생성 · 파스텔 배경 + 원 + 문구). 리뷰 경로는 서버가 재인코딩·메타데이터 제거한다."""
    palette = [(227, 218, 245), (217, 224, 247), (245, 221, 232), (216, 238, 232), (244, 237, 211)]
    background = palette[(sum(map(ord, product_name)) + photo_index) % len(palette)]
    image = Image.new("RGB", (REVIEW_PHOTO_SIZE, REVIEW_PHOTO_SIZE), background)
    draw = ImageDraw.Draw(image)
    radius = rng.randint(90, 150)
    center = (rng.randint(160, 320), rng.randint(160, 300))
    draw.ellipse((center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius), fill=(255, 255, 255))
    draw.text((32, 400), product_name, fill=(60, 50, 90), font=find_font(28))
    draw.text((32, 440), f"리뷰 사진 {photo_index + 1}", fill=(90, 80, 110), font=find_font(20))
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=85)
    return buffer.getvalue()


def review_candidates(state: dict) -> list[tuple[dict, dict]]:
    """클레임 없는 구매확정 주문의 품목(주문 기록, 품목 기록) — 리뷰 작성 자격(CONFIRMED)이 확실한 것만."""
    return [(record, item) for record in state["orders"] if record.get("confirmed") and record["claimType"] is None
            for item in record["items"]]


def pick_keywords(rng: random.Random, rating: int, category_name: str) -> list[str]:
    category_codes = [code for code, _, _ in CATEGORY_KEYWORDS.get(category_name, [])]
    picked = rng.sample(category_codes, k=min(len(category_codes), rng.randint(0, 2)))
    if rating >= 4:
        picked += rng.sample(POSITIVE_BASE_KEYWORDS, k=rng.randint(1, 2))
    elif rating == 3:
        picked += rng.sample(POSITIVE_BASE_KEYWORDS, k=rng.randint(0, 1))
    return picked


class BuyerSessions:
    """구매자 로그인 캐시(리뷰·도움됐어요 호출용). 로그인이 거부된 구매자(예: 관리자 임시 비밀번호 발급으로 state 비밀번호가 무효)는
    경고 후 rejected에 넣고 None을 돌려준다 — 호출부가 그 구매자의 품목·투표를 건너뛴다."""

    def __init__(self, api: ApiClient, state: dict):
        self.api = api
        self.state = state
        self.tokens: dict[int, Credential] = {}
        self.rejected: set[int] = set()

    def token(self, buyer_index: int) -> Credential | None:
        if buyer_index in self.rejected:
            return None
        if buyer_index not in self.tokens:
            buyer = self.state["buyers"][buyer_index]
            try:
                self.tokens[buyer_index] = self.api.login(buyer["email"], buyer["password"], "BUYER")
            except LoginRejected as rejected:
                log.warning("[건너뜀] %s — 이 구매자의 리뷰·도움됐어요를 만들지 않습니다", rejected)
                self.rejected.add(buyer_index)
                return None
        return self.tokens[buyer_index]


def upload_review_photo(api: ApiClient, token: Credential, name: str, data: bytes) -> str:
    """리뷰 사진은 요청당 1장(D-237 결정 3). 항상 200 · 파일별 결과라 success를 직접 본다."""
    body = api.json("POST", "/api/v1/reviews/attachments", token, files=[("files", (name, data, "image/jpeg"))])
    result = body["results"][0]
    if not result["success"]:
        raise SeedError(f"리뷰 사진 업로드 실패 {name}: {result.get('code')} {result.get('message')}")
    return result["attachmentId"]


def write_review(api: ApiClient, sessions: BuyerSessions, state: dict, record: dict, item: dict, rng: random.Random) -> dict | None:
    """리뷰 1건 작성. 작성자 로그인이 거부되면 None(건너뜀)."""
    token = sessions.token(record["buyerIndex"])
    if token is None:
        return None
    product = state["products"][item["productIndex"]]
    product_name, category_name = product["name"], product["category"]
    rating = rng.choices(list(REVIEW_RATING_WEIGHTS), weights=list(REVIEW_RATING_WEIGHTS.values()))[0]
    photo_count = rng.randint(*PHOTO_COUNT_RANGE) if rng.random() < PHOTO_REVIEW_RATIO else 0
    attachment_ids = [upload_review_photo(api, token, f"review-{index + 1}.jpg", make_review_image(product_name, index, rng))
                      for index in range(photo_count)]
    body = {"orderItemId": item["orderItemId"], "rating": rating, "keywordCodes": pick_keywords(rng, rating, category_name),
            "content": rng.choice(REVIEW_TEMPLATES[rating]).format(name=product_name), "attachmentIds": attachment_ids}
    response = api.request("POST", "/api/v1/reviews", token, expect=(201, 409), json=body)
    if response.status_code == 409:
        # 앞 실행이 작성(201) 뒤 state 저장 전에 멈춘 품목 — 이미 작성됐으므로 건너뛴다(올린 사진은 미연결로 24시간 뒤 정리).
        log.warning("[건너뜀] 이미 리뷰가 있는 품목: %s", item["orderItemId"])
        return None
    return {"reviewId": response.json()["reviewId"], "orderItemId": item["orderItemId"], "buyerIndex": record["buyerIndex"],
            "productPublicId": product["publicId"], "rating": rating, "photoCount": photo_count}


def add_helpful_votes(api: ApiClient, sessions: BuyerSessions, state: dict, review: dict, rng: random.Random) -> int:
    voters = [index for index in range(len(state["buyers"])) if index != review["buyerIndex"]]
    chosen = rng.sample(voters, k=rng.randint(0, min(HELPFUL_VOTERS_MAX, len(voters))))
    votes = 0
    for voter in chosen:
        token = sessions.token(voter)
        if token is None:
            continue
        api.json("POST", f"/api/v1/reviews/{review['reviewId']}/helpful", token)
        votes += 1
    return votes


def shift_review_times(conn, state: dict, rng: random.Random) -> None:
    """리뷰 작성 시각을 구매확정 뒤 0~N일로 옮긴다(timeshift 관례 · 데모 구매자 리뷰만). 실행 시각을 넘기지 않는다."""
    limit = datetime.now() - timedelta(minutes=5)
    conn.begin()
    try:
        for review in state["reviews"]:
            # 모든 변수는 %s 바인딩 사용, SQL injection 위험 없음. 데모 구매자 소유 리뷰로 한정(데모 마커 WHERE 포함)
            row = query_one(conn, "SELECT r.id, oi.confirmed_at FROM review r JOIN order_item oi ON oi.id = r.order_item_id "
                                  "JOIN `user` u ON u.id = r.buyer_id WHERE r.public_id = %s AND u.email LIKE %s",
                            (review["reviewId"], f"%@{DEMO_EMAIL_DOMAIN}"))
            if row is None or row["confirmed_at"] is None:
                raise SeedError(f"데모 리뷰 또는 구매확정 시각을 찾을 수 없음: {review['reviewId']}")
            written_at = min(row["confirmed_at"] + timedelta(days=rng.randint(0, REVIEW_DELAY_MAX_DAYS), hours=rng.randint(1, 10)), limit)
            execute(conn, "UPDATE review SET created_at=%s, updated_at=%s WHERE id=%s", (written_at, written_at, row["id"]))
            execute(conn, "UPDATE attachment SET created_at=%s, updated_at=%s WHERE target_type='REVIEW' AND target_id=%s",
                    (written_at, written_at, row["id"]))
        conn.commit()
    except Exception:
        conn.rollback()
        raise


def step_reviews(api: ApiClient, conn, state: dict, rng: random.Random) -> None:
    if not state.get("time_shifted"):
        raise SeedError("시각 보정(timeshift) 전에는 리뷰를 만들지 않습니다(작성 시각 = 보정된 구매확정 시각 기준)")
    log.info("카테고리 키워드 신규 %d개(이미 있으면 건너뜀)", insert_category_keywords(conn, state))
    candidates = review_candidates(state)
    # 대상 선택은 전용 난수로 — 공유 rng는 앞 단계 실행 여부(--step all · reviews 단독 재개)에 따라 소비량이 달라 대상 집합이 바뀐다.
    target_rng = random.Random(REVIEW_TARGET_SEED)
    targets = [pair for pair in candidates if target_rng.random() < REVIEW_RATIO]
    sessions = BuyerSessions(api, state)
    reviews = state.setdefault("reviews", [])
    done = {review["orderItemId"] for review in reviews}
    log.info("리뷰 대상 %d/%d 품목 (완료 %d건부터 재개)", len(targets), len(candidates), len(done))
    skipped_items = 0
    for record, item in targets:
        if item["orderItemId"] in done:
            continue
        review = write_review(api, sessions, state, record, item, rng)
        if review is None:
            skipped_items += 1
            continue
        done.add(item["orderItemId"])
        reviews.append(review)
        save_state(state)
    helpful = sum(add_helpful_votes(api, sessions, state, review, rng) for review in reviews if "helpful" not in review)
    for review in reviews:
        review["helpful"] = True
    shift_review_times(conn, state, rng)
    counts = state.setdefault("counts", {})
    counts["review"] = len(reviews)
    counts["review_photo"] = sum(review["photoCount"] for review in reviews)
    save_state(state)
    ratings = {rating: sum(1 for review in reviews if review["rating"] == rating) for rating in REVIEW_RATING_WEIGHTS}
    log.info("reviews 완료: 리뷰 %d · 사진 %d장 · 도움됐어요 %d · 별점 분포 %s", counts["review"], counts["review_photo"], helpful, ratings)
    log.info("건너뛴 구매자 %d명 · 품목 %d개 (로그인 거부: %s)", len(sessions.rejected), skipped_items,
             ", ".join(state["buyers"][index]["email"] for index in sorted(sessions.rejected)) or "없음")


# ---------------------------------------------------------------------------
# STEP delivered · inquiries · qna (시더 PR2 · D-243)
# 조건 충족형: 가드용 SELECT로 현재 상태를 읽어 부족한 조건만 API로 만든다(직접 쓰기 SQL 없음). 판정은 순수 함수로 분리한다.
# ---------------------------------------------------------------------------
def server_now() -> datetime:
    return datetime.now(SERVER_ZONE).replace(tzinfo=None)


def delivered_missing(rows: list[dict], now: datetime) -> bool:
    """자동확정 창 안의 배송완료 품목(진행 중 클레임 없음)이 하나도 없으면 True.
    rows: item_status · delivered_at · active_claim(REQUESTED·APPROVED 클레임 존재 여부)."""
    return not any(row["item_status"] == "DELIVERED" and not row["active_claim"] and row["delivered_at"] is not None
                   and now <= row["delivered_at"] + timedelta(days=RETURN_WINDOW_DAYS) for row in rows)


def missing_inquiry_conditions(rows: list[dict]) -> list[str]:
    """공개 데모 구매자 문의(삭제 제외)에서 충족되지 않은 조건. rows: answered · checked · has_order."""
    satisfied = {
        "answered_checked": any(row["answered"] and row["checked"] for row in rows),
        "answered_unread": any(row["answered"] and not row["checked"] for row in rows),
        "unanswered": any(not row["answered"] for row in rows),
        "order_attached": any(row["has_order"] for row in rows),
    }
    return [condition for condition in INQUIRY_CONDITIONS if not satisfied[condition]]


def plan_inquiries(missing: list[str]) -> list[tuple[str, bool]]:
    """부족 조건을 만들 문의 목록(종류, 주문 첨부). 주문 첨부가 부족하면 새로 만드는 첫 문의에 붙이고, 만들 문의가 없으면 미답변 1건을 첨부로 만든다."""
    kinds = [condition for condition in INQUIRY_CONDITIONS if condition in missing and condition != "order_attached"]
    attach = "order_attached" in missing
    if attach and not kinds:
        kinds = ["unanswered"]
    return [(kind, attach and index == 0) for index, kind in enumerate(kinds)]


def description_fragments(description: str | None) -> list[str]:
    if not description:
        return []
    return [fragment.strip() for fragment in DESCRIPTION_SPLIT.split(description) if fragment.strip()]


def missing_qna_conditions(questions: list[dict], descriptions: list[str | None]) -> list[str]:
    """데모 셀러 상품 기준으로 충족되지 않은 조건. questions: status(VISIBLE·HIDDEN) · answered(삭제 제외)."""
    satisfied = {
        "multi_sentence_description": any(len(description_fragments(description)) >= 2 for description in descriptions),
        "answered": any(question["status"] == "VISIBLE" and question["answered"] for question in questions),
        "unanswered": any(question["status"] == "VISIBLE" and not question["answered"] for question in questions),
        "hidden": any(question["status"] == "HIDDEN" for question in questions),
    }
    return [condition for condition in QNA_CONDITIONS if not satisfied[condition]]


# 가드 판정용 SELECT(쓰기 없음). 모든 변수는 %s 바인딩 사용, SQL injection 위험 없음
DELIVERED_ROWS_SQL = (
    "SELECT oi.item_status, d.delivered_at, "
    "EXISTS(SELECT 1 FROM claim c WHERE c.order_item_id = oi.id AND c.status IN ('REQUESTED', 'APPROVED')) AS active_claim "
    "FROM order_item oi JOIN `order` o ON o.id = oi.order_id JOIN `user` u ON u.id = o.buyer_id "
    "JOIN delivery d ON d.order_item_id = oi.id AND d.direction = 'OUTBOUND' AND d.claim_id IS NULL AND d.status = 'DELIVERED' "
    "WHERE u.email = %s")
INQUIRY_ROWS_SQL = (
    "SELECT i.answered_at IS NOT NULL AS answered, i.answer_checked_at IS NOT NULL AS checked, i.order_id IS NOT NULL AS has_order "
    "FROM inquiry i JOIN `user` u ON u.id = i.buyer_id WHERE u.email = %s AND i.deleted_at IS NULL")
# 셀러 조회는 데모 상호로 한정한다 — DEMO_SELLER_EMAIL이 비데모 셀러를 가리키면 대상 상품 0건으로 중단(설명 덮어쓰기 방지)
SELLER_PRODUCT_ROWS_SQL = (
    "SELECT p.public_id, p.name, p.description FROM product p JOIN seller sl ON sl.id = p.seller_id "
    "JOIN seller_user su ON su.seller_id = p.seller_id JOIN `user` u ON u.id = su.user_id "
    "WHERE u.email = %s AND sl.company_name LIKE %s AND p.deleted_at IS NULL")
SELLER_QUESTION_ROWS_SQL = (
    "SELECT q.status, q.answered_at IS NOT NULL AS answered FROM product_question q JOIN product p ON p.id = q.product_id "
    "JOIN seller sl ON sl.id = p.seller_id JOIN seller_user su ON su.seller_id = p.seller_id JOIN `user` u ON u.id = su.user_id "
    "WHERE u.email = %s AND sl.company_name LIKE %s AND q.deleted_at IS NULL AND p.deleted_at IS NULL")


def demo_seller_product(api: ApiClient, seller_key: str) -> dict:
    """공개 카탈로그에서 데모 셀러의 구매 가능한 상품 1개(state 없이 · OrderRunner 입력 형태)."""
    company = next(seller["companyName"] for seller in SELLERS if seller["key"] == seller_key)
    probe = next(name for _, key, name, _, _ in PRODUCTS if key == seller_key)
    found = api.json("GET", "/api/v1/products", params={"keyword": probe, "size": CATALOG_PAGE_SIZE})["items"]
    seller_public_id = next((item["sellerPublicId"] for item in found if item["sellerName"] == company), None)
    if seller_public_id is None:
        raise SeedError(f"공개 카탈로그에서 데모 셀러({company}) 상품을 찾지 못했습니다 — master 시드 여부 확인")
    items = api.json("GET", "/api/v1/products", params={"sellerPublicId": seller_public_id, "size": CATALOG_PAGE_SIZE})["items"]
    for item in items:
        if item["soldOut"]:
            continue
        detail = api.json("GET", f"/api/v1/products/{item['productPublicId']}")
        variant = next((variant for variant in detail["variants"] if not variant["soldOut"]), None)
        if variant is not None:
            return {"publicId": item["productPublicId"], "name": item["name"], "variantPublicIds": [variant["variantPublicId"]]}
    raise SeedError(f"데모 셀러({company})에 구매 가능한 상품이 없습니다")


def demo_buyer_login(api: ApiClient) -> Credential:
    return api.login(env("DEMO_BUYER_EMAIL"), env("DEMO_BUYER_PASSWORD"), "BUYER")


def step_delivered(api: ApiClient, conn, admin_token: Credential, rng: random.Random) -> None:
    """공개 데모 구매자에게 자동확정 창 안의 배송완료 품목이 없으면 새 주문 1건을 배송완료까지 만든다(서버 now 그대로 · timeshift 없음)."""
    buyer_email = env("DEMO_BUYER_EMAIL")
    if not delivered_missing(query_all(conn, DELIVERED_ROWS_SQL, (buyer_email,)), server_now()):
        log.info("[delivered] 자동확정 창 안의 배송완료 품목이 이미 있어 건너뜁니다")
        return
    product = demo_seller_product(api, DELIVERED_SELLER_KEY)
    buyer = {"email": buyer_email, "password": env("DEMO_BUYER_PASSWORD"), "name": BUYER_NAMES[0], "address": ADDRESSES[0]}
    # OrderRunner는 state 형태(buyers·products)만 요구한다 — 메모리 안에서만 쓰고 저장하지 않는다(비밀번호 state 미기록)
    runner = OrderRunner(api, conn, {"buyers": [buyer], "products": [product]}, admin_token, rng)
    runner.tracking_seq = int(time.time()) % TRACKING_SEQUENCE_MODULUS
    now = server_now()
    record = runner.run(OrderPlan(now.month, now.day, now.hour, now.minute, 0, [0], "DELIVERED"))
    log.info("[delivered] 배송완료 주문 1건 생성: %s (%s)", record["orderId"], product["name"])


def step_inquiries(api: ApiClient, conn, admin_token: Credential) -> None:
    """공개 데모 구매자 문의에서 ①답변+확인 ②답변+미확인 ③미답변 ④주문 첨부 중 부족한 조건만 만든다."""
    missing = missing_inquiry_conditions(query_all(conn, INQUIRY_ROWS_SQL, (env("DEMO_BUYER_EMAIL"),)))
    if not missing:
        log.info("[inquiries] 문의 조건 4종이 이미 충족돼 건너뜁니다")
        return
    token = demo_buyer_login(api)
    plans = plan_inquiries(missing)
    order_id = None
    if any(attach for _, attach in plans):
        orders = api.json("GET", "/api/v1/orders?page=0&size=1", token)["items"]
        if not orders:
            raise SeedError("공개 데모 구매자 주문이 없어 주문 첨부 문의를 만들 수 없습니다 — delivered 단계 먼저 실행")
        order_id = orders[0]["orderId"]
    for kind, attach in plans:
        category, content, answer = INQUIRY_TEMPLATES[kind]
        body = {"category": category, "content": content}
        if attach:
            body["orderId"] = order_id
        inquiry_id = api.json("POST", "/api/v1/inquiries", token, json=body)["inquiryId"]
        if answer is not None:
            api.json("PUT", f"/api/v1/admin/inquiries/{inquiry_id}/answer", admin_token, expect=(204,), json={"content": answer})
        if kind == "answered_checked":
            api.json("PUT", f"/api/v1/inquiries/{inquiry_id}/answer-check", token, expect=(204,))
    log.info("[inquiries] 부족 조건 %s → 문의 %d건 생성", missing, len(plans))


def step_qna(api: ApiClient, conn, admin_token: Credential) -> None:
    """데모 셀러 상품 기준 ①답변됨 ②미답변 ③숨김 ④여러 문장 설명 상품 중 부족한 조건만 만든다(질문 공개 데모 구매자 · 답변 셀러 · 숨김 관리자)."""
    seller_email = env("DEMO_SELLER_EMAIL")
    seller_params = (seller_email, f"{DEMO_SELLER_PREFIX}%")
    products = query_all(conn, SELLER_PRODUCT_ROWS_SQL, seller_params)
    questions = query_all(conn, SELLER_QUESTION_ROWS_SQL, seller_params)
    missing = missing_qna_conditions(questions, [product["description"] for product in products])
    if not missing:
        log.info("[qna] Q&A 조건 4종이 이미 충족돼 건너뜁니다")
        return
    target = next((product for product in products if product["name"] == QNA_PRODUCT_NAME), None)
    if target is None:
        raise SeedError(f"데모 셀러({seller_email})에 대상 상품 '{QNA_PRODUCT_NAME}'이 없습니다")
    product_id = target["public_id"]
    seller_token = api.login(seller_email, env("DEMO_SELLER_PASSWORD"), "SELLER")
    if "multi_sentence_description" in missing:
        # 셀러 기본정보 수정은 4필드 전체 치환 — 현재 값을 읽어 설명만 바꾼다(상태·variant·재고·이미지 무변경)
        current = api.json("GET", f"/api/v1/seller/products/{product_id}", seller_token)
        api.json("PUT", f"/api/v1/seller/products/{product_id}", seller_token, json={
            "categoryId": current["categoryId"], "name": current["name"], "description": QNA_PRODUCT_DESCRIPTION,
            "basePrice": current["basePrice"]})
    question_kinds = [condition for condition in missing if condition in QNA_TEMPLATES]
    buyer_token = demo_buyer_login(api) if question_kinds else None
    for kind in question_kinds:
        content, answer = QNA_TEMPLATES[kind]
        question_id = api.json("POST", "/api/v1/product-questions", buyer_token,
                               json={"productId": product_id, "content": content})["questionId"]
        if answer is not None:
            api.json("PUT", f"/api/v1/seller/product-questions/{question_id}/answer", seller_token, expect=(204,),
                     json={"content": answer})
        if kind == "hidden":
            api.json("PATCH", f"/api/v1/admin/product-questions/{question_id}/status", admin_token, expect=(204,),
                     json={"status": "HIDDEN", "reason": QNA_HIDDEN_REASON})
    log.info("[qna] 부족 조건 %s → 설명 갱신 %s · 질문 %d건 생성", missing, "multi_sentence_description" in missing, len(question_kinds))


def log_demo_data_status(conn) -> None:
    """106 데이터 충족 여부 출력(D2·R1·R2) — 판정만 하고 exit code에는 반영하지 않는다."""
    buyer_email = os.environ.get("DEMO_BUYER_EMAIL")
    if buyer_email:
        d2 = not delivered_missing(query_all(conn, DELIVERED_ROWS_SQL, (buyer_email,)), server_now())
        log.info("[106] D2 배송완료 대기 품목(자동확정 창 안): %s", "충족" if d2 else "부족")
        r1 = missing_inquiry_conditions(query_all(conn, INQUIRY_ROWS_SQL, (buyer_email,)))
        log.info("[106] R1 1:1 문의: %s", "충족" if not r1 else f"부족 {r1}")
    else:
        log.info("[106] D2 배송완료 대기 품목(자동확정 창 안): 미판정(DEMO_BUYER_EMAIL 미설정)")
        log.info("[106] R1 1:1 문의: 미판정(DEMO_BUYER_EMAIL 미설정)")
    seller_email = os.environ.get("DEMO_SELLER_EMAIL")
    if not seller_email:
        log.info("[106] R2 상품 Q&A: 미판정(DEMO_SELLER_EMAIL 미설정)")
        return
    seller_params = (seller_email, f"{DEMO_SELLER_PREFIX}%")
    products = query_all(conn, SELLER_PRODUCT_ROWS_SQL, seller_params)
    r2 = missing_qna_conditions(query_all(conn, SELLER_QUESTION_ROWS_SQL, seller_params),
                                [product["description"] for product in products])
    log.info("[106] R2 상품 Q&A: %s", "충족" if not r2 else f"부족 {r2}")


# ---------------------------------------------------------------------------
# STEP verify
# ---------------------------------------------------------------------------
def step_verify(conn, state: dict) -> None:
    log_demo_data_status(conn)  # 106 데이터 충족 여부는 출력만(아래 기존 검증이 실패해도 먼저 보이도록 앞에 둔다)
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
             "/orders, /payments/mock-callback, /admin/orders/items/{oit}/prepare-shipment, /admin/deliveries/{dlv}/mark-delivered, "
             "/orders/{ord}/items/{oit}/confirm, /claims(+approve/return-shipment/confirm-pickup/inspect/register-exchange-shipment), "
             "/admin/settlements(+confirm/pay) · /admin/sellers/{slr}/bank-accounts(Track 89-F) · "
             "/reviews/attachments(1장씩)·/reviews·/reviews/{rvw}/helpful(Track 106-1) · "
             "SQL: 시각 UPDATE · settlement.paid_at UPDATE · review_keyword INSERT(카테고리 세트) · review.created_at UPDATE")
    log.info("[dry-run] 106 데이터(D-243 · 부족분만): delivered(주문→배송완료) · inquiries(/inquiries·관리자 answer·answer-check) · "
             "qna(셀러 설명 갱신 PUT /seller/products·/product-questions·셀러 answer·관리자 숨김 PATCH) · SQL: 가드 판정 SELECT만")
    log.info("[dry-run] state 파일: %s (존재: %s · 대상 %s)", active_state_path, active_state_path.exists(), state.get("target"))


def main() -> int:
    global active_state_path
    parser = argparse.ArgumentParser(description="zslab-mall 데모 시드")
    parser.add_argument("--target", choices=TARGETS, required=True, help="실행 대상(기본값 없음 · D-242)")
    parser.add_argument("--step", choices=ALL_STEPS + ["all"], default="all")
    parser.add_argument("--dry-run", action="store_true", help="쓰기 없이 대상 가드·교차 검증 후 계획만 출력")
    parser.add_argument("--force", action="store_true", help="재실행 가드 무시(prod 금지)")
    parser.add_argument("--seed", type=int, default=20260918, help="난수 시드(재현용)")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949) 한글 로그 깨짐 방지
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)

    steps = list(ALL_STEPS) if args.step == "all" else [args.step]
    # dry-run도 교차 검증(읽기)을 하므로 env 전부 필요 · 106 데이터 단계는 계정 env 추가
    step_env = [name for step in steps for name in STEP_REQUIRED_ENV.get(step, [])]
    missing = [name for name in dict.fromkeys(REQUIRED_ENV + step_env) if not os.environ.get(name)]
    if missing:
        log.error("환경변수 미설정: %s", ", ".join(missing))
        return 2

    try:
        reject_legacy_state()
        api_host = api_host_of(env("API_BASE_URL"))
        check_target(args.target, urllib.parse.urlsplit(env("API_BASE_URL")).scheme, api_host, env("DB_HOST"), steps, args.force)
        if args.target == "prod":
            confirm_prod_domain(api_host)
        active_state_path = state_path(args.target)
        state = load_state(active_state_path)
        bind_state_target(state, {"name": args.target, "apiHost": api_host, "dbHost": env("DB_HOST"), "dbName": env("DB_NAME")})
    except SeedError as error:
        log.error("중단: %s", error)
        return 1

    api = ApiClient(env("API_BASE_URL"))
    conn = connect_db()
    rng = random.Random(args.seed)
    total_start = time.monotonic()
    try:
        admin_token = api.login(env("ADMIN_EMAIL"), env("ADMIN_PASSWORD"), "ADMIN")
        cross_check_api_db(api, conn, admin_token)
        if args.dry_run:
            print_dry_run(state)
            return 0
        save_state(state)  # 대상 기록(교차 검증 통과 후 첫 쓰기)
        for step in steps:
            if step != "verify":
                guard(step, conn, state, args.force)
            if step in ("master", "orders", "settlement", "delivered", "inquiries", "qna"):
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
                elif step == "reviews":
                    step_reviews(api, conn, state, rng)
                elif step == "delivered":
                    step_delivered(api, conn, admin_token, rng)
                elif step == "inquiries":
                    step_inquiries(api, conn, admin_token)
                elif step == "qna":
                    step_qna(api, conn, admin_token)
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
