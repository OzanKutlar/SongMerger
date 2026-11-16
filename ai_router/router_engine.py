from __future__ import annotations

import math
from typing import Any

import numpy as np

DEFAULT_CROSSFADE_MS = 6000
MIN_SEGMENT_MS = 22000
BEAM_WIDTH = 12


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


def score_transition(out_cue: dict[str, Any], in_cue: dict[str, Any]) -> float:
    """Scores how seamlessly out_cue blends into in_cue in [0.0, 1.0]."""
    harmonic = 0.55 * _cosine(out_cue["chroma"], in_cue["chroma"]) + 0.45 * _cosine(
        out_cue["tonnetz"], in_cue["tonnetz"]
    )
    timbral = 0.65 * _cosine(out_cue["mfcc"], in_cue["mfcc"]) + 0.35 * _cosine(
        out_cue["contrast"], in_cue["contrast"]
    )

    e_out = float(out_cue.get("energy", 0.0))
    e_in = float(in_cue.get("energy", 0.0))
    energy_diff = abs(e_out - e_in) / max(e_out, e_in, 1e-4)
    energy_score = math.exp(-2.2 * energy_diff)

    c_out = float(out_cue.get("centroid_hz", 1500.0))
    c_in = float(in_cue.get("centroid_hz", 1500.0))
    centroid_diff = abs(c_out - c_in) / max(c_out, c_in, 100.0)
    centroid_score = math.exp(-1.5 * centroid_diff)

    t_out = float(out_cue.get("transient", 0.0))
    t_in = float(in_cue.get("transient", 0.0))
    transient_penalty = min(0.25, 0.03 * (t_out + t_in))

    raw = (
        0.38 * max(0.0, harmonic)
        + 0.32 * max(0.0, timbral)
        + 0.18 * energy_score
        + 0.12 * centroid_score
        - transient_penalty
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


EXPECTED_DIMS = {"chroma": 12, "tonnetz": 6, "mfcc": 13, "contrast": 7}
MAX_ROUTE_TRACKS = 150


def _is_usable(profile: dict[str, Any]) -> bool:
    cues = profile.get("cues") or []
    if len(cues) < 2 or int(profile.get("duration_ms", 0)) <= 0:
        return False
    for cue in cues:
        for key, dims in EXPECTED_DIMS.items():
            if len(cue.get(key) or []) != dims:
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


def _pair_scores(cues: list[dict[str, Any]]) -> np.ndarray:
    """score_transition for every (out cue, in cue) pair at once."""
    harmonic = 0.55 * _cos_matrix([c["chroma"] for c in cues]) + 0.45 * _cos_matrix([c["tonnetz"] for c in cues])
    timbral = 0.65 * _cos_matrix([c["mfcc"] for c in cues]) + 0.35 * _cos_matrix([c["contrast"] for c in cues])
    energy_score = np.exp(-2.2 * _relative_diff(_scalar(cues, "energy", 0.0), 1e-4))
    centroid_score = np.exp(-1.5 * _relative_diff(_scalar(cues, "centroid_hz", 1500.0), 100.0))
    transient = _scalar(cues, "transient", 0.0)
    penalty = np.minimum(0.25, 0.03 * (transient[:, None] + transient[None, :]))
    raw = (
        0.38 * np.maximum(0.0, harmonic)
        + 0.32 * np.maximum(0.0, timbral)
        + 0.18 * energy_score
        + 0.12 * centroid_score
        - penalty
    )
    return np.clip(raw, 0.0, 1.0).astype(np.float32)


class CueTable:
    """Transition scores between every cue of every song, computed once as one matrix."""

    def __init__(self, profiles: list[dict[str, Any]]) -> None:
        cues: list[dict[str, Any]] = []
        self.offsets: dict[str, tuple[int, int]] = {}
        for profile in profiles:
            start = len(cues)
            cues.extend(profile.get("cues", []))
            self.offsets[str(profile["track_id"])] = (start, len(cues))
        self.times = np.asarray([int(c["time_ms"]) for c in cues], dtype=np.int64)
        self.scores = _pair_scores(cues) if cues else np.zeros((0, 0), dtype=np.float32)

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
        ok_a = times_a >= min_a_start_ms + MIN_SEGMENT_MS
        ok_b = (b_duration_ms - times_b) >= MIN_SEGMENT_MS // 2
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
                    diversity_penalty = 0.25 if (cur_id, cand_id) in used_edges else 0.0
                    new_segs = [dict(s) for s in segs]
                    new_segs[-1]["end_ms"] = int(out_cue["time_ms"])
                    new_segs.append(
                        {
                            "track_id": cand_id,
                            "title": str(cand.get("title") or cand_id),
                            "start_ms": int(in_cue["time_ms"]),
                            "end_ms": int(cand["duration_ms"]),
                            "crossfade_ms": DEFAULT_CROSSFADE_MS,
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
