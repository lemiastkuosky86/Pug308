# Peugeot 308 Launcher v1.14 — GitHub Actions

## Como compilar sem computador
1. Crie um repositório privado no GitHub.
2. Envie os arquivos DE DENTRO desta pasta para a raiz do repositório (não envie apenas o ZIP como um arquivo).
3. Abra **Actions > Compilar Peugeot 308 APK > Run workflow**.
4. Ao finalizar, abra **Artifacts** e baixe `Peugeot308Launcher-1.14-v114`.
5. Extraia o ZIP do artifact e pegue `app-debug.apk`.

## ATENÇÃO — atualizar o launcher já instalado
Mesmo pacote e versionCode mais alto NÃO bastam: o APK precisa ser assinado com a mesma chave da instalação atual.
Sem configurar a chave original, o GitHub assina com uma chave DEBUG nova, que NÃO atualiza o APK anterior.
Não desinstale o launcher atual só para testar: suas configurações podem ser apagadas.

Para usar a chave original, obtenha no computador que gerou o APK anterior o arquivo de assinatura
(para builds debug normalmente `%USERPROFILE%\.android\debug.keystore`). Não envie a chave no chat
nem faça commit no repositório. Gere o Base64 localmente e configure os Secrets do repositório:

- `PEUGEOT_KEYSTORE_BASE64`: conteúdo Base64 do arquivo `.keystore`
- `PEUGEOT_KEYSTORE_PASSWORD`: senha do keystore
- `PEUGEOT_KEY_ALIAS`: alias da chave
- `PEUGEOT_KEY_PASSWORD`: senha da chave

Para uma chave DEBUG padrão, normalmente o alias é `androiddebugkey` e as senhas são `android`,
mas confirme: isso NÃO substitui a necessidade do arquivo original.

## Detalhes do APK
Pacote: `com.peugeot308.oem`
Versão visível: `1.14`
versionCode: `114` (maior que 21/22/23/24 anteriormente usados)
minSdk: 26
compileSdk: 35
Java: 17; Gradle: 8.9; Android Gradle Plugin: 8.7.3.

O arquivo `update.json` usa o mesmo versionCode. Ele é apropriado para o servidor local.
As atualizações do GitHub Release dependem de publicar o APK como asset de uma Release e
configurar o repositório público no launcher; um artifact do Actions NÃO é uma Release.

O ZIP foi preparado para compilação online, mas não foi compilado neste ambiente.
