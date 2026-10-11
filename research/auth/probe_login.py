#!/usr/bin/env python3
"""Shape-only probe of the Bolboljan account API (M3.1 follow-up). NOT RUN YET.

Run by the user (or by the manager with credentials from the gitignored .env), never by a
research agent. It logs in once, tries the three plausible ways of sending the token, and reads
the two list endpoints. It prints only the SHAPE of every response (keys, JSON types, string
lengths and a format hint such as "hex64" or "jwt"), never a token, a password or a user value.
The only string values printed are the short server messages of failed calls and a few enum-like
keys (message, msg, act, result, type, quality).

    BOLBOL_USER=... BOLBOL_PASS=... python3 -I research/auth/probe_login.py
    python3 -I research/auth/probe_login.py --toggle favorite-list --post-id 353371
    python3 -I research/auth/probe_login.py --poll-tv 60   # then press the website's play-on-TV button

Credentials come from the environment or from a .env file in the current directory
(BOLBOL_USER, BOLBOL_PASS). The .env file is gitignored. Paste the printed shapes back; they are
safe to share.
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

SAFE_KEYS = {"success", "message", "msg", "act", "action", "result", "type", "quality", "per_page", "page", "total_page"}


def load_env():
    values = dict(os.environ)
    try:
        for line in open(".env", encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                values.setdefault(k.strip(), v.strip().strip("'\""))
    except OSError:
        pass
    return values


def hint(s):
    if re.fullmatch(r"[0-9a-f]{64}", s):
        return "hex64"
    if re.fullmatch(r"[0-9a-f]{64}\|\d+", s):
        return "hex64|digits"
    if re.fullmatch(r"[0-9a-f]+", s):
        return "hex%d" % len(s)
    if re.fullmatch(r"[\w-]+\.[\w-]+\.[\w-]+", s):
        return "jwt-like"
    if s.startswith(("http://", "https://")):
        return "url"
    return "text"


def shape(v, key=None, depth=0):
    if isinstance(v, dict):
        return {k: shape(x, k, depth + 1) for k, x in v.items()}
    if isinstance(v, list):
        return ["len=%d" % len(v)] + ([shape(v[0], key, depth + 1)] if v else [])
    if isinstance(v, bool):
        return v
    if isinstance(v, str):
        if key in SAFE_KEYS and len(v) <= 160:
            return v
        return "str(len=%d,%s)" % (len(v), hint(v))
    if v is None:
        return None
    return "int" if isinstance(v, int) else "float"


def find_token(v):
    """First string under a key named token/access_token/api_token/auth_token, depth first."""
    if isinstance(v, dict):
        for k, x in v.items():
            if k.lower() in ("token", "access_token", "api_token", "auth_token") and isinstance(x, str) and x:
                return x
        for x in v.values():
            t = find_token(x)
            if t:
                return t
    elif isinstance(v, list):
        for x in v:
            t = find_token(x)
            if t:
                return t
    return None


def contains_number(v, n):
    if isinstance(v, dict):
        return any(contains_number(x, n) for x in v.values())
    if isinstance(v, list):
        return any(contains_number(x, n) for x in v)
    return str(v) == str(n)


def call(base, method, path, headers=None, form=None, query=None):
    url = base + "/wp-json/mapi/v1/" + path
    if query:
        url += "?" + urllib.parse.urlencode(query)
    data = urllib.parse.urlencode(form).encode() if form is not None else None
    hdrs = {"User-Agent": "Mozilla/5.0 (Linux; Android 10; TV) BolbolTV-probe", "Accept": "application/json"}
    hdrs.update(headers or {})
    if data is not None:
        hdrs["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            status, body = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, body = e.code, e.read()
    try:
        return status, json.loads(body)
    except ValueError:
        return status, {"_non_json_len": len(body)}


def report(out, title, status, body):
    out.append("%s -> HTTP %s" % (title, status))
    out.append(json.dumps(shape(body), ensure_ascii=False, indent=2))
    out.append("")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="https://euploadi.com")
    ap.add_argument("--toggle", choices=["watch-list", "favorite-list"], help="send ONE POST (post_id=...) to this list")
    ap.add_argument("--post-id", help="post id for --toggle (needs a prior successful login)")
    ap.add_argument("--cross-mirror", metavar="BASE",
                    help="also call GET user/profile on this other mirror (e.g. https://uuploadi.ir) with the same token")
    ap.add_argument("--poll-tv", type=int, metavar="SECONDS",
                    help="poll GET post/player-links (no id) every 5 s while you press the website's play-on-TV button")
    ap.add_argument("--out", help="write the shape report to this file as well")
    args = ap.parse_args()

    env = load_env()
    user, pw = env.get("BOLBOL_USER"), env.get("BOLBOL_PASS")
    if not user or not pw:
        sys.exit("Set BOLBOL_USER and BOLBOL_PASS (environment or .env).")
    out = []

    status, body = call(args.base, "POST", "login", form={"username": user, "password": pw})
    report(out, "POST login", status, body)
    token = find_token(body)
    out.append("token found in login response: %s" % ("yes (%s)" % hint(token) if token else "NO"))
    out.append("")
    if not token:
        print_and_save(out, args.out)
        return

    variants = {
        "X-Token header": dict(headers={"X-Token": token}),
        "Authorization: Bearer": dict(headers={"Authorization": "Bearer " + token}),
        "?token= query": dict(query={"token": token}),
    }
    working = None
    for name, kw in variants.items():
        st, bd = call(args.base, "GET", "user/profile", **kw)
        ok = st == 200 and isinstance(bd, dict) and bd.get("success") is True
        out.append("GET user/profile with %-22s -> HTTP %s success=%s" % (name, st, bd.get("success") if isinstance(bd, dict) else "?"))
        if ok and working is None:
            working = (name, kw, bd)
    out.append("")
    if not working:
        out.append("No variant authenticated. Check the login response shape above.")
        print_and_save(out, args.out)
        return
    out.append("Working token transport: %s" % working[0])
    out.append("")
    report(out, "GET user/profile", 200, working[2])
    kw = working[1]
    for path in ("user/watch-list", "user/favorite-list"):
        st, bd = call(args.base, "GET", path, **kw)
        report(out, "GET " + path, st, bd)
    if args.cross_mirror:
        st, bd = call(args.cross_mirror.rstrip("/"), "GET", "user/profile", **kw)
        out.append("GET user/profile on %s with the same token -> HTTP %s success=%s" % (
            args.cross_mirror, st, bd.get("success") if isinstance(bd, dict) else "?"))
        out.append("")
    if args.toggle and args.post_id:
        st, bd = call(args.base, "POST", "user/" + args.toggle, form={"post_id": args.post_id}, **kw)
        report(out, "POST user/%s post_id=<id>" % args.toggle, st, bd)
        st, bd = call(args.base, "GET", "user/" + args.toggle, **kw)
        out.append("GET user/%s afterwards: HTTP %s, id %s present: %s" % (args.toggle, st, args.post_id, contains_number(bd, args.post_id)))
        out.append("(run --toggle again to see whether the same call removes it or is add-only)")
    if args.poll_tv:
        import time
        msg = "Polling GET post/player-links (no id) for %d s; press the website's play-on-TV button now." % args.poll_tv
        print(msg, file=sys.stderr, flush=True)
        out.append(msg)
        last = None
        for _ in range(max(1, args.poll_tv // 5)):
            st, bd = call(args.base, "GET", "post/player-links", **kw)
            sig = json.dumps([st, shape(bd)], ensure_ascii=False, sort_keys=True)
            if sig != last:
                report(out, "GET post/player-links (no id)", st, bd)
                last = sig
            time.sleep(5)
        out.append("(only changes are printed; a change from the 404 'post not found' means the endpoint serves the pushed item)")
    print_and_save(out, args.out)


def print_and_save(out, path):
    text = "\n".join(out)
    print(text)
    if path:
        open(path, "w", encoding="utf-8").write(text + "\n")


if __name__ == "__main__":
    main()
