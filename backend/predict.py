"""Daily forecast -> docs/forecast.json (+ a small archive copy for accuracy tracking).

Usage: python -m backend.predict
"""
import json
from datetime import date, timedelta

import lightgbm as lgb
import numpy as np
import pandas as pd

from .config import DOCS, MODELS, TARGET_ZONE, TZ
from .features import _local_day_hours, build_issue, weather_aggregates
from .train import _ns, apply_calibration
from .sources import fx, hydro, prices as prices_src, umm, weather

CHEAP_HOURS = 3


def _load_models():
    feats = json.loads((MODELS / "features.json").read_text())
    models = {n: lgb.Booster(model_file=str(MODELS / f"{n}.txt")) for n in ("p10", "p50", "p90")}
    return models, feats


def _ore(eur_mwh, rate):
    """EUR/MWh -> öre/kWh (excl. VAT)."""
    return None if eur_mwh is None or np.isnan(eur_mwh) else round(float(eur_mwh) * rate / 10, 1)


def run() -> dict:
    today = pd.Timestamp.now(tz=TZ).date()
    prices = _ns(prices_src.load_prices())
    umm_df = umm.update()
    hy = hydro.update()
    live_w = _ns(weather_aggregates(weather.forecast()))
    rate = fx.eur_sek()

    # Before ~13:00 tomorrow's prices are not out yet: forecast from yesterday's position
    # so tomorrow is covered (the model never sees lead 1).
    tomorrow_known = prices[TARGET_ZONE].reindex(_local_day_hours(today + timedelta(days=1))).notna().sum() >= 20
    issue_day = today if tomorrow_known else today - timedelta(days=1)
    rows = build_issue(issue_day, prices, lambda lead: live_w, umm_df, hy, with_target=False)
    models, feats = _load_models()
    X = rows[feats]
    pred = pd.DataFrame({n: m.predict(X) + rows["a_se3_7d"].values for n, m in models.items()},
                        index=rows.index)
    pred[["p10", "p50", "p90"]] = np.sort(pred[["p10", "p50", "p90"]].values, axis=1)
    calib = json.loads((MODELS / "calibration.json").read_text())
    pred = apply_calibration(pred, rows["lead"].values, calib)

    hours, days = [], []
    for offset in range(0, 8):
        day = today + timedelta(days=offset)
        idx = _local_day_hours(day)
        actual = prices[TARGET_ZONE].reindex(idx)
        known = actual.notna().sum() >= 20
        if known:
            p50, p10, p90 = actual, actual, actual
        elif idx.isin(pred.index).all():
            p50, p10, p90 = (pred[c].reindex(idx) for c in ("p50", "p10", "p90"))
        else:
            continue
        for t in idx:
            hours.append({
                "t": t.tz_convert(TZ).isoformat(),
                "p50": _ore(p50[t], rate), "p10": _ore(p10[t], rate), "p90": _ore(p90[t], rate),
                "known": bool(known),
            })
        cheap = p50.sort_values().index[:CHEAP_HOURS]
        days.append({
            "date": day.isoformat(),
            "known": bool(known),
            "lead": offset,
            "mean": _ore(p50.mean(), rate),
            "low": _ore(p10.mean(), rate),
            "high": _ore(p90.mean(), rate),
            "min": _ore(p50.min(), rate),
            "max": _ore(p50.max(), rate),
            "cheapest_hours": sorted(t.tz_convert(TZ).strftime("%H:%M") for t in cheap),
        })

    nuc_now = rows[["nuc_out_SE3", "nuc_out_FI"]].groupby(rows["target_day"]).mean()
    out = {
        "generated_at": pd.Timestamp.now(tz=TZ).isoformat(timespec="seconds"),
        "zone": TARGET_ZONE,
        "unit": "öre/kWh exkl. moms",
        "eur_sek": rate,
        "days": days,
        "hours": hours,
        "drivers": {
            "nuclear_out_se_mw": {str(k.date()): round(float(v)) for k, v in nuc_now["nuc_out_SE3"].items()},
            "hydro_fill_no": round(float(hy["hydro_fill"].iloc[-1]), 3),
            "hydro_dev_no": round(float(hy["hydro_dev"].iloc[-1]), 3),
            "wind_nordic_ms": _daily(live_w["wind_nordic"], today),
            "temp_nordic_c": _daily(live_w["temp_nordic"], today),
        },
    }
    acc = DOCS / "accuracy.json"
    if acc.exists():
        out["accuracy"] = json.loads(acc.read_text())
    DOCS.mkdir(exist_ok=True)
    (DOCS / "forecast.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")

    # Archive just the predicted daily means/hours for later scoring.
    arch = DOCS / "archive"
    arch.mkdir(exist_ok=True)
    (arch / f"{today.isoformat()}.json").write_text(json.dumps({
        "issued": out["generated_at"],
        "eur_sek": rate,
        "hours": [h for h in hours if not h["known"]],
    }), encoding="utf-8")
    return out


def _daily(s: pd.Series, today: date) -> dict:
    g = s.groupby(s.index.tz_convert(TZ).date).mean()
    return {d.isoformat(): round(float(v), 1) for d, v in g.items() if d >= today}


if __name__ == "__main__":
    o = run()
    for d in o["days"]:
        tag = "känt " if d["known"] else "prognos"
        print(f'{d["date"]} {tag}: {d["mean"]} öre (intervall {d["low"]}–{d["high"]}), billigast {d["cheapest_hours"]}')
