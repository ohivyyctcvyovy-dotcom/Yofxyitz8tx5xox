# Leitor de Tela (Japonês / Coreano / Inglês → Português)

## Abrir e gerar o APK
1. Instale o Android Studio (gratuito, para PC).
2. Abra a pasta `TelaLeitor` com "Open".
3. Espere o Gradle sincronizar (botão "Sync Now", se aparecer).
4. Menu Build > Build Bundle(s) / APK(s) > Build APK(s).
5. O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

## Usar
1. Abra o app, escolha o idioma e toque em "Iniciar leitura".
2. Conceda "Exibir sobre outros apps" e aceite a captura de tela.
3. Volte para o app com o texto. A tela escurece: arraste sobre o texto.
4. Ouça a tradução. Para outra área, use "Selecionar área" na notificação.

## Observações
- Na primeira tradução de cada idioma, é preciso internet para baixar o modelo.
- Apps com proteção de tela (streaming, bancos) mostram tela preta. Isso é uma restrição do Android.
