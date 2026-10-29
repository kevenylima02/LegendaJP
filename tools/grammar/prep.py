"""
Prepara a geração do banco de gramática do Michi (app/src/main/assets/grammar_bank.json).

Entrada: a lista do Bunpro (tools/bunpro/out/bunpro_grammar.json, fora do git) e o catálogo do app
(Grammar.kt). Saída: tools/grammar/in/batch_XX.json (fora do git), cada um com ~25 pontos:
    id ("bp:<id do Bunpro>" ou "cat:<id do app>"), titulo, leitura, nivel, ordem, slug,
    sentido_en (só como pista para quem escreve; não vai para o banco), app (id do padrão do app ligado).

Os padrões do app que já têm par no Bunpro entram pelo ponto do Bunpro (campo "app");
os que não têm par entram como "cat:<id>".

Uso: python -X utf8 tools/grammar/prep.py
"""
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
BP = os.path.join(ROOT, "tools", "bunpro", "out", "bunpro_grammar.json")
GK = os.path.join(ROOT, "app", "src", "main", "java", "com", "keveny", "legendajp", "Grammar.kt")
OUT = os.path.join(HERE, "in")
SIZE = 25


def catalog():
    src = open(GK, encoding="utf-8").read()
    out = []
    for m in re.finditer(r'pt\("([^"]+)",\s*"([^"]+)",\s*"(N\d)",\s*"((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)"\)', src):
        if m.group(1) not in [c["id"] for c in out]:
            out.append({"id": m.group(1), "name": m.group(2), "level": m.group(3), "expl": m.group(4), "ex": m.group(5)})
    return out


def norm(s):
    for c in "～~〜 　①②③④⑤⑥⑦⑧⑨":
        s = s.replace(c, "")
    return s.strip()


def keys(p):
    parts = [p["title"]] + p["title"].split("・")
    m = re.match(r"^[A-Za-z\-]*[［\[](.*)[］\]]$", p["title"].strip())
    if m:
        parts += m.group(1).split("・")
    out = []
    for x in parts:
        n = norm(x)
        if not n:
            continue
        out.append(n)
        if n.endswith("だ") and len(n) > 2:
            out.append(n[:-1])
    out.append(norm(p["slug"]))
    return out


ALIAS = {
    "mada-te-inai": "まだ-ていません", "mou-ta": "もう", "rareru": "Verb[passive]", "saseru": "causative",
    "you": "よう-おう", "adj-kunai": "negative-い-adjectives", "imperative": "命令形", "hou-ga": "より-のほうが",
    "te-kuremasenka": "ていただけませんか", "hou-ga-ii": "たほうがいい", "tokoro": "るところだ", "aida-ni": "の間に",
    "tara-dou": "たらどう", "nai": "うverb--ない", "to-cond": "と-conditional", "te": "verb-て", "you-ni": "ように", "amari-nai": "あまり-ない", "tame-ni": "ため-に", "nakute": "なくて", "to-omou": "とおもう", "hajimeru": "はじめる", "kata": "かた", "shi": "し-し", "mae-ni": "まえに", "ato-de": "あとで",
}


def links(points, cat):
    by_key = {}
    for p in points:
        for k in keys(p):
            by_key.setdefault(k, p)
    by_slug = {p["slug"]: p for p in points}
    used, out = set(), {}
    for c in cat:
        hit = by_slug.get(ALIAS.get(c["id"], "\0"))
        if hit is None:
            name = c["name"].split(" (")[0].split("（")[0]
            for a in [norm(x) for x in name.split("／") if norm(x)]:
                h = by_key.get(a)
                if h and h["id"] not in used:
                    hit = h
                    break
        if hit is not None and hit["id"] not in used:
            out[c["id"]] = hit
            used.add(hit["id"])
    return out


def main():
    pts = json.load(open(BP, encoding="utf-8"))["points"]
    cat = catalog()
    ln = links(pts, cat)
    app_of = {p["id"]: cid for cid, p in ln.items()}
    rows = []
    for p in sorted(pts, key=lambda x: x["order"]):
        r = {"id": f"bp:{p['id']}", "titulo": p["title"], "leitura": p["furigana"], "nivel": p["level"],
             "ordem": p["order"], "slug": p["slug"], "sentido_en": p["meaning"]}
        if p["id"] in app_of:
            c = next(x for x in cat if x["id"] == app_of[p["id"]])
            r["app"] = c["id"]
            r["app_nome"] = c["name"]
        rows.append(r)
    for i, c in enumerate(cat):
        if c["id"] not in ln:
            rows.append({"id": f"cat:{c['id']}", "titulo": c["name"], "leitura": "", "nivel": c["level"],
                         "ordem": 100000 + i, "slug": "", "sentido_en": "", "app": c["id"], "app_nome": c["name"],
                         "explicacao_app": c["expl"], "exemplo_app": c["ex"]})
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        os.remove(os.path.join(OUT, f))
    n = 0
    for i in range(0, len(rows), SIZE):
        n += 1
        json.dump(rows[i:i + SIZE], open(os.path.join(OUT, f"batch_{n:02d}.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"catálogo {len(cat)}, ligados {len(ln)}, só do app {len(cat) - len(ln)}, total {len(rows)}, lotes {n}")


if __name__ == "__main__":
    main()
