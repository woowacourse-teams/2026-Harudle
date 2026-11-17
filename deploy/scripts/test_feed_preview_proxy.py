"""Exercise the actual frontend Nginx with a local stub, never dev or prod.

Docker is required: a missing/unresponsive engine fails this required CI check.
The runtime image is taken from frontend/Dockerfile. Only the upstream authority
is replaced in the test copy, so a random-port Python server can replace backend.
"""

import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid


ROOT = Path(__file__).resolve().parents[2]
FEED_ID = "123e4567-e89b-42d3-a456-426614174000"
SPA = b"<!doctype html><title>SPA fixture</title>original React app shell"
JPEG = b"\xff\xd8proxy-image-fixture\xff\xd9"


class BackendStub(BaseHTTPRequestHandler):
    def do_GET(self):
        self.reply()

    def do_HEAD(self):
        self.reply(head=True)

    def reply(self, head=False):
        image = self.path.split("?", 1)[0].endswith("/image")
        body = JPEG if image else json.dumps({
            "path": self.path,
            "method": self.command,
            "host": self.headers.get("Host"),
            "realIp": self.headers.get("X-Real-IP"),
            "forwardedFor": self.headers.get("X-Forwarded-For"),
            "forwardedHost": self.headers.get("X-Forwarded-Host"),
            "forwardedProto": self.headers.get("X-Forwarded-Proto"),
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg" if image else "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Stub-Method", self.command)
        self.send_header("X-Stub-Path", self.path)
        self.end_headers()
        if not head:
            self.wfile.write(body)

    def log_message(self, *_args):
        pass


class FeedPreviewProxyTest(unittest.TestCase):
    @classmethod
    def docker(cls, *arguments, timeout=10, check=True):
        result = subprocess.run(
            ["docker", *arguments], text=True, capture_output=True, timeout=timeout
        )
        if check and result.returncode:
            raise RuntimeError(f"docker {' '.join(arguments[:2])}: {result.stderr[-4000:]}")
        return result

    @classmethod
    def setUpClass(cls):
        cls.directory = None
        cls.server = None
        cls.thread = None
        cls.container = None
        cls.addClassCleanup(cls.cleanup)
        if shutil.which("docker") is None:
            raise RuntimeError("Docker is required for the feed preview proxy test.")
        cls.docker("info", "--format", "{{.ServerVersion}}", timeout=8)
        dockerfile = (ROOT / "frontend/Dockerfile").read_text()
        runtime = re.search(r"^FROM\s+(\S+)\s+AS\s+runtime\s*$", dockerfile, re.M | re.I)
        if runtime is None:
            raise RuntimeError("frontend/Dockerfile must identify its Nginx runtime image.")
        image = runtime.group(1)
        if cls.docker("image", "inspect", image, check=False).returncode:
            cls.docker("pull", image, timeout=90)

        cls.server = ThreadingHTTPServer(("0.0.0.0", 0), BackendStub)
        cls.server.daemon_threads = True
        cls.thread = threading.Thread(
            target=cls.server.serve_forever, kwargs={"poll_interval": 0.1}, daemon=True
        )
        cls.thread.start()
        cls.directory = tempfile.TemporaryDirectory(prefix="harudle-feed-preview-proxy-")
        directory = Path(cls.directory.name)
        html = directory / "html"
        html.mkdir()
        (html / "index.html").write_bytes(SPA)
        configuration = directory / "default.conf"
        configuration.write_text(
            (ROOT / "frontend/nginx/default.conf").read_text().replace(
                "backend:8080", f"host.docker.internal:{cls.server.server_port}"
            )
        )
        cls.container = f"harudle-feed-preview-{uuid.uuid4().hex}"
        host = ["--add-host", "host.docker.internal:host-gateway"] if sys.platform == "linux" else []
        cls.docker(
            "run", "--detach", "--pull=never", "--name", cls.container,
            "--publish", "127.0.0.1::80", *host,
            "--volume", f"{configuration}:/etc/nginx/conf.d/default.conf:ro",
            "--volume", f"{html}:/usr/share/nginx/html:ro", image,
            timeout=20,
        )
        cls.docker("exec", cls.container, "nginx", "-t")
        address = cls.docker("port", cls.container, "80/tcp").stdout.strip().splitlines()[0]
        cls.port = int(address.rsplit(":", 1)[1])
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                if cls.fetch("/health")[0] == 200:
                    return
            except (OSError, http.client.HTTPException):
                pass
            time.sleep(0.1)
        logs = cls.docker("logs", cls.container, check=False).stdout[-4000:]
        raise RuntimeError(f"Nginx did not become ready: {logs}")

    @classmethod
    def cleanup(cls):
        try:
            if cls.container is not None:
                result = cls.docker("rm", "--force", cls.container, timeout=10, check=False)
                if result.returncode and "No such container" not in result.stderr:
                    raise RuntimeError("The Nginx fixture container could not be removed.")
        finally:
            try:
                if cls.server is not None:
                    if cls.thread is not None and cls.thread.is_alive():
                        cls.server.shutdown()
                    cls.server.server_close()
                if cls.thread is not None:
                    cls.thread.join(timeout=2)
                    if cls.thread.is_alive():
                        raise RuntimeError("The backend stub did not stop.")
            finally:
                if cls.directory is not None:
                    cls.directory.cleanup()

    @classmethod
    def fetch(cls, path, user_agent="Mozilla/5.0", method="GET", extra_headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", cls.port, timeout=2)
        try:
            headers = {"Host": "www.harudle.com", "User-Agent": user_agent}
            headers.update(extra_headers or {})
            connection.request(method, path, headers=headers)
            response = connection.getresponse()
            response_headers = {}
            for name, value in response.getheaders():
                key = name.lower()
                response_headers[key] = ", ".join(filter(None, [response_headers.get(key), value]))
            return response.status, response_headers, response.read()
        finally:
            connection.close()

    def test_known_preview_collectors_use_backend(self):
        for agent in (
            "kakaotalk-scrap/1.0", "facebookexternalhit/1.1", "Facebot",
            "Twitterbot/1.0", "Slackbot-LinkExpanding 1.0", "Discordbot/2.0",
            "TelegramBot (like TwitterBot)", "LinkedInBot/1.0", "Pinterestbot/1.0",
            "Line-Poker/1.0", "WhatsApp/2.24",
        ):
            with self.subTest(user_agent=agent):
                status, headers, body = self.fetch(f"/feeds/{FEED_ID}", agent)
                self.assertEqual(status, 200)
                self.assertEqual(json.loads(body)["path"], f"/api/v1/public/feeds/{FEED_ID}")
                self.assertIn("User-Agent", headers.get("vary", ""))
                self.assertEqual(headers.get("cache-control"), "no-store")

    def test_browser_kakao_webview_and_search_bots_keep_spa_and_vary(self):
        for agent in (
            "Mozilla/5.0 Chrome/130.0 Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14) KAKAOTALK 26.11.0 (INAPP)",
            "Googlebot/2.1 (+http://www.google.com/bot.html)", "bingbot/2.0",
        ):
            with self.subTest(user_agent=agent):
                status, headers, body = self.fetch(f"/feeds/{FEED_ID}?utm_source=feed_share", agent)
                self.assertEqual(status, 200)
                self.assertEqual(body, SPA)
                self.assertIn("User-Agent", headers.get("vary", ""))

    def test_trailing_slash_original_query_and_forwarded_headers_survive_proxy(self):
        query = "?utm_source=feed_share&campaign=a%2Fb&empty="
        status, _headers, body = self.fetch(
            f"/feeds/{FEED_ID}/{query}", "kakaotalk-scrap/1.0",
            extra_headers={"X-Forwarded-Proto": "https", "X-Forwarded-For": "198.51.100.12"},
        )
        self.assertEqual(status, 200)
        upstream = json.loads(body)
        self.assertEqual(upstream["path"], f"/api/v1/public/feeds/{FEED_ID}{query}")
        self.assertEqual(upstream["host"], "www.harudle.com")
        self.assertEqual(upstream["forwardedHost"], "www.harudle.com")
        self.assertEqual(upstream["forwardedProto"], "https")
        self.assertTrue(upstream["realIp"])
        self.assertTrue(upstream["forwardedFor"].startswith("198.51.100.12, "))

    def test_invalid_or_encoded_feed_paths_are_not_preview_routes(self):
        for path in (
            "/feeds/not-a-uuid", f"/feeds/{FEED_ID}/comments", f"/feeds/{FEED_ID}x",
            f"/feeds/%31{FEED_ID[1:]}", f"/other?next=/feeds/{FEED_ID}",
        ):
            with self.subTest(path=path):
                status, _headers, body = self.fetch(path, "kakaotalk-scrap/1.0")
                self.assertEqual(status, 200)
                self.assertEqual(body, SPA)

    def test_public_image_api_is_proxied_for_browsers_and_bots(self):
        path = f"/api/v1/public/feeds/{FEED_ID}/image?version=1"
        for agent in ("Mozilla/5.0", "kakaotalk-scrap/1.0"):
            with self.subTest(user_agent=agent):
                status, headers, body = self.fetch(path, agent)
                self.assertEqual(status, 200)
                self.assertEqual(body, JPEG)
                self.assertEqual(headers.get("content-type"), "image/jpeg")
                self.assertEqual(headers.get("x-stub-path"), path)
                self.assertEqual(headers.get("cache-control"), "no-store")

    def test_collector_head_preserves_method_without_response_body(self):
        status, headers, body = self.fetch(f"/feeds/{FEED_ID}", "Twitterbot/1.0", method="HEAD")
        self.assertEqual(status, 200)
        self.assertEqual(headers.get("x-stub-method"), "HEAD")
        self.assertEqual(headers.get("x-stub-path"), f"/api/v1/public/feeds/{FEED_ID}")
        self.assertIn("User-Agent", headers.get("vary", ""))
        self.assertEqual(body, b"")


if __name__ == "__main__":
    unittest.main(verbosity=2)
