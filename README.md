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
- **Sessão de estudo**: botão Anterior para rever itens já respondidos (só leitura); ao terminar, os errados
  voltam até acertar (escrita à mão em 3 passos: traçar sobre o kanji em cinza, sem modelo com botão Lembrar,
  sem ajuda); Refazer repete os mesmos itens; sair no meio pede confirmação; só entra no histórico o que foi
  respondido.
- **Depois de responder**: significado da palavra, conjugação na frase, leituras on/kun e significado de cada
  kanji, a frase com tradução e a ordem dos traços animada.
- **Tela da palavra**: ordem dos traços de cada kanji, leituras e significados, e **Estudar esta palavra**
  (exercícios de kanji e vocabulário só dela; inclui "Significado do kanji").
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
