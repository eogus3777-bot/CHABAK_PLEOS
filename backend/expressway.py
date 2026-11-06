"""Local Korea Expressway Corporation API gateway for CHABAK."""

import argparse
import json
import os
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, unquote, urlencode, urlsplit
from urllib.request import Request, urlopen

INFO_URL = "https://data.ex.co.kr/openapi/locationinfo/locationinfoRest"
FOOD_URL = "https://data.ex.co.kr/openapi/restinfo/restBestfoodList"
SOURCE = "한국도로공사"
CODE = re.compile(r"[A-Za-z0-9_-]{1,32}")
DIRECTION_IN_NAME = re.compile(r"\(([^()]*)\)\s*$")
FOOD_CACHE_TTL = 15 * 60
_food_cache = {}
_food_lock = Lock()


class ProviderError(Exception):
    def __init__(self, reason, *, http_status=None, provider_code=None):
        self.reason = reason
        self.http_status = http_status
        self.provider_code = provider_code
        super().__init__(reason)

    def diagnostic(self):
        result = {"status": self.reason}
        if self.http_status is not None:
            result["httpStatus"] = self.http_status
        if self.provider_code is not None:
            result["providerCode"] = self.provider_code
        return result


def key_for(dataset):
    return (os.environ.get(f"CHABAK_EXPRESSWAY_{dataset}_KEY", "").strip()
            or os.environ.get("CHABAK_EXPRESSWAY_API_KEY", "").strip())


def official(url, key, parameter, **params):
    if not key:
        raise ProviderError("key_missing")
    query = urlencode({"type": "json", parameter: unquote(key), **params})
    req = Request(url + "?" + query, headers={"Accept": "application/json"})
    try:
        with urlopen(req, timeout=10) as response:
            payload = json.loads(response.read(8 * 1024 * 1024))
    except HTTPError as error:
        with error:
            raise ProviderError("provider_http_error", http_status=error.code) from None
    except (URLError, OSError):
        raise ProviderError("provider_unavailable") from None
    except ValueError:
        raise ProviderError("invalid_response") from None
    if not isinstance(payload, dict):
        raise ProviderError("invalid_response")
    if payload.get("code") != "SUCCESS":
        # Preserve only numeric codes; upstream text can contain request details.
        code = field(payload, "code")
        raise ProviderError("provider_rejected", provider_code=code if re.fullmatch(r"[0-9]{1,6}", code) else None)
    rows = payload.get("list")
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise ProviderError("invalid_response")
    return payload, rows


def field(row, key):
    value = row.get(key)
    return str(value).strip() if isinstance(value, (str, int, float)) else ""


def normalize_direction(value):
    return re.sub(r"\s+", "", value).removesuffix("방향")


def position(row):
    try:
        lat, lon = float(row["yValue"]), float(row["xValue"])
    except (KeyError, TypeError, ValueError):
        return None
    if 32 <= lat <= 40 and 123 <= lon <= 133:
        return {"latitude": lat, "longitude": lon}
    return None


def area_from(rows, code, road_number, direction):
    matches = []
    for row in rows:
        if field(row, "stdRestCd") != code or field(row, "routeNo") not in ("", road_number):
            continue
        name = field(row, "unitName")
        road = field(row, "routeName")
        explicit = field(row, "direction") or field(row, "roadRouteDrc")
        suffix = DIRECTION_IN_NAME.search(name)
        label = explicit or (suffix.group(1) if suffix else "")
        if name and road and label and normalize_direction(label) == normalize_direction(direction):
            matches.append({
                "key": {"code": code, "roadNumber": road_number, "direction": direction},
                "name": name, "roadName": road, "point": position(row)
            })
    return matches[0] if len(matches) == 1 else None


def representative_food(rows, code):
    result = {}
    for row in rows:
        if field(row, "stdRestCd") != code or field(row, "bestfoodyn").upper() != "Y":
            continue
        name = field(row, "foodNm")
        value = field(row, "foodCost").replace(",", "")
        price = int(value) if re.fullmatch(r"[0-9]{1,9}", value) else 0
        if name:
            result[(name, price or None)] = {"name": name, "priceWon": price or None}
    return list(result.values())


def foods_by_code():
    now = time.monotonic()
    with _food_lock:
        if _food_cache and now - _food_cache["checked"] < FOOD_CACHE_TTL:
            return _food_cache["rows"]
        rows = []
        for page in range(1, 31):
            payload, chunk = official(FOOD_URL, key_for("FOOD"), "serviceKey", numOfRows=1000, pageNo=page)
            rows.extend(chunk)
            count = payload.get("count")
            if not chunk or (str(count).isdigit() and len(rows) >= int(count)):
                _food_cache.update(checked=time.monotonic(), rows=rows)
                return rows
        raise ProviderError("incomplete_food_catalog")


def find_rest_area(code, road_number, direction):
    _, rows = official(INFO_URL, key_for("INFO"), "key", stdRestCd=code, routeNo=road_number)
    area = area_from(rows, code, road_number, direction)
    if not area:
        return None
    try:
        area["foods"] = representative_food(foods_by_code(), code)
    except ProviderError:
        area["foods"] = []
    area["collectedAtMillis"] = int(time.time() * 1000)
    return area


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def reply(self, status, body):
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        url = urlsplit(self.path)
        if url.path == "/health":
            self.reply(200, {"source": SOURCE, "infoConfigured": bool(key_for("INFO")), "foodConfigured": bool(key_for("FOOD"))})
            return
        if url.path != "/restareas":
            self.reply(404, {"error": "not_found"})
            return
        params = parse_qs(url.query)
        code, road, direction = (params.get(name, [""])[0].strip() for name in ("code", "roadNumber", "direction"))
        if not CODE.fullmatch(code) or not CODE.fullmatch(road) or not 1 <= len(direction) <= 24:
            self.reply(400, {"error": "invalid_request"})
            return
        try:
            area = find_rest_area(code, road, direction)
        except ProviderError as error:
            self.reply(503, {"error": error.reason})
            return
        self.reply(200, {"source": SOURCE, "area": area})


def check():
    results = {}
    for name, url, param in (("INFO", INFO_URL, "key"), ("FOOD", FOOD_URL, "serviceKey")):
        try:
            _, rows = official(url, key_for(name), param, numOfRows=1, pageNo=1)
            results[name] = {"status": "success", "records": len(rows)}
        except ProviderError as error:
            results[name] = error.diagnostic()
    print(json.dumps(results))
    return 0 if all(result["status"] == "success" for result in results.values()) else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check official APIs and print statuses only")
    args = parser.parse_args()
    if args.check:
        raise SystemExit(check())
    ThreadingHTTPServer(("127.0.0.1", 8086), Handler).serve_forever()
