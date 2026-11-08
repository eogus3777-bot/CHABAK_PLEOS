import unittest
from unittest.mock import patch

import server


def official_camp(index, **fields):
    return {
        "contentId": str(index),
        "facltNm": f"테스트 캠핑장 {index}",
        "doNm": "경기도",
        "sigunguNm": "가평군",
        "addr1": "경기도 가평군",
        "lctCl": "숲,산",
        "sbrsCl": "전기,매점",
        "toiletCo": "2",
        "swrmCo": "1",
        "wtrplCo": "0",
        "resveUrl": "https://example.invalid/reserve",
        **fields,
    }


class GoCampingTest(unittest.TestCase):
    def test_recommendation_enforces_registered_facilities_and_limit(self):
        camps = [server.normalize(official_camp(index)) for index in range(1, 8)]
        excluded = (
            official_camp(8, sbrsCl="전기차 충전소"),
            official_camp(9, swrmCo=""),
            official_camp(10, sigunguNm="양평군", addr1="경기도 양평군"),
            official_camp(11, lctCl="바다"),
        )
        camps.extend(map(server.normalize, excluded))
        query = {key: [value] for key, value in {
            "region": "경기", "district": "가평", "nature": "숲",
            "electric": "1", "toilet": "1", "shower": "1", "limit": "20",
        }.items()}
        matches, notices = server.results(camps, query, False)
        self.assertEqual(len(matches), 7)
        self.assertFalse(notices)
        recommendations, _ = server.results(camps, query, True)
        self.assertEqual(len(recommendations), 5)
        for entry in recommendations:
            self.assertIn("샤워실 시설 등록", entry["reasons"])
            camp = entry["place"]
            self.assertEqual((camp["toilets"], camp["showers"], camp["sinks"]), (2, 1, 0))
            self.assertIsNone(camp["onsiteEvCharger"])
            self.assertIsNone(camp["nearbyEvCharger"])
            self.assertIsNone(camp["vehicleStayAllowed"])

    def test_coordinates_keep_official_precision_and_support_exact_id_lookup(self):
        x, y = "127.123456789012345678", "37.123456789012345678"
        place = server.normalize(official_camp(5, mapX=x, mapY=y))
        self.assertEqual((place["mapX"], place["mapY"]), (x, y))
        other = server.normalize(official_camp(50))
        items, _ = server.results([other, place], {"id": ["gocamping_5"], "limit": ["1"]}, False)
        self.assertEqual(items, [place])
        items, _ = server.results([other, place], {"id": ["gocamping_5"]}, True)
        self.assertEqual(items[0]["place"], place)
        for bad_x, bad_y in [(y, x), ("0", y), (x, None), ("Infinity", y), (True, y), ("1_27", y)]:
            camp = server.normalize(official_camp(5, mapX=bad_x, mapY=bad_y))
            self.assertIsNone(camp["mapX"])
            self.assertIsNone(camp["mapY"])

    def test_repeated_later_page_is_not_published_as_complete(self):
        row = official_camp(1)
        with patch.object(server, "fetch_page", side_effect=[(2, [row]), (2, [row])]):
            with self.assertRaisesRegex(server.ProviderError, "incomplete_provider_response"):
                server.collect("fixture")

    def test_refresh_failure_does_not_publish_cached_or_fake_recommendations(self):
        catalog = server.Catalog()
        catalog.snapshot = ("old", [server.normalize(official_camp(1))])
        with patch.object(server, "service_key", return_value="fixture"), patch.object(
            server, "collect", side_effect=server.ProviderError("provider_unavailable")
        ):
            with self.assertRaisesRegex(server.ProviderError, "provider_unavailable"):
                catalog.get()
