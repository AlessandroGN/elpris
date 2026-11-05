"""Small HTTP helper with retries."""
import time

import requests

_session = requests.Session()
_session.headers["User-Agent"] = "elpris-forecast/0.1 (personal use)"


def get_json(url: str, params: dict | None = None, retries: int = 4, timeout: int = 60):
    last = None
    for attempt in range(retries):
        try:
            r = _session.get(url, params=params, timeout=timeout)
            if r.status_code == 429:
                time.sleep(20 * (attempt + 1))
                continue
            r.raise_for_status()
            return r.json()
        except (requests.RequestException, ValueError) as e:
            last = e
            time.sleep(3 * (attempt + 1))
    raise RuntimeError(f"GET failed: {url} {params}: {last}")
