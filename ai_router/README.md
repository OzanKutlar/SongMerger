# AI DJ Mashup Routing Server

Runs on any machine reachable by your phone over LAN/WAN. It analyzes each `.mp3` (or other audio file) once, caches its compact harmonic/timbral/phrase profile on disk, and computes seamless DJ-style mashup playlists.

## Setup (Windows desktop with an NVIDIA GPU)

Use 64-bit Python 3.11 or 3.12.

```powershell
cd ai_router
py -3.12 -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
pip install -r requirements-gpu.txt
python -c "import torch; print(torch.__version__, torch.cuda.is_available(), torch.cuda.get_device_name(0))"
```

The last command must print `True` and the GPU name. RTX 50-series cards need a PyTorch build for CUDA 12.8 or newer (installed by `requirements-gpu.txt`) and a recent NVIDIA driver.

Without `requirements-gpu.txt` the server still works, using librosa on the CPU.

## Run

```powershell
python server.py --host 0.0.0.0 --port 8765
```

The startup line shows the analysis device, for example `device=cuda:0 NVIDIA GeForce RTX 5060 Ti`.

- `--device auto|cuda|cpu`: `auto` (the default) uses the GPU when PyTorch can see one; `cuda` refuses to start without it.
- `--workers N`: processes that decode songs in parallel (default: CPU cores minus one, at most 8).
- `--token SECRET` (or the `AI_ROUTER_TOKEN` environment variable): every request must then carry it in an `X-Auth-Token` header. Enter the same token in the app under Settings, AI mashup server. Strongly recommended with `--host 0.0.0.0`.
- Allow Python through Windows Firewall on private networks, otherwise the phone cannot connect.

## How a run works

1. The phone uploads the songs the server has not analysed yet. Uploads are only stored, so they finish as fast as the network allows.
2. The phone starts one analysis job. Worker processes decode songs on the CPU while the GPU extracts their features.
3. The phone asks for routes. All transition scores are computed at once as one matrix.

## Verify

```bash
python server.py --self-test
```

## Endpoints

- `GET /api/v1/health` — Server status, cached track count and analysis device.
- `POST /api/v1/check-cache` — Given `{ "tracks": [{"id", "fingerprint"}] }`, answers at once with `missing_ids` (not analysed yet) and `stored_ids` (audio already on the server: analyse it, no upload needed). `missing_ids` also contains the stored IDs, for older clients.
- `POST /api/v1/upload-track` — Stores a raw audio upload for the next analysis job and answers at once. Sent with `X-Track-Id`, `X-Track-Title`, `X-Track-Artist`, `X-Track-Fingerprint`, `X-Track-Filename` headers. The title, artist and filename values are URL-encoded (UTF-8) and decoded by the server.
- `POST /api/v1/analyze` — Given `{ "track_ids": [...] }`, starts a background analysis job for the stored uploads and returns its `job_id`.
- `GET /api/v1/jobs/<job_id>` — Job phase (`queued`, `analysing`, `done`, `failed`) and progress (`done`, `total`, `failed`).
- `POST /api/v1/route` — Computes `playlist_count` seamless mashup playlists and returns both structured segments and compact route strings (`Song A,1:45,Song B,0:33,Song B,2:45,Song C,0:15`).
