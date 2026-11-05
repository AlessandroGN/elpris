"""Day-ahead prices (EUR/MWh) per bidding zone from energy-charts.info.

Returned as an hourly series indexed in UTC (15-minute data is averaged).
"""
from datetime import date, timedelta

import pandas as pd

from ..config import DATA, HISTORY_START, PRICE_ZONES
from .http import get_json

URL = "https://api.energy-charts.info/price"


def _fetch(zone: str, start: date, end: date) -> pd.Series:
    d = get_json(URL, {"bzn": zone, "start": start.isoformat(), "end": end.isoformat()})
    s = pd.Series(d["price"], index=pd.to_datetime(d["unix_seconds"], unit="s", utc=True), dtype="float64")
    return s.resample("1h").mean()


def load_prices(refresh_days: int = 10) -> pd.DataFrame:
    """Hourly prices for all PRICE_ZONES, cached in data/prices.parquet.

    Only the last `refresh_days` (plus tomorrow) are re-downloaded once the cache exists.
    """
    path = DATA / "prices.parquet"
    cached = pd.read_parquet(path) if path.exists() else None
    today = date.today()
    end = today + timedelta(days=2)
    cols = {}
    for zone in PRICE_ZONES:
        parts = []
        if cached is not None and zone in cached:
            old = cached[zone].dropna()
            start = (old.index.max() - pd.Timedelta(days=refresh_days)).date()
            parts.append(old[old.index < pd.Timestamp(start, tz="UTC")])
        else:
            start = date.fromisoformat(HISTORY_START)
        y = start
        while y < end:
            nxt = min(date(y.year + 1, 1, 1), end)
            parts.append(_fetch(zone, y, nxt))
            y = nxt
        s = pd.concat(parts)
        cols[zone] = s[~s.index.duplicated(keep="last")].sort_index()
    df = pd.DataFrame(cols)
    DATA.mkdir(exist_ok=True)
    df.to_parquet(path)
    return df
