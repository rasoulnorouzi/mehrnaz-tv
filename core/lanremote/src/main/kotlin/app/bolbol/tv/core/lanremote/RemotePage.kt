package app.bolbol.tv.core.lanremote

/**
 * The page the phone opens after scanning the QR code. Self-contained (inline CSS and JS, no external resources),
 * Persian and right-to-left. It reads the token from its own address (`?t=`) and POSTs `{"text": ...}` to
 * `/text?t=...` 250 ms after the last keystroke and on Enter, one request at a time so the TV never gets stale text.
 * ES5-style script and no template literals: it has to run on old iOS Safari and Android System WebView/Chrome.
 * The zero-width non-joiner in the Persian title is written as an HTML entity so no invisible character sits in the source.
 */
internal object RemotePage {
    val html: String = """
<!doctype html>
<html lang="fa" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="dark light">
<meta name="referrer" content="no-referrer">
<title>تایپ برای بلبل تی&zwnj;وی</title>
<style>
:root{color-scheme:dark light;--bg:#0b0d12;--fg:#f2f4f8;--mut:#a9b0bd;--card:#151922;--line:#3a4252;--acc:#ffc93c;--ok:#4cd98a;--err:#ff5a5f}
@media (prefers-color-scheme:light){:root{--bg:#f4f5f8;--fg:#14161c;--mut:#5b6472;--card:#fff;--line:#c9cfda;--ok:#13804a;--err:#c62828}}
*{box-sizing:border-box}
html,body{margin:0}
body{background:var(--bg);color:var(--fg);font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,Tahoma,sans-serif;padding:24px 16px;display:flex;justify-content:center}
main{width:100%;max-width:520px}
h1{font-size:22px;margin:0 0 16px}
form{margin:0}
input{width:100%;font:inherit;font-size:24px;padding:16px 18px;border-radius:14px;border:2px solid var(--line);background:var(--card);color:var(--fg);outline:none;-webkit-appearance:none;appearance:none}
input:focus{border-color:var(--acc)}
#st{min-height:30px;margin-top:12px;font-size:18px;color:var(--ok)}
#st.err{color:var(--err)}
p{color:var(--mut);margin:8px 0 0;font-size:14px}
</style>
</head>
<body>
<main>
<h1>تایپ برای بلبل تی&zwnj;وی</h1>
<form id="f" action="#" autocomplete="off">
<input id="q" name="q" type="search" dir="rtl" autofocus enterkeyhint="search" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" maxlength="200" placeholder="اینجا تایپ کنید…" aria-label="جستجو">
</form>
<div id="st" role="status" aria-live="polite"></div>
<p>آنچه می&zwnj;نویسید همزمان روی تلویزیون نمایش داده می&zwnj;شود.</p>
</main>
<script>
(function () {
  var q = document.getElementById('q'), st = document.getElementById('st'), f = document.getElementById('f');
  var tok = '';
  try { tok = new URLSearchParams(location.search).get('t') || ''; } catch (e) {}
  var timer = 0, busy = false, dirty = false, last = null;
  function show(msg, err) { st.textContent = msg; st.className = err ? 'err' : ''; }
  function send(force) {
    clearTimeout(timer); timer = 0;
    if (busy) { dirty = true; return; }
    var v = q.value;
    if (!force && v === last) return;
    busy = true;
    fetch('/text?t=' + encodeURIComponent(tok), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: v }),
      cache: 'no-store',
      credentials: 'omit'
    }).then(function (r) {
      if (r.ok) { last = v; show('ارسال شد ✓', false); }
      else if (r.status === 403) show('لینک معتبر نیست؛ کد را دوباره اسکن کنید', true);
      else if (r.status === 429) show('لطفا کمی صبر کنید', true);
      else show('خطا در ارسال', true);
    }).catch(function () {
      show('تلویزیون در دسترس نیست', true);
    }).then(function () {
      busy = false;
      if (dirty) { dirty = false; send(false); }
    });
  }
  q.addEventListener('input', function () {
    show('', false);
    clearTimeout(timer);
    timer = setTimeout(function () { send(false); }, 250);
  });
  q.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' || e.keyCode === 13) { e.preventDefault(); send(true); }
  });
  f.addEventListener('submit', function (e) { e.preventDefault(); send(true); });
  q.focus();
})();
</script>
</body>
</html>
""".trimStart()

    val bytes: ByteArray = html.toByteArray(Charsets.UTF_8)
}
