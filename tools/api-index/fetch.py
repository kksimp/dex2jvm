"""Fetch every public API signature file (api/current.txt) for one AOSP branch.

    python3 fetch.py android16-release [outdir]

Walks frameworks/base, libcore, ICU, Conscrypt, MediaProvider and every
packages/modules/* repo on android.googlesource.com and saves each
api/current.txt it finds. Rate limits are retried with backoff; files already
saved are skipped, so a rerun resumes."""
import urllib.error, json, base64, sys, os, urllib.request, concurrent.futures as cf
BR = sys.argv[1]; OUT = sys.argv[2] if len(sys.argv) > 2 else f'api-{BR}'; os.makedirs(OUT, exist_ok=True)
G = 'https://android.googlesource.com/'
import time
def get(url):
    for attempt in range(8):
        try:
            with urllib.request.urlopen(url, timeout=60) as r: return r.read()
        except urllib.error.HTTPError as e:
            if e.code not in (429, 500, 502, 503): raise
            time.sleep(2 * (attempt + 1))
    raise RuntimeError('gave up: ' + url)
def gjson(url):
    t = get(url).decode(); return json.loads(t[t.index('\n')+1:])   # gitiles prefixes )]}'
mods = [l.strip() for l in get(G + 'platform/packages/modules/?format=TEXT').decode().split('\n') if l.strip()]
repos = ['platform/frameworks/base', 'platform/libcore', 'platform/external/icu', 'platform/external/conscrypt',
         'platform/packages/providers/MediaProvider'] + ['platform/packages/modules/' + m for m in mods]
def scan(repo):
    try:
        tree = gjson(f'{G}{repo}/+/refs/heads/{BR}/?format=JSON&recursive=1')
    except Exception as e:
        return repo, [], str(e)[:60]
    hits = [e['name'] for e in tree.get('entries', []) if e['name'].endswith('/current.txt') or e['name']=='api/current.txt']
    keep = []
    for p in hits:
        parent = p.rsplit('/', 2)
        if not (p.endswith('api/current.txt') or p.endswith('api/public/current.txt')): continue
        low = p.lower()
        if any(x in low for x in ('test', 'sample', 'example', 'prebuilt', 'tools/')): continue
        keep.append(p)
    files = []
    for p in keep:
        fn = os.path.join(OUT, (repo + '/' + p).replace('/', '__'))
        if os.path.exists(fn): files.append(p); continue
        data = base64.b64decode(get(f'{G}{repo}/+/refs/heads/{BR}/{p}?format=TEXT'))
        if not data.startswith(b'// Signature format'): continue
        fn = os.path.join(OUT, (repo + '/' + p).replace('/', '__'))
        open(fn, 'wb').write(data); files.append(p)
    return repo, files, None
with cf.ThreadPoolExecutor(3) as ex:
    for repo, files, err in ex.map(scan, repos):
        if files or err: print(repo, files if files else '', err or '')
