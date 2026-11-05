"""Builds the model table: one row per (issue day, target hour).

An "issue" is the moment a forecast is made: day D at 13:30 Stockholm time,
right after the next day's prices are published. Everything a row uses must
have been known at that moment:
- prices up to the last fully known day (normally D+1),
- weather as it was forecast on day D (previous-runs data for lead L),
- nuclear outages from UMM messages published before the issue time,
- the latest weekly hydro figure.
"""
from datetime import date, timedelta

import holidays
import numpy as np
import pandas as pd

from .config import MAX_LEAD_DAYS, TARGET_ZONE, TEMP_WEIGHTS, TZ
from .sources import umm as umm_src

SE_HOLIDAYS = holidays.Sweden(years=range(2020, 2032))
ISSUE_HOUR = 13.5


def _local_day_hours(day: date) -> pd.DatetimeIndex:
    """UTC hours that make up one Stockholm calendar day (23–25 hours)."""
    start = pd.Timestamp(day, tz=TZ)
    end = pd.Timestamp(day + timedelta(days=1), tz=TZ)
    return pd.date_range(start, end, freq="1h", inclusive="left", unit="ns").tz_convert("UTC")


def weather_aggregates(w: pd.DataFrame) -> pd.DataFrame:
    """Add combined columns to a raw weather frame ('<point>_<var>' columns)."""
    w = w.copy()
    wind = [c for c in w.columns if c.startswith("wind_") and c.endswith("wind_speed_100m")]
    nordic = [c for c in wind if not c.startswith("wind_de")]
    de = [c for c in wind if c.startswith("wind_de")]
    # Turbine output grows roughly with the cube of wind speed until it saturates.
    power = lambda s: np.clip(s, 3, 12) ** 3 / 12 ** 3  # noqa: E731
    w["wind_nordic"] = w[nordic].mean(axis=1)
    w["wind_de"] = w[de].mean(axis=1)
    w["windpow_nordic"] = power(w[nordic]).mean(axis=1)
    w["windpow_de"] = power(w[de]).mean(axis=1)
    w["temp_nordic"] = sum(w[f"{k}_temperature_2m"] * v for k, v in TEMP_WEIGHTS.items())
    w["temp_de"] = w["t_de_temperature_2m"]
    w["solar_de"] = w["t_de_shortwave_radiation"]
    w["solar_se"] = w["s_se_shortwave_radiation"]
    w["precip"] = w[["p_no_precipitation", "p_norr_precipitation"]].mean(axis=1)
    return w


WEATHER_COLS = ["wind_nordic", "wind_de", "windpow_nordic", "windpow_de", "temp_nordic",
                "temp_de", "solar_de", "solar_se", "precip",
                "wind_se1_wind_speed_100m", "wind_se2_wind_speed_100m", "wind_se3_wind_speed_100m",
                "wind_se4_wind_speed_100m", "wind_fi_wind_speed_100m", "wind_dk_wind_speed_100m",
                "wind_no_wind_speed_100m"]


def _day_mean(s: pd.Series, day: date) -> float:
    v = s.reindex(_local_day_hours(day))
    return float(v.mean()) if v.notna().sum() >= 20 else np.nan


def build_issue(issue_day: date, prices: pd.DataFrame, weather_for_lead, umm_df: pd.DataFrame,
                hydro: pd.DataFrame, with_target: bool = True) -> pd.DataFrame | None:
    """Rows for one issue day. `weather_for_lead(lead)` returns an aggregated hourly weather frame."""
    issue_ts = pd.Timestamp(issue_day, tz=TZ) + pd.Timedelta(hours=ISSUE_HOUR)
    p = prices[TARGET_ZONE]

    # Last fully known price day (D+1 normally, D if tomorrow is not published yet).
    known_day = issue_day + timedelta(days=1)
    if np.isnan(_day_mean(p, known_day)):
        known_day = issue_day
        if np.isnan(_day_mean(p, known_day)):
            return None
    known_end = pd.Timestamp(known_day + timedelta(days=1), tz=TZ).tz_convert("UTC")
    past = prices[prices.index < known_end]

    anchors = {
        "a_se3_day": _day_mean(p, known_day),
        "a_se3_7d": float(past[TARGET_ZONE].iloc[-24 * 7:].mean()),
        "a_se3_28d": float(past[TARGET_ZONE].iloc[-24 * 28:].mean()),
        "a_se3_max_day": float(p.reindex(_local_day_hours(known_day)).max()),
        "a_se3_min_day": float(p.reindex(_local_day_hours(known_day)).min()),
    }
    for z in ("SE4", "NO1", "FI", "DK1", "DE-LU"):
        anchors[f"a_{z}_day"] = _day_mean(prices[z], known_day)
        anchors[f"a_{z}_7d"] = float(past[z].iloc[-24 * 7:].mean())

    # Weather on the anchor day (lead 1 = what was forecast for D+1).
    anchor_lead = (known_day - issue_day).days
    w_anchor = weather_for_lead(max(anchor_lead, 0)).reindex(_local_day_hours(known_day))
    for c in ("wind_nordic", "windpow_nordic", "windpow_de", "temp_nordic", "solar_de"):
        anchors[f"aw_{c}"] = float(w_anchor[c].mean())

    hy = hydro[hydro.index <= issue_ts.tz_localize(None)]
    hydro_fill = float(hy["hydro_fill"].iloc[-1]) if len(hy) else np.nan
    hydro_dev = float(hy["hydro_dev"].iloc[-1]) if len(hy) else np.nan

    all_hours = _local_day_hours(known_day).union(
        pd.date_range(known_end, periods=24 * (MAX_LEAD_DAYS + 1), freq="1h"))
    nuc = umm_src.unavailable_as_of(umm_df, issue_ts.tz_convert("UTC"), all_hours)
    nuc_anchor = nuc.reindex(_local_day_hours(known_day)).mean()

    blocks = []
    for lead in range(2, MAX_LEAD_DAYS + 1):
        target_day = issue_day + timedelta(days=lead)
        if target_day <= known_day:
            continue
        hours = _local_day_hours(target_day)
        w = weather_for_lead(lead).reindex(hours)
        if w["wind_nordic"].isna().mean() > 0.2:
            continue
        b = pd.DataFrame(index=hours)
        b["issue_day"] = pd.Timestamp(issue_day)
        b["target_day"] = pd.Timestamp(target_day)
        b["lead"] = lead
        b["gap"] = (target_day - known_day).days
        local = hours.tz_convert(TZ)
        b["hour"] = local.hour
        b["dow"] = local.dayofweek
        b["holiday"] = [int(d in SE_HOLIDAYS) for d in local.date]
        doy = local.dayofyear
        b["doy_sin"] = np.sin(2 * np.pi * doy / 365.25)
        b["doy_cos"] = np.cos(2 * np.pi * doy / 365.25)
        for c in WEATHER_COLS:
            b[c] = w[c].values
        for c in ("wind_nordic", "windpow_nordic", "windpow_de", "temp_nordic"):
            b[f"{c}_daymean"] = float(w[c].mean())
            b[f"{c}_vs_anchor"] = float(w[c].mean()) - anchors[f"aw_{c}"]
        for k, v in anchors.items():
            b[k] = v
        # Same hour on the anchor day and one week before the target.
        b["p_same_hour_anchor"] = p.reindex(hours - pd.Timedelta(days=b["gap"].iloc[0])).values
        b["p_same_hour_lastweek"] = p.reindex(hours - pd.Timedelta(days=7)).where(
            lambda s: s.index < known_end).values
        b["nuc_out_SE3"] = nuc["nuc_out_SE3"].reindex(hours).values
        b["nuc_out_FI"] = nuc["nuc_out_FI"].reindex(hours).values
        b["nuc_out_SE3_vs_anchor"] = b["nuc_out_SE3"] - nuc_anchor["nuc_out_SE3"]
        b["nuc_out_FI_vs_anchor"] = b["nuc_out_FI"] - nuc_anchor["nuc_out_FI"]
        b["hydro_fill"] = hydro_fill
        b["hydro_dev"] = hydro_dev
        if with_target:
            b["y"] = p.reindex(hours).values
        blocks.append(b)
    return pd.concat(blocks) if blocks else None


FEATURES_EXCLUDE = {"issue_day", "target_day", "y"}


def feature_columns(df: pd.DataFrame) -> list[str]:
    return [c for c in df.columns if c not in FEATURES_EXCLUDE]
