# Michi (LegendaJP)

Captura o áudio interno do celular, transcreve em japonês com o SpeechRecognizer
on-device do Android e salva em `Download/LegendaJP/legenda_*.txt`.

- Cada push na branch `main` gera um APK em **Releases** (`/releases/latest`).
- Requer Android 13+.
- Na primeira vez: menu ⋮ → **Verificar japonês offline** para baixar o pacote.
- Fontes de áudio: **Áudio interno** (captura via MediaProjection; no diálogo escolha **Tela inteira**),
  **Microfone** e **Arquivo WAV** (reprocessa um .wav do app).
- O Spotify bloqueia a captura interna. Use o **Spotify Web** no Chrome (botão na tela) ou outro app de podcast.
- Cada transcrição vira `legenda_<data>.txt` (+ `.wav` se "Salvar áudio" estiver ligado, padrão sim).
  Nada é salvo se nenhuma frase foi reconhecida.
- **Gravações** lista as transcrições; no visualizador, toque numa frase para ouvir o trecho.
- **Legenda sobre os apps** (menu ⋮): legenda flutuante por cima de qualquer app durante a captura,
  como a legenda instantânea do Galaxy. Arraste pela barrinha para mover em qualquer direção; toque na
  barrinha alterna 2/5 linhas; jogue para a lateral para esconder num bloquinho na borda (toque nele para
  reabrir). Toque numa palavra para abrir o cartão logo abaixo.
  Precisa da permissão "Exibir sobre outros apps".
- **Vocabulário**: toque numa palavra (legenda flutuante, tela principal ou visualizador) para ver forma de
  dicionário, leitura, classe e conjugação (análise morfológica com Kuromoji, offline) e salvar. Palavras salvas
  ficam em lavanda em todas as legendas, em qualquer conjugação. A tela Vocabulário lista as palavras com as
  frases onde apareceram (com Ouvir) e exporta CSV para o Anki.
- **Tradução offline** (ML Kit, japonês → português): o cartão da palavra mostra a tradução da forma de
  dicionário; nas frases do Vocabulário há um botão Traduzir. O modelo (~60 MB) é baixado na primeira vez e
  cada texto só é traduzido uma vez (cache). Por isso o APK é por arquitetura (arm64-v8a para o celular).
- O .wav salvo é cortado no silêncio antes da primeira frase e depois da última; os tempos do .txt são ajustados.
- **Gramática da frase**: toque longo numa frase do visualizador lista os padrões gramaticais usados
  (catálogo N5–N3 com regras sobre o Kuromoji e explicações em português).
- **Estudar** (botão no visualizador): menu com foco (Misto, Gramática, Vocabulário, Kanji) e contagem de
  exercícios disponíveis; sessão de até 12 itens (ordenar a frase, lacuna gramatical, identificar o padrão,
  lacuna com áudio, tradução↔palavra, montar a palavra com kanji, leitura↔palavra em kanji). O áudio toca só o
  trecho da frase. Cada sessão fica no histórico da gravação, item a item, com a resposta dada e a certa.
  Nos exercícios de leitura as alternativas erradas têm o mesmo okurigana da palavra (歩く: あるく / あゆく /
  あるぐ / かく; e 歩く / 書く / 泣く / 暫く), geradas de outras leituras do kanji no KANJIDIC2, confusões
  de som (dakuten, vogal longa, っ, ん, ゃゅょ) e kanji com o mesmo okurigana, para o final não entregar a resposta.
- **Sessão de estudo**: botão Anterior para rever itens já respondidos (só leitura); ao terminar, os errados
  voltam até acertar (escrita à mão em 3 passos: traçar sobre o kanji em cinza, sem modelo com botão Lembrar,
  sem ajuda); Refazer repete os mesmos itens; sair no meio pede confirmação; só entra no histórico o que foi
  respondido.
- **Depois de responder**: significado da palavra, conjugação na frase, leituras on/kun e significado de cada
  kanji, a frase com tradução e a ordem dos traços animada.
- **Tela da palavra**: ordem dos traços de cada kanji, leituras e significados, e **Estudar esta palavra**
  (exercícios de kanji e vocabulário só dela; inclui "Significado do kanji").
- **Gramática para usar** (Conj + GrammarDrill): conjugador de verbos (godan, ichidan, する, 来る) e adjetivos-い
  a partir do Kuromoji, e a regra de ligação de ~50 padrões do catálogo. Exercícios: Qual forma combina,
  Transforme com o padrão (erros comuns como alternativas), Português → padrão e Escrever com o padrão
  (frase livre corrigida pela IA). O Ordenar a frase mostra a tradução como guia.
- **Reportar erro** (⋮ no exercício e na escolha de palavras) e tela **Reportes** (⋮ na tela principal):
  tradução da palavra ou da frase pode ser corrigida com "✦ Corrigir com IA" e aceita (vale no app inteiro);
  "Copiar todos" leva os outros para ajuste no código. Tudo fica no celular.
- **Dificuldade do áudio** no visualizador: % de palavras que você conhece (verde), está estudando (lavanda)
  e novas, nível JLPT estimado do texto e padrões de gramática por nível.
- **Furigana configurável** (Configurações → Furigana: Todos / Só os que não domino / Nenhum): a leitura por cima
  dos kanji nos textos, no visualizador, na tela principal, na escolha de palavras, na tela da palavra e nos
  exercícios (frase da lacuna, gramática, legenda do áudio, frase do retorno, flashcards). "Não domino" tira o
  furigana das palavras Já conheço e das com cartão de kanji dominado (estabilidade ≥ 21 dias). A palavra de um
  exercício de kanji nunca ganha leitura antes da resposta. Na legenda flutuante é opcional (desligado).
- **Textos** (botão na tela principal): colar, compartilhar de outro app (Compartilhar → Michi), arquivo .txt,
  foto ou imagem da galeria (ML Kit lê no celular; "✦ Ler com IA" usa o Gemini, melhor para texto vertical e
  mangá). O leitor tem cara de página (temas Papel, Sépia e Noite em ⋮ → Tema da página), progresso e volta
  de onde parou. A barra embaixo muda o que o toque faz: ✋ só rola; ✨ varinha abre um balão com leitura,
  classe, significado, ☆ Salvar e Já conheço; 🖍 marcador livre: arrasta o dedo sobre um trecho e abre o
  bloco com tradução (ML Kit e ✦ IA), as palavras do trecho (salvar/conheço), gramática, Ouvir e **Salvar
  como expressão** (o trecho vira uma entrada do vocabulário e fica destacado e tocável onde aparecer — útil
  quando o Kuromoji corta a palavra errado); 🔊 lê a frase tocada (áudio do texto ou voz do celular),
  destacando-a. Também ふ (furigana), A− e A+. Toque longo na frase: tradução, gramática e áudio. Menu:
  Estudar, traduções embaixo dos parágrafos, ✦ Traduzir, Criar áudio. Salvos como texto_AAAAMMDD_HHMMSS.txt
  (#p entre parágrafos).
- **Pastas** (Gravações e Textos): fichas Tudo / Sem pasta / suas pastas em cima da lista, com a contagem; as
  mesmas pastas valem para as duas telas (cada uma mostra só os itens dela). Toque longo num item: Mover para
  pasta, Selecionar vários (marca e move de uma vez), Renomear, Apagar. Toque longo numa pasta: Estudar a
  pasta inteira (as frases de todos os itens juntas), Renomear, Apagar (os itens voltam para Sem pasta). Um
  texto criado ou importado dentro de uma pasta já entra nela. Só no banco: os arquivos não mudam de lugar.
- **Vocabulário**: filtros Todas / Estudando / Conheço / Novas (sem exercício ainda) / Com kanji / Expressões,
  cada um com a contagem; ordenar por mais recentes, leitura, mais ou menos dominadas, mais frequentes; a
  linha de resumo mostra quantas estão em estudo e quantas você já conhece.
- **Importar texto** (Gravações → ⋮): colar ou escolher .txt/.srt. Formato Michi (modelo em ⋮ → Formato do texto):
  `# michi: 1`, `# titulo: …`, `# locutores: A=Nome (feminino), B=Nome (masculino)`, e uma frase por linha
  `A: 日本語のフレーズ。 | tradução`. Vira uma gravação sem áudio, com título, locutores e traduções.
- **Gerar áudio** (visualizador → ⋮): voz do celular (TTS do Android, grátis e offline) ou Gemini TTS
  (gemini-3.8-flash-lite-tts, blocos de até 12 frases com até 2 vozes, dividido nas pausas; espera o limite
  de 3 pedidos por minuto do plano grátis). Escolha em Configurações. Cada frase começa num segundo exato.
  A voz do celular usa só vozes instaladas; se uma voz falha numa frase, o locutor passa para outra voz
  (mesmo gênero, depois qualquer uma, depois a padrão) e a frase é tentada de novo; o erro mostra a voz e o
  motivo. Cada frase gerada fica guardada; se parar no meio, dá para completar com o outro motor
  ("Completar com o Gemini" / "Completar com a voz do celular").
- **Locutores**: ao terminar uma gravação com áudio (com o Gemini na nuvem; chave em Configurações → IA) o
  app identifica quem fala pela voz e chama de Personagem 1, 2, 3…; "✦ Identificar locutores (IA)" faz o
  mesmo a pedido (partes de 6 min). Nomes e tipo de voz em "Locutores (nomes)". A etiqueta colorida aparece
  em cada frase.
- **Revisão espaçada** (botão Revisar na tela principal e menu ⋮): FSRS 4.5 (o algoritmo do Anki), retenção de 90%.
  Cartões de significado e de kanji para cada palavra salva (o "Estudar" da escolha de palavras salva a palavra)
  e de gramática para os padrões que aparecem nos exercícios. Toda primeira resposta conta (acertou = Bom,
  errou = De novo, "Foi fácil" = Fácil). A revisão de hoje usa os nossos exercícios com frases das gravações,
  flashcards (frente/verso, notas De novo/Difícil/Bom/Fácil com o próximo intervalo) ou os dois (Misto).
  Novos por dia em Configurações (10 por padrão).
- **✦ IA** (Configurações → Inteligência artificial: Desligada, Gemini Nano no aparelho ou Gemini na nuvem):
  bloco no fim do retorno dos exercícios e botão ✦ IA na tela da palavra. Traduz a frase com contexto
  (a transcrição inteira até 60 min na nuvem; ±2 min no Nano), explica a palavra na frase e a gramática, sempre no mesmo formato.
  As respostas ficam salvas. A chave do Gemini nunca vai para o git: em Configurações, ou embutida na
  compilação a partir de `gemini.key` no local.properties ou do segredo `GEMINI_API_KEY` do Actions.
- **Duas traduções, ML Kit ⇄ Gemini**: as do Gemini (✦ IA e "✦ Traduzir gravação com IA" no menu do
  visualizador) ficam ao lado das do ML Kit, sem substituir. Onde aparece a tradução de uma frase, a etiqueta
  diz a fonte e tocar alterna; o Gemini aparece primeiro quando houver (Configurações). A gravação inteira é
  traduzida em partes (80 frases na nuvem, 15 no Nano) com a conversa como contexto e barra de progresso.
- **Legenda no exercício**: botão Legenda; durante o áudio a palavra falada acende. Gravações novas pedem o
  tempo de cada palavra ao reconhecedor do Android (quando ele informa); nas outras o tempo é estimado
  pela voz no WAV, com o aviso "ⓘ tempo estimado".
- **Escolher palavras** antes de estudar (Misto, Vocabulário e Kanji): um cartão por palavra com leitura,
  significado, kanji (leituras, partes e ordem dos traços) e a frase com áudio; Pular, Já conheço ou Estudar,
  até o número de Configurações (10 por padrão). Os exercícios saem só dessas palavras.
- Na escolha de palavras ficam de fora as já estudadas (com cartão já respondido; voltam pela Revisão), as
  puladas nas últimas 24 h e as "Já conheço". Ordem: Aleatória, Mais frequentes ou Ordem do áudio (no
  vocabulário salvo, Recentes), sempre com os **níveis marcados** primeiro.
- **Níveis JLPT** (Configurações): marque um ou mais; é prioridade, não filtro. Palavras e exercícios dos níveis
  marcados vêm primeiro, o resto depois.
- **Montar o kanji com as partes**: o quadrado do kanji com uma lacuna por parte (雪 = 雨 + ⺕) e peças
  desenhadas a partir do KanjiVG (assets/parts.txt).
- **Já conheço** (no cartão da palavra e na tela da palavra): a palavra sai dos exercícios, da fila de erros e do
  destaque nas legendas; "Estudar esta palavra" continua funcionando para ela.
- **Meia tela**: com pouca altura (tela dividida, janela, paisagem) a tela principal vira uma barra fina
  (estado, Iniciar/Parar, Vocab, Gravações, menu) e a legenda ocupa o resto.
- **Revisar erros** (menu ⋮ e menu Estudar): fila única com tudo que foi errado; sessões de 10 itens
  aleatórios; acertou, sai da fila.
- **Estudar no Vocabulário**: sessão só com as palavras salvas e as frases de origem (áudio da gravação de
  origem, escolhida ao acaso quando a frase aparece em várias). Cada palavra tem duas barrinhas, kanji e
  significado (0 a 1000: +10 por acerto, −5 por erro).
- O nível em Configurações vale para gramática, kanji e vocabulário (listas de kanji N5–N2 embutidas):
  itens do seu nível ou acima entram primeiro.
- **Escrever a palavra à mão** (exercício de kanji, difícil): uma lacuna por kanji; desenhe com o dedo e o app
  reconhece (ML Kit Digital Ink, modelo de japonês baixado na primeira vez, ~20 MB).
- **Configurações** (menu ⋮): nível JLPT (os exercícios de gramática preferem padrões do seu nível ou acima),
  página com a gramática do catálogo por nível, tipos de exercício ligados/desligados com a dificuldade de cada
  um, legenda flutuante e salvar .wav.

## Dados de terceiros

- Ordem dos traços: [KanjiVG](https://kanjivg.tagaini.net), Ulrich Apel, CC BY-SA 3.0 (`app/src/main/assets/strokes.txt`).
- Leituras e significados dos kanji: [KANJIDIC2](https://www.edrdg.org/wiki/index.php/KANJIDIC_Project),
  Electronic Dictionary Research and Development Group, CC BY-SA 4.0 (`app/src/main/assets/kanjidic.txt`).

## Compilar localmente (Windows)

Requer JDK 17 e o Android SDK (o AGP baixa a plataforma 35 sozinho).

```bash
JAVA_HOME="C:\Users\keven\.jdks\jdk-17.0.20.1+1" ./gradlew assembleDebug
```

APK gerado em `app/build/outputs/apk/debug/app-debug.apk`.
