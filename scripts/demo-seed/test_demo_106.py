"""106 데이터 단계(D-243) 부족 조건 판정 순수 함수 테스트. DB·HTTP 없음.

실행: python -m unittest discover -s scripts/demo-seed -p "test_*.py"
"""

import unittest
from datetime import datetime, timedelta

import seed

NOW = datetime(2026, 10, 1, 12, 0, 0)


def delivered_row(item_status="DELIVERED", days_ago=1.0, active_claim=0):
    return {"item_status": item_status, "delivered_at": NOW - timedelta(days=days_ago), "active_claim": active_claim}


def inquiry(answered=0, checked=0, has_order=0):
    return {"answered": answered, "checked": checked, "has_order": has_order}


def question(status="VISIBLE", answered=0):
    return {"status": status, "answered": answered}


MULTI = "첫 문장입니다. 둘째 문장입니다."


class DeliveredMissingTest(unittest.TestCase):

    def test_missing_when_no_rows(self):
        self.assertTrue(seed.delivered_missing([], NOW))

    def test_satisfied_by_delivered_item_inside_window(self):
        self.assertFalse(seed.delivered_missing([delivered_row(days_ago=6.9)], NOW))

    def test_window_boundary_is_inclusive(self):
        # 백엔드 ReturnWindowPolicy: 기한 시각(배송완료 + 7일) 자체는 창 안
        self.assertFalse(seed.delivered_missing([delivered_row(days_ago=seed.RETURN_WINDOW_DAYS)], NOW))

    def test_missing_when_window_passed(self):
        self.assertTrue(seed.delivered_missing([delivered_row(days_ago=7.01)], NOW))

    def test_missing_when_item_has_active_claim(self):
        self.assertTrue(seed.delivered_missing([delivered_row(active_claim=1)], NOW))

    def test_missing_when_item_not_delivered_status(self):
        self.assertTrue(seed.delivered_missing([delivered_row(item_status="CONFIRMED")], NOW))


class InquiryConditionTest(unittest.TestCase):

    def test_all_missing_when_no_inquiry(self):
        self.assertEqual(seed.INQUIRY_CONDITIONS, seed.missing_inquiry_conditions([]))

    def test_all_satisfied(self):
        rows = [inquiry(answered=1, checked=1), inquiry(answered=1, checked=0), inquiry(has_order=1)]
        self.assertEqual([], seed.missing_inquiry_conditions(rows))

    def test_checked_answer_does_not_count_as_unread(self):
        # 외부인이 미확인 답변을 확인하면 ②가 다시 부족해진다
        rows = [inquiry(answered=1, checked=1), inquiry(has_order=1)]
        self.assertEqual(["answered_unread"], seed.missing_inquiry_conditions(rows))

    def test_plan_attaches_order_to_first_created(self):
        plans = seed.plan_inquiries(["answered_unread", "unanswered", "order_attached"])
        self.assertEqual([("answered_unread", True), ("unanswered", False)], plans)

    def test_plan_creates_attached_unanswered_when_only_order_missing(self):
        self.assertEqual([("unanswered", True)], seed.plan_inquiries(["order_attached"]))

    def test_plan_empty_when_nothing_missing(self):
        self.assertEqual([], seed.plan_inquiries([]))

    def test_templates_fit_server_limits(self):
        for kind, (category, content, answer) in seed.INQUIRY_TEMPLATES.items():
            with self.subTest(kind=kind):
                self.assertIn(category, ["ORDER_PAYMENT", "DELIVERY", "CLAIM", "ACCOUNT", "OTHER"])
                self.assertTrue(5 <= len(content.strip()) <= 500)
                self.assertTrue(answer is None or 1 <= len(answer) <= 1000)


class QnaConditionTest(unittest.TestCase):

    def test_all_missing_when_empty(self):
        self.assertEqual(seed.QNA_CONDITIONS, seed.missing_qna_conditions([], [None, "한 문장 설명"]))

    def test_all_satisfied(self):
        questions = [question(answered=1), question(), question(status="HIDDEN")]
        self.assertEqual([], seed.missing_qna_conditions(questions, [MULTI]))

    def test_hidden_answered_question_does_not_count_as_answered(self):
        questions = [question(status="HIDDEN", answered=1), question()]
        self.assertEqual(["answered"], seed.missing_qna_conditions(questions, [MULTI]))

    def test_description_fragments_follow_server_split_rule(self):
        self.assertEqual(["첫 줄", "둘째 줄"], seed.description_fragments("첫 줄\r\n\n둘째 줄"))
        # 문장부호 뒤 공백에서만 자른다(3.5처럼 공백 없는 마침표는 유지)
        self.assertEqual(["가격은 3.5만원 좋아요!", "정말?"], seed.description_fragments("가격은 3.5만원 좋아요! 정말?"))
        self.assertEqual(1, len(seed.description_fragments("{name} — 데모 시드 상품입니다.")))

    def test_seeded_description_is_multi_sentence(self):
        self.assertGreaterEqual(len(seed.description_fragments(seed.QNA_PRODUCT_DESCRIPTION)), 2)

    def test_templates_fit_server_limits(self):
        for kind, (content, answer) in seed.QNA_TEMPLATES.items():
            with self.subTest(kind=kind):
                self.assertTrue(5 <= len(content.strip()) <= 500)
                self.assertTrue(answer is None or 1 <= len(answer) <= 1000)
        self.assertTrue(1 <= len(seed.QNA_HIDDEN_REASON) <= 200)


if __name__ == "__main__":
    unittest.main()
