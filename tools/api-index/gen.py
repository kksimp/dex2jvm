"""Build dex2jvm's bundled Android API hierarchy index from AOSP api files.

    python3 fetch.py android16-release api-android16-release
    python3 gen.py api-android16-release 36 \
        ../../src/main/resources/io/github/kksimp/dex2jvm/android-api.idx.gz

The index holds, for every public class in the Android SDK, only what the
converter's class-hierarchy oracle reads: access flags, superclass and direct
interfaces. java.* and javax.* are left out because the JDK supplies them.
Source: AOSP api/current.txt signature files, Apache License 2.0.
"""
import glob, gzip, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import apiparse

src, api, out = sys.argv[1], sys.argv[2], sys.argv[3]
raw = {}
files = sorted(glob.glob(os.path.join(src, '*')))
for f in files:
    for k, v in apiparse.parse(open(f, encoding='utf8').read()).items():
        raw.setdefault(k, v)
index = apiparse.resolve(raw)
lines = [f'# dex2jvm Android API hierarchy index: API {api}, {len(files)} AOSP api/current.txt files (Apache 2.0)',
         '# flags(hex) name super interfaces...']
n = 0
for name in sorted(index):
    if name.startswith(('java/', 'javax/')):
        continue
    flags, sup, ifaces = index[name]
    lines.append(' '.join([format(flags, 'x'), name, sup or '-'] + ifaces))
    n += 1
data = ('\n'.join(lines) + '\n').encode()
with gzip.GzipFile(out, 'wb', mtime=0) as g:   # mtime=0: reproducible bytes
    g.write(data)
print(f'{n} classes -> {out} ({os.path.getsize(out)} bytes)')
