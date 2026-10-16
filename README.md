# Oráculo Cripto (simulação)

App Android que:

1. **Escaneia o CoinGecko** (top 100 por valor de mercado) e escolhe **1 ativo por dia** — o mais "operável" para curto prazo (amplitude de ~4% em 24h, boa liquidez, giro saudável). Stablecoins e moedas sem par com preço de 1 min ficam de fora.
2. **Acompanha o preço minuto a minuto** (velas de 1 min da API pública da Binance, só leitura, sem conta e sem chave).
3. **Prevê a cada minuto** se o preço, daqui a 5 minutos, estará acima do atual, com uma **rede neural pequena que roda no próprio celular**.
4. **Aprende com o resultado**: acerto → +recompensa e reforço leve; erro → −punição (maior se estava confiante) e passo de aprendizado maior; amostras antigas são revisitadas (replay). O modelo e o placar ficam salvos e continuam evoluindo nos dias seguintes.
5. **Opera numa carteira 100% simulada** (US$ 1.000 fictícios, com taxas e derrapagem). Não existe nenhuma função de ordem real.

## Como gerar o APK (sem instalar nada no PC)

1. Crie um repositório **privado** no GitHub e envie o conteúdo desta pasta (botão *Add file → Upload files*, ou `git push`).
2. Abra a aba **Actions → "Gerar APK" → Run workflow** (ou espere o push disparar sozinho).
3. Em ~3 min, baixe o artefato **oraculo-cripto-apk** → `app-debug.apk`.

*Alternativa:* abra a pasta no **Android Studio** e use *Build → Build APK(s)*.

## Instalar no Galaxy A55

1. Passe o `app-debug.apk` para o celular (Telegram "Mensagens salvas", Drive, cabo…).
2. Toque no arquivo e permita *Instalar apps desconhecidos* para o app que o abriu.
3. Abra **Oráculo Cripto**, aceite as notificações e toque em **Iniciar oráculo**.
4. Para rodar com a tela apagada: *Configurações → Apps → Oráculo Cripto → Bateria → Sem restrições*. Deixar carregando é o ideal (o app mantém o processador acordado enquanto roda).

## Como ler a tela

- **Gráfico**: pontos verdes = acertou, vermelhos = errou, cinza = aguardando; a seta tracejada é a previsão atual.
- **Margem de erro 95%**: a taxa de acerto *verdadeira* pode estar em qualquer ponto dessa faixa. Previsões vizinhas se sobrepõem no tempo, então o cálculo já desconta isso.
- **Palpites bobos**: “repetir a tendência” e “sempre alta”. Se o oráculo não ganha deles, ele não está aprendendo nada útil.
- **Selo**: fica verde só quando, com ≥ 500 previsões, o limite inferior da margem de erro passa de 50%.
- **Carteira**: mostra o resultado já com taxas. Acertar mais de 50% **não basta** — cada operação custa ~0,24% e o movimento típico de 5 min é de ~0,15–0,25%.

## Testes (rodam em qualquer JDK, sem Android)

```
git clone --depth 1 https://github.com/stleary/JSON-java.git /tmp/JSON-java
bash tools/run-tests.sh /tmp/JSON-java/src/main/java
```

- `CoreSelfTest`: em dados aleatórios o oráculo fica em ~50% e o selo **não** acende; em dados com padrão escondido ele aprende (~57%); salvar/carregar, dados ruins e scanner.
- `SessionTest`: ciclo completo com corretora falsa — escolha do ativo do dia, 600 min ao vivo sem duplicar previsões, recuperação após 40 min de celular dormindo, queda de rede, troca de ativo, persistência e virada de dia.

## Limites conhecidos

- **Não testei o APK em um aparelho nem compilei a camada de tela/serviço** (este ambiente não tem o Android SDK). O núcleo, a rede e a sessão foram compilados e testados; a tela e o serviço foram revisados, mas o primeiro build pode pedir um ajuste.
- Android 15+ limita serviços de sincronização a ~6 h/dia; ao atingir o limite o app salva e para (basta tocar em Iniciar de novo).
- CoinGecko público tem limite de requisições; se aparecer erro 429, o app tenta de novo sozinho.
- A Binance pode ser bloqueada em algumas redes/países; o app tenta também o espelho `data-api.binance.vision`.
