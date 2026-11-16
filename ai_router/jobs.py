from __future__ import annotations

import json
import threading
import time
import traceback
import uuid
from concurrent.futures import FIRST_COMPLETED, Future, ProcessPoolExecutor, ThreadPoolExecutor, wait
from concurrent.futures.process import BrokenProcessPool
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO

import numpy as np

from audio_analyzer import HOP_LENGTH, SAMPLE_RATE, FeatureCache, analyze_decoded, decode_audio

MAX_UPLOAD_BYTES = 80 * 1024 * 1024
READ_CHUNK_BYTES = 65536
MAX_JOBS_KEPT = 20
MAX_JOB_TRACKS = 2000
MAX_INBOX_FILES = 20000
STALE_INBOX_SEC = 24 * 3600
ALLOWED_SUFFIXES = (".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus", ".wma")


def safe_name(track_id: str) -> str:
    return "".join(ch if ch.isalnum() or ch in ("-", "_") else "_" for ch in track_id)[:120]


@dataclass(frozen=True)
class InboxItem:
    track_id: str
    audio_path: Path
    meta: dict[str, Any]


class UploadInbox:
    """Uploaded songs waiting for analysis: one audio file and one .meta file per song."""

    def __init__(self, inbox_dir: Path) -> None:
        self.inbox_dir = inbox_dir
        self.inbox_dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()

    def store(self, track_id: str, suffix: str, meta: dict[str, Any], stream: BinaryIO, length: int) -> int:
        """Reads exactly [length] bytes from [stream]; a newer upload replaces an older one."""
        if not track_id or length <= 0 or length > MAX_UPLOAD_BYTES:
            raise ValueError("invalid upload size")
        clean_suffix = suffix if suffix in ALLOWED_SUFFIXES else ".mp3"
        name = safe_name(track_id)
        tmp_path = self.inbox_dir / f"{name}.{uuid.uuid4().hex}.part"
        received = 0
        try:
            with tmp_path.open("wb") as out:
                for _ in range(length // READ_CHUNK_BYTES + 2):
                    if received >= length:
                        break
                    chunk = stream.read(min(READ_CHUNK_BYTES, length - received))
                    if not chunk:
                        break
                    out.write(chunk)
                    received += len(chunk)
            if received != length:
                raise ValueError(f"upload ended after {received} of {length} bytes")
            with self._lock:
                self._remove_audio(name)
                audio_path = self.inbox_dir / f"{name}{clean_suffix}"
                tmp_path.replace(audio_path)
                record = dict(meta)
                record["audio"] = audio_path.name
                (self.inbox_dir / f"{name}.meta").write_text(json.dumps(record), encoding="utf-8")
        finally:
            if tmp_path.exists():
                tmp_path.unlink(missing_ok=True)
        return received

    def take(self, track_id: str) -> InboxItem | None:
        """The stored upload of [track_id], or None when there is none."""
        name = safe_name(track_id)
        with self._lock:
            try:
                meta = json.loads((self.inbox_dir / f"{name}.meta").read_text(encoding="utf-8"))
            except (OSError, ValueError):
                return None
            audio_path = self.inbox_dir / Path(str(meta.get("audio", ""))).name
            if not audio_path.is_file():
                return None
            return InboxItem(track_id, audio_path, meta)

    def discard(self, item: InboxItem) -> None:
        name = safe_name(item.track_id)
        with self._lock:
            for path in (item.audio_path, self.inbox_dir / f"{name}.meta"):
                try:
                    path.unlink(missing_ok=True)
                except OSError as exc:
                    print(f"[ai_router] cannot delete {path.name}: {exc}")

    def purge_stale(self) -> int:
        """Deletes uploads that were never analysed, older than a day."""
        cutoff = time.time() - STALE_INBOX_SEC
        removed = 0
        for path in list(self.inbox_dir.iterdir())[:MAX_INBOX_FILES]:
            try:
                if path.is_file() and path.stat().st_mtime < cutoff:
                    path.unlink()
                    removed += 1
            except OSError as exc:
                print(f"[ai_router] cannot purge {path.name}: {exc}")
        return removed

    def _remove_audio(self, name: str) -> None:
        for suffix in ALLOWED_SUFFIXES:
            (self.inbox_dir / f"{name}{suffix}").unlink(missing_ok=True)


class AnalysisJob:
    """Progress of one analysis request; read by HTTP threads, written by the runner."""

    def __init__(self, track_ids: list[str]) -> None:
        self.job_id = uuid.uuid4().hex
        self.track_ids = track_ids
        self.created = time.time()
        self._lock = threading.Lock()
        self.phase = "queued"
        self.total = 0
        self.done = 0
        self.failed: list[str] = []
        self.error = ""

    def update(self, **changes: Any) -> None:
        with self._lock:
            for key, value in changes.items():
                setattr(self, key, value)

    def record(self, track_id: str, ok: bool) -> None:
        with self._lock:
            self.done += 1
            if not ok:
                self.failed.append(track_id)

    def is_finished(self) -> bool:
        with self._lock:
            return self.phase in ("done", "failed")

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return {
                "job_id": self.job_id,
                "phase": self.phase,
                "total": self.total,
                "done": self.done,
                "failed": len(self.failed),
                "failed_ids": list(self.failed),
                "error": self.error,
            }


class JobRunner:
    """Runs analysis jobs one after another. Worker processes decode songs while this
    runner's thread extracts features on the GPU (or with librosa on the CPU)."""

    def __init__(self, cache: FeatureCache, inbox: UploadInbox, device: str, workers: int) -> None:
        self.cache = cache
        self.inbox = inbox
        self.device = device
        self.workers = max(1, workers)
        self._jobs: dict[str, AnalysisJob] = {}
        self._lock = threading.Lock()
        self._thread = ThreadPoolExecutor(max_workers=1, thread_name_prefix="analysis")
        self._extractor: Any = None
        self._extractor_failed = False

    def start(self, track_ids: list[str]) -> AnalysisJob:
        job = AnalysisJob([tid for tid in (str(t) for t in track_ids[:MAX_JOB_TRACKS]) if tid])
        with self._lock:
            self._jobs[job.job_id] = job
            self._trim()
        self._thread.submit(self._run_safely, job)
        return job

    def get(self, job_id: str) -> AnalysisJob | None:
        with self._lock:
            return self._jobs.get(job_id)

    def shutdown(self) -> None:
        self._thread.shutdown(wait=False, cancel_futures=True)

    def _trim(self) -> None:
        excess = len(self._jobs) - MAX_JOBS_KEPT
        if excess <= 0:
            return
        finished = sorted((j for j in self._jobs.values() if j.is_finished()), key=lambda j: j.created)
        for job in finished[:excess]:
            del self._jobs[job.job_id]

    # ------------------------------------------------------------------ runner thread

    def _run_safely(self, job: AnalysisJob) -> None:
        try:
            self._run(job)
        except Exception as exc:  # noqa: BLE001 - the job reports any failure
            traceback.print_exc()
            job.update(phase="failed", error=str(exc))

    def _run(self, job: AnalysisJob) -> None:
        items = [item for item in (self.inbox.take(tid) for tid in job.track_ids) if item is not None]
        job.update(phase="analysing", total=len(items))
        started = time.perf_counter()
        if items:
            self._analyse(job, items)
        job.update(phase="done")
        status = job.snapshot()
        print(
            f"[ai_router] job {job.job_id[:8]}: analysed {status['done'] - status['failed']}/{status['total']} "
            f"in {time.perf_counter() - started:.1f}s"
        )

    def _analyse(self, job: AnalysisJob, items: list[InboxItem]) -> None:
        queue = list(items)
        window = self.workers * 2
        in_flight: dict[Future, InboxItem] = {}
        with ProcessPoolExecutor(max_workers=min(self.workers, len(items))) as pool:
            # Every pass finishes at least one song, so this bound is never reached.
            for _ in range(2 * len(items) + 2):
                self._fill(pool, queue, in_flight, window)
                if not in_flight:
                    return
                finished, _ = wait(list(in_flight), return_when=FIRST_COMPLETED)
                for future in finished:
                    self._finish(job, in_flight.pop(future), future)

    def _fill(
        self,
        pool: ProcessPoolExecutor,
        queue: list[InboxItem],
        in_flight: dict[Future, InboxItem],
        window: int,
    ) -> None:
        for _ in range(max(0, window - len(in_flight))):
            if not queue:
                return
            item = queue.pop(0)
            try:
                in_flight[pool.submit(decode_audio, item.audio_path)] = item
            except RuntimeError as exc:  # includes BrokenProcessPool
                print(f"[ai_router] decode pool unavailable, decoding here: {exc}")
                in_flight[self._decode_here(item.audio_path)] = item

    @staticmethod
    def _decode_here(path: Path) -> Future:
        future: Future = Future()
        try:
            future.set_result(decode_audio(path))
        except Exception as exc:  # noqa: BLE001 - reported with the song
            future.set_exception(exc)
        return future

    def _finish(self, job: AnalysisJob, item: InboxItem, future: Future) -> None:
        ok = False
        title = str(item.meta.get("title") or item.track_id)
        try:
            try:
                y = future.result()
            except BrokenProcessPool:
                y = decode_audio(item.audio_path)
            self.cache.put(self._profile(y, item, title))
            ok = True
        except Exception as exc:  # noqa: BLE001 - one bad song must not stop the job
            print(f"[ai_router] cannot analyse {title}: {exc}")
        finally:
            self.inbox.discard(item)
            job.record(item.track_id, ok)
        status = job.snapshot()
        print(f"[ai_router] {status['done']}/{status['total']} {'ok  ' if ok else 'FAIL'} {title}")

    def _profile(self, y: np.ndarray, item: InboxItem, title: str) -> dict[str, Any]:
        meta = item.meta
        args = (item.track_id, title, str(meta.get("artist") or ""), str(meta.get("fingerprint") or ""))
        extractor = self._get_extractor()
        if extractor is not None:
            try:
                return analyze_decoded(y, *args, extractor=extractor)
            except RuntimeError as exc:  # CUDA out of memory and similar
                print(f"[ai_router] GPU analysis failed for {title}, using the CPU: {exc}")
        return analyze_decoded(y, *args, extractor=None)

    def _get_extractor(self) -> Any:
        if not self.device or self._extractor_failed:
            return None
        if self._extractor is None:
            try:
                from gpu_features import FeatureExtractor  # PyTorch loads only in this process

                self._extractor = FeatureExtractor(self.device, SAMPLE_RATE, HOP_LENGTH)
            except Exception as exc:  # noqa: BLE001 - fall back to librosa
                print(f"[ai_router] cannot start the {self.device} extractor, using librosa: {exc}")
                self._extractor_failed = True
                return None
        return self._extractor
