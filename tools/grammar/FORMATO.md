# Banco de gramática do Michi

O arquivo `app/src/main/assets/grammar_bank.json` vai dentro do app. Ele tem todo o material de
cada padrão: a tela de detalhe, os exemplos, os exercícios e o reconhecimento nas frases. Foi escrito
uma vez e não depende de IA nem de internet no celular.

Do Bunpro vêm só o nome do padrão, o nível e a ordem. Todo o texto em português, os exemplos e os
dados de exercício foram escritos para o Michi.

## Como gerar de novo

1. `python tools/bunpro/build_grammar.py` baixa a lista (fora do git).
2. `python -X utf8 tools/grammar/prep.py` divide em lotes em `tools/grammar/in/` (fora do git).
3. Cada lote vira `tools/grammar/parts/batch_XX.json` no formato abaixo.
4. `python -X utf8 tools/grammar/check.py tools/grammar/parts/batch_XX.json` confere um lote.
5. `python -X utf8 tools/grammar/merge.py` junta tudo no asset do app.

Onde fica cada coisa:

- `app/src/main/assets/grammar_bank.json`: o banco pronto, no git. Toda build usa este arquivo; nada
  é gerado de novo na build nem no celular.
- `tools/grammar/parts/`: os lotes escritos (fonte do banco), no git. `drills_extra.json` tem os
  exercícios de conjugação completados depois.
- `tools/grammar/in/`: as entradas com dados do Bunpro, fora do git. Saem de novo com `prep.py`.

O `merge.py` não grava um banco menor que o atual, para não apagar o material por engano (use
`--forcar` se for de propósito).

## Um lote (`parts/batch_XX.json`)

Uma lista JSON, um objeto por ponto do lote de entrada, na mesma ordem, com estes campos:

| Campo | O que é |
|---|---|
| `id` | O mesmo `id` da entrada (`bp:123` ou `cat:ta`). |
| `leitura` | O título todo em hiragana (sem ～ e sem a parte em inglês). |
| `romaji` | O padrão em romaji, para a busca. |
| `resumo` | O sentido em português, curto (até 60 letras). Aparece na lista. |
| `significado` | O que o padrão quer dizer, em 1 a 3 frases. |
| `liga_a` | Lista de códigos da forma a que o padrão se liga (tabela abaixo). |
| `como_formar` | Lista de linhas: como montar, com exemplos curtos. Verbos: um exemplo do grupo 1, do grupo 2 e do grupo 3 quando o padrão se liga a verbos. |
| `uso` | Quando usar: registro (formal, casual, escrito), nuances, 2 a 4 frases. |
| `exemplos` | Exatamente 5 objetos `{jp, pt, alvo, erradas}` (ver abaixo). |
| `parecidos` | 0 a 3 objetos `{padrao, diferenca}`. |
| `erros_comuns` | 1 a 3 frases sobre erros comuns de brasileiros. |
| `drill` | Exercício de conjugação, ou `null` (ver abaixo). |
| `detectar` | Textos que, quando aparecem numa frase, indicam o padrão quase com certeza. Lista vazia se não der. |

### Exemplos

- `jp`: frase natural e correta, com vocabulário do nível do padrão ou mais fácil. Kanji normal.
- `pt`: tradução natural em português do Brasil.
- `alvo`: o trecho exato de `jp` que é o padrão (precisa estar dentro de `jp`). Sem o radical da
  palavra a que se liga: em 食べてしまった, o alvo é てしまった.
- `erradas`: 3 textos que poderiam ocupar o lugar do `alvo`, mas ficam errados nessa frase com essa
  tradução: conjugação errada, partícula errada ou padrão parecido com outro sentido. Nunca uma
  alternativa que também estaria certa.

### Códigos de `liga_a`

`radical_masu`, `dicionario`, `te`, `ta`, `nai`, `volitiva`, `ba`, `tara`, `passiva`, `causativa`,
`potencial`, `imperativa`, `forma_simples` (qualquer forma simples: dicionário, た, ない, なかった),
`substantivo`, `adjetivo_i`, `adjetivo_na`, `frase` (frase inteira ou oração), `inicio_de_frase`
(conjunção ou expressão que abre a frase), `sozinho` (palavra ou expressão usada sozinha).

### `drill`

Só quando o padrão se liga a um verbo ou adjetivo-い conjugado e o resultado é exatamente
"forma conjugada + sufixo fixo". Senão, `null`.

```json
{"verbo": [["TE", "しまう"]], "adj": [], "sentido": "acabar … (sem querer / por completo)",
 "teste": {"かく": "かいてしまう", "たべる": "たべてしまう", "する": "してしまう", "くる": "きてしまう"}}
```

- Formas de verbo: `DICT`, `STEM` (radical da ます: たべ, かき), `TE`, `TA`, `NAI`, `NA` (ない sem o い:
  たべな, かかな), `VOL`, `BA`, `PASS`, `CAUS`, `MASU`, `IMP`, `ZU`, `CAUSPASS`.
- Formas de adjetivo-い: `DICT`, `STEM` (たか), `KU`, `KATTA`, `KUTE`, `KEREBA`.
- O sufixo é o que vem depois da forma, como se escreve normalmente.
- Mais de um par em `verbo` quer dizer que todas as formas valem.
- `sentido`: o sentido curto em português com … no lugar da palavra.
- `teste`: o resultado com かく, たべる, する, くる (se tem `verbo`) e たかい (se tem `adj`), usando o
  primeiro par: a forma conjugada em hiragana seguida do sufixo do jeito que ele está escrito (pode ter
  kanji: かき始める, かく前に). O app confere com o conjugador dele.

### `detectar`

Textos literais, do jeito que aparecem nas frases, incluindo as formas conjugadas comuns
(わけにはいかない, わけにはいきません, わけにもいかない). Só textos com 3 letras ou mais, ou que não
apareçam por acaso em outras palavras. Partículas, padrões de uma letra e padrões que dependem só da
conjugação ficam com a lista vazia.
