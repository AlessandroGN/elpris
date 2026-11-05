"""EUR/SEK from Riksbanken (free, no key)."""
from .http import get_json

URL = "https://api.riksbank.se/swea/v1/Observations/Latest/SEKEURPMI"


def eur_sek() -> float:
    try:
        return float(get_json(URL)["value"])
    except Exception:
        return 11.0  # fallback if the API is down
