# Peugeot 308 OEM Launcher v1.14

## Novidades da v1.14 — Som
- **Botão EQ** no topo, ao lado do Bluetooth e do Wi-Fi (continua tocável com o carro em movimento). Também em Config. > Som > "Abrir equalizador".
- **Equalizador de 10 bandas** (60, 230, 910, 3,6k, 5k, 11k, 13k, 14k, 16k e 19,9k Hz, as mesmas da central), faixa ±12 dB em passos de 0,5. Sliders grandes; dá para arrastar direto no gráfico para "desenhar" a curva; toque no número para zerar a banda.
- **Pré-ganho**, liga/desliga geral ("ATIVO"), **Redefinir** e **Comparar** (ouve o som original sem equalização).
- **Predefinições**: Usuário, Plano, Rock, Pop, Ao vivo, Eletrônica, Clássica, Suave, Jazz, Voz, Graves+ e Agudos+. **+ Salvar** guarda a curva atual com o nome que você quiser (até 12); segure uma predefinição sua para apagar.
- **Loudness dinâmico** (aba Ajustes): reforça graves e agudos quando o volume está baixo e some aos poucos quando sobe (transição suave, sem estalo). Intensidade e "começa a agir abaixo de" ajustáveis, com gráfico ao vivo. O tracejado no gráfico do equalizador mostra o que o loudness soma no volume atual.
- **Volume automático por velocidade** (aba Ajustes): soma de 1 a 6 níveis ao seu volume conforme o GPS (a partir de 40 km/h, máximo perto de 110), um nível a cada ~2 s. Se você mexer no volume, o novo valor vira a referência. Com isso ligado o GPS continua ativo com o launcher em segundo plano.
- **Campo sonoro** (aba): ponto de escuta arrastável, Motorista/Todos/Passageiro/Traseiros, balanço, fader e reforço de graves.
- **Tudo é salvo** e reaplicado quando o launcher abre (ao ligar o carro), ao acordar a tela e se o sistema de áudio reiniciar.
- **Diagnóstico** (aba Ajustes): mostra se o equalizador do Android foi aceito e quantas bandas ele tem, e o botão **Testar efeito (4 s)** aplica graves +9 dB e agudos −9 dB para você ouvir se algo muda.

## Limites (importante)
- O equalizador usa o **efeito de áudio do Android** (efeito global). Se a central rotear o som por um DSP/amplificador próprio (comum em Jancar), o Android pode aceitar o efeito sem o som mudar. O teste de 4 s e o diagnóstico existem para descobrir isso. O Android costuma ter só 5 bandas: as 10 da tela são "amostradas" nelas (agudos acima de 10 kHz ficam agrupados em uma banda).
- **Balanço e fader** não têm comando público no Android. Os valores ficam salvos e saem em broadcast `com.peugeot308.oem.AUDIO_COMMAND` (extras `balance`, `fader`, `bass`, `x`, `y`), igual ao CANBUS: só funcionam se a central (ou um app ponte) ouvir esse comando. O "reforço de graves" usa o BassBoost do Android.
- O volume por velocidade depende do GPS; se o Android da central limitar o GPS com o Waze na frente, ele só age com o launcher aberto.

# Peugeot 308 OEM Launcher v1.13

## Novidades da v1.13
- **Tema padrão "Peugeot Âmbar"**: fundo preto puro com luz âmbar (baixo ofuscamento à noite).
- **12 temas novos**: 5 com fundo preto (âmbar, vermelho, verde, branco, azul) e 7 com **cinza chumbo** (âmbar, laranja, vermelho, verde, azul, ciano, branco). Âmbar e vermelho agravam menos a visão noturna. Os 20 coloridos anteriores continuam (agora 32 temas, em Configurações > Aparência). No dia, os temas preto/chumbo viram cinza neutro claro.
- **Barra de separação** ao lado de "PEUGEOT 308", igual à do lado do Bluetooth.
- **Popups padronizados**: todos abrem centralizados, com tamanho proporcional à tela, fundo escurecido e no estilo do tema (sem a barra do sistema reaparecer e deslocar a janela). Janelas de texto sobem para não ficarem atrás do teclado. Avisos rápidos (antes Toast do Android) usam o mesmo estilo. O aviso de radar/limite agora aparece no centro, com véu e animação, e fecha ao toque.
- **Atualização pelo GitHub** (Configurações > Sistema): consulta a última Release do repositório, compara a versão (v1.13 > 1.12; 1.10 > 1.9), baixa o APK direto do GitHub e abre o instalador sozinho quando termina. Verificação automática a cada 12 h com internet (e ao conectar), com aviso uma vez por versão. O servidor local da versão anterior continua como fonte alternativa.

## Como publicar uma atualização no GitHub
1. Repositório **público** (ex.: `usuario/peugeot308-launcher`).
2. Gere o APK, vá em *Releases > Draft a new release*, crie a tag **`v1.14`** (maior que a instalada) e anexe o `.apk`. Publique (não como rascunho nem pre-release).
3. No launcher: Configurações > Sistema > Definir repositório (`usuario/repositorio`) > Buscar atualização.
4. Na primeira instalação, o Android pede para permitir "instalar de fontes desconhecidas" para o launcher; depois a atualização é de um toque.
Obs.: o APK precisa ser assinado com a mesma chave do instalado, senão o Android recusa a atualização (use sempre o mesmo keystore).

# Peugeot 308 OEM Launcher v1.12

## Novidades da v1.12
- **Topo**: "308" colado no "PEUGEOT" e com um brilho que corre pelo logo a cada poucos segundos; chips de BT/Wi-Fi e Config. só com ícones (o ícone acende na cor do tema quando conectado e apaga quando não); sem bolinhas de status.
- **Mini cards no topo** (3 posições): escolha em Config. > Veículo o que mostrar em cada um: velocidade, temperatura do motor, RPM, consumo instantâneo, limite de velocidade, temperatura externa, rumo, altitude, autonomia, combustível, portas. Itens de CANBUS mostram `--` até a canbox conectar.
- **Botões do carro** (MENU, PC BORDO, VOLTAR) em destaque, com ícones. **Segure o botão** na tela para renomear (nome e comando); também dá para editar em Config. > Veículo.
- **Alertas** (Config. > Alertas): aviso ao passar do limite de velocidade (popup + som opcional) e aviso de radares à frente (experimental, dados do OpenStreetMap via internet, só no sentido da viagem). O som pode ser desligado e deixar só o popup.
- **Mudo**: o popup mostra um alto-falante com sinal de mudo em vermelho e um botão grande "TOQUE AQUI PARA TIRAR DO MUDO"; tocar em qualquer parte do cartão tira do mudo (volta aos poucos).
- **Menus de configuração 50% maiores** e a janela ocupa quase a tela inteira.
- Previsão: valores de vento e umidade subiram para não encostar no fim do cartão.

## Limites dos alertas
O popup é desenhado pelo launcher: aparece com o launcher na tela. Com outro app na frente (ex.: Waze) ele não aparece, e o Android limita o GPS de apps em segundo plano. Para radares com o Waze aberto, use o Radarbot ou o próprio Waze avisando em segundo plano. Mostrar o popup sobre outros apps exigiria um serviço em primeiro plano e a permissão de sobreposição (não incluído).

# Peugeot 308 OEM Launcher v1.11

## Novidades da v1.11
- **Fade de entrada ao ligar**: ao ligar o carro (boot recente) ou acordar a central, o volume sobe de 0 até o nível anterior em 1,8 s (mesma rampa do mudo). Se o Spotify só retomar depois, o fade se repete uma vez (janela de 2 min). Liga/desliga em Configurações > Som, com botão de teste.
- **Modo direção**: acima de 10 km/h (GPS ou CAN; ajustável) o dock mostra só o essencial (Waze, Rádio, Spotify, Telefone, Casa, Trabalho), some o botão Config. e os apps fixados, BT/Wi-Fi viram só indicadores e janelas abertas (Config./Apps) são fechadas. Sai depois de 6 s parado. Perda de GPS mantém o modo por 1 min. Botão "Testar modo direção (15 s)" em Configurações > Veículo.
- **Brilho automático** acompanhando o tema dia/noite (padrão 100% de dia, 40% à noite, ajustável). Vale dentro do launcher; para valer no sistema inteiro, toque em "Permitir" (permissão Modificar configurações do sistema).
- **Casa e Trabalho** no dock: abrem o Waze já navegando (`waze://?q=...&navigate=yes`, ou `ll=` se for coordenada). Endereços em Configurações > Atalhos.
- **Último app** como atalho extra no dock: o último app aberto pelo launcher que ainda não está no dock.
- **Fundo com a cor da capa** do álbum, bem sutil (brilho de fundo + véu nos cartões), com transição suave.
- **20 temas de cores prontos** (Configurações > Aparência), com tingimento leve das superfícies e contraste automático do texto sobre a cor.
- Topo: **removida a temperatura do clima ao lado da velocidade**. A temperatura externa só aparece com CANBUS. Cada item do topo (velocidade, temperatura, consumo, rumo) pode ser ligado/desligado; alerta de velocidade opcional.
- **Menu do veículo** (Configurações > Veículo), desligado por padrão: 3ª coluna com **3 botões do carro** (padrão MENU, PC BORDO, VOLTAR; nome e comando editáveis) e os indicadores. Desligado, relógio/clima e o Spotify largo ocupam a tela sozinhos.
- Configurações agora rolam em todas as abas.

## Botões do carro e CANBUS (importante)
Ainda não existe protocolo CANBUS conhecido da Jancar neste projeto. Hoje cada botão:
1. envia um broadcast `com.peugeot308.oem.VEHICLE_COMMAND` com os extras `command` (texto), `button` (0..2) e `label`, para um app/serviço ponte ouvir;
2. mostra o aviso "CANBUS não conectado: o comando não chegou ao carro".

Para falar de verdade com o carro, implemente `canSend(String command)` em `MainActivity.java` (já é chamado ao tocar no botão) quando soubermos como a central transmite ao módulo CAN (modelo da caixa decodificadora / serviço da Jancar).

## Observações
- O GPS liga se o menu do veículo, o widget do topo ou o modo direção estiverem ativos.
- "Último app" só conhece apps abertos pelo próprio launcher.
- Algumas centrais Jancar controlam o backlight por serviço próprio; nesse caso o brilho automático pode não ter efeito.
- Se houver falha gráfica, use `setLayerType(View.LAYER_TYPE_SOFTWARE, null);` no construtor de `Dashboard`.
- Mapa (OpenStreetMap) ficou para depois do CANBUS.
