"""Download / refresh all data sources.  Usage: python -m backend.fetch"""
import time

from .sources import hydro, prices, umm, weather


def main() -> None:
    for name, fn in [
        ("prices", prices.load_prices),
        ("umm", umm.update),
        ("hydro", hydro.update),
        ("weather", weather.update_history),
    ]:
        t = time.time()
        res = fn()
        size = "" if res is None else f" rows={len(res)}"
        print(f"{name}: ok{size} ({time.time() - t:.0f}s)", flush=True)


if __name__ == "__main__":
    main()
