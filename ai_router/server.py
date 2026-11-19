from __future__ import annotations

import argparse
import hmac
import json
import os
import socket
import tempfile
import threading
import time
import wave
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import unquote_plus

import numpy as np

from audio_analyzer import FeatureCache
from jobs import MAX_JOB_TRACKS, MAX_UPLOAD_BYTES, JobRunner, UploadInbox
from router_engine import CueTable, generate_mashup_playlists, score_transition
MAX_JSON_BYTES = 4 * 1024 * 1024
DEFAULT_CACHE_DIR = Path(__file__).resolve().parent / "cache"
JOBS_PREFIX = "/api/v1/jobs/"
MAX_WORKERS = 8
SELF_TEST_POLLS = 2400
TOKEN_HEADER = "X-Auth-Token"
TOKEN_ENV = "AI_ROUTER_TOKEN"
LOCAL_HOSTS = ("127.0.0.1", "localhost", "::1")
MAX_PLAYLISTS = 20
MAX_TRACKS_PER_PLAYLIST = 50
MATRIX_TOLERANCE = 1e-3
DISCOVERY_PORT = 8766
DISCOVERY_MAGIC = b"AIDJ_DISCOVER_V1"


def default_workers() -> int:
    return max(1, min(MAX_WORKERS, (os.cpu_count() or 2) - 1))


def content_length(headers: Any) -> int:
    """The declared body size, or -1 when the header is not a number."""
    try:
        return int(headers.get("Content-Length", "0") or "0")
    except ValueError:
        return -1


def bounded_int(value: Any, default: int, low: int, high: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    return max(low, min(high, number))


class RouterContext:
    def __init__(self, cache_dir: Path, device: str, device_label: str, workers: int, token: str) -> None:
        self.cache = FeatureCache(cache_dir)
        self.inbox = UploadInbox(cache_dir / "inbox")
        self.inbox.purge_stale()
        self.device_label = device_label
        self.token = token
        self.runner = JobRunner(self.cache, self.inbox, device, workers)


def make_handler(ctx: RouterContext) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        server_version = "AiDjRouter/1.1"

        def log_message(self, format: str, *args: Any) -> None:
            print(f"[ai_router] {self.address_string()} - {format % args}")

        def _send_json(self, status: int, payload: dict[str, Any]) -> None:
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            """Without a token every request is allowed; with one, it must match exactly."""
            if not ctx.token:
                return True
            supplied = self.headers.get(TOKEN_HEADER, "")
            if hmac.compare_digest(supplied.encode("utf-8"), ctx.token.encode("utf-8")):
                return True
            # The request body is left unread, so this connection cannot be reused.
            self.close_connection = True
            self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized: wrong or missing server token"})
            return False

        def _read_json(self) -> dict[str, Any] | None:
            length = content_length(self.headers)
            if length <= 0 or length > MAX_JSON_BYTES:
                return None
            raw = self.rfile.read(length)
            try:
                data = json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, ValueError):
                return None
            return data if isinstance(data, dict) else None

        def do_GET(self) -> None:  # noqa: N802
            if not self._authorized():
                return
            if self.path == "/api/v1/health":
                self._send_json(
                    HTTPStatus.OK,
                    {"status": "ok", "cached_tracks": ctx.cache.count(), "device": ctx.device_label},
                )
                return
            if self.path.startswith(JOBS_PREFIX):
                job = ctx.runner.get(self.path[len(JOBS_PREFIX):].strip("/"))
                if job is None:
                    self._send_json(HTTPStatus.NOT_FOUND, {"error": "Unknown job"})
                else:
                    self._send_json(HTTPStatus.OK, job.snapshot())
                return
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "Not found"})

        def do_POST(self) -> None:  # noqa: N802
            if not self._authorized():
                return
            if self.path == "/api/v1/check-cache":
                self._handle_check_cache()
            elif self.path == "/api/v1/upload-track":
                self._handle_upload_track()
            elif self.path == "/api/v1/analyze":
                self._handle_analyze()
            elif self.path == "/api/v1/route":
                self._handle_route()
            else:
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "Not found"})

        def _handle_check_cache(self) -> None:
            """Answers at once: what is not analysed yet, and which of those the inbox still holds."""
            data = self._read_json()
            if not data or not isinstance(data.get("tracks"), list):
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Invalid tracks payload"})
                return
            missing: list[str] = []
            stored: list[str] = []
            for item in data["tracks"][:MAX_JOB_TRACKS]:
                if not isinstance(item, dict):
                    continue
                tid = str(item.get("id", ""))
                fp = str(item.get("fingerprint", ""))
                if not tid or ctx.cache.has(tid, fp):
                    continue
                if ctx.inbox.has(tid, fp):
                    stored.append(tid)
                else:
                    missing.append(tid)
            # Stored songs need analysing but no upload. They stay in missing_ids as well, so
            # clients that predate stored_ids still work: they simply upload them again.
            self._send_json(HTTPStatus.OK, {"missing_ids": missing + stored, "stored_ids": stored})

        def _handle_upload_track(self) -> None:
            track_id = self.headers.get("X-Track-Id", "").strip()
            title = unquote_plus(self.headers.get("X-Track-Title", "")).strip() or track_id
            artist = unquote_plus(self.headers.get("X-Track-Artist", "")).strip()
            fingerprint = self.headers.get("X-Track-Fingerprint", "").strip()
            filename = unquote_plus(self.headers.get("X-Track-Filename", "track.mp3")).strip()
            length = content_length(self.headers)

            if not track_id or length <= 0 or length > MAX_UPLOAD_BYTES:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Invalid upload headers or size"})
                return

            suffix = Path(filename).suffix.lower() or ".mp3"
            meta = {"title": title, "artist": artist, "fingerprint": fingerprint}
            try:
                stored = ctx.inbox.store(track_id, suffix, meta, self.rfile, length)
            except ValueError as exc:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
                return
            except OSError as exc:
                self._send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": f"cannot store the upload: {exc}"})
                return
            self._send_json(HTTPStatus.OK, {"status": "stored", "track_id": track_id, "bytes": stored})

        def _handle_analyze(self) -> None:
            data = self._read_json()
            if not data or not isinstance(data.get("track_ids"), list):
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Invalid track_ids"})
                return
            job = ctx.runner.start([str(tid) for tid in data["track_ids"]])
            self._send_json(HTTPStatus.OK, job.snapshot())

        def _handle_route(self) -> None:
            data = self._read_json()
            if not data or not isinstance(data.get("track_ids"), list):
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Invalid track_ids"})
                return
            profiles = []
            for tid in data["track_ids"][:MAX_JOB_TRACKS]:
                p = ctx.cache.get(str(tid))
                if p is not None:
                    profiles.append(p)
            playlist_count = bounded_int(data.get("playlist_count"), 3, 1, MAX_PLAYLISTS)
            tracks_per = bounded_int(data.get("tracks_per_playlist"), 5, 2, MAX_TRACKS_PER_PLAYLIST)
            seed_id = str(data.get("seed_track_id") or "").strip()
            playlists = generate_mashup_playlists(profiles, playlist_count, tracks_per, seed_id)
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


def _matrix_error(profiles: list[dict[str, Any]], table: CueTable) -> float:
    """Largest gap between the vectorised matrix and score_transition over every cue pair."""
    worst = 0.0
    for first in profiles:
        for second in profiles:
            bpm_a = float(first.get("bpm", 120.0) or 120.0)
            bpm_b = float(second.get("bpm", 120.0) or 120.0)
            for i, out_cue in enumerate(first["cues"]):
                for j, in_cue in enumerate(second["cues"]):
                    expected = score_transition(out_cue, in_cue, bpm_a, bpm_b)
                    actual = table.score_of(first["track_id"], i, second["track_id"], j)
                    worst = max(worst, abs(expected - actual))
    return worst


def run_self_test(device: str, workers: int, device_label: str) -> None:
    """Upload inbox, analysis job (decode workers + extractor), score matrix and routing."""
    print(f"Self-test on {device_label} with {workers} decode workers")
    tones = [("Song A", 220.0), ("Song B", 330.0), ("Song C", 440.0), ("Song D", 293.66)]
    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        cache = FeatureCache(tmp / "cache")
        inbox = UploadInbox(tmp / "cache" / "inbox")
        ids: list[str] = []
        for idx, (name, freq) in enumerate(tones):
            wav_path = tmp / f"song_{idx}.wav"
            _write_test_wav(wav_path, freq)
            track_id = f"id{idx}"
            meta = {"title": name, "artist": "Test Artist", "fingerprint": "fp1"}
            with wav_path.open("rb") as stream:
                inbox.store(track_id, ".wav", meta, stream, wav_path.stat().st_size)
            ids.append(track_id)

        runner = JobRunner(cache, inbox, device, workers)
        started = time.perf_counter()
        job = runner.start(ids)
        for _ in range(SELF_TEST_POLLS):
            if job.is_finished():
                break
            time.sleep(0.25)
        runner.shutdown()
        status = job.snapshot()
        assert status["phase"] == "done" and status["failed"] == 0, f"Analysis job failed: {status}"
        print(f"Analysed {status['done']} songs in {time.perf_counter() - started:.1f}s")

        profiles = [cache.get(tid) for tid in ids]
        assert all(p is not None for p in profiles), "Expected every song in the cache"
        assert all("loudness_db" in p for p in profiles), "Expected a loudness in every profile"
        started = time.perf_counter()
        table = CueTable(profiles)
        print(f"Scored {table.scores.size} cue pairs in {time.perf_counter() - started:.3f}s")
        worst = _matrix_error(profiles, table)
        assert worst < MATRIX_TOLERANCE, f"Score matrix differs from score_transition by {worst}"

        started = time.perf_counter()
        playlists = generate_mashup_playlists(profiles, playlist_count=2, tracks_per_playlist=3)
        assert playlists, "Expected at least one generated mashup playlist"
        assert all("gain_db" in seg for seg in playlists[0]["segments"]), "Expected a gain per segment"
        print(f"Routed in {time.perf_counter() - started:.2f}s")
        print(f"Self-test passed! Sample route: {playlists[0]['route_string']}")


def _run_discovery_responder(http_port: int, device_label: str, stop_event: threading.Event) -> None:
    """Answers UDP LAN discovery broadcasts from the phone so users need not type the IP."""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("0.0.0.0", DISCOVERY_PORT))
        sock.settimeout(1.0)
    except OSError as exc:
        print(f"[ai_router] LAN discovery responder disabled (port {DISCOVERY_PORT}): {exc}")
        return
    payload = json.dumps({"service": "ai_dj_router", "port": http_port, "device": device_label}).encode("utf-8")
    with sock:
        while not stop_event.is_set():
            try:
                data, addr = sock.recvfrom(256)
            except TimeoutError:
                continue
            except OSError:
                break
            if data.strip() == DISCOVERY_MAGIC:
                try:
                    sock.sendto(payload, addr)
                except OSError:
                    pass


def main() -> None:
    parser = argparse.ArgumentParser(description="AI DJ Mashup Routing Server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE_DIR)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument(
        "--device",
        choices=("auto", "cuda", "cpu"),
        default="auto",
        help="auto uses the GPU when PyTorch can see one",
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=default_workers(),
        help="processes that decode songs in parallel",
    )
    parser.add_argument(
        "--token",
        default=os.environ.get(TOKEN_ENV, ""),
        help=f"shared secret every request must send in {TOKEN_HEADER} (default: ${TOKEN_ENV})",
    )
    args = parser.parse_args()

    # PyTorch is imported only here, so the decode worker processes never load it.
    from gpu_features import describe_device, pick_device

    try:
        device = pick_device(args.device)
    except RuntimeError as exc:
        raise SystemExit(f"[ai_router] {exc}") from exc
    workers = max(1, min(MAX_WORKERS, args.workers))
    label = describe_device(device)

    if args.self_test:
        run_self_test(device, workers, label)
        return

    token = str(args.token or "").strip()
    if not token and args.host not in LOCAL_HOSTS:
        print("[ai_router] WARNING: no --token set; anyone on the network can use this server")
    ctx = RouterContext(args.cache_dir, device, label, workers, token)
    server = ThreadingHTTPServer((args.host, args.port), make_handler(ctx))
    stop_beacon = threading.Event()
    beacon_thread = threading.Thread(
        target=_run_discovery_responder,
        args=(args.port, label, stop_beacon),
        daemon=True,
        name="lan-discovery",
    )
    beacon_thread.start()
    print(
        f"AI DJ Router listening on http://{args.host}:{args.port} "
        f"(device={label}, workers={workers}, cached={ctx.cache.count()}, auth={'token' if token else 'off'})"
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down...")
    finally:
        stop_beacon.set()
        ctx.runner.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
