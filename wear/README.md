# Super Mario World (SMW) para Wear OS

Port do clássico **Super Mario World** (baseado no engine nativo descompilado C/C++ `snesrev/smw` e frontend Android `freyta/smw-android`) com suporte total a smartwatches rodando **Wear OS** (Galaxy Watch 4/5/6/7, Pixel Watch, Xiaomi Watch 2, etc.).

## 🎮 Controles Inteligentes (Mesma Lógica do gen1recomp)

A tela circular do relógio se transforma no controle do jogo de maneira intuitiva e sem poluição visual por botões opacos sobre a tela:

| Ação / Gesto | Botão Mapeado | Comportamento |
| :--- | :--- | :--- |
| **Deslize rápido (Flick)** | **D-Pad (Cima / Baixo / Esquerda / Direita)** | 1 passo / 1 toque direcional rápido |
| **Deslizar e manter o dedo** | **Andar contínuo** | O Mario continua andando; a âncora segue o dedo suavemente, permitindo curvar o movimento livremente como um analógico |
| **Toque no Meio (Metade Esquerda)** | **B** | Pulo tradicional / Confirmação de menus |
| **Toque no Meio (Metade Direita)** | **A** | Spin Jump (Pulo giratório que quebra blocos) |
| **Toque no Topo (Esquerda)** | **L** | Olhar/Scroll para a esquerda |
| **Toque no Topo (Direita)** | **R** | Olhar/Scroll para a direita |
| **Toque no Topo (Centro)** | **SELECT** | Soltar item de reserva (cogumelo/flor/pena) |
| **Toque na Base (Fundo Centro)** | **START** | Pausar jogo |
| **Deslizar com 2 dedos** | **Y + Direcional** | Correr e direcionar ao mesmo tempo (Mario Run / Dash) |
| **Girar Coroa / Touch Bezel** | **Cima / Baixo** | Navegar menus e opções no mapa |
| **Botão físico Back do relógio** | **START** | Pausar / Despausar |

### Feedback Visual & Tátil
- Cada botão acionado projeta arcos coloridos translúcidos na borda da tela com a letra de identificação (`A`, `B`, `L`, `R`, `START`, `SELECT`).
- O direcional ativo acende um arco luminoso branco na direção de movimento.
- Resposta tátil por vibração sutil (haptics) nos botões de sistema.

---

## 🚀 Como Compilar e Instalar

No terminal dentro de `smw-android`:

```bash
# Compilar o APK para Wear OS
gradlew :wear:assembleDebug

# Instalar via ADB no relógio conectado por Wi-Fi
adb connect <IP_DO_RELOGIO>:<PORTA>
adb install -r wear/build/outputs/apk/debug/wear-debug.apk
```

## 📦 Arquivo de Assets (`smw_assets.dat`)
Assim como na versão padrão, coloque o arquivo `smw_assets.dat` extraído da ROM limpa do SNES no diretório de arquivos do app no relógio:
```bash
adb push smw_assets.dat /sdcard/Android/data/net.freyta.smw.wear/files/
```
