from __future__ import annotations

import argparse
import json
import os
import tempfile
import time
import wave
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import unquote_plus

import numpy as np

from audio_analyzer import FeatureCache
from jobs import JobRunner, UploadInbox
from router_engine import CueTable, generate_mashup_playlists, score_transition

MAX_UPLOAD_BYTES = 80 * 1024 * 1024
DEFAULT_CACHE_DIR = Path(__file__).resolve().parent / "cache"
JOBS_PREFIX = "/api/v1/jobs/"
MAX_WORKERS = 8
SELF_TEST_POLLS = 2400


def default_workers() -> int:
    return max(1, min(MAX_WORKERS, (os.cpu_count() or 2) - 1))


class RouterContext:
    def __init__(self, cache_dir: Path, device: str, device_label: str, workers: int) -> None:
        self.cache = FeatureCache(cache_dir)
        self.inbox = UploadInbox(cache_dir / "inbox")
        self.inbox.purge_stale()
        self.device_label = device_label
        self.runner = JobRunner(self.cache, self.inbox, device, workers)


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
            data = self._read_json()
            if not data or not isinstance(data.get("tracks"), list):
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "Invalid tracks payload"})
                return
            missing: list[str] = []
            reanalyze: list[str] = []
            for item in data["tracks"]:
                tid = str(item.get("id", ""))
                fp = str(item.get("fingerprint", ""))
                if not tid or ctx.cache.has(tid, fp):
                    continue
                if ctx.inbox.has(tid, fp):
                    reanalyze.append(tid)
                else:
                    missing.append(tid)
            # If audio is already stored in inbox but profile version changed, re-analyse it now
            if reanalyze and not missing:
                job = ctx.runner.start(reanalyze)
                for _ in range(SELF_TEST_POLLS):
                    if job.is_finished():
                        break
                    time.sleep(0.25)
            else:
                missing.extend(reanalyze)
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
        table = CueTable(profiles)
        first, second = profiles[0], profiles[1]
        expected = score_transition(
            first["cues"][0],
            second["cues"][0],
            float(first.get("bpm", 120.0)),
            float(second.get("bpm", 120.0)),
        )
        actual = table.score_of(first["track_id"], 0, second["track_id"], 0)
        assert abs(expected - actual) < 1e-3, f"Score matrix mismatch: {expected} vs {actual}"

        started = time.perf_counter()
        playlists = generate_mashup_playlists(profiles, playlist_count=2, tracks_per_playlist=3)
        assert playlists, "Expected at least one generated mashup playlist"
        print(f"Routed in {time.perf_counter() - started:.2f}s")
        print(f"Self-test passed! Sample route: {playlists[0]['route_string']}")


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

    ctx = RouterContext(args.cache_dir, device, label, workers)
    server = ThreadingHTTPServer((args.host, args.port), make_handler(ctx))
    print(
        f"AI DJ Router listening on http://{args.host}:{args.port} "
        f"(device={label}, workers={workers}, cached={ctx.cache.count()})"
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down...")
    finally:
        ctx.runner.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
