# Atualizações do WinDock

O WinDock instalado via MSI em Windows x64 verifica a última release estável do repositório
`arthurriosprogramador/win-dock` 20 segundos após abrir e a cada 6 horas enquanto estiver em execução.
Nas configurações há também **Verificar atualizações**. O próprio WinDock consulta o GitHub e usa o
Windows Installer para instalar a versão escolhida; a distribuição não passa pelo Windows Update.

O modal mostra a versão e as notas da release. **Agora não** dispensa essa versão até a próxima abertura
do app; a consulta manual continua disponível. Nenhum instalador é baixado antes de **Atualizar agora**.
Durante o download é possível cancelar. O arquivo é gravado em disco, conferido pelo tamanho e pelo
SHA-256 da API do GitHub e só então passado ao instalador.

Um processo auxiliar espera o dock fechar, confere novamente o SHA-256 e executa o MSI com
`/passive /norestart`. O encerramento normal devolve a barra nativa ao Windows; depois da instalação
o WinDock abre novamente e carrega as preferências existentes. Erros do instalador são mostrados e,
quando possível, a versão anterior é reaberta. Logs ficam em `%APPDATA%\WinDock\updates\download-*\`.
O hash valida a integridade do download; ele não substitui a assinatura de código do publicador.

## Desinstalação e arquivos em uso

O empacotamento incorpora ao MSI uma ação de manutenção antes de `InstallValidate`. Na remoção
ou reparação de uma instalação existente, ela solicita o encerramento dos processos `WinDock.exe`
da sessão atual, aguarda sua saída e permite que o shutdown hook restaure a barra nativa.
O helper usa as mensagens de sessão do Windows, sem encerrar processos à força. Execuções do
Android Studio (`java.exe`/`javaw.exe`) não são alvos dessa ação.

O MSI define `MSIRESTARTMANAGERCONTROL=Disable` para desativar a interação com o Restart Manager
e `REBOOT=ReallySuppress` para impedir reinicialização automática. Bloqueios reais ainda podem
produzir o diálogo tradicional de arquivos em uso ou exigir reinicialização posterior. Isso não
garante que qualquer arquivo possa ser removido enquanto estiver aberto por outro processo.
As mudanças valem para a instalação feita com este MSI; uma versão antiga já instalada utiliza
seu próprio pacote em cache na desinstalação.

`tools/configure-msi.ps1` é executado também pelo `:composeApp:packageMsi`, antes da cópia e do
SHA-256 da release. Se adicionar assinatura de código, assine **depois** dessa etapa e calcule o
hash do arquivo assinado. A alteração no banco MSI invalida qualquer assinatura anterior.
Valide desinstalação com o dock aberto e com Android Studio/NVIDIA Overlay em execução em uma
VM antes da distribuição; o empacotamento não desinstala programas da máquina de desenvolvimento.

Referência: [controle do Restart Manager no MSI](https://learn.microsoft.com/en-us/windows/win32/msi/msirestartmanagercontrol).

## Preparar uma release

1. Aumente `appVersion` em `gradle.properties`, sempre `MAJOR.MINOR.PATCH` (por exemplo, `1.1.1`).
   A versão embutida no aplicativo e a do MSI são geradas a partir do mesmo valor.
2. Em Windows x64, com JDK e as ferramentas de empacotamento MSI usadas pelo projeto:

   ```powershell
   .\tools\package-release.ps1 -Version 1.1.1
   ```

   O comando executa os testes e empacota `build\release\1.1.1\WinDock.msi`. Não publica nada.
   `-Version` também permite gerar uma versão específica sem editar o arquivo de propriedades.
3. Crie a release **v1.1.1** no GitHub, envie **WinDock.msi** e escreva as notas da atualização.
   O tag precisa corresponder exatamente à versão usada no empacotamento.
4. Publique como release estável e marque como **latest**. Drafts, prereleases, versões iguais ou
   inferiores e releases sem o MSI esperado não são oferecidos. O GitHub fornece o digest SHA-256
   do asset; se ele não estiver disponível ou for inválido, o atualizador recusa a atualização.

Mantenha o nome `WinDock`, a instalação por usuário e o `windows.upgradeUuid` do Gradle:
é essa identidade que permite ao MSI atualizar a instalação existente.
O nome público do asset deve continuar `WinDock.msi`, preservando também o link do README.

## Primeira versão e validação

A versão `1.0.0` originalmente distribuída não contém este mecanismo. Para o teste local, geramos
uma nova build `1.0.0` que já inclui o atualizador. Instale essa build manualmente e depois publique
uma release estável `v1.0.1`, marcada como latest, com o novo `WinDock.msi`.
Se já houver uma instalação 1.0.0 antiga ou uma versão superior, desinstale-a antes de instalar
esta base de teste; não apague `%APPDATA%\WinDock`, onde ficam as preferências.
A release 1.0.0 antiga do GitHub não é alterada automaticamente pelo empacotamento local.
Execuções pelo Android Studio permitem consultar releases manualmente, mas não substituem a
instalação do Windows nem fazem verificação periódica.

Os testes locais cobrem comparação de versões, política de releases/URLs e integridade do arquivo.
Antes de distribuir, valide em uma VM Windows com duas versões MSI: configure um tema e pins na
primeira, ofereça uma release superior, teste **Agora não**, download/cancelamento, instalação,
reabertura e preservação das preferências. Repita com rede indisponível e instalador inválido.
Essa validação completa precisa de releases/instalações reais; não é executada pelos testes unitários.

Referências: [API de releases do GitHub](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)
e [opções do Windows Installer](https://learn.microsoft.com/en-us/windows/win32/msi/standard-installer-command-line-options).
