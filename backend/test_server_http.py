"""Isolated HTTP failures. These tests never contact or stop the running 8085 service."""
import io
import json
import unittest
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing, contextmanager
from http.client import HTTPConnection
from threading import Thread
from unittest.mock import Mock, patch
from urllib.error import URLError

import server
from test_gocamping import official_camp


@contextmanager
def isolated_gateway(catalog):
    class TestHandler(server.Handler):
        pass

    TestHandler.catalog = catalog
    httpd = server.ThreadingHTTPServer(("127.0.0.1", 0), TestHandler)
    thread = Thread(target=httpd.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
    thread.start()
    try:
        yield httpd.server_address[1]
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=2)


def request(port, path):
    with closing(HTTPConnection("127.0.0.1", port, timeout=2)) as client:
        client.request("GET", path)
        response = client.getresponse()
        return response.status, json.loads(response.read())


class GatewayHttpTest(unittest.TestCase):
    def test_provider_failures_return_errors_without_items(self):
        for reason, status in (("key_missing", 503), ("provider_rejected", 502), ("provider_unavailable", 502)):
            with self.subTest(reason=reason):
                catalog = Mock()
                catalog.get.side_effect = server.ProviderError(reason, status)
                with isolated_gateway(catalog) as port:
                    actual, body = request(port, "/recommend")
                self.assertEqual(status, actual)
                self.assertEqual({"error": reason}, body)

    def test_invalid_query_empty_result_and_unknown_path(self):
        catalog = Mock()
        catalog.get.return_value = ("fixture-time", [server.normalize(official_camp(1))])
        with isolated_gateway(catalog) as port:
            self.assertEqual((400, {"error": "invalid_query"}), request(port, "/camps?limit=0"))
            status, body = request(port, "/recommend?q=will-not-match")
            self.assertEqual(200, status)
            self.assertEqual([], body["items"])
            self.assertEqual((404, {"error": "not_found"}), request(port, "/unknown"))

    def test_transport_timeout_and_malformed_provider_data(self):
        for failure in (TimeoutError(), URLError("fixture offline")):
            with self.subTest(failure=type(failure).__name__):
                with patch.object(server, "urlopen", side_effect=failure):
                    with self.assertRaisesRegex(server.ProviderError, "^provider_unavailable$"):
                        server.fetch_page("fixture-placeholder", 1)
        with patch.object(server, "urlopen", return_value=io.BytesIO(b"invalid json")):
            with self.assertRaisesRegex(server.ProviderError, "^invalid_provider_response$"):
                server.fetch_page("fixture-placeholder", 1)

    def test_concurrent_cache_reads_collect_once(self):
        catalog = server.Catalog()
        fixture = [server.normalize(official_camp(1))]
        with patch.object(server, "service_key", return_value="fixture-placeholder"), patch.object(
            server, "collect", return_value=fixture
        ) as collect:
            with ThreadPoolExecutor(max_workers=4) as executor:
                results = list(executor.map(lambda _: catalog.get(), range(12)))
        self.assertEqual(1, collect.call_count)
        self.assertTrue(all(result == results[0] for result in results))
