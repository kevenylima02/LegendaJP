"""
Monta a lista de gramática do Bunpro para o Michi (passo 2 e 3) e salva em
tools/bunpro/out/bunpro_grammar.json (fora do git). O app importa esse arquivo; nada do Bunpro vai
para o código do app.

Fonte: a página pública https://bunpro.jp/grammar_points, que traz todos os pontos no JSON
__NEXT_DATA__. Uma requisição só (com nova tentativa espaçada se falhar); não precisa de token.

Campos de cada ponto:
    id, slug, title (japonês), furigana (título com leitura), meaning (inglês, curto),
    level ("N5".."N1", "Fora do JLPT" ou "Kansai-ben"), keywords (romaji e variações, para a busca),
    order (ordem do Bunpro), lesson (número da lição).

Uso:
    python tools/bunpro/build_grammar.py
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
PAGE = "https://bunpro.jp/grammar_points"
UA = "Mozilla/5.0 (Michi, uso pessoal)"
TRIES = 3
DELAY = 1.0  # segundos; dobra a cada nova tentativa

LEVELS = {"JLPT5": "N5", "JLPT4": "N4", "JLPT3": "N3", "JLPT2": "N2", "JLPT1": "N1",
          "Non-JLPT": "Fora do JLPT", "関西弁": "Kansai-ben"}

sys.stdout.reconfigure(encoding="utf-8")


def fetch():
    wait = DELAY
    last = None
    for attempt in range(1, TRIES + 1):
        try:
            req = urllib.request.Request(PAGE, headers={"User-Agent": UA, "Accept": "text/html"})
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}"
            if e.code in (401, 403, 404):
                break  # não adianta tentar de novo
        except Exception as e:
            last = str(e)
        print(f"tentativa {attempt}: {last}; nova tentativa em {wait:.0f} s")
        time.sleep(wait)
        wait *= 2
    raise SystemExit(f"Não consegui baixar {PAGE}: {last}")


def main():
    html = fetch()
    m = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', html, re.S)
    if not m:
        raise SystemExit("A página não tem __NEXT_DATA__ (o site mudou?). Rode explore.py para ver o formato.")
    raw = json.loads(m.group(1))["props"]["pageProps"].get("grammarPoints") or []
    points, skipped = [], []
    for g in raw:
        try:
            title = (g.get("title") or "").strip()
            if not title or g.get("id") is None:
                skipped.append(g.get("id")); continue
            level = LEVELS.get(g.get("level"), g.get("level") or "?")
            points.append({
                "id": int(g["id"]),
                "slug": g.get("slug") or "",
                "title": title,
                "furigana": (g.get("furigana") or title).strip(),
                "meaning": (g.get("meaning") or "").strip(),
                "level": level,
                "keywords": (g.get("metadata") or "").strip(),
                "order": g.get("grammar_order") or 0,
                "lesson": g.get("lesson_id") or 0,
            })
        except Exception as e:  # um ponto estranho não derruba o resto
            skipped.append(g.get("id")); print("pulei", g.get("id"), e)
    points.sort(key=lambda p: p["order"])
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "bunpro_grammar.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"source": "bunpro.jp/grammar_points", "fetched": time.strftime("%Y-%m-%d"), "points": points},
                  f, ensure_ascii=False, indent=1)
    by = {}
    for p in points:
        by[p["level"]] = by.get(p["level"], 0) + 1
    print(f"{len(points)} pontos salvos em {path}")
    print("por nível:", by)
    if skipped:
        print("pulados (sem título ou id):", skipped)


if __name__ == "__main__":
    main()
