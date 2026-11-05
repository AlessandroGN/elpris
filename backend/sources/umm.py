"""Nuclear outages from Nord Pool UMM (Urgent Market Messages), free API.

Every message version is kept with its publication time, so we can rebuild
what the market knew at any moment ("as of") and avoid leaking the future
into training.
"""
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd

from ..config import AREA_EIC, DATA
from .http import get_json

URL = "https://ummapi.nordpoolgroup.com/messages"
NUCLEAR = 14
CANCELLED = 3
AREAS = {"SE3": AREA_EIC["SE3"], "FI": AREA_EIC["FI"]}
START = "2021-06-01"


def update() -> pd.DataFrame:
    """Download all nuclear message versions into data/umm_nuclear.parquet.

    One row per (message version, unit, period).
    """
    path = DATA / "umm_nuclear.parquet"
    pub_from = START
    old = None
    if path.exists():
        old = pd.read_parquet(path)
        pub_from = (old["pub"].max() - pd.Timedelta(days=3)).strftime("%Y-%m-%d")

    rows, skip = [], 0
    while True:
        d = get_json(URL, {
            "FuelTypes": NUCLEAR, "Areas": list(AREAS.values()), "IncludeOutdated": "true",
            "PublicationStartDate": pub_from, "Limit": 2000, "Skip": skip,
            "OrderFields": "PublicationDate", "OrderDirection": "asc",
        }, timeout=180)
        items = d["items"]
        for m in items:
            units = (m.get("productionUnits") or []) + (m.get("generationUnits") or [])
            for u in units:
                if u.get("areaName") not in AREAS:
                    continue
                for p in u.get("timePeriods") or []:
                    rows.append({
                        "msg": m["messageId"], "version": m["version"],
                        "pub": m["publicationDate"], "status": m["eventStatus"],
                        "area": u["areaName"], "unit": u.get("eic") or u["name"],
                        "installed": u.get("installedCapacity") or 0,
                        "unavailable": p.get("unavailableCapacity") or 0,
                        "start": p["eventStart"], "stop": p.get("eventStop") or "2100-01-01T00:00:00Z",
                    })
        skip += len(items)
        if not items or skip >= d["total"]:
            break

    new = pd.DataFrame(rows)
    if not new.empty:
        for c in ("pub", "start", "stop"):
            new[c] = pd.to_datetime(new[c], utc=True, format="ISO8601")
    df = pd.concat([old, new]) if old is not None else new
    df = df.drop_duplicates(["msg", "version", "unit", "start", "stop"], keep="last")
    DATA.mkdir(exist_ok=True)
    df.to_parquet(path)
    return df


def load() -> pd.DataFrame:
    return pd.read_parquet(DATA / "umm_nuclear.parquet")


def unavailable_as_of(df: pd.DataFrame, as_of: pd.Timestamp, hours: pd.DatetimeIndex) -> pd.DataFrame:
    """Unavailable nuclear MW per area for `hours`, using only messages published before `as_of`."""
    known = df[df["pub"] <= as_of]
    if known.empty:
        return pd.DataFrame(0.0, index=hours, columns=[f"nuc_out_{a}" for a in AREAS])
    latest = known.groupby("msg")["version"].transform("max")
    known = known[(known["version"] == latest) & (known["status"] != CANCELLED)]
    known = known[(known["stop"] > hours[0]) & (known["start"] <= hours[-1])]

    h = hours.values
    out = {}
    for area in AREAS:
        total = np.zeros(len(hours))
        for unit, g in known[known["area"] == area].groupby("unit"):
            per_unit = np.zeros(len(hours))
            for start, stop, mw in zip(g["start"].values, g["stop"].values, g["unavailable"].values):
                per_unit[(h >= start) & (h < stop)] += mw
            cap = g["installed"].max() or per_unit.max()
            total += np.minimum(per_unit, cap)
        out[f"nuc_out_{area}"] = total
    return pd.DataFrame(out, index=hours)
