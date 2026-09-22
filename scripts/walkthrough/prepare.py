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
# 셀러 출고 시나리오 2개(seller-order-ship 1건 + seller-order-ship-multi 3건)가 서로 다른 품목을 쓴다.
REQUIRED_SELLER_PAID_ITEMS = 4
# 관리자 취소 클레임 시나리오 2개(승인·거부)가 서로 다른 클레임을 쓴다.
REQUIRED_CANCEL_REQUESTED_CLAIMS = 2
CARRIER = "CJ"
# 데모 구매자 주문 목록은 시나리오용 상태(배송완료·배송중·결제완료)로만 두기 위해, 셀러 출고분·취소 클레임분은 전용 계정으로 주문한다.
# 로컬 전용 고정 자격증명 — 재실행 시 같은 계정으로 로그인해 멱등을 유지한다.
WALKTHROUGH_BUYER_EMAIL = "walkthrough-buyer@demo.zslab-mall.com"
WALKTHROUGH_BUYER_PASSWORD = "Walkthrough!2026"
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


def create_paid_order(api: ApiClient, buyer_token: str, product_id: str, variant_id: str) -> tuple:
    """주문(품목 1개) → mock 결제 승인. (주문 publicId, 첫 품목 publicId)를 돌려준다."""
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
    return order_public_id, detail["sellers"][0]["items"][0]["orderItemId"]


def ship_item(api: ApiClient, admin_token: str, order_item_id: str) -> str:
    """품목 송장 등록(PAID → SHIPPING). 만들어진 배송 publicId를 돌려준다."""
    # tracking_no는 UNIQUE(DLV-1)라 실행 시각 기반으로 만든다.
    tracking_no = "WT" + str(int(time.time() * 1000))
    shipped = api.json("POST", "/api/v1/admin/orders/items/" + order_item_id + "/prepare-shipment", admin_token,
                       {"carrier": CARRIER, "trackingNo": tracking_no})
    return shipped["deliveryPublicId"]


def create_delivered_order(api: ApiClient, buyer_token: str, admin_token: str) -> str:
    """주문 → 결제 → 송장 등록 → 배송완료까지 진행해 배송완료 주문 1건을 만든다."""
    product_id, variant_id = pick_sellable_variant(api)
    order_public_id, order_item_id = create_paid_order(api, buyer_token, product_id, variant_id)
    delivery_public_id = ship_item(api, admin_token, order_item_id)
    api.json("POST", "/api/v1/admin/deliveries/" + delivery_public_id + "/mark-delivered", admin_token)
    return order_public_id


def walkthrough_buyer_token(api: ApiClient) -> str:
    """전용 구매자 계정 토큰. 없으면 가입시키고 이미 있으면(409) 그대로 로그인한다."""
    status, payload = api.request("POST", "/api/v1/users", body={
        "email": WALKTHROUGH_BUYER_EMAIL, "name": "워크스루구매자",
        "phone": "010-5000-0001", "password": WALKTHROUGH_BUYER_PASSWORD})
    if status >= 400 and status != 409:
        raise WalkthroughError("전용 구매자 가입 실패 " + str(status) + ": " + str(payload)[:200])
    return api.login(WALKTHROUGH_BUYER_EMAIL, WALKTHROUGH_BUYER_PASSWORD, "BUYER")


def pick_seller_variant(api: ApiClient, seller_token: str) -> tuple:
    """데모 셀러 상품 중 주문 가능한 (상품 publicId, variant publicId)."""
    listing = api.json("GET", "/api/v1/seller/products?page=0&size=50", seller_token).get("items", [])
    for summary in listing:
        if summary.get("status") != "SALE":
            continue
        detail = api.json("GET", "/api/v1/products/" + summary["productPublicId"])
        if detail.get("saleStopped"):
            continue
        for variant in detail.get("variants", []):
            if not variant.get("soldOut"):
                return summary["productPublicId"], variant["variantPublicId"]
    raise WalkthroughError("데모 셀러의 주문 가능한 상품/옵션을 찾지 못했습니다.")


def pick_exchange_pair(api: ApiClient) -> tuple:
    """교환용 (상품 publicId, 주문 variant publicId, 교환 variant publicId).

    BE는 같은 상품·같은 단가·판매중 옵션으로만 교환을 허용하므로(ClaimExchangeService.validateExchangeOption)
    판매가가 같은 미품절 옵션 2개를 가진 상품을 고른다.
    """
    listing = api.json("GET", "/api/v1/products?page=0&size=50")
    for summary in listing.get("items", []):
        detail = api.json("GET", "/api/v1/products/" + summary["productPublicId"])
        if detail.get("saleStopped"):
            continue
        by_price = {}
        for variant in detail.get("variants", []):
            if variant.get("soldOut"):
                continue
            by_price.setdefault(variant["salePrice"], []).append(variant["variantPublicId"])
        for variants in by_price.values():
            if len(variants) >= 2:
                return summary["productPublicId"], variants[0], variants[1]
    raise WalkthroughError("같은 가격의 교환 옵션 2개를 가진 상품을 찾지 못했습니다.")


def buyer_claims(api: ApiClient, buyer_token: str) -> list:
    return api.json("GET", "/api/v1/claims?page=0&size=50", buyer_token).get("items", [])


def ensure_buyer_shipping_order(api: ApiClient, buyer_token: str, admin_token: str) -> int:
    """buyer-order-tracking용: 데모 구매자에게 배송중(SHIPPING) 주문이 없으면 1건 만든다."""
    orders = api.json("GET", "/api/v1/orders?page=0&size=50", buyer_token).get("items", [])
    if any(order.get("status", {}).get("code") == "SHIPPING" for order in orders):
        return 0
    product_id, variant_id = pick_sellable_variant(api)
    _, order_item_id = create_paid_order(api, buyer_token, product_id, variant_id)
    ship_item(api, admin_token, order_item_id)
    return 1


def ensure_return_claim_awaiting_pickup(api: ApiClient, buyer_token: str, admin_token: str) -> int:
    """admin-claim-return-inspect용: 회수 송장 등록 단계(RETURN·APPROVED·회수 송장 없음) 클레임이 없으면 1건 만든다."""
    for claim in buyer_claims(api, buyer_token):
        if claim.get("claimType") == "RETURN" and claim.get("status") == "APPROVED":
            detail = api.json("GET", "/api/v1/claims/" + claim["publicId"], buyer_token)
            if detail.get("returnShipmentRequired"):
                return 0
    order_public_id = create_delivered_order(api, buyer_token, admin_token)
    detail = api.json("GET", "/api/v1/orders/" + order_public_id, buyer_token)
    order_item_id = detail["sellers"][0]["items"][0]["orderItemId"]
    created = api.json("POST", "/api/v1/claims", buyer_token, {
        "orderItemPublicId": order_item_id, "claimType": "RETURN",
        "reasonCode": "WRONG_PRODUCT", "reasonDetail": "워크스루 기준 상태(반품 검수용)"})
    api.json("POST", "/api/v1/admin/claims/" + created["publicId"] + "/approve", admin_token)
    return 1


def ensure_exchange_claim_requested(api: ApiClient, buyer_token: str, admin_token: str) -> int:
    """admin-claim-exchange-full용: 교환 요청(EXCHANGE·REQUESTED) 클레임이 없으면 1건 만든다."""
    if any(claim.get("claimType") == "EXCHANGE" and claim.get("status") == "REQUESTED"
           for claim in buyer_claims(api, buyer_token)):
        return 0
    product_id, variant_id, exchange_variant_id = pick_exchange_pair(api)
    _, order_item_id = create_paid_order(api, buyer_token, product_id, variant_id)
    delivery_public_id = ship_item(api, admin_token, order_item_id)
    api.json("POST", "/api/v1/admin/deliveries/" + delivery_public_id + "/mark-delivered", admin_token)
    api.json("POST", "/api/v1/claims", buyer_token, {
        "orderItemPublicId": order_item_id, "claimType": "EXCHANGE",
        "reasonCode": "PRODUCT_DEFECT", "reasonDetail": "워크스루 기준 상태(교환 처리용)",
        "exchangeVariantId": exchange_variant_id})
    return 1


def newest_paid_order_cancelable(api: ApiClient, buyer_token: str) -> int:
    """가장 최근 결제완료 주문에 취소 신청 가능한 품목(PAID)이 있으면 1, 아니면 0.

    주문 상태가 결제완료여도 품목이 교환요청 등으로 넘어가 있으면 화면에 '취소 요청' 버튼이 없다(claimableTypes).
    buyer-order-cancel-request는 목록(주문일시 내림차순)의 첫 결제완료 주문을 고르므로 '가장 최근 1건'이 판정 대상이다.
    """
    orders = api.json("GET", "/api/v1/orders?page=0&size=50", buyer_token).get("items", [])
    paid = [order for order in orders if order.get("status", {}).get("code") == "PAID"]
    if not paid:
        return 0
    detail = api.json("GET", "/api/v1/orders/" + paid[0]["orderId"], buyer_token)
    statuses = [item["status"]["code"] for seller in detail["sellers"] for item in seller["items"]]
    return 1 if "PAID" in statuses else 0


def ensure_buyer_cancelable_order(api: ApiClient, buyer_token: str) -> int:
    """buyer-order-cancel-request용: 가장 최근 결제완료 주문이 취소 불가면 취소 가능한 주문을 1건 더 만든다(새 주문이 최신이 된다).

    데모 구매자 주문을 만드는 보충 중 마지막에 호출해야 한다.
    """
    if newest_paid_order_cancelable(api, buyer_token):
        return 0
    product_id, variant_id = pick_sellable_variant(api)
    create_paid_order(api, buyer_token, product_id, variant_id)
    return 1


def count_cancel_requested(api: ApiClient, admin_token: str) -> int:
    page = api.json("GET", "/api/v1/admin/claims?type=CANCEL&status=REQUESTED&page=0&size=50", admin_token)
    return len(page.get("items", []))


def ensure_cancel_requested_claims(api: ApiClient, admin_token: str, other_buyer_token: str) -> int:
    """admin-claim-cancel-approve·admin-claim-cancel-reject용: 취소 요청 클레임을 2건까지 채운다."""
    created = 0
    while count_cancel_requested(api, admin_token) < REQUIRED_CANCEL_REQUESTED_CLAIMS:
        product_id, variant_id = pick_sellable_variant(api)
        _, order_item_id = create_paid_order(api, other_buyer_token, product_id, variant_id)
        api.json("POST", "/api/v1/claims", other_buyer_token, {
            "orderItemPublicId": order_item_id, "claimType": "CANCEL",
            "reasonCode": "ORDER_MISTAKE", "reasonDetail": "워크스루 기준 상태(취소 처리용)"})
        created += 1
        if created > REQUIRED_CANCEL_REQUESTED_CLAIMS:
            raise WalkthroughError("취소 요청 클레임 보충이 늘지 않습니다.")
    return created


def seller_delivery_ready(api: ApiClient, seller_token: str) -> int:
    return api.json("GET", "/api/v1/seller/dashboard", seller_token)["pending"]["deliveryReady"]


def ensure_seller_paid_items(api: ApiClient, seller_token: str, other_buyer_token: str) -> int:
    """seller-order-ship·seller-order-ship-multi용: 데모 셀러 배송 대기 품목을 4건까지 채운다."""
    created = 0
    while seller_delivery_ready(api, seller_token) < REQUIRED_SELLER_PAID_ITEMS:
        product_id, variant_id = pick_seller_variant(api, seller_token)
        create_paid_order(api, other_buyer_token, product_id, variant_id)
        created += 1
        if created > REQUIRED_SELLER_PAID_ITEMS:
            raise WalkthroughError("셀러 배송 대기 품목 보충이 늘지 않습니다(상품·재고 확인).")
    return created


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


def buyer_status_count(api: ApiClient, buyer_token: str, status_code: str) -> int:
    orders = api.json("GET", "/api/v1/orders?page=0&size=50", buyer_token).get("items", [])
    return len([o for o in orders if o.get("status", {}).get("code") == status_code])


def buyer_claim_count(api: ApiClient, buyer_token: str, claim_type: str, status: str) -> int:
    return len([c for c in buyer_claims(api, buyer_token)
                if c.get("claimType") == claim_type and c.get("status") == status])


def shipping_delivery_count(api: ApiClient, admin_token: str) -> int:
    """배송중(SHIPPING) 배송 수. 자동 배송완료 스케줄러(Track 99 D-210)가 켜져 있으면 준비 직후에도 줄어들 수 있다 —
    워크스루 실행 전 DELIVERY_AUTO_COMPLETE_ENABLED=false를 확인한다(README '자동 배송완료 끄기')."""
    page = api.json("GET", "/api/v1/admin/deliveries?status=SHIPPING&page=0&size=100", admin_token)
    return page.get("totalCount", len(page.get("items", [])))


def report(api: ApiClient, admin_token: str, seller_token: str, buyer_token: str, delivered_orders_count: int) -> bool:
    """시나리오별 데이터 충족 여부를 출력하고 전부 충족인지 반환한다."""
    admin_pending = api.json("GET", "/api/v1/admin/dashboard", admin_token)["pending"]
    seller_pending = api.json("GET", "/api/v1/seller/dashboard", seller_token)["pending"]
    seller_products = api.json("GET", "/api/v1/seller/products?page=0&size=50", seller_token).get("items", [])
    sale_products = len([p for p in seller_products if p.get("status") == "SALE"])
    seller_shipping = api.json("GET", "/api/v1/seller/deliveries?status=SHIPPING&page=0&size=50", seller_token)
    seller_shipping_count = seller_shipping.get("totalCount", len(seller_shipping.get("items", [])))
    seller_inventory = api.json("GET", "/api/v1/seller/inventories?page=0&size=50", seller_token).get("items", [])
    rows = [
        ("admin-claim-cancel-approve / -reject", "취소 클레임 요청(REQUESTED)", count_cancel_requested(api, admin_token),
         REQUIRED_CANCEL_REQUESTED_CLAIMS),
        ("admin-settlement-confirm-pay", "정산 대기(PENDING)", admin_pending["settlementPending"], 1),
        ("admin-seller-approve", "셀러 승인 대기(PENDING)", admin_pending["sellerPending"], 1),
        ("admin-claim-return-inspect", "반품 회수 대기(APPROVED)", buyer_claim_count(api, buyer_token, "RETURN", "APPROVED"), 1),
        ("admin-claim-exchange-full", "교환 요청(REQUESTED)", buyer_claim_count(api, buyer_token, "EXCHANGE", "REQUESTED"), 1),
        ("admin-delivery-complete / -tracking-fix", "배송중(SHIPPING) 배송", shipping_delivery_count(api, admin_token), 3),
        ("seller-order-ship / -multi", "셀러 배송 대기(PAID 품목)", seller_pending["deliveryReady"], REQUIRED_SELLER_PAID_ITEMS),
        ("seller-delivery-tracking-fix", "셀러 배송중(SHIPPING) 배송", seller_shipping_count, 1),
        ("seller-inventory-inbound", "셀러 재고 행", len(seller_inventory), 1),
        ("seller-product-price-edit / -stop-resume", "셀러 판매중 상품", sale_products, 2),
        ("buyer-order-confirm / buyer-claim-return", "구매자 배송완료 주문", delivered_orders_count, REQUIRED_DELIVERED_ORDERS),
        ("buyer-order-tracking", "구매자 배송중 주문", buyer_status_count(api, buyer_token, "SHIPPING"), 1),
        ("buyer-order-cancel-request", "최근 결제완료 주문 취소 가능", newest_paid_order_cancelable(api, buyer_token), 1),
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

        other_buyer_token = walkthrough_buyer_token(api)

        # 클레임·배송중 주문을 먼저 만든다 — 이들이 배송완료 주문을 소비하므로 배송완료 보충은 마지막이어야 한다.
        log("반품 회수 대기 클레임 보충: " + ("1건 생성" if ensure_return_claim_awaiting_pickup(api, buyer_token, admin_token)
                                              else "불필요(멱등)"))
        log("교환 요청 클레임 보충: " + ("1건 생성" if ensure_exchange_claim_requested(api, buyer_token, admin_token)
                                        else "불필요(멱등)"))
        log("구매자 배송중 주문 보충: " + ("1건 생성" if ensure_buyer_shipping_order(api, buyer_token, admin_token)
                                          else "불필요(멱등)"))
        added_cancel = ensure_cancel_requested_claims(api, admin_token, other_buyer_token)
        log("취소 요청 클레임 보충: " + (str(added_cancel) + "건 생성" if added_cancel else "불필요(멱등)"))
        added_paid = ensure_seller_paid_items(api, seller_token, other_buyer_token)
        log("셀러 배송 대기 품목 보충: " + (str(added_paid) + "건 생성" if added_paid else "불필요(멱등)"))

        existing = delivered_orders(api, buyer_token)
        log("구매자 배송완료 주문 현재 " + str(len(existing)) + "건 (필요 " + str(REQUIRED_DELIVERED_ORDERS) + "건)")
        created = 0
        while len(existing) + created < REQUIRED_DELIVERED_ORDERS:
            order_id = create_delivered_order(api, buyer_token, admin_token)
            created += 1
            log("  배송완료 주문 생성: " + order_id)
        if created == 0:
            log("  보충 불필요(멱등)")

        # 취소 가능 주문은 데모 구매자 주문을 만드는 보충 중 마지막이어야 한다(시나리오가 가장 최근 결제완료 주문을 고른다).
        log("구매자 취소 가능 주문 보충: " + ("1건 생성" if ensure_buyer_cancelable_order(api, buyer_token)
                                              else "불필요(멱등)"))

        added_seller = ensure_pending_seller(api, admin_token)
        log("승인 대기 셀러 보충: " + ("1건 생성" if added_seller else "불필요(멱등)"))

        ok = report(api, admin_token, seller_token, buyer_token, len(delivered_orders(api, buyer_token)))
        log("")
        if not ok:
            return fail("일부 시나리오 데이터가 부족합니다 — 데모 시드(scripts/demo-seed) 실행 상태를 확인하세요.")
        log("기준 상태 준비 완료. 다음: python scripts/walkthrough/dump.py")
        return 0
    except WalkthroughError as error:
        return fail(str(error))


if __name__ == "__main__":
    sys.exit(main())
