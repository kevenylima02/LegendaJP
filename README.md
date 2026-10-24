# LegendaJP

Captura o áudio interno do celular, transcreve em japonês com o SpeechRecognizer
on-device do Android e salva em `Download/LegendaJP/legenda_*.txt`.

- Cada push na branch `main` gera um APK em **Releases** (`/releases/latest`).
- Requer Android 13+.
- Na primeira vez: toque em **Verificar japonês** para baixar o pacote offline.
- No diálogo de captura, escolha **Tela inteira**.

## Compilar localmente (Windows)

Requer JDK 17 e o Android SDK (o AGP baixa a plataforma 35 sozinho).

```bash
JAVA_HOME="C:\Users\keven\.jdks\jdk-17.0.20.1+1" ./gradlew assembleDebug
```

APK gerado em `app/build/outputs/apk/debug/app-debug.apk`.
