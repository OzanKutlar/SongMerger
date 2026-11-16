from __future__ import annotations

from typing import Any

import librosa
import numpy as np
import scipy.fft

try:
    import torch
    import torch.nn.functional as torch_fn
except ImportError:  # PyTorch is optional; the server falls back to librosa
    torch = None
    torch_fn = None

# Import this module only in the main server process: decode workers never need PyTorch.

N_FFT = 2048
N_MELS = 128
N_MFCC = 13
N_CHROMA = 12
MEDIAN_KERNEL = 31
CONTRAST_BANDS = 6
CONTRAST_FMIN = 200.0
CONTRAST_QUANTILE = 0.02
AMIN = 1e-10
TOP_DB = 80.0

# Upper bound on elements in one median-filter chunk (about 200 MB of float32).
MEDIAN_BUDGET = 48 * 1024 * 1024


def torch_available() -> bool:
    return torch is not None


def pick_device(preference: str) -> str:
    """Returns 'cuda:0', 'cpu' (PyTorch on the CPU) or '' (no PyTorch: use librosa)."""
    if torch is None:
        if preference == "cuda":
            raise RuntimeError("PyTorch is not installed; run  pip install -r requirements-gpu.txt")
        return ""
    if preference == "cpu":
        return "cpu"
    if not torch.cuda.is_available():
        if preference == "cuda":
            raise RuntimeError("PyTorch cannot see a CUDA GPU (CPU-only build or an old driver?)")
        return "cpu"
    try:
        probe = torch.ones(8, device="cuda:0")
        float((probe * 2.0).sum().item())
    except Exception as exc:  # noqa: BLE001 - an unsupported GPU fails in many ways
        if preference == "cuda":
            raise RuntimeError(f"the GPU cannot run PyTorch kernels: {exc}") from exc
        print(f"[ai_router] GPU unusable, analysing on the CPU instead: {exc}")
        return "cpu"
    return "cuda:0"


def describe_device(device: str) -> str:
    if not device:
        return "cpu (librosa)"
    if device.startswith("cuda") and torch is not None:
        return f"{device} {torch.cuda.get_device_name(torch.device(device))}"
    return "cpu (pytorch)"


class FeatureExtractor:
    """Computes the analyzer's frame features with PyTorch, mirroring librosa's maths.

    Every feature uses the same STFT frames (centered, hop_length), so all arrays have the
    same number of frames. Not thread-safe: use it from one thread.
    """

    def __init__(self, device: str, sample_rate: int, hop_length: int) -> None:
        if torch is None:
            raise RuntimeError("PyTorch is not installed")
        self.device = torch.device(device)
        self.sample_rate = sample_rate
        self.hop_length = hop_length
        freqs = librosa.fft_frequencies(sr=sample_rate, n_fft=N_FFT)
        self.window = torch.hann_window(N_FFT, device=self.device)
        self.chroma_fb = self._tensor(librosa.filters.chroma(sr=sample_rate, n_fft=N_FFT, n_chroma=N_CHROMA))
        self.mel_fb = self._tensor(librosa.filters.mel(sr=sample_rate, n_fft=N_FFT, n_mels=N_MELS))
        self.dct = self._tensor(scipy.fft.dct(np.eye(N_MELS), type=2, norm="ortho", axis=0)[:N_MFCC])
        self.freqs = self._tensor(freqs)
        self.bands = self._contrast_bands(freqs)

    def _tensor(self, array: np.ndarray) -> Any:
        return torch.as_tensor(np.asarray(array, dtype=np.float32), device=self.device)

    def _contrast_bands(self, freqs: np.ndarray) -> list[tuple[Any, int]]:
        """Row indices and quantile size per octave band, as in librosa.feature.spectral_contrast."""
        octa = np.zeros(CONTRAST_BANDS + 2)
        octa[1:] = CONTRAST_FMIN * (2.0 ** np.arange(0, CONTRAST_BANDS + 1))
        bands: list[tuple[Any, int]] = []
        for k in range(CONTRAST_BANDS + 1):
            current = np.logical_and(freqs >= octa[k], freqs <= octa[k + 1])
            idx = np.flatnonzero(current)
            if idx.size == 0:
                idx = np.array([0])
                current[0] = True
            if k > 0 and idx[0] > 0:
                current[idx[0] - 1] = True
            if k == CONTRAST_BANDS:
                current[idx[-1] + 1:] = True
            rows = np.flatnonzero(current)
            if k < CONTRAST_BANDS and rows.size > 1:
                rows = rows[:-1]
            take = int(max(np.rint(CONTRAST_QUANTILE * np.sum(current)), 1))
            bands.append((torch.as_tensor(rows, device=self.device), min(take, int(rows.size))))
        return bands

    def extract(self, y: np.ndarray) -> dict[str, np.ndarray]:
        """Features of one peak-normalised mono signal at the extractor's sample rate."""
        if y.ndim != 1 or y.size < N_FFT:
            raise ValueError("signal is too short or not mono")
        with torch.inference_mode():
            signal = torch.as_tensor(np.ascontiguousarray(y, dtype=np.float32), device=self.device)
            spec = torch.stft(
                signal,
                n_fft=N_FFT,
                hop_length=self.hop_length,
                window=self.window,
                center=True,
                pad_mode="constant",
                return_complex=True,
            )
            mag = spec.abs()
            del spec
            harmonic, percussive = self._hpss(mag)
            features = {
                "chroma": self._chroma(harmonic),
                "mfcc": self._mfcc(mag),
                "contrast": self._contrast(mag),
                "centroid": self._centroid(mag),
                "rms": self._rms(signal, mag.shape[1]),
                "onset": self._onset(percussive),
            }
            return {key: value.float().cpu().numpy() for key, value in features.items()}

    # ------------------------------------------------------------------ features

    def _median_rows(self, matrix: Any) -> Any:
        """Median filter along the last axis, in row chunks so memory stays bounded."""
        half = MEDIAN_KERNEL // 2
        rows, length = matrix.shape
        chunk = max(1, MEDIAN_BUDGET // max(1, length * MEDIAN_KERNEL))
        out = torch.empty_like(matrix)
        for start in range(0, rows, chunk):
            block = matrix[start:start + chunk]
            padded = torch_fn.pad(block.unsqueeze(0), (half, half), mode="reflect").squeeze(0)
            out[start:start + chunk] = padded.unfold(1, MEDIAN_KERNEL, 1).median(dim=-1).values
        return out

    def _hpss(self, mag: Any) -> tuple[Any, Any]:
        """Harmonic/percussive split with soft masks (power 2), as librosa.decompose.hpss."""
        harmonic = self._median_rows(mag)
        percussive = self._median_rows(mag.transpose(0, 1).contiguous()).transpose(0, 1)
        h2 = harmonic * harmonic
        p2 = percussive * percussive
        total = (h2 + p2).clamp_min(AMIN)
        return mag * (h2 / total), mag * (p2 / total)

    def _power_db(self, power: Any) -> Any:
        db = 10.0 * torch.log10(power.clamp_min(AMIN))
        return torch.maximum(db, db.max() - TOP_DB)

    def _chroma(self, harmonic: Any) -> Any:
        raw = self.chroma_fb @ (harmonic * harmonic)
        return raw / raw.amax(dim=0, keepdim=True).clamp_min(AMIN)

    def _mfcc(self, mag: Any) -> Any:
        return self.dct @ self._power_db(self.mel_fb @ (mag * mag))

    def _contrast(self, mag: Any) -> Any:
        rows = []
        for indices, take in self.bands:
            ordered = torch.sort(mag.index_select(0, indices), dim=0).values
            valley = ordered[:take].mean(dim=0)
            peak = ordered[-take:].mean(dim=0)
            rows.append(10.0 * torch.log10(peak.clamp_min(AMIN)) - 10.0 * torch.log10(valley.clamp_min(AMIN)))
        return torch.stack(rows)

    def _centroid(self, mag: Any) -> Any:
        total = mag.sum(dim=0).clamp_min(AMIN)
        return (self.freqs.unsqueeze(1) * mag).sum(dim=0) / total

    def _rms(self, signal: Any, frames: int) -> Any:
        half = N_FFT // 2
        padded = torch_fn.pad(signal, (half, half))
        framed = padded.unfold(0, N_FFT, self.hop_length)[:frames]
        return torch.sqrt((framed * framed).mean(dim=1))

    def _onset(self, percussive: Any) -> Any:
        mel_db = self._power_db(self.mel_fb @ (percussive * percussive))
        rise = (mel_db[:, 1:] - mel_db[:, :-1]).clamp_min(0.0).mean(dim=0)
        return torch_fn.pad(rise, (1, 0))
