import unittest
from unittest.mock import patch
import refresh_trip_places as places
import expressway


class OfficialPlacesTest(unittest.TestCase):
    def test_periodic_days_exclude_implicit_thirty_first(self):
        self.assertEqual([1, 6, 11, 16, 21, 26], places.periodic_days("1일, 6일"))
        self.assertEqual([5, 10, 15, 20, 25, 30], places.periodic_days("5,10"))
        self.assertEqual([1, 11, 21, 31], places.periodic_days("1,11,21,31"))
        for raw in ("매일", "상설", "1,6 (휴일 제외)", "0,5", "1,32", "매주 토요일"):
            self.assertEqual([], places.periodic_days(raw))

    def test_market_coordinates_and_rules_must_be_verified(self):
        def row(lat, lon, rule):
            fields = ["시험시장", "상설장", "검증용 주소", "", rule, lat, lon] + [""] * 8 + ["2026-01-01"]
            return '<tr class="contentsTr">' + ''.join('<td>' + f + '</td>' for f in fields) + '</tr>'
        self.assertEqual([], places.markets_from_page(row("0", "0", "1,6")))
        self.assertEqual([], places.markets_from_page(row("127", "37", "1,6")))
        self.assertEqual([], places.markets_from_page(row("37", "127", "확인 필요")))
        valid = places.markets_from_page(row("37", "127", "1,6"))
        self.assertEqual(37.0, valid[0]["latitude"])
        self.assertEqual(127.0, valid[0]["longitude"])

    def test_food_failure_is_distinct_from_an_empty_official_catalog(self):
        info = {"stdRestCd": "123", "routeNo": "10", "unitName": "시험(서울)", "routeName": "시험선"}
        with patch("expressway.official", return_value=({}, [info])), patch("expressway.foods_by_code", return_value=[]):
            self.assertEqual("empty", expressway.find_rest_area("123", "10", "서울")["foodStatus"])
        with patch("expressway.official", return_value=({}, [info])), patch("expressway.foods_by_code", side_effect=expressway.ProviderError("provider_rejected", provider_code="30")):
            area = expressway.find_rest_area("123", "10", "서울")
            self.assertEqual("error", area["foodStatus"])
            self.assertEqual({"status": "provider_rejected", "providerCode": "30"}, area["foodError"])
            self.assertEqual([], area["foods"])

    def test_brewery_file_format_changes_fail_closed(self):
        with self.assertRaises(ValueError):
            places.breweries_from_csv(b"bad\nformat", "")

    def test_blank_brewery_visit_flags_are_explicitly_unconfirmed(self):
        self.assertEqual("확인 필요", places.visit_status(""))
        self.assertEqual("확인 필요", places.visit_status("unknown"))
        self.assertEqual("가능 등록", places.visit_status("Y"))
        self.assertEqual("불가 등록", places.visit_status("N"))
