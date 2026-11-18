from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any

import librosa
import numpy as np

PROFILE_VERSION = 3  # 3: beat-snapped cues and loudness_db
LOUDNESS_GATE = 0.1  # frames quieter than this share of the loudest frame do not count
SILENCE_DB = -60.0
SAMPLE_RATE = 22050
HOP_LENGTH = 512
WINDOW_SEC = 4.5
MIN_TRACK_SEC = 18.0
MAX_CUES_PER_TRACK = 16
MIN_CUE_SPACING_SEC = 8.0
EDGE_MARGIN_SEC = 4.5
MAX_ANALYSIS_SEC = 600.0
RHYTHM_STEPS = 32  # 8 beats x 4 subdivisions (~4-5s of beat pattern)
FEATURE_KEYS = ("chroma", "mfcc", "contrast", "centroid", "rms", "onset")


def _safe_unit(vec: np.ndarray, center: bool = False) -> np.ndarray:
    arr = np.asarray(vec, dtype=np.float32)
    if center and arr.size > 1:
        arr = arr - float(np.mean(arr))
    norm = float(np.linalg.norm(arr))
    if norm <= 1e-9 or not math.isfinite(norm):
        return np.zeros_like(arr, dtype=np.float32)
    return (arr / norm).astype(np.float32)


def _estimate_beat_grid(onset_env: np.ndarray, total_frames: int, frames_per_sec: float) -> tuple[float, np.ndarray]:
    """Estimates tempo (BPM) and a complete grid of beat frame indices across the track."""
    try:
        tempo_val, beat_frames = librosa.beat.beat_track(
            onset_envelope=onset_env,
            sr=SAMPLE_RATE,
            hop_length=HOP_LENGTH,
            trim=False,
        )
        bpm = float(np.ndim(tempo_val) and np.asarray(tempo_val).flat[0] or tempo_val)
    except Exception:
        bpm = 120.0
        beat_frames = np.array([], dtype=int)

    if not math.isfinite(bpm) or bpm < 55.0 or bpm > 215.0:
        bpm = 120.0
    # Fold extreme double/half tempo into the standard 84-168 DJ range
    while bpm < 84.0:
        bpm *= 2.0
    while bpm > 168.0:
        bpm *= 0.5

    beat_period = (60.0 / bpm) * frames_per_sec
    beat_frames = np.asarray(beat_frames, dtype=int)
    beat_frames = beat_frames[(beat_frames >= 0) & (beat_frames < total_frames)]

    if beat_frames.size < 8:
        # Synthesize a regular beat grid anchored at the strongest early onset
        early = onset_env[:min(total_frames, int(10.0 * frames_per_sec))]
        anchor = int(np.argmax(early)) if early.size > 0 else 0
        anchor = int(anchor % max(1.0, round(beat_period)))
        beat_frames = np.arange(anchor, total_frames, max(1.0, beat_period)).astype(int)
    else:
        # Extend detected beats to the start and end of the track
        first = int(beat_frames[0])
        pre = np.arange(first - beat_period, -1, -beat_period)[::-1].astype(int)
        last = int(beat_frames[-1])
        post = np.arange(last + beat_period, total_frames, beat_period).astype(int)
        beat_frames = np.unique(np.concatenate([pre, beat_frames, post]))

    return round(bpm, 2), beat_frames


def _pick_phrase_cues(
    novelty: np.ndarray,
    rms: np.ndarray,
    onset_env: np.ndarray,
    beat_frames: np.ndarray,
    frames_per_sec: float,
) -> list[int]:
    """Selects bar-aligned phrase boundaries (section transitions) plus intro/outro cues."""
    total_frames = len(novelty)
    min_frame = int(EDGE_MARGIN_SEC * frames_per_sec)
    max_frame = total_frames - int(EDGE_MARGIN_SEC * frames_per_sec)
    if max_frame <= min_frame:
        mid = total_frames // 2
        return [mid] if total_frames > 0 else []

    spacing_frames = max(1, int(MIN_CUE_SPACING_SEC * frames_per_sec))
    rms_peak = float(np.max(rms)) if len(rms) > 0 else 1.0
    rms_floor = rms_peak * 0.08

    # Pick the best 4-beat bar phase (0..3) where downbeats have the strongest onsets
    if len(beat_frames) >= 8:
        phase_scores = [
            float(np.mean(onset_env[beat_frames[offset::4]]))
            for offset in range(4)
        ]
        best_phase = int(np.argmax(phase_scores))
        bar_frames = beat_frames[best_phase::4]
    else:
        bar_frames = beat_frames

    bar_frames = bar_frames[(bar_frames >= min_frame) & (bar_frames <= max_frame)]
    if bar_frames.size == 0:
        bar_frames = np.linspace(min_frame, max_frame, num=8, dtype=int)

    # Measure structural change across each bar boundary (2s before vs 2s after)
    win = max(2, int(2.0 * frames_per_sec))
    candidates: list[tuple[float, int]] = []
    for idx, frame in enumerate(bar_frames):
        f = int(frame)
        if rms[f] < rms_floor:
            continue
        lo = max(0, f - win)
        hi = min(total_frames, f + win)
        before_rms = float(np.mean(rms[lo:f])) if f > lo else float(rms[f])
        after_rms = float(np.mean(rms[f:hi])) if hi > f else float(rms[f])
        energy_jump = abs(after_rms - before_rms) / max(rms_peak, 1e-6)
        nov_score = float(np.mean(novelty[max(0, f - 4):min(total_frames, f + 5)]))
        # Strongest phrase boundaries occur every 4 or 8 bars with high novelty/energy change
        phrase_bonus = 0.25 if (idx % 4 == 0) else (0.10 if (idx % 2 == 0) else 0.0)
        score = nov_score + 0.8 * energy_jump + phrase_bonus
        candidates.append((score, f))

    candidates.sort(key=lambda pair: pair[0], reverse=True)
    chosen: list[int] = []
    # Always include earliest and latest usable bar downbeats for intro/outro transitions
    if bar_frames.size >= 2:
        chosen.append(int(bar_frames[0]))
        if abs(int(bar_frames[-1]) - chosen[0]) >= spacing_frames:
            chosen.append(int(bar_frames[-1]))

    for _, frame in candidates:
        if len(chosen) >= MAX_CUES_PER_TRACK:
            break
        if all(abs(frame - existing) >= spacing_frames for existing in chosen):
            chosen.append(frame)

    if len(chosen) < 4:
        for frame in np.linspace(min_frame, max_frame, num=6, dtype=int):
            f = int(frame)
            if all(abs(f - existing) >= spacing_frames // 2 for existing in chosen):
                chosen.append(f)

    snapped = _snap_to_beats(chosen, beat_frames, min_frame, max_frame)
    return snapped[:MAX_CUES_PER_TRACK]


def _snap_to_beats(frames: list[int], beat_frames: np.ndarray, min_frame: int, max_frame: int) -> list[int]:
    """Moves each cue onto its nearest beat, so a transition starts on the beat grid."""
    beats = np.asarray(beat_frames, dtype=int)
    beats = beats[(beats >= min_frame) & (beats <= max_frame)]
    if beats.size == 0:
        return sorted(set(int(frame) for frame in frames))
    snapped: set[int] = set()
    for frame in frames:
        idx = int(np.searchsorted(beats, frame))
        options = beats[max(0, idx - 1):min(beats.size, idx + 1)]
        snapped.add(int(options[int(np.argmin(np.abs(options - frame)))]))
    return sorted(snapped)


def _sample_rhythm_signature(
    onset_env: np.ndarray,
    rms: np.ndarray,
    start_frame: int,
    end_frame: int,
) -> list[float]:
    """Samples a 32-step (8-beat) beat-synchronous rhythm pattern over [start_frame, end_frame]."""
    total = len(onset_env)
    lo = max(0, min(total - 2, start_frame))
    hi = max(lo + 1, min(total - 1, end_frame))
    grid = np.linspace(lo, hi, num=RHYTHM_STEPS, endpoint=False)
    idx = np.clip(np.round(grid).astype(int), 0, total - 1)
    combined = 0.70 * onset_env[idx] + 0.30 * (rms[idx] / max(float(np.max(rms)), 1e-6))
    return _safe_unit(combined, center=True).tolist()


def _estimate_vocal_presence(
    lo: int,
    hi: int,
    contrast: np.ndarray,
    centroid: np.ndarray,
    timbre_mfcc: np.ndarray,
    rms: np.ndarray,
) -> float:
    """Estimates lead vocal activity in [0, 1] from mid-band harmonic contrast, centroid and MFCC modulation."""
    if hi <= lo:
        return 0.0
    # Vocal formants sit primarily in 300 Hz - 3.2 kHz (spectral contrast bands 1, 2, 3)
    mid_contrast = float(np.mean(contrast[1:4, lo:hi])) if contrast.shape[0] >= 4 else 20.0
    low_high_contrast = float(np.mean(contrast[[0, -1], lo:hi])) if contrast.shape[0] >= 2 else 20.0
    band_focus = float(np.clip((mid_contrast - 0.75 * low_high_contrast) / 18.0, 0.0, 1.0))

    cent = float(np.mean(centroid[lo:hi]))
    # Lead vocals typically pull spectral centroid toward 1100 - 2900 Hz
    cent_score = math.exp(-((cent - 1950.0) / 1250.0) ** 2)

    # syllabic vocal articulation modulates MFCCs 1..4 over 3-6 Hz
    if hi - lo >= 4 and timbre_mfcc.shape[0] >= 4:
        mfcc_diff = np.diff(timbre_mfcc[:4, lo:hi], axis=1)
        articulation = float(np.clip(np.mean(np.std(mfcc_diff, axis=1)) / 14.0, 0.0, 1.0))
    else:
        articulation = 0.3

    rms_peak = max(float(np.max(rms)), 1e-6)
    rel_energy = float(np.clip(float(np.mean(rms[lo:hi])) / rms_peak, 0.0, 1.0))
    if rel_energy < 0.12:
        return 0.0
    raw = (0.40 * band_focus + 0.32 * cent_score + 0.28 * articulation) * min(1.0, rel_energy * 1.35)
    return round(float(np.clip(raw, 0.0, 1.0)), 4)


def _window_stats(
    lo: int,
    hi: int,
    chroma: np.ndarray,
    tonnetz: np.ndarray,
    timbre_mfcc: np.ndarray,
    contrast: np.ndarray,
    centroid: np.ndarray,
    rms: np.ndarray,
    onset_env: np.ndarray,
) -> dict[str, Any]:
    chroma_raw = np.mean(chroma[:, lo:hi], axis=1)
    return {
        "chroma": _safe_unit(chroma_raw, center=False).tolist(),
        "chroma_rel": _safe_unit(chroma_raw, center=True).tolist(),
        "tonnetz": _safe_unit(np.mean(tonnetz[:, lo:hi], axis=1), center=False).tolist(),
        "mfcc": _safe_unit(np.mean(timbre_mfcc[:, lo:hi], axis=1), center=True).tolist(),
        "contrast": _safe_unit(np.mean(contrast[:, lo:hi], axis=1), center=True).tolist(),
        "centroid_hz": round(float(np.mean(centroid[lo:hi])), 2),
        "energy": round(float(np.mean(rms[lo:hi])), 6),
        "transient": round(float(np.mean(onset_env[lo:hi])), 4),
        "vocal": _estimate_vocal_presence(lo, hi, contrast, centroid, timbre_mfcc, rms),
        "rhythm": _sample_rhythm_signature(onset_env, rms, lo, hi),
    }


def _extract_cue_descriptor(
    frame: int,
    win_frames: int,
    chroma: np.ndarray,
    tonnetz: np.ndarray,
    mfcc: np.ndarray,
    contrast: np.ndarray,
    centroid: np.ndarray,
    rms: np.ndarray,
    onset_env: np.ndarray,
    frames_per_sec: float,
) -> dict[str, Any]:
    """Extracts both outgoing [frame - win, frame] and incoming [frame, frame + win] descriptors."""
    total_frames = chroma.shape[1]
    # Drop mfcc[0] (overall loudness) so mfcc[1:13] captures actual vocal/instrument timbre
    timbre_mfcc = mfcc[1:13, :] if mfcc.shape[0] >= 13 else mfcc

    out_lo = max(0, frame - win_frames)
    out_hi = max(out_lo + 1, min(total_frames, frame))
    in_lo = max(0, min(total_frames - 1, frame))
    in_hi = max(in_lo + 1, min(total_frames, frame + win_frames))

    out_stats = _window_stats(out_lo, out_hi, chroma, tonnetz, timbre_mfcc, contrast, centroid, rms, onset_env)
    in_stats = _window_stats(in_lo, in_hi, chroma, tonnetz, timbre_mfcc, contrast, centroid, rms, onset_env)

    return {
        "time_ms": int(round((frame / frames_per_sec) * 1000.0)),
        "pos_ratio": round(float(frame) / float(max(1, total_frames)), 4),
        # Backwards-compatible keys (default to incoming window)
        "chroma": in_stats["chroma"],
        "tonnetz": in_stats["tonnetz"],
        "mfcc": in_stats["mfcc"],
        "contrast": in_stats["contrast"],
        "centroid_hz": in_stats["centroid_hz"],
        "energy": in_stats["energy"],
        "transient": in_stats["transient"],
        "vocal": in_stats["vocal"],
        # Directional descriptors for exact [out -> in] overlap matching
        "out": out_stats,
        "in": in_stats,
    }


def decode_audio_with_peak(file_path: Path) -> tuple[np.ndarray, float]:
    """CPU work, safe in a worker process: peak-normalised mono float32 at SAMPLE_RATE, plus the
    original peak, so the true loudness survives the normalisation."""
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
    return np.ascontiguousarray(y, dtype=np.float32), peak


def decode_audio(file_path: Path) -> np.ndarray:
    """The peak-normalised signal alone."""
    return decode_audio_with_peak(file_path)[0]


def _loudness_db(rms: np.ndarray, peak: float) -> float:
    """Gated RMS level of the original (not peak-normalised) signal, in dBFS."""
    values = np.asarray(rms, dtype=np.float64).reshape(-1)
    if values.size == 0 or not math.isfinite(peak) or peak <= 1e-9:
        return SILENCE_DB
    loud = values[values >= LOUDNESS_GATE * float(np.max(values))]
    if loud.size == 0:
        return SILENCE_DB
    level = math.sqrt(float(np.mean(loud * loud))) * peak
    return round(20.0 * math.log10(max(level, 1e-3)), 2)


def librosa_features(y: np.ndarray) -> dict[str, np.ndarray]:
    """CPU feature extraction with librosa; the fallback when PyTorch or the GPU is unavailable."""
    sr = SAMPLE_RATE
    y_harm, y_perc = librosa.effects.hpss(y)

    chroma = librosa.feature.chroma_cqt(y=y_harm, sr=sr, hop_length=HOP_LENGTH)
    mfcc = librosa.feature.mfcc(y=y, sr=sr, n_mfcc=13, hop_length=HOP_LENGTH)
    contrast = librosa.feature.spectral_contrast(y=y, sr=sr, hop_length=HOP_LENGTH)
    centroid = librosa.feature.spectral_centroid(y=y, sr=sr, hop_length=HOP_LENGTH)[0]
    rms = librosa.feature.rms(y=y, hop_length=HOP_LENGTH)[0]
    onset_env = librosa.onset.onset_strength(y=y_perc, sr=sr, hop_length=HOP_LENGTH)
    return {
        "chroma": chroma,
        "mfcc": mfcc,
        "contrast": contrast,
        "centroid": centroid,
        "rms": rms,
        "onset": onset_env,
    }


def build_profile(
    features: dict[str, np.ndarray],
    duration_sec: float,
    track_id: str,
    title: str,
    artist: str,
    fingerprint: str,
    peak: float = 1.0,
) -> dict[str, Any]:
    """Picks phrase-boundary cues and describes each one; shared by the GPU and CPU paths.

    [peak] is the signal's peak before normalising, used to report its true loudness.
    """
    missing = [key for key in FEATURE_KEYS if key not in features]
    if missing:
        raise ValueError(f"Missing features: {missing}")
    frames = min(int(np.shape(features[key])[-1]) for key in FEATURE_KEYS)
    if frames < 2:
        raise ValueError("Too few analysis frames")
    sr = SAMPLE_RATE
    chroma = np.asarray(features["chroma"], dtype=np.float32)[:, :frames]
    mfcc = np.asarray(features["mfcc"], dtype=np.float32)[:, :frames]
    contrast = np.asarray(features["contrast"], dtype=np.float32)[:, :frames]
    centroid = np.asarray(features["centroid"], dtype=np.float32).reshape(-1)[:frames]
    rms = np.asarray(features["rms"], dtype=np.float32).reshape(-1)[:frames]
    onset_env = np.asarray(features["onset"], dtype=np.float32).reshape(-1)[:frames]
    tonnetz = librosa.feature.tonnetz(chroma=chroma)

    # Spectral novelty from MFCC delta magnitude
    mfcc_delta = np.diff(mfcc, axis=1, prepend=mfcc[:, :1])
    novelty = np.linalg.norm(mfcc_delta, axis=0)

    frames_per_sec = float(sr) / float(HOP_LENGTH)
    bpm, beat_frames = _estimate_beat_grid(onset_env, frames, frames_per_sec)
    # 8 beats duration in frames (~4s at 120 BPM) for directional beat-matched windows
    eight_beat_sec = 8.0 * (60.0 / max(bpm, 60.0))
    win_frames = max(8, int(round(eight_beat_sec * frames_per_sec)))
    cue_frames = _pick_phrase_cues(novelty, rms, onset_env, beat_frames, frames_per_sec)

    cues = [
        _extract_cue_descriptor(
            frame,
            win_frames,
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
        "version": PROFILE_VERSION,
        "track_id": track_id,
        "title": title,
        "artist": artist,
        "fingerprint": fingerprint,
        "duration_ms": int(round(duration_sec * 1000.0)),
        "bpm": bpm,
        "loudness_db": _loudness_db(rms, peak),
        "cues": cues,
    }


def analyze_decoded(
    y: np.ndarray,
    track_id: str,
    title: str,
    artist: str,
    fingerprint: str,
    extractor: Any = None,
    peak: float = 1.0,
) -> dict[str, Any]:
    """Profiles an already decoded signal; [extractor] is a gpu_features.FeatureExtractor or None."""
    features = extractor.extract(y) if extractor is not None else librosa_features(y)
    duration_sec = float(len(y)) / float(SAMPLE_RATE)
    return build_profile(features, duration_sec, track_id, title, artist, fingerprint, peak)


def analyze_audio_file(
    file_path: Path,
    track_id: str,
    title: str,
    artist: str,
    fingerprint: str,
    extractor: Any = None,
) -> dict[str, Any]:
    """Decodes and profiles one file in the calling thread."""
    y, peak = decode_audio_with_peak(file_path)
    return analyze_decoded(y, track_id, title, artist, fingerprint, extractor, peak)


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
            return (
                data.get("version") == PROFILE_VERSION
                and data.get("fingerprint") == fingerprint
                and bool(data.get("cues"))
            )
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
