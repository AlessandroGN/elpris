"""Score archived forecasts against actual prices -> docs/accuracy.json.

Usage: python -m backend.evaluate
"""
import json
from datetime import date

import pandas as pd

from .config import DOCS, TARGET_ZONE, TZ
from .sources import prices as prices_src

WINDOW_DAYS = 60


def run() -> dict:
    prices = pd.read_parquet(prices_src.DATA / "prices.parquet")[TARGET_ZONE]
    rows = []
    for f in sorted((DOCS / "archive").glob("*.json")):
        issued = date.fromisoformat(f.stem)
        if (date.today() - issued).days > WINDOW_DAYS + 8:
            continue
        a = json.loads(f.read_text(encoding="utf-8"))
        rate = a["eur_sek"]
        for h in a["hours"]:
            t = pd.Timestamp(h["t"])
            actual = prices.get(t.tz_convert("UTC"))
            if actual is None or pd.isna(actual):
                continue
            rows.append({
                "lead": (t.tz_convert(TZ).date() - issued).days,
                "err": abs(h["p50"] - actual * rate / 10),
                "inside": h["p10"] <= actual * rate / 10 <= h["p90"],
            })
    out = {"window_days": WINDOW_DAYS, "per_lead": []}
    if rows:
        df = pd.DataFrame(rows)
        for lead, g in df.groupby("lead"):
            out["per_lead"].append({
                "lead": int(lead), "mae_ore": round(float(g["err"].mean()), 1),
                "coverage": round(float(g["inside"].mean()), 2), "n_hours": int(len(g)),
            })
    DOCS.mkdir(exist_ok=True)
    (DOCS / "accuracy.json").write_text(json.dumps(out, indent=1), encoding="utf-8")
    return out


if __name__ == "__main__":
    print(json.dumps(run(), indent=1))
