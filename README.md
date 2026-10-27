# WinDock

<p align="center">
  <a href="https://github.com/arthurriosprogramador/win-dock/releases/latest/download/WinDock.msi"><img src="docs/download.svg" alt="Baixar WinDock para Windows · Download for Windows" width="360"></a>
</p>

<p align="center">Windows 10 / 11 · <a href="#português">Português</a> · <a href="#english">English</a></p>

![Vidro / Glass](docs/gifs/glass.gif)

---

## Português

WinDock é uma barra de tarefas personalizada para Windows 10/11, feita com Kotlin e Compose Multiplatform. Ela substitui a barra nativa e mantém o que você já usa: apps fixados, janelas abertas, pesquisa, relógio, bateria, volume, clima e controle de música.

### Destaques

- Temas animados (Halloween, outono, Natal), além de Casual, Escuro, Flutuante e Vidro
- Mascotes que andam pela barra e param para fazer algo: gato, cachorro, onça, dragão ocidental e dragão oriental
- Prévia das janelas ao passar o mouse, menu de botão direito com as listas de atalhos do Windows
- Pesquisa de apps e configurações, fixar e desafixar apps, mostrar a área de trabalho
- Tema **Sistema**: devolve a barra de tarefas padrão do Windows com um clique

### Download

**[⬇ Baixar WinDock.msi](https://github.com/arthurriosprogramador/win-dock/releases/latest/download/WinDock.msi)** — depois é só abrir o arquivo baixado. A instalação é por usuário, sem pedir administrador. O instalador não é assinado, então o Windows pode mostrar o aviso do SmartScreen: clique em **Mais informações → Executar assim mesmo**.

### Como rodar o código

Requisitos: Windows 10 (2004+) ou 11 e JDK 17 ou mais novo.

```
./gradlew :composeApp:run
```

Na tela de personalização, escolha um tema e clique em **Aplicar no Windows**. Para voltar à barra normal, escolha o tema **Sistema**.

Instalador (MSI): `./gradlew :composeApp:packageMsi`

Atualizações: esta build inclui um atualizador que verifica novas releases e pede confirmação
antes de baixar e instalar. Consulte [como preparar e publicar atualizações](docs/auto-update.md).

---

## English

WinDock is a custom taskbar for Windows 10/11, built with Kotlin and Compose Multiplatform. It replaces the native taskbar and keeps what you already use: pinned apps, open windows, search, clock, battery, volume, weather and media controls.

### Highlights

- Animated themes (Halloween, autumn, Christmas), plus Casual, Dark, Floating and Glass
- Mascots that walk along the bar and stop to do something: cat, dog, jaguar, western dragon and eastern dragon
- Window previews on hover, right-click menu with Windows jump lists
- App and settings search, pin and unpin apps, show desktop
- **System** theme: gives the standard Windows taskbar back in one click

### Download

**[⬇ Download WinDock.msi](https://github.com/arthurriosprogramador/win-dock/releases/latest/download/WinDock.msi)** — then just open the downloaded file. It installs per user, with no administrator prompt. The installer isn't code-signed, so Windows may show a SmartScreen warning: click **More info → Run anyway**.

### Running from source

Requirements: Windows 10 (2004+) or 11 and JDK 17 or newer.

```
./gradlew :composeApp:run
```

In the settings window, pick a theme and click **Aplicar no Windows** (apply to Windows). To go back to the normal taskbar, pick the **Sistema** (System) theme.

Installer (MSI): `./gradlew :composeApp:packageMsi`

---

## Temas · Themes

| | |
|---|---|
| **Casual**<br>![](docs/gifs/casual.gif) | **Escuro · Dark**<br>![](docs/gifs/dark.gif) |
| **Flutuante · Floating**<br>![](docs/gifs/floating.gif) | **Vidro · Glass**<br>![](docs/gifs/glass.gif) |
| **Halloween · Fantasma / Ghost**<br>![](docs/gifs/halloween-ghost.gif) | **Halloween · Zumbis / Zombies**<br>![](docs/gifs/halloween-zombies.gif) |
| **Halloween · Doces ou travessuras / Trick or treat**<br>![](docs/gifs/halloween-trick-or-treat.gif) | **Outono · Autumn**<br>![](docs/gifs/autumn.gif) |
| **Natal · Boneco de neve / Snowman**<br>![](docs/gifs/christmas-snowman.gif) | **Natal · Oficina do Papai Noel / Santa's workshop**<br>![](docs/gifs/christmas-workshop.gif) |
| **Natal · Casa aconchegante / Cozy home**<br>![](docs/gifs/christmas-cozy.gif) | |

## Mascotes · Mascots

| | |
|---|---|
| **Gato · Cat**<br>![](docs/gifs/mascot-cat.gif) | **Cachorro · Dog**<br>![](docs/gifs/mascot-dog.gif) |
| **Onça · Jaguar**<br>![](docs/gifs/mascot-jaguar.gif) | **Dragão ocidental · Western dragon**<br>![](docs/gifs/mascot-western-dragon.gif) |
| **Dragão oriental · Eastern dragon**<br>![](docs/gifs/mascot-eastern-dragon.gif) | |
