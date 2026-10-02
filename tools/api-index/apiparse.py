"""Parse AOSP api/current.txt (signature format) -> {internal_name: (flags, super, [ifaces])}."""
import re, sys
DECL = re.compile(r'^\s*((?:@\S+\s+)*)((?:public|protected|private|static|abstract|final|sealed|non-sealed|deprecated|strictfp|\s)*)\s*(class|interface|@interface|enum|record)\s+(\S+?)(<.*?>)?\s*(?:extends\s+(.+?))?\s*(?:implements\s+(.+?))?\s*\{\s*$')
def strip_generics(s):
    out, depth = [], 0
    for ch in s:
        if ch == '<': depth += 1
        elif ch == '>': depth -= 1
        elif depth == 0: out.append(ch)
    return ''.join(out)
def split_types(s):
    return [t.strip() for t in strip_generics(s).replace(',', ' ').split() if t.strip()]
def parse(text, pkgs=None):
    out = {}; pkg = None
    known = set()
    for line in text.splitlines():
        m = re.match(r'^package (\S+) \{', line)
        if m: pkg = m.group(1); continue
        if pkg is None: continue
        d = DECL.match(line)
        if not d: continue
        mods, kind, name, _, ext, impl = d.group(2), d.group(3), d.group(4), d.group(5), d.group(6), d.group(7)
        mods = mods.split()
        internal = pkg.replace('.', '/') + '/' + name.replace('.', '$')
        flags = 0x0001 if 'public' in mods else 0
        if 'final' in mods: flags |= 0x0010
        if kind in ('interface', '@interface'):
            flags |= 0x0200 | 0x0400
            if kind == '@interface': flags |= 0x2000
            sup = 'java/lang/Object'
            ifaces = split_types(ext) if ext else []
            if kind == '@interface': ifaces = ['java.lang.annotation.Annotation']
        else:
            if 'abstract' in mods: flags |= 0x0400
            if kind == 'enum':
                flags |= 0x4000; sup = 'java/lang/Enum'
            elif kind == 'record':
                sup = 'java/lang/Record'
            else:
                sup = split_types(ext)[0] if ext else 'java.lang.Object'
            ifaces = split_types(impl) if impl else []
        out[internal] = [flags, sup, ifaces, pkg]
    return out
def resolve(index):
    # Turn dotted names (java.lang.Object, android.app.Notification.Builder) into internal names,
    # using the index itself to tell nested classes from packages.
    names = set(index)
    def internal(dotted):
        parts = dotted.split('.')
        for cut in range(len(parts) - 1, 0, -1):
            cand = '/'.join(parts[:cut]) + '/' + '$'.join(parts[cut:])
            if cand in names: return cand
        # not in the index (a java.* type or another API surface): first capitalised segment starts the class
        for i, p in enumerate(parts):
            if p[:1].isupper(): return '/'.join(parts[:i]) + '/' + '$'.join(parts[i:])
        return '/'.join(parts)
    res = {}
    for n, (flags, sup, ifaces, pkg) in index.items():
        res[n] = (flags, sup if '/' in sup else internal(sup), [internal(i) for i in ifaces])
    return res
if __name__ == '__main__':
    idx = resolve(parse(open(sys.argv[1]).read()))
    print(len(idx), 'classes')
