"""Train the SE3 price model and run a backtest.

Usage:
  python -m backend.train --backtest      # train on older data, score on the most recent months
  python -m backend.train                 # train on everything and save models/
"""
import argparse
import json
from datetime import date, timedelta
from functools import lru_cache

import lightgbm as lgb
import numpy as np
import pandas as pd

from .config import MAX_LEAD_DAYS, MODELS
from .features import build_issue, feature_columns, weather_aggregates
from .sources import hydro as hydro_src
from .sources import prices as prices_src
from .sources import umm as umm_src
from .sources import weather as weather_src

QUANTILES = {"p10": 0.1, "p50": 0.5, "p90": 0.9}
PARAMS = dict(n_estimators=900, learning_rate=0.03, num_leaves=48, min_child_samples=40,
              subsample=0.8, subsample_freq=1, colsample_bytree=0.7, reg_lambda=1.0, verbose=-1)
TRAIN_FROM = date(2022, 3, 1)


def _ns(df: pd.DataFrame) -> pd.DataFrame:
    """Same timestamp precision everywhere; mixed units make every lookup ~100x slower."""
    df.index = df.index.as_unit("ns")
    return df


def load_inputs():
    prices = _ns(pd.read_parquet(prices_src.DATA / "prices.parquet"))
    base = _ns(weather_aggregates(weather_src.load_history(0)))

    @lru_cache(maxsize=None)
    def weather_for_lead(lead: int) -> pd.DataFrame:
        if lead == 0:
            return base
        try:
            w = _ns(weather_aggregates(weather_src.load_history(lead)))
        except FileNotFoundError:
            return base
        # Before the previous-runs archive starts, fall back to the short-range forecast.
        return w.combine_first(base)

    return prices, weather_for_lead, umm_src.load(), hydro_src.load()


def build_dataset(start: date, end: date) -> pd.DataFrame:
    """Training table, cached in data/dataset.parquet. Only the last few weeks are rebuilt."""
    path = prices_src.DATA / "dataset.parquet"
    cached = pd.read_parquet(path) if path.exists() else None
    rebuild_from = start
    if cached is not None:
        # Issues whose targets were not complete last time must be rebuilt.
        rebuild_from = max(start, (cached["issue_day"].max() - pd.Timedelta(days=21)).date())
        cached = cached[cached["issue_day"] < pd.Timestamp(rebuild_from)]

    prices, wfl, umm_df, hydro = load_inputs()
    blocks = [cached] if cached is not None else []
    d = rebuild_from
    while d <= end:
        b = build_issue(d, prices, wfl, umm_df, hydro)
        if b is not None:
            blocks.append(b.astype({c: "float32" for c in b.columns if b[c].dtype == "float64"}))
        if d.day == 1:
            print(f"  built {d}", flush=True)
        d += timedelta(days=1)
    df = pd.concat(blocks)
    df = df[df["y"].notna()]
    df.to_parquet(path)
    return df[(df["issue_day"] >= pd.Timestamp(start)) & (df["issue_day"] <= pd.Timestamp(end))]


def _target(df: pd.DataFrame) -> pd.Series:
    # Model the deviation from the recent price level; trees cannot extrapolate levels.
    return df["y"] - df["a_se3_7d"]


def fit(df: pd.DataFrame) -> dict:
    X = df[feature_columns(df)]
    y = _target(df)
    # Weight recent data a bit higher; the market changed a lot after 2022.
    age = (df["issue_day"].max() - df["issue_day"]).dt.days
    w = np.exp(-age / 900)
    ds = lgb.Dataset(X, y, weight=w, free_raw_data=False)
    params = {k: v for k, v in PARAMS.items() if k != "n_estimators"}
    params.update(bagging_fraction=params.pop("subsample"), bagging_freq=params.pop("subsample_freq"),
                  feature_fraction=params.pop("colsample_bytree"))
    return {name: lgb.train({**params, "objective": "quantile", "alpha": q}, ds,
                            num_boost_round=PARAMS["n_estimators"])
            for name, q in QUANTILES.items()}


def predict(models: dict, df: pd.DataFrame, calib: dict | None = None) -> pd.DataFrame:
    X = df[feature_columns(df)]
    out = pd.DataFrame({n: m.predict(X) + df["a_se3_7d"].values for n, m in models.items()}, index=df.index)
    # Keep quantiles ordered.
    out[["p10", "p50", "p90"]] = np.sort(out[["p10", "p50", "p90"]].values, axis=1)
    if calib:
        return apply_calibration(out, df["lead"].values, calib)
    return out


def apply_calibration(pr: pd.DataFrame, leads, calib: dict) -> pd.DataFrame:
    """Stretch the 10–90 % band per lead so it really covers ~80 % of outcomes."""
    lo = np.array([calib[str(int(l))]["lo"] for l in leads])
    hi = np.array([calib[str(int(l))]["hi"] for l in leads])
    pr = pr.copy()
    pr["p10"] = pr["p50"] - lo * (pr["p50"] - pr["p10"])
    pr["p90"] = pr["p50"] + hi * (pr["p90"] - pr["p50"])
    return pr


CALIB_DAYS = 150


def fit_calibrated(df: pd.DataFrame) -> tuple[dict, dict]:
    """Fit on all but the last CALIB_DAYS, measure how wide the band must be there, then refit on everything.

    The training rows from before 2024 use near-perfect weather, so raw quantiles are too narrow.
    """
    cut = df["issue_day"].max() - pd.Timedelta(days=CALIB_DAYS)
    early = df[df["issue_day"] < cut - pd.Timedelta(days=MAX_LEAD_DAYS)]
    cal = df[df["issue_day"] >= cut]
    pr = predict(fit(early), cal)
    calib = {}
    for lead in sorted(cal["lead"].unique()):
        m = (cal["lead"] == lead).values
        p, y = pr[m], cal["y"].values[m]
        lo = (p["p50"] - y) / np.maximum(p["p50"] - p["p10"], 1.0)
        hi = (y - p["p50"]) / np.maximum(p["p90"] - p["p50"], 1.0)
        calib[str(int(lead))] = {"lo": float(np.quantile(lo, 0.9)), "hi": float(np.quantile(hi, 0.9))}
    return fit(df), calib


def backtest(test_from: date) -> None:
    end = date.today() - timedelta(days=MAX_LEAD_DAYS + 1)
    df = build_dataset(TRAIN_FROM, end)
    train = df[df["issue_day"] < pd.Timestamp(test_from - timedelta(days=MAX_LEAD_DAYS))]
    test = df[df["issue_day"] >= pd.Timestamp(test_from)].copy()
    print(f"train rows={len(train)} test rows={len(test)} "
          f"test issues {test['issue_day'].min().date()}..{test['issue_day'].max().date()}")
    models, calib = fit_calibrated(train)
    print("calibration:", {k: (round(v["lo"], 2), round(v["hi"], 2)) for k, v in calib.items()})
    pr = predict(models, test, calib)
    test[["p10", "p50", "p90"]] = pr.values

    # Hours repeat once per lead, so compare columns within each group, never via the index.
    base = {
        "model": "p50",
        "same_hour_last_week": "p_same_hour_lastweek",
        "repeat_anchor_day": "p_same_hour_anchor",
        "anchor_7d_mean": "a_se3_7d",
    }
    rows = []
    for lead, g in test.groupby("lead"):
        r = {"lead": lead}
        for n, col in base.items():
            r[n] = float((g[col] - g["y"]).abs().mean())
        r["daily_mean_mae"] = float(g.groupby("target_day").apply(
            lambda x: abs(x["p50"].mean() - x["y"].mean())).mean())
        r["coverage_p10_p90"] = float(((g["y"] >= g["p10"]) & (g["y"] <= g["p90"])).mean())
        rows.append(r)
    res = pd.DataFrame(rows).set_index("lead")
    pd.set_option("display.width", 200); pd.set_option("display.max_columns", 20)
    print("MAE EUR/MWh per lead (hourly):")
    print(res.round(2))
    imp = pd.Series(models["p50"].feature_importance("gain"),
                    index=feature_columns(train)).sort_values(ascending=False)
    print("Top features:\n", (imp / imp.sum()).head(20).round(3))
    MODELS.mkdir(exist_ok=True)
    (MODELS / "backtest.json").write_text(json.dumps(
        {"test_from": str(test_from), "per_lead": res.reset_index().to_dict("records")}, indent=1))


def train_full() -> None:
    end = date.today() - timedelta(days=2)
    df = build_dataset(TRAIN_FROM, end)
    models, calib = fit_calibrated(df)
    MODELS.mkdir(exist_ok=True)
    for n, m in models.items():
        m.save_model(str(MODELS / f"{n}.txt"))
    (MODELS / "features.json").write_text(json.dumps(feature_columns(df)))
    (MODELS / "calibration.json").write_text(json.dumps(calib, indent=1))
    print(f"trained on {len(df)} rows, saved to {MODELS}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--backtest", action="store_true")
    ap.add_argument("--test-from", default="2026-01-01")
    a = ap.parse_args()
    if a.backtest:
        backtest(date.fromisoformat(a.test_from))
    else:
        train_full()
