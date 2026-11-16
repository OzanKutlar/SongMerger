from __future__ import annotations

import argparse
import json
import tempfile
import wave
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import unquote_plus

import numpy as np

from audio_analyzer import FeatureCache, analyze_audio_file
from router_engine import generate_mashup_playlists

MAX_UPLOAD_BYTES = 80 * 1024 * 1024
DEFAULT_CACHE_DIR = Path(__file__).resolve().parent / "cache"


class RouterContext:
    def __init__(self, cache_dir: Path) -> None:
        self.cache = FeatureCache(cache_dir)


def make_handler(ctx: RouterContext) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        server_version = "AiDjRouter/1.0"

        def log_message(self, format: str, *args: Any) -> None:
            print(f"[ai_router] {self.address_string()} - {format % args}")

        def _send_json(self, status: int, payload: dict[str, Any]) -> None:
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _read_json(self) -> dict[str, Any] | None:
            length = int(self.headers.get("Content-Length", "0") or "0")
            if length <= 0 or length > 4 * 1024 * 1024:
                return None
            raw = self.rfile.read(length)
            try:
                return json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, ValueError):
                return None

        def do_GET(self) -> None:  # noqa: N802
            if self.path == "/api/v1/health":
                self._send_json(
                    HTTPStatus.OK,
                    {"status": "ok", "cached_tracks": ctx.cache.count()},
                )
                return
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "Not found"})

        def do_POST(self) -> None:  # noqa: N802
            if self.path == "/api/v1/check-cache":
                self._handle_check_cache()
            elif self.path == "/api/v1/upload-track":
                self._handle_upload_track()
            elif self.path == "/api/v1/route":
                self._handle_route()
            else:
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "Not found"})

        def _handle_check_cache(self) -> None:
            data = self._read_json()
            if not data or not isinstance(data.get("tracks"), list):
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Invalid tracks payload"})
                return
            missing: list[str] = []
            for item in data["tracks"]:
                tid = str(item.get("id", ""))
                fp = str(item.get("fingerprint", ""))
                if tid and not ctx.cache.has(tid, fp):
                    missing.append(tid)
            self._send_json(HTTPStatus.OK, {"missing_ids": missing})

        def _handle_upload_track(self) -> None:
            track_id = self.headers.get("X-Track-Id", "").strip()
            title = unquote_plus(self.headers.get("X-Track-Title", "")).strip() or track_id
            artist = unquote_plus(self.headers.get("X-Track-Artist", "")).strip()
            fingerprint = self.headers.get("X-Track-Fingerprint", "").strip()
            filename = unquote_plus(self.headers.get("X-Track-Filename", "track.mp3")).strip()
            length = int(self.headers.get("Content-Length", "0") or "0")

            if not track_id or length <= 0 or length > MAX_UPLOAD_BYTES:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Invalid upload headers or size"})
                return

            suffix = Path(filename).suffix or ".mp3"
            tmp_path: Path | None = None
            try:
                with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
                    tmp_path = Path(tmp.name)
                    remaining = length
                    while remaining > 0:
                        chunk = self.rfile.read(min(65536, remaining))
                        if not chunk:
                            break
                        tmp.write(chunk)
                        remaining -= len(chunk)
                profile = analyze_audio_file(tmp_path, track_id, title, artist, fingerprint)
                ctx.cache.put(profile)
                self._send_json(
                    HTTPStatus.OK,
                    {"status": "analyzed", "track_id": track_id, "cues": len(profile["cues"])},
                )
            except Exception as exc:  # noqa: BLE001
                self._send_json(HTTPStatus.UNPROCESSABLE_ENTITY, {"error": str(exc)})
            finally:
                if tmp_path is not None and tmp_path.exists():
                    tmp_path.unlink(missing_ok=True)

        def _handle_route(self) -> None:
            data = self._read_json()
            if not data or not isinstance(data.get("track_ids"), list):
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Invalid track_ids"})
                return
            profiles = []
            for tid in data["track_ids"]:
                p = ctx.cache.get(str(tid))
                if p is not None:
                    profiles.append(p)
            playlist_count = int(data.get("playlist_count", 3))
            tracks_per = int(data.get("tracks_per_playlist", 5))
            playlists = generate_mashup_playlists(profiles, playlist_count, tracks_per)
            self._send_json(HTTPStatus.OK, {"playlists": playlists})

    return Handler


def _write_test_wav(path: Path, freq_hz: float, duration_sec: float = 32.0) -> None:
    sr = 22050
    t = np.linspace(0.0, duration_sec, int(sr * duration_sec), endpoint=False)
    sig = 0.4 * np.sin(2.0 * np.pi * freq_hz * t) + 0.2 * np.sin(2.0 * np.pi * (freq_hz * 1.5) * t)
    pcm = np.int16(np.clip(sig, -1.0, 1.0) * 32767)
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(pcm.tobytes())


def run_self_test() -> None:
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        profiles = []
        for idx, (name, freq) in enumerate([("Song A", 220.0), ("Song B", 330.0), ("Song C", 440.0)]):
            wav_path = tmp / f"song_{idx}.wav"
            _write_test_wav(wav_path, freq)
            profiles.append(analyze_audio_file(wav_path, f"id_{idx}", name, "Test Artist", "fp_1"))
        playlists = generate_mashup_playlists(profiles, playlist_count=2, tracks_per_playlist=3)
        assert playlists, "Expected at least one generated mashup playlist"
        print(f"Self-test passed! Sample route: {playlists[0]['route_string']}")


def main() -> None:
    parser = argparse.ArgumentParser(description="AI DJ Mashup Routing Server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE_DIR)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        run_self_test()
        return

    ctx = RouterContext(args.cache_dir)
    server = ThreadingHTTPServer((args.host, args.port), make_handler(ctx))
    print(f"AI DJ Router listening on http://{args.host}:{args.port} (cached={ctx.cache.count()})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down...")
        server.server_close()


if __name__ == "__main__":
    main()
