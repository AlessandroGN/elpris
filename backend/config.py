"""Shared settings: paths, bidding zones and weather points."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"          # raw + cached data (not committed)
MODELS = ROOT / "models"      # trained model files
DOCS = ROOT / "docs"          # published JSON (GitHub Pages)

TZ = "Europe/Stockholm"
TARGET_ZONE = "SE3"
HISTORY_START = "2022-01-01"
MAX_LEAD_DAYS = 7

# Price zones used as features (energy-charts bidding-zone names).
PRICE_ZONES = ["SE3", "SE4", "NO1", "FI", "DK1", "DE-LU"]

# Weather points. Each point feeds one or more aggregated features.
# (name, lat, lon, variables)
WIND = "wind_speed_100m"
TEMP = "temperature_2m"
SOLAR = "shortwave_radiation"
PRECIP = "precipitation"

POINTS = [
    # Wind: Nordic + German wind clusters
    ("wind_se1", 65.6, 21.8, [WIND]),
    ("wind_se2", 63.2, 16.5, [WIND]),
    ("wind_se2b", 62.4, 14.8, [WIND]),
    ("wind_se3", 58.6, 13.4, [WIND]),
    ("wind_se4", 55.9, 13.9, [WIND]),
    ("wind_fi", 64.6, 25.2, [WIND]),
    ("wind_no", 58.9, 5.9, [WIND]),
    ("wind_dk", 56.2, 8.4, [WIND]),
    ("wind_de_n", 53.9, 9.0, [WIND]),
    ("wind_de_ne", 53.6, 12.4, [WIND]),
    ("wind_de_off", 54.4, 6.6, [WIND]),
    # Temperature -> demand
    ("t_sthlm", 59.33, 18.07, [TEMP]),
    ("t_gbg", 57.71, 11.97, [TEMP]),
    ("t_malmo", 55.6, 13.0, [TEMP]),
    ("t_hki", 60.17, 24.94, [TEMP]),
    ("t_oslo", 59.91, 10.75, [TEMP]),
    ("t_de", 51.2, 10.4, [TEMP, SOLAR]),
    # Precipitation for hydro areas
    ("p_no", 61.0, 8.0, [PRECIP]),
    ("p_norr", 64.0, 16.5, [PRECIP]),
    # Solar Sweden
    ("s_se", 58.5, 14.5, [SOLAR]),
]

# Population-ish weights for a combined Nordic temperature.
TEMP_WEIGHTS = {"t_sthlm": 0.35, "t_gbg": 0.2, "t_malmo": 0.15, "t_hki": 0.2, "t_oslo": 0.1}

# Nuclear units (installed MW, approximate net). Name patterns are matched
# case-insensitively against UMM production/generation unit names.
NUCLEAR_SE = {
    "forsmark 1": 1022, "forsmark 2": 1121, "forsmark 3": 1167,
    "ringhals 3": 1074, "ringhals 4": 1130, "oskarshamn 3": 1400,
}
NUCLEAR_FI = {"olkiluoto 1": 890, "olkiluoto 2": 890, "olkiluoto 3": 1600}

AREA_EIC = {"SE3": "10Y1001A1001A46L", "SE4": "10Y1001A1001A47J", "FI": "10YFI-1--------U"}
