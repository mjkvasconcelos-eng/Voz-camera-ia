# Voz Câmera IA — versão inicial 0.1.0

Projeto Android nativo em Kotlin, com CameraX.

## O que já está incluído
- Visualização da câmera traseira.
- Captura de fotos em qualidade priorizada.
- Salvamento local em `Pictures/VozCameraIA`.
- Alternância visual entre IA automática e modo profissional.
- Compensação de exposição (EV) quando suportada pelo aparelho.

## Limitações honestas desta versão
Esta é uma base inicial, ainda não é a versão final com IA. Os botões de modo ainda não executam análise inteligente de cena. ISO, tempo de exposição, foco manual, RAW e seleção funcional da câmera frontal ainda precisam ser implementados e validados por aparelho. A disponibilidade depende do hardware e dos recursos expostos pelo fabricante.

## Compilação
O GitHub Actions compila o APK de depuração automaticamente. O projeto requer JDK 17 e Android SDK 35.
