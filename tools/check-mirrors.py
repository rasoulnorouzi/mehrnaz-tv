#!/usr/bin/env python3
"""Which API mirror is up to date? (diagnosis for "new titles are missing", 2026-10-24)

Run on a computer that can reach the site (the cloud sessions are blocked by it):

    python3 tools/check-mirrors.py "Carrie"

For each mirror in core/network NetworkModule.DEFAULT_MIRRORS it asks 3 things (12 small GETs in total,
one after another, never in parallel): the newest series, the newest movies, and a search. It prints the
newest id/date each mirror knows; a mirror that is far behind the others is serving a stale copy.
Standard library only; sends no credentials.
"""
import json
import sys
import urllib.parse
import urllib.request

MIRRORS = ["https://euploadi.com", "https://uuploadi.ir", "https://playgen.ir", "https://arzanycenter.ir"]
UA = "okhttp/5.1.0"  # what the TV app sends


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read().decode("utf-8", "replace")), None
    except Exception as e:  # noqa: BLE001 (diagnostic script: report every failure as text)
        return None, f"{type(e).__name__}: {e}"[:80]


def newest(body):
    items = (body or {}).get("data") or []
    if not isinstance(items, list) or not items:
        return None
    top = max(items, key=lambda x: int(x.get("id", 0) or 0))
    return int(top.get("id", 0) or 0), top.get("created_at", "?"), top.get("title", "?")


def main():
    query = sys.argv[1] if len(sys.argv) > 1 else "Carrie"
    q = urllib.parse.quote(query)
    best = {}
    for m in MIRRORS:
        api = f"{m}/wp-json/mapi/v1"
        print(f"\n== {m}")
        for name, path in (("series", "post/series?page=1"), ("movies", "post/movies?page=1")):
            body, err = get(f"{api}/{path}")
            n = newest(body)
            if err or not n:
                print(f"  newest {name:7}: FAILED {err or 'no data'}")
                continue
            best[name] = max(best.get(name, 0), n[0])
            print(f"  newest {name:7}: id {n[0]}  added {n[1]}  {n[2]}")
            best.setdefault("_" + m, {})[name] = n[0]
        body, err = get(f"{api}/post/search?search={q}&page=1")
        items = (body or {}).get("data") or []
        if err:
            print(f"  search '{query}': FAILED {err}")
        else:
            hits = [f"{x.get('title', '?')} ({x.get('release', '?')})" for x in items[:5]] if isinstance(items, list) else []
            print(f"  search '{query}': {len(hits)} shown: " + (" | ".join(hits) if hits else "nothing"))

    print("\n== Verdict (newest id per mirror vs the best one)")
    for m in MIRRORS:
        seen = best.get("_" + m, {})
        parts = []
        for name in ("series", "movies"):
            if name not in seen:
                parts.append(f"{name}: failed")
            else:
                behind = best[name] - seen[name]
                parts.append(f"{name}: {'UP TO DATE' if behind == 0 else f'BEHIND by {behind} ids'}")
        print(f"  {m:26} " + ", ".join(parts))


if __name__ == "__main__":
    main()
