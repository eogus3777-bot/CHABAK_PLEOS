"""Refresh public official snapshots atomically. No credentials or geocoded estimates."""
import csv
import hashlib
import html
import io
import json
import os
from pathlib import Path
import re
import tempfile
from datetime import datetime, timezone
from urllib.parse import urlencode
from urllib.request import Request, urlopen

MARKET_URL = "https://www.data.go.kr/data/15012894/standard.do"
BREWERY_URL = "https://www.data.go.kr/data/15048756/fileData.do"
LOCATION_URL = "https://www.pocheon.go.kr/ktour/viewTursmResrceWeb.do?firstIndex=0&key=9750&lastIndex=30&rcpp=30&resrceNo=1858&sc1=ktour&sc2=71"
OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/trip_places.json"


def fetch(url, data=None):
    request = Request(url, data=data, headers={"User-Agent": "Mozilla/5.0", "Referer": BREWERY_URL})
    with urlopen(request, timeout=25) as response:
        return response.read(8 * 1024 * 1024)


def periodic_days(rule):
    if not re.fullmatch(r"[0-9\s일월매,+·ㆍ/및]+", rule):
        return []
    days = sorted(set(map(int, re.findall(r"\d+", rule))))
    if len(days) == 2 and 1 <= days[0] <= 5 and days[1] == days[0] + 5:
        return sorted(day + offset for day in days for offset in (0, 10, 20))
    if len(days) >= 3 and all(1 <= day <= 31 for day in days):
        return days
    return []


def cell_text(value):
    return html.unescape(re.sub(r"<[^>]+>", "", value)).strip()


def markets_from_page(page):
    result = []
    for row in re.findall(r'<tr class="contentsTr">(.*?)</tr>', page, re.S):
        cells = [cell_text(cell) for cell in re.findall(r"<td[^>]*>(.*?)</td>", row, re.S)]
        if len(cells) < 16:
            continue
        days = periodic_days(cells[4])
        if not days:
            continue
        try:
            lat, lon = float(cells[5]), float(cells[6])
        except ValueError:
            continue
        if not (32 <= lat <= 40 and 123 <= lon <= 133):
            continue
        identity = hashlib.sha256((cells[0] + "|" + (cells[2] or cells[3])).encode()).hexdigest()[:16]
        result.append({
            "id": "market_" + identity, "category": "MARKET", "name": cells[0],
            "address": cells[2] or cells[3], "latitude": lat, "longitude": lon,
            "sourceUrl": MARKET_URL, "source": "소상공인시장진흥공단 전통시장 표준데이터",
            "sourceDate": cells[15], "marketRule": cells[4], "marketDays": days,
            "phone": cells[14], "program": "", "hours": "", "reservation": ""
        })
    return list({row["id"]: row for row in result}.values())


def breweries_from_csv(raw, location_page):
    for encoding in ("utf-8-sig", "cp949"):
        try:
            text = raw.decode(encoding)
            break
        except UnicodeDecodeError:
            continue
    else:
        raise ValueError("Unknown brewery CSV encoding")
    rows = list(csv.DictReader(io.StringIO(text)))
    if len(rows) != 64 or not rows or "상호명" not in rows[0]:
        raise ValueError("The official brewery snapshot layout or row count changed")
    coordinates = re.search(r'data-latitude="([0-9.]+)" data-longtitude="([0-9.]+)"', location_page)
    if not coordinates or "산사원" not in location_page or "화동로432번길25" not in re.sub(r"\s+", "", cell_text(location_page)):
        raise ValueError("Cannot verify the official Sansawon visitor location")
    result = []
    for row in rows:
        identity = hashlib.sha256((row["상호명"] + row["주소"]).encode()).hexdigest()[:16]
        entry = {
            "id": "brewery_" + identity, "category": "BREWERY",
            "name": row["상호명"], "address": row["주소"], "latitude": None, "longitude": None,
            "sourceUrl": BREWERY_URL, "source": "한국농수산식품유통공사 찾아가는양조장정보",
            "sourceDate": "2026-07-31", "marketRule": "", "marketDays": [],
            "hours": "", "program": "", "phone": "",
            "reservation": "상시 방문 " + visit_status(row.get("양조장상시방문가능여부", "")) +
                           " · 예약 방문 " + visit_status(row.get("양조장예약방문가능여부", ""))
        }
        if "배상면주가" in entry["name"] and "포천" in entry["address"]:
            entry.update(name="배상면주가 산사원", address="경기도 포천시 화현면 화동로432번길 25",
                         latitude=float(coordinates[1]), longitude=float(coordinates[2]),
                         locationSourceUrl=LOCATION_URL, program="전통술 전시·관람 등록 · 현재 체험 운영 확인 필요")
        result.append(entry)
    return result


def visit_status(value):
    return {"Y": "가능 등록", "N": "불가 등록"}.get(value.strip().upper(), "확인 필요")


def refresh():
    markets_raw = fetch(MARKET_URL)
    brewery_page = fetch(BREWERY_URL).decode("utf-8")
    detail = re.search(r'const detailPk = "([^"]+)"', brewery_page)
    if not detail:
        raise ValueError("Official brewery dataset identifier is unavailable")
    parameters = {
        "publicDataDetailPk": detail[1], "publicDataPk": "15048756", "atchFileId": "",
        "fileDetailSn": "1", "publicDataTyCode": "PR0051"
    }
    meta = json.loads(fetch("https://www.data.go.kr/tcs/dss/selectFileDataDownload.do", urlencode(parameters).encode()))
    if meta.get("status") is not True:
        raise ValueError("Official download requires approval")
    file_query = urlencode({"atchFileId": meta["atchFileId"], "fileDetailSn": meta["fileDetailSn"]})
    limit = json.loads(fetch("https://www.data.go.kr/cmm/cmm/check-limit.json", file_query.encode()))
    if limit.get("needCaptcha") is not False:
        raise ValueError("Official download requires verification")
    breweries_raw = fetch("https://www.data.go.kr/cmm/cmm/fileDownload.do?" + file_query)
    location_raw = fetch(LOCATION_URL)
    markets = markets_from_page(markets_raw.decode("utf-8"))
    breweries = breweries_from_csv(breweries_raw, location_raw.decode("utf-8"))
    if not markets or sum(row["latitude"] is not None for row in breweries) != 1:
        raise ValueError("Official snapshots are incomplete")
    payload = {
        "schemaVersion": 1, "collectedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "coverage": "전통시장 표준 페이지 499개 중 주기·좌표 확인 항목과 양조장 64개. 양조장 좌표 검증은 산사원 1곳.",
        "breweryLicense": "이용허락범위 제한 없음 · 한국농수산식품유통공사 15048756",
        "marketLicenseUrl": "https://www.data.go.kr/ugs/selectPortalPolicyView.do",
        "marketDayPolicy": "끝자리 두 개 규칙은 30일까지 확장. 31일은 명시된 날짜 목록에만 포함. 실제 휴장 미확인.",
        "sourceSha256": {name: hashlib.sha256(raw).hexdigest() for name, raw in
                         (("markets", markets_raw), ("breweries", breweries_raw), ("location", location_raw))},
        "places": markets + breweries
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=OUTPUT.parent, delete=False) as temp:
        json.dump(payload, temp, ensure_ascii=False, indent=2)
        temporary = temp.name
    os.replace(temporary, OUTPUT)
    print(json.dumps({"markets": len(markets), "breweries": len(breweries), "verifiedBreweryCoordinates": 1}))


if __name__ == "__main__":
    refresh()
