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

## Compilar localmente (Windows)

Requer JDK 17 e o Android SDK (o AGP baixa a plataforma 35 sozinho).

```bash
JAVA_HOME="C:\Users\keven\.jdks\jdk-17.0.20.1+1" ./gradlew assembleDebug
```

APK gerado em `app/build/outputs/apk/debug/app-debug.apk`.
