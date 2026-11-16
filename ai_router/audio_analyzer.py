from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any

import librosa
import numpy as np

SAMPLE_RATE = 22050
HOP_LENGTH = 512
WINDOW_SEC = 4.0
MIN_TRACK_SEC = 18.0
MAX_CUES_PER_TRACK = 18
MIN_CUE_SPACING_SEC = 7.0
EDGE_MARGIN_SEC = 6.0
MAX_ANALYSIS_SEC = 600.0


def _safe_unit(vec: np.ndarray) -> np.ndarray:
    norm = float(np.linalg.norm(vec))
    if norm <= 1e-9 or not math.isfinite(norm):
        return np.zeros_like(vec, dtype=np.float32)
    return (vec / norm).astype(np.float32)


def _pick_phrase_cues(
    novelty: np.ndarray,
    rms: np.ndarray,
    duration_sec: float,
    frames_per_sec: float,
) -> list[int]:
    """Selects musically natural transition points at local novelty troughs."""
    total_frames = len(novelty)
    min_frame = int(EDGE_MARGIN_SEC * frames_per_sec)
    max_frame = total_frames - int(EDGE_MARGIN_SEC * frames_per_sec)
    if max_frame <= min_frame:
        mid = total_frames // 2
        return [mid] if total_frames > 0 else []

    spacing_frames = max(1, int(MIN_CUE_SPACING_SEC * frames_per_sec))
    rms_peak = float(np.max(rms)) if len(rms) > 0 else 1.0
    rms_floor = rms_peak * 0.12

    # Smooth novelty slightly to avoid micro-jitter
    kernel = np.ones(9, dtype=np.float32) / 9.0
    smooth_nov = np.convolve(novelty, kernel, mode="same")

    candidates: list[tuple[float, int]] = []
    for idx in range(min_frame + 1, max_frame - 1):
        if rms[idx] < rms_floor:
            continue
        if smooth_nov[idx] <= smooth_nov[idx - 1] and smooth_nov[idx] <= smooth_nov[idx + 1]:
            # Lower novelty + moderate energy = clean phrase boundary
            score = float(smooth_nov[idx]) - 0.15 * float(rms[idx] / max(rms_peak, 1e-6))
            candidates.append((score, idx))

    candidates.sort(key=lambda pair: pair[0])
    chosen: list[int] = []
    for _, frame in candidates:
        if all(abs(frame - existing) >= spacing_frames for existing in chosen):
            chosen.append(frame)
            if len(chosen) >= MAX_CUES_PER_TRACK:
                break

    # Fallback uniform grid if track is very steady
    if len(chosen) < 4:
        grid = np.linspace(min_frame, max_frame, num=6, dtype=int)
        for frame in grid:
            f = int(frame)
            if all(abs(f - existing) >= spacing_frames // 2 for existing in chosen):
                chosen.append(f)

    chosen.sort()
    return chosen[:MAX_CUES_PER_TRACK]


def _extract_cue_descriptor(
    frame: int,
    half_win: int,
    chroma: np.ndarray,
    tonnetz: np.ndarray,
    mfcc: np.ndarray,
    contrast: np.ndarray,
    centroid: np.ndarray,
    rms: np.ndarray,
    onset_env: np.ndarray,
    frames_per_sec: float,
) -> dict[str, Any]:
    total_frames = chroma.shape[1]
    lo = max(0, frame - half_win)
    hi = min(total_frames, frame + half_win)
    if hi <= lo:
        hi = min(total_frames, lo + 1)

    chroma_vec = _safe_unit(np.mean(chroma[:, lo:hi], axis=1))
    tonnetz_vec = _safe_unit(np.mean(tonnetz[:, lo:hi], axis=1))
    mfcc_vec = _safe_unit(np.mean(mfcc[:, lo:hi], axis=1))
    contrast_vec = _safe_unit(np.mean(contrast[:, lo:hi], axis=1))
    centroid_hz = float(np.mean(centroid[lo:hi]))
    energy = float(np.mean(rms[lo:hi]))
    transient = float(np.mean(onset_env[lo:hi]))

    return {
        "time_ms": int(round((frame / frames_per_sec) * 1000.0)),
        "chroma": chroma_vec.tolist(),
        "tonnetz": tonnetz_vec.tolist(),
        "mfcc": mfcc_vec.tolist(),
        "contrast": contrast_vec.tolist(),
        "centroid_hz": round(centroid_hz, 2),
        "energy": round(energy, 6),
        "transient": round(transient, 4),
    }


def analyze_audio_file(
    file_path: Path,
    track_id: str,
    title: str,
    artist: str,
    fingerprint: str,
) -> dict[str, Any]:
    """Analyzes an audio file into harmonic, timbral, and phrase-boundary cue descriptors."""
    y, sr = librosa.load(str(file_path), sr=SAMPLE_RATE, mono=True, duration=MAX_ANALYSIS_SEC)
    if y is None or len(y) == 0:
        raise ValueError("Decoded audio is empty")

    duration_sec = float(len(y)) / float(sr)
    if duration_sec < MIN_TRACK_SEC:
        raise ValueError(f"Track too short ({duration_sec:.1f}s < {MIN_TRACK_SEC}s)")

    # Normalize peak amplitude so loudness comparisons stay consistent
    peak = float(np.max(np.abs(y)))
    if peak > 1e-6:
        y = (y / peak).astype(np.float32)

    y_harm, y_perc = librosa.effects.hpss(y)

    chroma = librosa.feature.chroma_cqt(y=y_harm, sr=sr, hop_length=HOP_LENGTH)
    tonnetz = librosa.feature.tonnetz(chroma=chroma)
    mfcc = librosa.feature.mfcc(y=y, sr=sr, n_mfcc=13, hop_length=HOP_LENGTH)
    contrast = librosa.feature.spectral_contrast(y=y, sr=sr, hop_length=HOP_LENGTH)
    centroid = librosa.feature.spectral_centroid(y=y, sr=sr, hop_length=HOP_LENGTH)[0]
    rms = librosa.feature.rms(y=y, hop_length=HOP_LENGTH)[0]
    onset_env = librosa.onset.onset_strength(y=y_perc, sr=sr, hop_length=HOP_LENGTH)

    # Spectral novelty from MFCC delta magnitude
    mfcc_delta = np.diff(mfcc, axis=1, prepend=mfcc[:, :1])
    novelty = np.linalg.norm(mfcc_delta, axis=0)

    frames_per_sec = float(sr) / float(HOP_LENGTH)
    half_win = max(1, int((WINDOW_SEC * 0.5) * frames_per_sec))
    cue_frames = _pick_phrase_cues(novelty, rms, duration_sec, frames_per_sec)

    cues = [
        _extract_cue_descriptor(
            frame,
            half_win,
            chroma,
            tonnetz,
            mfcc,
            contrast,
            centroid,
            rms,
            onset_env,
            frames_per_sec,
        )
        for frame in cue_frames
    ]

    return {
        "track_id": track_id,
        "title": title,
        "artist": artist,
        "fingerprint": fingerprint,
        "duration_ms": int(round(duration_sec * 1000.0)),
        "cues": cues,
    }


class FeatureCache:
    """Persistent JSON cache of analyzed track descriptors keyed by track_id."""

    def __init__(self, cache_dir: Path) -> None:
        self.cache_dir = cache_dir
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    def _path_for(self, track_id: str) -> Path:
        safe = "".join(ch if ch.isalnum() or ch in ("-", "_") else "_" for ch in track_id)
        return self.cache_dir / f"{safe}.json"

    def has(self, track_id: str, fingerprint: str) -> bool:
        path = self._path_for(track_id)
        if not path.is_file():
            return False
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            return data.get("fingerprint") == fingerprint and bool(data.get("cues"))
        except (OSError, ValueError):
            return False

    def get(self, track_id: str) -> dict[str, Any] | None:
        path = self._path_for(track_id)
        if not path.is_file():
            return None
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None

    def put(self, profile: dict[str, Any]) -> None:
        path = self._path_for(str(profile["track_id"]))
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(profile), encoding="utf-8")
        tmp.replace(path)

    def count(self) -> int:
        return sum(1 for _ in self.cache_dir.glob("*.json"))
