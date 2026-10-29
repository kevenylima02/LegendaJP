"""
Junta os lotes conferidos (tools/grammar/parts/batch_XX.json) no banco que vai dentro do app:
app/src/main/assets/grammar_bank.json. Pontos com problema no check.py ficam de fora (e são listados).

Do Bunpro ficam só o nome do padrão, o nível e a ordem; o sentido em inglês não entra.

Uso: python -X utf8 tools/grammar/merge.py
"""
import glob
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "grammar_bank.json")
sys.path.insert(0, HERE)
from check import check_point  # noqa: E402

KEYS = ["leitura", "romaji", "resumo", "significado", "liga_a", "como_formar", "uso", "exemplos", "parecidos",
        "erros_comuns", "drill", "detectar"]


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    points, missing, bad = [], 0, []
    for f in sorted(glob.glob(os.path.join(HERE, "in", "batch_*.json"))):
        inp = json.load(open(f, encoding="utf-8"))
        part = os.path.join(HERE, "parts", os.path.basename(f))
        try:
            out = json.load(open(part, encoding="utf-8")) if os.path.exists(part) else []
        except ValueError:
            print("lote ilegível:", os.path.basename(part))
            out = []
        by_id = {p.get("id"): p for p in out if isinstance(p, dict)}
        for src in inp:
            p = by_id.get(src["id"])
            if p is None:
                missing += 1
                continue
            errs = check_point(p, src["id"])
            if errs:
                bad.append(f"{src['id']} {src['titulo']}: {'; '.join(errs)}")
                continue
            row = {"id": src["id"], "titulo": src["titulo"], "nivel": src["nivel"], "ordem": src["ordem"]}
            if src.get("app"):
                row["app"] = src["app"]
            for k in KEYS:
                row[k] = p[k]
            # Lacuna no meio do padrão: "…" (alguns lotes usaram espaço)
            row["leitura"] = " ".join(row["leitura"].split()).replace(" ", "…")
            points.append(row)
    # Exercícios de conjugação completados numa passada separada (parts/drills_extra.json: {id: drill})
    extra_path = os.path.join(HERE, "parts", "drills_extra.json")
    if os.path.exists(extra_path):
        extra = json.load(open(extra_path, encoding="utf-8"))
        added = 0
        for p in points:
            d = extra.get(p["id"])
            if d and not p["drill"] and not p.get("app"):
                trial = dict(p, drill=d)
                if not [e for e in check_point(trial, p["id"]) if e.startswith("drill")]:
                    p["drill"] = d
                    added += 1
        print(f"exercícios de conjugação extras: {added} de {len(extra)}")
    # Reconhecimento: um texto que indica mais de um padrão (ときたら ① e ②, ている) não indica nenhum.
    # Os padrões ligados ao catálogo do app são reconhecidos pelas regras do app, não por texto.
    from collections import Counter
    import re
    def base(t):
        return re.sub(r"[①②③④⑤\s]|\d$", "", t)
    linked = {base(p["titulo"]) for p in points if p.get("app")}
    for p in points:
        # ている② ao lado de ている① (do app): o texto sozinho não separa os sentidos
        if p.get("app") or base(p["titulo"]) in linked:
            p["detectar"] = []
    use = Counter(s for p in points for s in set(p["detectar"]))
    dropped = sorted(s for s, n in use.items() if n > 1)
    for p in points:
        p["detectar"] = [s for s in p["detectar"] if use[s] == 1]
    if dropped:
        print("textos de reconhecimento repetidos (fora):", "、".join(dropped))
    # Trava: nunca trocar o banco por um menor (ex.: rodar sem os lotes em parts/) sem pedir com --forcar
    if os.path.exists(OUT) and "--forcar" not in sys.argv:
        try:
            old = len(json.load(open(OUT, encoding="utf-8"))["points"])
        except Exception:
            old = 0
        if len(points) < old:
            print(f"PAREI: o banco atual tem {old} pontos e o novo teria {len(points)}. Nada foi gravado.")
            print("Confira tools/grammar/parts/ (e tools/grammar/in/, gerado por prep.py) ou rode com --forcar.")
            sys.exit(1)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump({"v": 1, "points": points}, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    for m in bad:
        print("FORA:", m)
    print(f"{len(points)} pontos no banco, {missing} ainda sem lote, {len(bad)} com problema; {os.path.getsize(OUT) // 1024} KB")


if __name__ == "__main__":
    main()
