# Leitor de Tela 455 — pacotes offline

O aplicativo foi preparado para oferecer instalação individual de pacotes de tradução ML Kit e OCR Tesseract no próprio Android.

## Funcionamento pretendido
1. Abra a tela Gerenciar idiomas offline.
2. Escolha um idioma e toque em Instalar pacote.
3. O pacote de tradução e o arquivo OCR são baixados uma vez.
4. Selecione Usar este idioma.
5. Ative o leitor. A captura, o OCR e a tradução são executados localmente.

A internet é necessária apenas para obter os pacotes. O leitor não envia o texto capturado para um serviço de tradução.

## Compatibilidade e limitações
A lista inclui idiomas asiáticos para os quais o ML Kit oferece modelos de tradução. O OCR usa arquivos Tesseract individuais para cada escrita. A qualidade depende da resolução, fonte, contraste e idioma; é necessário validar em aparelhos reais com exemplos de cada escrita. Não se deve considerar uma língua testada apenas porque o pacote instalou.

## Gerar APK
O workflow GitHub Actions gera o APK de depuração quando há alterações em app/**, build.gradle.kts, settings.gradle.kts ou no workflow.
