# AI DJ Mashup Routing Server

Runs on any machine reachable by your phone over LAN/WAN. It analyzes each `.mp3` (or other audio file) once, caches its compact harmonic/timbral/phrase profile on disk, and computes seamless DJ-style mashup playlists.

## Setup

```bash
cd ai_router
pip install -r requirements.txt
```

## Run

```bash
python server.py --host 0.0.0.0 --port 8765
```

## Verify

```bash
python server.py --self-test
```

## Endpoints

- `GET /api/v1/health` — Server status and cached track count.
- `POST /api/v1/check-cache` — Given `{ "tracks": [{"id", "fingerprint"}] }`, returns which track IDs still need uploading.
- `POST /api/v1/upload-track` — Raw audio stream upload with `X-Track-Id`, `X-Track-Title`, `X-Track-Artist`, `X-Track-Fingerprint`, `X-Track-Filename` headers. The title, artist and filename values are URL-encoded (UTF-8) and decoded by the server.
- `POST /api/v1/route` — Computes `playlist_count` seamless mashup playlists and returns both structured segments and compact route strings (`Song A,1:45,Song B,0:33,Song B,2:45,Song C,0:15`).
