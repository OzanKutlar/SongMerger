from __future__ import annotations

import math
from typing import Any

import numpy as np

DEFAULT_CROSSFADE_MS = 4000
MIN_SEGMENT_MS = 32000
SHORT_TRACK_MIN_SEGMENT_MS = 18000
BEAM_WIDTH = 16
MAX_TEMPO_DIFF = 0.085  # +/- 8.5% for beat-matched blends


def format_mmss(ms: int) -> str:
    total_sec = max(0, int(round(ms / 1000.0)))
    minutes = total_sec // 60
    seconds = total_sec % 60
    return f"{minutes}:{seconds:02d}"


def _cosine(a: list[float], b: list[float]) -> float:
    va = np.asarray(a, dtype=np.float32)
    vb = np.asarray(b, dtype=np.float32)
    denom = float(np.linalg.norm(va) * np.linalg.norm(vb))
    if denom <= 1e-9:
        return 0.0
    return float(np.clip(np.dot(va, vb) / denom, -1.0, 1.0))


def _side(cue: dict[str, Any], side: str) -> dict[str, Any]:
    sub = cue.get(side)
    return sub if isinstance(sub, dict) else cue


def _best_rhythm_match(r_out: list[float], r_in: list[float]) -> float:
    """Peak correlation across 4 beat-phase shifts (0, 1, 2, 3 beats = 0, 8, 16, 24 steps)."""
    if not r_out or not r_in or len(r_out) != len(r_in):
        return 0.5
    va = np.asarray(r_out, dtype=np.float32)
    vb = np.asarray(r_in, dtype=np.float32)
    na = float(np.linalg.norm(va))
    nb = float(np.linalg.norm(vb))
    if na <= 1e-9 or nb <= 1e-9:
        return 0.3
    va = va / na
    vb = vb / nb
    step = max(1, len(vb) // 4)
    best = max(float(np.dot(va, np.roll(vb, shift))) for shift in (0, step, step * 2, step * 3))
    return float(np.clip(0.5 * (best + 1.0), 0.0, 1.0))


def _harmonic_consonance(c_out: list[float], c_in: list[float], t_out: list[float], t_in: list[float]) -> float:
    """Combines direct pitch-class match, fifths/relative-key match (+7/-5 semitones), and tonnetz."""
    va = np.asarray(c_out, dtype=np.float32)
    vb = np.asarray(c_in, dtype=np.float32)
    direct = _cosine(c_out, c_in)
    fifth_up = float(np.dot(va, np.roll(vb, 7))) if va.size == 12 and vb.size == 12 else direct
    fifth_dn = float(np.dot(va, np.roll(vb, 5))) if va.size == 12 and vb.size == 12 else direct
    chroma_best = max(direct, 0.92 * max(fifth_up, fifth_dn))
    tonnetz_sim = 0.5 * (_cosine(t_out, t_in) + 1.0)
    return float(np.clip(0.60 * chroma_best + 0.40 * tonnetz_sim, 0.0, 1.0))


def _tempo_compatibility(bpm_a: float, bpm_b: float) -> tuple[float, float]:
    """Returns (compatibility_score in [0, 1], playback_tempo_ratio for Track B)."""
    if bpm_a <= 0.0 or bpm_b <= 0.0:
        return 0.7, 1.0
    best_rel = 1.0
    best_ratio = 1.0
    for mult in (1.0, 0.5, 2.0):
        scaled_b = bpm_b * mult
        rel = abs(bpm_a - scaled_b) / max(bpm_a, scaled_b, 60.0)
        if rel < best_rel:
            best_rel = rel
            best_ratio = bpm_a / scaled_b
    if best_rel <= MAX_TEMPO_DIFF:
        score = math.exp(-12.0 * (best_rel ** 2))
        return float(score), float(np.clip(best_ratio, 0.92, 1.08))
    # Outside beat-matching range: heavily penalized unless energy is low
    return float(max(0.05, math.exp(-6.0 * best_rel))), 1.0


def score_transition(
    out_cue: dict[str, Any],
    in_cue: dict[str, Any],
    bpm_a: float = 120.0,
    bpm_b: float = 120.0,
) -> float:
    """Scores how seamlessly out_cue (outgoing window) blends into in_cue (incoming window)."""
    out_w = _side(out_cue, "out")
    in_w = _side(in_cue, "in")

    harmonic = _harmonic_consonance(out_w["chroma"], in_w["chroma"], out_w["tonnetz"], in_w["tonnetz"])
    mfcc_sim = 0.5 * (_cosine(out_w["mfcc"], in_w["mfcc"]) + 1.0)
    contrast_sim = 0.5 * (_cosine(out_w["contrast"], in_w["contrast"]) + 1.0)
    timbral = 0.65 * mfcc_sim + 0.35 * contrast_sim

    rhythm_match = _best_rhythm_match(out_w.get("rhythm", []), in_w.get("rhythm", []))
    tempo_score, _ = _tempo_compatibility(bpm_a, bpm_b)

    e_out = float(out_w.get("energy", 0.0))
    e_in = float(in_w.get("energy", 0.0))
    energy_diff = abs(e_out - e_in) / max(e_out, e_in, 1e-4)
    energy_score = math.exp(-2.2 * energy_diff)

    c_out = float(out_w.get("centroid_hz", 1500.0))
    c_in = float(in_w.get("centroid_hz", 1500.0))
    centroid_diff = abs(c_out - c_in) / max(c_out, c_in, 100.0)
    centroid_score = math.exp(-1.8 * centroid_diff)

    # Structural DJ flow bonus: prefer leaving in the second half and entering in the first half
    pos_out = float(out_cue.get("pos_ratio", 0.6))
    pos_in = float(in_cue.get("pos_ratio", 0.2))
    flow_bonus = (0.06 if pos_out >= 0.45 else -0.04) + (0.06 if pos_in <= 0.45 else -0.03)

    raw = (
        0.28 * harmonic
        + 0.24 * timbral
        + 0.22 * (rhythm_match * tempo_score)
        + 0.16 * energy_score
        + 0.10 * centroid_score
        + flow_bonus
    )
    return float(np.clip(raw, 0.0, 1.0))


def _best_pair(
    track_a: dict[str, Any],
    track_b: dict[str, Any],
    min_a_start_ms: int,
) -> tuple[float, dict[str, Any], dict[str, Any]] | None:
    cues_a = [c for c in track_a.get("cues", []) if int(c["time_ms"]) >= min_a_start_ms + MIN_SEGMENT_MS]
    cues_b = track_b.get("cues", [])
    if not cues_a or not cues_b:
        return None

    best: tuple[float, dict[str, Any], dict[str, Any]] | None = None
    for ca in cues_a:
        for cb in cues_b:
            # Ensure B still has room to play a segment after cb
            if int(track_b["duration_ms"]) - int(cb["time_ms"]) < MIN_SEGMENT_MS // 2:
                continue
            s = score_transition(ca, cb)
            if best is None or s > best[0]:
                best = (s, ca, cb)
    return best


EXPECTED_DIMS = {"chroma": 12, "tonnetz": 6, "contrast": 7}
MAX_ROUTE_TRACKS = 150


def _is_usable(profile: dict[str, Any]) -> bool:
    cues = profile.get("cues") or []
    if len(cues) < 2 or int(profile.get("duration_ms", 0)) <= 0:
        return False
    for cue in cues:
        for key, dims in EXPECTED_DIMS.items():
            if len(cue.get(key) or []) != dims:
                return False
        if len(cue.get("mfcc") or []) not in (12, 13):
            return False
    return True


def _unique_usable(profiles: list[dict[str, Any]]) -> list[dict[str, Any]]:
    seen: set[str] = set()
    usable: list[dict[str, Any]] = []
    for profile in profiles:
        track_id = str(profile.get("track_id", ""))
        if not track_id or track_id in seen or not _is_usable(profile):
            continue
        seen.add(track_id)
        usable.append(profile)
        if len(usable) >= MAX_ROUTE_TRACKS:
            break
    return usable


def _cos_matrix(rows: list[list[float]]) -> np.ndarray:
    matrix = np.asarray(rows, dtype=np.float32)
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    unit = np.where(norms > 1e-9, matrix / np.where(norms > 1e-9, norms, 1.0), 0.0)
    return np.clip(unit @ unit.T, -1.0, 1.0)


def _scalar(cues: list[dict[str, Any]], key: str, default: float) -> np.ndarray:
    return np.asarray([float(c.get(key, default)) for c in cues], dtype=np.float32)


def _relative_diff(values: np.ndarray, floor: float) -> np.ndarray:
    larger = np.maximum(np.maximum(values[:, None], values[None, :]), floor)
    return np.abs(values[:, None] - values[None, :]) / larger


def _pair_scores(cues: list[dict[str, Any]], cue_bpms: list[float]) -> np.ndarray:
    """Computes score_transition for every (out_cue, in_cue) pair across all tracks."""
    n = len(cues)
    scores = np.zeros((n, n), dtype=np.float32)
    for i in range(n):
        ci = cues[i]
        bpm_i = cue_bpms[i]
        for j in range(n):
            scores[i, j] = score_transition(ci, cues[j], bpm_i, cue_bpms[j])
    return scores


class CueTable:
    """Transition scores between every cue of every song, computed once as one matrix."""

    def __init__(self, profiles: list[dict[str, Any]]) -> None:
        cues: list[dict[str, Any]] = []
        cue_bpms: list[float] = []
        self.offsets: dict[str, tuple[int, int]] = {}
        self.bpms: dict[str, float] = {}
        self.durations: dict[str, int] = {}
        for profile in profiles:
            tid = str(profile["track_id"])
            bpm = float(profile.get("bpm", 120.0) or 120.0)
            self.bpms[tid] = bpm
            self.durations[tid] = int(profile.get("duration_ms", 0))
            start = len(cues)
            track_cues = profile.get("cues", [])
            cues.extend(track_cues)
            cue_bpms.extend([bpm] * len(track_cues))
            self.offsets[tid] = (start, len(cues))
        self.times = np.asarray([int(c["time_ms"]) for c in cues], dtype=np.int64)
        self.scores = _pair_scores(cues, cue_bpms) if cues else np.zeros((0, 0), dtype=np.float32)

    def score_of(self, a_id: str, a_index: int, b_id: str, b_index: int) -> float:
        return float(self.scores[self.offsets[a_id][0] + a_index, self.offsets[b_id][0] + b_index])

    def best_pair(
        self,
        a_id: str,
        b_id: str,
        b_duration_ms: int,
        min_a_start_ms: int,
    ) -> tuple[float, dict[str, Any], dict[str, Any]] | None:
        """Same rules as _best_pair, answered from the precomputed matrix."""
        if a_id not in self.offsets or b_id not in self.offsets:
            return None
        a0, a1 = self.offsets[a_id]
        b0, b1 = self.offsets[b_id]
        times_a = self.times[a0:a1]
        times_b = self.times[b0:b1]
        a_dur = self.durations.get(a_id, 0)
        min_seg = MIN_SEGMENT_MS if a_dur >= 75000 else SHORT_TRACK_MIN_SEGMENT_MS
        ok_a = times_a >= min_a_start_ms + min_seg
        ok_b = (b_duration_ms - times_b) >= max(12000, min_seg // 2)
        if not ok_a.any() or not ok_b.any():
            return None
        masked = np.where(ok_a[:, None] & ok_b[None, :], self.scores[a0:a1, b0:b1], -1.0)
        i, j = divmod(int(np.argmax(masked)), masked.shape[1])
        return float(masked[i, j]), {"time_ms": int(times_a[i])}, {"time_ms": int(times_b[j])}


def _build_route_string(segments: list[dict[str, Any]]) -> str:
    """Builds 'Song A,1:45,Song B,0:33,Song B,2:45,Song C,0:15' style route string."""
    parts: list[str] = []
    for idx in range(len(segments) - 1):
        cur = segments[idx]
        nxt = segments[idx + 1]
        parts.append(cur["title"])
        parts.append(format_mmss(int(cur["end_ms"])))
        parts.append(nxt["title"])
        parts.append(format_mmss(int(nxt["start_ms"])))
    return ",".join(parts)


def generate_mashup_playlists(
    profiles: list[dict[str, Any]],
    playlist_count: int = 3,
    tracks_per_playlist: int = 5,
) -> list[dict[str, Any]]:
    """Generates up to playlist_count diverse seamless DJ mashup playlists."""
    usable = _unique_usable(profiles)
    if len(usable) < 2:
        return []
    table = CueTable(usable)

    target_len = min(max(2, tracks_per_playlist), len(usable))
    used_edges: set[tuple[str, str]] = set()
    results: list[dict[str, Any]] = []

    for p_idx in range(max(1, min(playlist_count, len(usable)))):
        start_track = usable[p_idx % len(usable)]
        # Beam state: (total_score, visited_ids, segments, last_in_ms)
        beam: list[tuple[float, list[str], list[dict[str, Any]], int]] = [
            (
                0.0,
                [str(start_track["track_id"])],
                [
                    {
                        "track_id": str(start_track["track_id"]),
                        "title": str(start_track.get("title") or start_track["track_id"]),
                        "start_ms": 0,
                        "end_ms": int(start_track["duration_ms"]),
                        "crossfade_ms": DEFAULT_CROSSFADE_MS,
                    }
                ],
                0,
            )
        ]

        for _step in range(1, target_len):
            next_beam: list[tuple[float, list[str], list[dict[str, Any]], int]] = []
            for total_score, visited, segs, last_in_ms in beam:
                cur_id = visited[-1]
                for cand in usable:
                    cand_id = str(cand["track_id"])
                    if cand_id in visited:
                        continue
                    pair = table.best_pair(cur_id, cand_id, int(cand["duration_ms"]), last_in_ms)
                    if pair is None:
                        continue
                    edge_score, out_cue, in_cue = pair
                    bpm_a = table.bpms.get(cur_id, 120.0)
                    bpm_b = table.bpms.get(cand_id, 120.0)
                    _, tempo_ratio = _tempo_compatibility(bpm_a, bpm_b)
                    eight_beat_ms = int(round(8.0 * (60000.0 / max(bpm_a, 60.0))))
                    fade_ms = max(2500, min(5500, eight_beat_ms))
                    diversity_penalty = 0.25 if (cur_id, cand_id) in used_edges else 0.0
                    new_segs = [dict(s) for s in segs]
                    new_segs[-1]["end_ms"] = int(out_cue["time_ms"])
                    new_segs[-1]["crossfade_ms"] = fade_ms
                    new_segs.append(
                        {
                            "track_id": cand_id,
                            "title": str(cand.get("title") or cand_id),
                            "start_ms": int(in_cue["time_ms"]),
                            "end_ms": int(cand["duration_ms"]),
                            "crossfade_ms": DEFAULT_CROSSFADE_MS,
                            "tempo_ratio": round(float(tempo_ratio), 4),
                        }
                    )
                    next_beam.append(
                        (
                            total_score + edge_score - diversity_penalty,
                            visited + [cand_id],
                            new_segs,
                            int(in_cue["time_ms"]),
                        )
                    )
            if not next_beam:
                break
            next_beam.sort(key=lambda item: item[0], reverse=True)
            beam = next_beam[:BEAM_WIDTH]

        if not beam or len(beam[0][2]) < 2:
            continue
        best_score, best_ids, best_segs, _ = beam[0]
        for a, b in zip(best_ids, best_ids[1:]):
            used_edges.add((a, b))

        avg_score = best_score / max(1, len(best_segs) - 1)
        results.append(
            {
                "name": f"AI Mashup #{len(results) + 1}",
                "score": round(float(avg_score), 3),
                "route_string": _build_route_string(best_segs),
                "segments": best_segs,
            }
        )

    return results
