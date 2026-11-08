"""Local gateway for the Korea Tourism Organization's GoCamping API."""

import json
import os
import re
import time
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, unquote, urlencode, urlsplit
from urllib.request import urlopen

SOURCE = "한국관광공사 고캠핑"
API_URL = "https://apis.data.go.kr/B551011/GoCamping/basedList"
PAGE_SIZE = 1000
CACHE_SECONDS = 15 * 60
REGIONS = {
    "경기": ("경기", "인천", "서울"),
    "강원": ("강원",),
    "충청": ("충북", "충남", "충청", "대전", "세종"),
    "경상": ("경북", "경남", "경상", "부산", "대구", "울산"),
    "전라": ("전북", "전남", "전라", "광주"),
    "제주": ("제주",),
}
TAGS = {
    "숲": ("숲", "산림", "임야"),
    "산": ("산",),
    "계곡": ("계곡",),
    "바다": ("해변", "해안", "바다", "섬"),
    "강": ("강", "하천", "강변"),
    "호수": ("호수", "호반", "저수지"),
}
SEPARATORS = re.compile(r"[,/|·;\s]+")
FACILITY_FILTERS = {
    "toilet": ("화장실 시설 등록", lambda camp: (camp["toilets"] or 0) > 0),
    "shower": ("샤워실 시설 등록", lambda camp: (camp["showers"] or 0) > 0),
    "sink": ("개수대 시설 등록", lambda camp: (camp["sinks"] or 0) > 0),
    "electric": ("전기 시설 등록", lambda camp: "전기" in camp["facilities"].split(",")),
    "store": ("매점·편의시설 등록", lambda camp: any(
        word in camp["facilities"] for word in ("마트", "편의점", "매점")
    )),
}


class ProviderError(Exception):
    def __init__(self, reason, status=502):
        super().__init__(reason)
        self.status = status


def service_key():
    return os.environ.get("GOCAMPING_SERVICE_KEY", "").strip()


def text(value):
    return value.strip() if isinstance(value, str) else ""


def count(value):
    value = str(value).strip()
    return int(value) if re.fullmatch(r"[0-9]{1,9}", value) else None


def public_url(value):
    value = text(value)
    try:
        url = urlsplit(value)
        if url.scheme in ("http", "https") and url.hostname and url.username is None:
            return value
    except ValueError:
        pass
    return ""


def coordinate(value, minimum, maximum):
    if isinstance(value, bool) or not isinstance(value, (str, int, float, Decimal)):
        return None
    raw = str(value).strip()
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?", raw):
        return None
    try:
        number = Decimal(raw)
    except InvalidOperation:
        return None
    return raw if number.is_finite() and minimum <= number <= maximum else None


def normalize(row):
    content_id = str(row.get("contentId", "")).strip()
    name = text(row.get("facltNm"))
    address = " ".join(filter(None, (text(row.get("addr1")), text(row.get("addr2")))))
    if not re.fullmatch(r"[0-9]+", content_id) or not name or not address:
        return None

    province = text(row.get("doNm")) or address.split()[0]
    region = next((name for name, prefixes in REGIONS.items() if province.startswith(prefixes)), province)
    raw_tags = SEPARATORS.split(text(row.get("lctCl")))
    tags = dict.fromkeys(tag for tag in raw_tags if tag)
    for tag, aliases in TAGS.items():
        if any(alias in raw_tags for alias in aliases):
            tags[tag] = None
    longitude = coordinate(row.get("mapX"), 123, 133)
    latitude = coordinate(row.get("mapY"), 32, 40)
    return {
        "id": f"gocamping_{content_id}",
        "mapX": longitude if latitude is not None else None,
        "mapY": latitude if longitude is not None else None,
        "name": name,
        "region": region,
        "district": text(row.get("sigunguNm")),
        "setting": ",".join(tags),
        "facilities": ",".join(part.strip() for part in text(row.get("sbrsCl")).split(",") if part.strip()),
        "address": address,
        "phone": text(row.get("tel")),
        "toilets": count(row.get("toiletCo")),
        "showers": count(row.get("swrmCo")),
        "sinks": count(row.get("wtrplCo")),
        "website": public_url(row.get("homepage")),
        "reservationUrl": public_url(row.get("resveUrl")),
        "type": text(row.get("induty")),
        "status": text(row.get("operSttus")),
        "onsiteEvCharger": None,
        "nearbyEvCharger": None,
        "vehicleStayAllowed": None,
    }


def fetch_page(key, page):
    params = {
        "serviceKey": unquote(key),
        "MobileOS": "AND",
        "MobileApp": "CHABAK",
        "_type": "json",
        "numOfRows": PAGE_SIZE,
        "pageNo": page,
    }
    try:
        with urlopen(f"{API_URL}?{urlencode(params)}", timeout=8) as response:
            payload = response.read()
    except (HTTPError, URLError, OSError):
        raise ProviderError("provider_unavailable") from None

    # The gateway can return an XML error document even for a JSON request.
    if payload.lstrip().startswith(b"<"):
        raise ProviderError("provider_rejected")
    try:
        response = json.loads(payload, parse_float=Decimal)["response"]
        if str(response["header"]["resultCode"]) not in ("00", "0000", "0"):
            raise ProviderError("provider_rejected")
        body = response["body"]
        total = count(body["totalCount"])
        items = body.get("items")
        rows = items.get("item", []) if isinstance(items, dict) else []
        if isinstance(rows, dict):
            rows = [rows]
        if total is None or not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
            raise ValueError
        return total, rows
    except (ValueError, KeyError, TypeError, AttributeError):
        raise ProviderError("invalid_provider_response") from None


def collect(key):
    camps = {}
    page = 1
    received = 0
    while True:
        total, rows = fetch_page(key, page)
        received += len(rows)
        if (not rows and (page > 1 or total > 0)) or (total == 0 and rows):
            raise ProviderError("incomplete_provider_response")
        unique_before_page = len(camps)
        for row in rows:
            camp = normalize(row)
            if camp:
                camps[camp["id"]] = camp
        if page > 1 and len(camps) == unique_before_page:
            raise ProviderError("incomplete_provider_response")
        if received >= total:
            if total and not camps:
                raise ProviderError("invalid_provider_response")
            return list(camps.values())
        page += 1


class Catalog:
    def __init__(self):
        self.lock = Lock()
        self.snapshot = None
        self.expires_at = 0

    def get(self):
        key = service_key()
        if not key:
            raise ProviderError("key_missing", 503)
        with self.lock:
            if self.snapshot is None or time.monotonic() >= self.expires_at:
                camps = collect(key)
                self.snapshot = (datetime.now(timezone.utc).isoformat(timespec="seconds"), camps)
                self.expires_at = time.monotonic() + CACHE_SECONDS
            return self.snapshot


def results(camps, params, recommend):
    def param(name, default=""):
        return params.get(name, [default])[0].strip()

    maximum = 5 if recommend else 150
    limit = param("limit", str(maximum))
    if not re.fullmatch(r"[0-9]{1,9}", limit) or int(limit) == 0:
        raise ValueError("invalid_limit")
    limit = min(int(limit), maximum)
    campsite_id = param("id")
    if campsite_id and not re.fullmatch(r"gocamping_[0-9]+", campsite_id):
        raise ValueError("invalid_id")
    region = param("region")
    district = param("district").casefold()
    nature = param("nature")
    keywords = param("q").casefold().split()
    required = [rule for name, rule in FACILITY_FILTERS.items() if param(name) == "1"]
    notices = []
    if param("quiet") == "1":
        notices.append("고캠핑 자료에서 소음 수준을 확인할 수 없어요.")
    if param("evCharging") == "1" or param("vehicleStayAllowed") == "1":
        notices.append("EV 충전 또는 차량 숙박 허용 여부를 확인할 수 없어요.")
        return [], notices

    matches = []
    for camp in camps:
        if campsite_id and camp["id"] != campsite_id:
            continue
        if region not in ("", "전국", camp["region"]):
            continue
        if district and district not in f'{camp["district"]} {camp["address"]}'.casefold():
            continue
        if nature and nature not in camp["setting"].split(","):
            continue
        searchable = " ".join(camp[field] for field in (
            "name", "region", "district", "address", "setting", "facilities", "type"
        )).casefold()
        if any(keyword not in searchable for keyword in keywords):
            continue
        if any(not check(camp) for _, check in required):
            continue
        matches.append(camp)

    if not recommend:
        return matches[:limit], notices

    # Prefer candidates with documented useful facilities and reservation links.
    def rank(camp):
        score = sum(check(camp) for _, check in FACILITY_FILTERS.values()) + bool(camp["reservationUrl"])
        return -score, camp["name"].casefold(), camp["id"]

    entries = []
    for camp in sorted(matches, key=rank)[:limit]:
        reasons = [f"{nature} 환경 등록"] if nature else []
        reasons.extend(label for label, check in FACILITY_FILTERS.values() if check(camp))
        if camp["reservationUrl"]:
            reasons.append("예약 링크 등록")
        entries.append({"place": camp, "reasons": reasons or ["고캠핑 등록 야영장"]})
    return entries, notices


class Handler(BaseHTTPRequestHandler):
    catalog = Catalog()

    def log_message(self, format, *args):
        # Keep request targets and upstream credentials out of server logs.
        pass

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlsplit(self.path)
        if url.path == "/health":
            self.send_json(200, {"ok": True, "status": "ok", "source": SOURCE, "keyConfigured": bool(service_key())})
            return
        if url.path not in ("/camps", "/recommend"):
            self.send_json(404, {"error": "not_found"})
            return

        try:
            params = parse_qs(url.query, max_num_fields=32)
            updated_at, camps = self.catalog.get()
            items, notices = results(camps, params, url.path == "/recommend")
        except ProviderError as error:
            self.send_json(error.status, {"error": str(error)})
            return
        except ValueError:
            self.send_json(400, {"error": "invalid_query"})
            return
        self.send_json(200, {"source": SOURCE, "updatedAt": updated_at, "items": items, "notices": notices})


if __name__ == "__main__":
    with ThreadingHTTPServer(("127.0.0.1", 8085), Handler) as server:
        print("GoCamping server listening on http://127.0.0.1:8085", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
