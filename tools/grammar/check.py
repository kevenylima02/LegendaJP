"""
Confere um lote do banco de gramática (ver tools/grammar/FORMATO.md).

Uso: python -X utf8 tools/grammar/check.py tools/grammar/parts/batch_XX.json [...]
Sai com código 1 e lista os problemas se algum lote estiver errado.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

LIGA = {"radical_masu", "dicionario", "te", "ta", "nai", "volitiva", "ba", "tara", "passiva", "causativa", "potencial",
        "imperativa", "forma_simples", "substantivo", "adjetivo_i", "adjetivo_na", "frase", "inicio_de_frase", "sozinho"}
VF = {"DICT", "STEM", "TE", "TA", "NAI", "NA", "VOL", "BA", "PASS", "CAUS", "MASU", "IMP", "ZU", "CAUSPASS"}
AF = {"DICT", "STEM", "KU", "KATTA", "KUTE", "KEREBA"}


def is_text(x, n=1):
    return isinstance(x, str) and len(x.strip()) >= n


def check_point(p, want_id):
    e = []
    if p.get("id") != want_id:
        e.append(f"id {p.get('id')!r} devia ser {want_id!r}")
    for k in ("leitura", "romaji", "resumo", "significado", "uso"):
        if not is_text(p.get(k)):
            e.append(f"{k} vazio")
    if is_text(p.get("resumo")) and len(p["resumo"]) > 60:
        e.append(f"resumo com {len(p['resumo'])} letras (máx. 60)")
    liga = p.get("liga_a")
    if not isinstance(liga, list) or not liga or any(c not in LIGA for c in liga):
        e.append(f"liga_a inválido: {liga!r}")
    cf = p.get("como_formar")
    if not isinstance(cf, list) or not cf or not all(is_text(x) for x in cf):
        e.append("como_formar deve ser lista de linhas")
    ex = p.get("exemplos")
    if not isinstance(ex, list) or len(ex) != 5:
        e.append("exemplos: precisa de exatamente 5")
        ex = ex if isinstance(ex, list) else []
    for i, x in enumerate(ex):
        if not isinstance(x, dict):
            e.append(f"exemplo {i}: não é objeto")
            continue
        jp, alvo = x.get("jp", ""), x.get("alvo", "")
        if not is_text(jp) or not is_text(x.get("pt")) or not is_text(alvo):
            e.append(f"exemplo {i}: jp/pt/alvo vazio")
        elif alvo not in jp:
            e.append(f"exemplo {i}: alvo {alvo!r} não está em {jp!r}")
        er = x.get("erradas")
        if not isinstance(er, list) or len(er) != 3 or not all(is_text(w) for w in er):
            e.append(f"exemplo {i}: erradas precisa de 3 textos")
        elif alvo in er or len(set(er)) != 3:
            e.append(f"exemplo {i}: erradas repetidas ou igual ao alvo")
    par = p.get("parecidos")
    if not isinstance(par, list) or len(par) > 3 or not all(isinstance(x, dict) and is_text(x.get("padrao")) and is_text(x.get("diferenca")) for x in par):
        e.append("parecidos: 0 a 3 objetos {padrao, diferenca}")
    er = p.get("erros_comuns")
    if not isinstance(er, list) or not (1 <= len(er) <= 3) or not all(is_text(x) for x in er):
        e.append("erros_comuns: 1 a 3 frases")
    d = p.get("drill", "faltando")
    if d == "faltando":
        e.append("drill faltando (use null)")
    elif d is not None:
        if not isinstance(d, dict):
            e.append("drill deve ser objeto ou null")
        else:
            v, a = d.get("verbo", []), d.get("adj", [])
            if not isinstance(v, list) or not isinstance(a, list) or (not v and not a):
                e.append("drill sem verbo nem adj")
            for f in v if isinstance(v, list) else []:
                if not (isinstance(f, list) and len(f) == 2 and f[0] in VF and isinstance(f[1], str)):
                    e.append(f"drill.verbo inválido: {f!r}")
            for f in a if isinstance(a, list) else []:
                if not (isinstance(f, list) and len(f) == 2 and f[0] in AF and isinstance(f[1], str)):
                    e.append(f"drill.adj inválido: {f!r}")
            if not is_text(d.get("sentido")) or "…" not in d.get("sentido", ""):
                e.append("drill.sentido vazio ou sem …")
            t = d.get("teste")
            need = (["かく", "たべる", "する", "くる"] if v else []) + (["たかい"] if a else [])
            if not isinstance(t, dict) or any(not is_text(t.get(k)) for k in need):
                e.append(f"drill.teste precisa de {need}")
    det = p.get("detectar")
    if not isinstance(det, list) or not all(is_text(x, 2) for x in det):
        e.append("detectar deve ser lista de textos")
    elif det and not any(any(s in x.get("jp", "") for s in det) for x in ex if isinstance(x, dict)):
        e.append("nenhum texto de detectar aparece nos exemplos")
    return e


def check_file(path):
    name = os.path.basename(path)
    inp = json.load(open(os.path.join(HERE, "in", name), encoding="utf-8"))
    try:
        out = json.load(open(path, encoding="utf-8"))
    except Exception as ex:
        return [f"{name}: JSON inválido: {ex}"]
    errs = []
    if not isinstance(out, list) or len(out) != len(inp):
        return [f"{name}: precisa ser lista com {len(inp)} pontos (tem {len(out) if isinstance(out, list) else '?'})"]
    for want, p in zip(inp, out):
        for m in check_point(p if isinstance(p, dict) else {}, want["id"]):
            errs.append(f"{name} {want['id']} {want['titulo']}: {m}")
    return errs


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    allerr = []
    for f in sys.argv[1:]:
        allerr += check_file(f)
    for m in allerr:
        print(m)
    print("OK" if not allerr else f"{len(allerr)} problema(s)")
    sys.exit(1 if allerr else 0)
