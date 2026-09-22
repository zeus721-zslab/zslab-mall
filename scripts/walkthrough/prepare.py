"""워크스루 기준 상태 보충(Track 98 STEP 1). 데모 시드가 만들지 않는 시나리오 데이터만 API로 채운다.

멱등: 필요한 개수를 이미 채우고 있으면 아무것도 만들지 않는다. 실행 후 시나리오별 데이터 충족 여부를 표로 출력한다.
    python scripts/walkthrough/prepare.py
"""
from __future__ import annotations

import sys
import time

from common import ApiClient, WalkthroughError, fail, load_env, log

# 구매자 시나리오 2개(구매확정·반품 신청)는 주문 목록에서 '배송완료' 주문을 골라 서로 다른 주문 1건씩 쓴다.
# 품목이 섞인 주문은 주문 상태가 배송완료로 보이지 않으므로 '전 품목 배송완료' 주문 수를 기준으로 삼는다.
REQUIRED_DELIVERED_ORDERS = 2
CARRIER = "CJ"
SHIPPING_ADDRESS = {
    "recipientName": "워크스루",
    "recipientPhone": "010-2000-0000",
    "zonecode": "06236",
    "addressRoad": "서울 강남구 테헤란로 1",
    "addressJibun": "서울 강남구 역삼동 1",
    "addressDetail": "워크스루 기준 상태",
    "deliveryMemo": "워크스루 준비 데이터",
}


def delivered_orders(api: ApiClient, buyer_token: str) -> list:
    """주문 상태가 배송완료(DELIVERED)인 구매자 주문 publicId 목록."""
    orders = api.json("GET", "/api/v1/orders?page=0&size=50", buyer_token).get("items", [])
    return [order["orderId"] for order in orders if order.get("status", {}).get("code") == "DELIVERED"]


def pick_sellable_variant(api: ApiClient) -> tuple:
    """공개 카탈로그에서 주문 가능한 (상품 publicId, variant publicId)를 고른다."""
    listing = api.json("GET", "/api/v1/products?page=0&size=20")
    for summary in listing.get("items", []):
        detail = api.json("GET", "/api/v1/products/" + summary["productPublicId"])
        if detail.get("saleStopped"):
            continue
        for variant in detail.get("variants", []):
            if not variant.get("soldOut"):
                return summary["productPublicId"], variant["variantPublicId"]
    raise WalkthroughError("주문 가능한 상품/옵션을 찾지 못했습니다(데모 시드 확인).")


def create_delivered_order(api: ApiClient, buyer_token: str, admin_token: str) -> str:
    """주문(품목 1개) → mock 결제 승인 → 송장 등록 → 배송완료까지 진행해 배송완료 주문 1건을 만든다."""
    product_id, variant_id = pick_sellable_variant(api)
    body = {"items": [{"productId": product_id, "variantId": variant_id, "quantity": 1}],
            "method": "CARD", "shippingAddress": SHIPPING_ADDRESS}
    checkout = api.json("POST", "/api/v1/orders", buyer_token, body)
    redirect_url = checkout["payment"]["redirectUrl"]
    attempt_key = redirect_url.split("attemptKey=")[1].split("&")[0]
    api.json("POST", "/api/v1/payments/mock-callback", buyer_token,
             {"attemptKey": attempt_key, "callbackType": "SUCCESS"})
    latest = api.json("GET", "/api/v1/orders?page=0&size=1", buyer_token)["items"][0]
    order_public_id = latest["orderId"]
    detail = api.json("GET", "/api/v1/orders/" + order_public_id, buyer_token)
    order_item_id = detail["sellers"][0]["items"][0]["orderItemId"]
    # tracking_no는 UNIQUE(DLV-1)라 실행 시각 기반으로 만든다.
    tracking_no = "WT" + str(int(time.time() * 1000))
    shipped = api.json("POST", "/api/v1/admin/orders/items/" + order_item_id + "/prepare-shipment", admin_token,
                       {"carrier": CARRIER, "trackingNo": tracking_no})
    api.json("POST", "/api/v1/admin/deliveries/" + shipped["deliveryPublicId"] + "/mark-delivered", admin_token)
    return order_public_id


def ensure_pending_seller(api: ApiClient, admin_token: str) -> int:
    """승인 대기(PENDING) 셀러가 없으면 1건 만든다. 소유 회원도 함께 가입시킨다."""
    page = api.json("GET", "/api/v1/admin/sellers/page?page=0&size=50", admin_token)
    pending = [s for s in page.get("items", []) if s.get("status") == "PENDING"]
    if pending:
        return 0
    stamp = str(int(time.time()))
    signup = api.json("POST", "/api/v1/users", body={
        "email": "walkthrough-owner-" + stamp + "@demo.zslab-mall.com",
        "name": "워크스루대표", "phone": "010-4000-" + stamp[-4:], "password": "Walkthrough!" + stamp[-4:]})
    api.json("POST", "/api/v1/admin/sellers", admin_token, {
        "companyName": "워크스루 준비 셀러 " + stamp[-4:], "businessNo": None, "ceoName": "워크스루대표",
        "contactEmail": "walkthrough-" + stamp + "@demo.zslab-mall.com", "contactPhone": "010-4000-" + stamp[-4:],
        "status": "PENDING", "ownerUserPublicId": signup["userPublicId"]})
    return 1


def report(api: ApiClient, admin_token: str, seller_token: str, delivered_orders_count: int) -> bool:
    """시나리오별 데이터 충족 여부를 출력하고 전부 충족인지 반환한다."""
    admin_pending = api.json("GET", "/api/v1/admin/dashboard", admin_token)["pending"]
    seller_pending = api.json("GET", "/api/v1/seller/dashboard", seller_token)["pending"]
    seller_products = api.json("GET", "/api/v1/seller/products?page=0&size=50", seller_token).get("items", [])
    sale_products = len([p for p in seller_products if p.get("status") == "SALE"])
    rows = [
        ("admin-claim-cancel-approve", "클레임 요청(REQUESTED)", admin_pending["claimRequested"], 1),
        ("admin-settlement-confirm-pay", "정산 대기(PENDING)", admin_pending["settlementPending"], 1),
        ("admin-seller-approve", "셀러 승인 대기(PENDING)", admin_pending["sellerPending"], 1),
        ("seller-order-ship", "셀러 배송 대기(PAID 품목)", seller_pending["deliveryReady"], 1),
        ("seller-product-stop-resume", "셀러 판매중 상품", sale_products, 1),
        ("buyer-order-confirm / buyer-claim-return", "구매자 배송완료 주문", delivered_orders_count, REQUIRED_DELIVERED_ORDERS),
    ]
    log("")
    log("시나리오                                데이터                          보유  필요  판정")
    ok = True
    for scenario, label, actual, need in rows:
        verdict = "OK" if actual >= need else "부족"
        if actual < need:
            ok = False
        log("%-38s %-28s %5d %5d  %s" % (scenario, label, actual, need, verdict))
    return ok


def main() -> int:
    env = load_env()
    api = ApiClient()
    try:
        admin_token = api.login(env["ADMIN_BOOTSTRAP_EMAIL"], env["ADMIN_BOOTSTRAP_PASSWORD"], "ADMIN")
        buyer_token = api.login(env["NUXT_BUYER_DEMO_EMAIL"], env["NUXT_BUYER_DEMO_PASSWORD"], "BUYER")
        seller_token = api.login(env["NUXT_SELLER_DEMO_EMAIL"], env["NUXT_SELLER_DEMO_PASSWORD"], "SELLER")

        existing = delivered_orders(api, buyer_token)
        log("구매자 배송완료 주문 현재 " + str(len(existing)) + "건 (필요 " + str(REQUIRED_DELIVERED_ORDERS) + "건)")
        created = 0
        while len(existing) + created < REQUIRED_DELIVERED_ORDERS:
            order_id = create_delivered_order(api, buyer_token, admin_token)
            created += 1
            log("  배송완료 주문 생성: " + order_id)
        if created == 0:
            log("  보충 불필요(멱등)")

        added_seller = ensure_pending_seller(api, admin_token)
        log("승인 대기 셀러 보충: " + ("1건 생성" if added_seller else "불필요(멱등)"))

        ok = report(api, admin_token, seller_token, len(delivered_orders(api, buyer_token)))
        log("")
        if not ok:
            return fail("일부 시나리오 데이터가 부족합니다 — 데모 시드(scripts/demo-seed) 실행 상태를 확인하세요.")
        log("기준 상태 준비 완료. 다음: python scripts/walkthrough/dump.py")
        return 0
    except WalkthroughError as error:
        return fail(str(error))


if __name__ == "__main__":
    sys.exit(main())
