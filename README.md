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
  como a legenda instantânea do Galaxy. Arraste para mover, toque para mostrar mais linhas.
  Precisa da permissão "Exibir sobre outros apps".
- **Vocabulário**: toque numa palavra (legenda flutuante, tela principal ou visualizador) para ver forma de
  dicionário, leitura, classe e conjugação (análise morfológica com Kuromoji, offline) e salvar. Palavras salvas
  ficam em lavanda em todas as legendas, em qualquer conjugação. A tela Vocabulário lista as palavras com as
  frases onde apareceram (com Ouvir) e exporta CSV para o Anki.
- **Tradução offline** (ML Kit, japonês → português): o cartão da palavra mostra a tradução da forma de
  dicionário; nas frases do Vocabulário há um botão Traduzir. O modelo (~60 MB) é baixado na primeira vez e
  cada texto só é traduzido uma vez (cache). Por isso o APK é por arquitetura (arm64-v8a para o celular).
- O .wav salvo é cortado no silêncio antes da primeira frase e depois da última; os tempos do .txt são ajustados.

## Compilar localmente (Windows)

Requer JDK 17 e o Android SDK (o AGP baixa a plataforma 35 sozinho).

```bash
JAVA_HOME="C:\Users\keven\.jdks\jdk-17.0.20.1+1" ./gradlew assembleDebug
```

APK gerado em `app/build/outputs/apk/debug/app-debug.apk`.
