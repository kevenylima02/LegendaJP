"""
Exploração da gramática do Bunpro (passo 1): imprime o JSON bruto para ver o formato real.

1. Lista pública (sem token): https://bunpro.jp/grammar_points traz todos os pontos no JSON
   __NEXT_DATA__ da página. Uma requisição só.
2. Endpoints autenticados da API "frontend" (opcional): só rodam se houver token em
   BUNPRO_TOKEN (variável de ambiente) ou em tools/bunpro/token.txt (ignorado pelo git).

Uso:
    python tools/bunpro/explore.py            # lista pública + exemplos
    python tools/bunpro/explore.py 1 2 469    # também os endpoints autenticados para esses IDs

Uso pessoal. Os dados ficam só neste computador (tools/bunpro/out/, ignorado pelo git).
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
API = "https://api.bunpro.jp/api/frontend"
PAGE = "https://bunpro.jp/grammar_points"
UA = "Mozilla/5.0 (Michi, uso pessoal)"
DELAY = 1.0  # segundos entre requisições

sys.stdout.reconfigure(encoding="utf-8")


def token():
    t = os.environ.get("BUNPRO_TOKEN", "").strip()
    if t:
        return t
    p = os.path.join(HERE, "token.txt")
    if os.path.exists(p):
        return open(p, encoding="utf-8").read().strip()
    return ""


def get(url, tok=None):
    """(status, corpo) sem levantar exceção: 401/404/500 voltam como status."""
    h = {"User-Agent": UA, "Accept": "application/json, text/html"}
    if tok:
        h["Authorization"] = "Bearer " + tok
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=30) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # rede, timeout
        return -1, str(e)


def public_list():
    st, body = get(PAGE)
    print(f"== {PAGE} → HTTP {st}, {len(body)} bytes")
    if st != 200:
        return None
    m = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', body, re.S)
    if not m:
        print("   sem __NEXT_DATA__ (o site mudou?)")
        return None
    pp = json.loads(m.group(1))["props"]["pageProps"]
    gps = pp.get("grammarPoints") or []
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "raw_grammar_points.json"), "w", encoding="utf-8") as f:
        json.dump(pp, f, ensure_ascii=False, indent=1)
    print(f"   grammarPoints: {len(gps)} · lessons: {len(pp.get('lessons') or [])}")
    print("   campos:", sorted({k for g in gps for k in g}))
    for g in gps[:2] + gps[len(gps) // 2: len(gps) // 2 + 1] + gps[-1:]:
        print("  ", json.dumps(g, ensure_ascii=False))
    by = {}
    for g in gps:
        by[g.get("level")] = by.get(g.get("level"), 0) + 1
    print("   por nível:", by)
    print(f"   JSON bruto salvo em {os.path.join(OUT, 'raw_grammar_points.json')}")
    return gps


def private(ids):
    tok = token()
    if not tok:
        print("== endpoints autenticados: sem token (BUNPRO_TOKEN ou tools/bunpro/token.txt); pulando")
        return
    failed = []
    for gid in ids:
        for url in (f"{API}/reviewables/grammar_point/{gid}",
                    f"{API}/notes/hydrate?noteable_id={gid}&noteable_type=GrammarPoint"):
            st, body = get(url, tok)
            print(f"== {url} → HTTP {st}")
            if st == 401:
                print("   token expirado ou inválido: copie de novo o cookie frontend_api_token")
                return
            if st != 200:
                failed.append((gid, url, st))
                print("  ", body[:300].replace("\n", " "))
            else:
                print("  ", body[:1500])
            time.sleep(DELAY)
    if failed:
        print("== falharam:", failed)


if __name__ == "__main__":
    public_list()
    ids = [a for a in sys.argv[1:] if a.isdigit()]
    if ids:
        time.sleep(DELAY)
        private(ids)
