"""Weather from Open-Meteo (free, no key).

Three kinds of data, all hourly in UTC with columns "<point>_<variable>":
- history(lead=0): the model's own short-range forecast (historical-forecast API), back to 2022.
- history(lead=L): what was forecast L days before (previous-runs API, from ~2024).
- forecast(): the live forecast for the coming days.
"""
import time
from collections import defaultdict
from datetime import date, timedelta

import pandas as pd

from ..config import DATA, HISTORY_START, MAX_LEAD_DAYS, POINTS
from .http import get_json

FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
HIST_FC_URL = "https://historical-forecast-api.open-meteo.com/v1/forecast"
PREV_RUNS_URL = "https://previous-runs-api.open-meteo.com/v1/forecast"
PREV_RUNS_START = date(2024, 2, 1)


def _groups():
    """Group points that share the same variable set (one request per group)."""
    g = defaultdict(list)
    for name, lat, lon, vars_ in POINTS:
        g[tuple(vars_)].append((name, lat, lon))
    return g


def _request(url: str, points, hourly_vars: list[str], **params) -> pd.DataFrame:
    p = {
        "latitude": ",".join(str(x[1]) for x in points),
        "longitude": ",".join(str(x[2]) for x in points),
        "hourly": ",".join(hourly_vars),
        "timezone": "UTC",
        "wind_speed_unit": "ms",
        **params,
    }
    d = get_json(url, p, timeout=180)
    if isinstance(d, dict):
        d = [d]
    frames = []
    for (name, _, _), loc in zip(points, d):
        h = loc["hourly"]
        idx = pd.to_datetime(h.pop("time"), utc=True)
        frames.append(pd.DataFrame({f"{name}|{k}": v for k, v in h.items()}, index=idx))
    return pd.concat(frames, axis=1).astype("float64")


def _year_chunks(start: date, end: date):
    y = start
    while y <= end:
        nxt = min(date(y.year, 12, 31), end)
        yield y, nxt
        y = nxt + timedelta(days=1)


def _rename(df: pd.DataFrame, suffix: str = "") -> pd.DataFrame:
    """'wind_se1|wind_speed_100m_previous_day3' -> 'wind_se1_wind_speed_100m'."""
    out = {}
    for c in df.columns:
        name, var = c.split("|")
        if suffix:
            var = var.replace(suffix, "")
        out[c] = f"{name}_{var}"
    return df.rename(columns=out)


def _merge_cache(path, new: pd.DataFrame) -> pd.DataFrame:
    if path.exists():
        old = pd.read_parquet(path)
        new = pd.concat([old[old.index < new.index.min()], new])
    new = new[~new.index.duplicated(keep="last")].sort_index()
    DATA.mkdir(exist_ok=True)
    new.to_parquet(path)
    return new


def update_history() -> None:
    """Download/refresh weather history for lead 0..MAX_LEAD_DAYS into data/weather_lead*.parquet."""
    end = date.today() - timedelta(days=1)

    # Lead 0: historical-forecast API.
    path = DATA / "weather_lead0.parquet"
    start = date.fromisoformat(HISTORY_START)
    if path.exists():
        start = (pd.read_parquet(path).index.max() - pd.Timedelta(days=5)).date()
    parts = []
    for a, b in _year_chunks(start, end):
        cols = [_request(HIST_FC_URL, pts, list(vs), start_date=a.isoformat(), end_date=b.isoformat())
                for vs, pts in _groups().items()]
        parts.append(_rename(pd.concat(cols, axis=1)))
        time.sleep(2)
    if parts:
        _merge_cache(path, pd.concat(parts))

    # Leads 1..N: previous-runs API, one request returns all leads.
    first = DATA / "weather_lead1.parquet"
    start = PREV_RUNS_START
    if first.exists():
        start = (pd.read_parquet(first).index.max() - pd.Timedelta(days=5)).date()
    per_lead = defaultdict(list)
    for a, b in _year_chunks(start, end):
        for vs, pts in _groups().items():
            hv = [f"{v}_previous_day{l}" for v in vs for l in range(1, MAX_LEAD_DAYS + 1)]
            df = _request(PREV_RUNS_URL, pts, hv, start_date=a.isoformat(), end_date=b.isoformat())
            for l in range(1, MAX_LEAD_DAYS + 1):
                suf = f"_previous_day{l}"
                sub = df[[c for c in df.columns if c.endswith(suf)]]
                per_lead[(l, a)].append(_rename(sub, suf))
            time.sleep(2)
    for l in range(1, MAX_LEAD_DAYS + 1):
        chunks = [pd.concat(per_lead[(l, a)], axis=1) for (ll, a) in sorted(per_lead) if ll == l]
        if chunks:
            _merge_cache(DATA / f"weather_lead{l}.parquet", pd.concat(chunks))


def load_history(lead: int) -> pd.DataFrame:
    return pd.read_parquet(DATA / f"weather_lead{lead}.parquet")


def forecast(days: int = MAX_LEAD_DAYS + 2) -> pd.DataFrame:
    """Live forecast (plus a few past days) for all points."""
    cols = [_request(FORECAST_URL, pts, list(vs), forecast_days=days, past_days=2)
            for vs, pts in _groups().items()]
    return _rename(pd.concat(cols, axis=1))
