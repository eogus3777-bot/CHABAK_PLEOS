import io
import unittest
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit
from unittest.mock import patch

import expressway


class ExpresswayTest(unittest.TestCase):
    def test_direction_and_unique_station(self):
        row = {"stdRestCd": "00123", "unitCode": "10023", "routeNo": "0010", "unitName": "시험휴게소(서울방향)",
               "routeName": "시험선", "xValue": "127.1", "yValue": "37.3"}
        area = expressway.area_from([row], "00123", "0010", "서울")
        self.assertEqual(area["point"], {"latitude": 37.3, "longitude": 127.1})
        self.assertIsNone(expressway.area_from([row], "00123", "0010", "부산"))
        self.assertIsNone(expressway.area_from([row, row], "00123", "0010", "서울"))
        self.assertIsNone(expressway.area_from([row], "00124", "0010", "서울"))
        self.assertIsNone(expressway.area_from([row], "10023", "0010", "서울"))

    def test_food_keeps_only_officially_marked_items_for_the_exact_code(self):
        rows = [
            {"stdRestCd": "00123", "bestfoodyn": "Y", "foodNm": "메뉴 A", "foodCost": "7,500"},
            {"stdRestCd": "00123", "bestfoodyn": "Y", "foodNm": "메뉴 B", "foodCost": ""},
            {"stdRestCd": "00123", "bestfoodyn": "N", "foodNm": "일반 메뉴", "foodCost": "9000"},
            {"stdRestCd": "00124", "bestfoodyn": "Y", "foodNm": "반대 방향", "foodCost": "5000"},
        ]
        self.assertEqual(expressway.representative_food(rows, "00123"), [
            {"name": "메뉴 A", "priceWon": 7500}, {"name": "메뉴 B", "priceWon": None}
        ])

    def test_missing_key_blocks_upstream_request(self):
        with patch("expressway.urlopen") as request:
            with self.assertRaisesRegex(expressway.ProviderError, "key_missing"):
                expressway.official(expressway.INFO_URL, "", "key")
            request.assert_not_called()

    def test_http_and_provider_errors_are_distinct(self):
        error = HTTPError(expressway.FOOD_URL, 403, "Forbidden", {}, io.BytesIO(b""))
        with patch("expressway.urlopen", side_effect=error):
            with self.assertRaises(expressway.ProviderError) as raised:
                expressway.official(expressway.FOOD_URL, "fixture", "serviceKey")
        self.assertEqual(raised.exception.diagnostic(), {"status": "provider_http_error", "httpStatus": 403})

        with patch("expressway.urlopen", return_value=io.BytesIO(b'{"code":"30","message":"private"}')) as request:
            with self.assertRaises(expressway.ProviderError) as raised:
                expressway.official(expressway.FOOD_URL, "fixture%2Bkey", "serviceKey", pageNo=1)
        self.assertEqual(raised.exception.diagnostic(), {"status": "provider_rejected", "providerCode": "30"})
        query = parse_qs(urlsplit(request.call_args.args[0].full_url).query)
        self.assertEqual(query["serviceKey"], ["fixture+key"])

    def test_unexpected_provider_content_is_invalid(self):
        with patch("expressway.urlopen", return_value=io.BytesIO(b"<html>unexpected</html>")):
            with self.assertRaisesRegex(expressway.ProviderError, "invalid_response"):
                expressway.official(expressway.INFO_URL, "fixture", "key")
