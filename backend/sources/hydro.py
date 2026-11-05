"""Norwegian reservoir filling (weekly) from NVE, free API.

Used as a proxy for the Nordic hydro balance. Feature = filling and its
deviation from the median for the same ISO week over earlier years.
"""
import pandas as pd

from ..config import DATA
from .http import get_json

URL = "https://biapi.nve.no/magasinstatistikk/api/Magasinstatistikk/HentOffentligData"


def update() -> pd.DataFrame:
    d = pd.DataFrame(get_json(URL, timeout=120))
    no = d[d["omrType"] == "NO"].copy()  # whole of Norway
    no["date"] = pd.to_datetime(no["dato_Id"])
    no = no.sort_values("date")[["date", "iso_aar", "iso_uke", "fyllingsgrad"]]
    med = no.groupby("iso_uke")["fyllingsgrad"].transform(
        lambda s: s.shift(1).expanding().median())
    no["hydro_fill"] = no["fyllingsgrad"]
    no["hydro_dev"] = no["fyllingsgrad"] - med
    out = no.set_index("date")[["hydro_fill", "hydro_dev"]]
    DATA.mkdir(exist_ok=True)
    out.to_parquet(DATA / "hydro.parquet")
    return out


def load() -> pd.DataFrame:
    return pd.read_parquet(DATA / "hydro.parquet")
