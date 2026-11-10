# Roteiro do pitch: PertoDeMim (para a Larissa gravar)

Duração alvo: **3 minutos** (cerca de 380 palavras faladas, ritmo calmo). Se o enunciado da GS pedir outro tempo, corte ou estique a parte "Qualidade" e a "Demonstração".
Formato: voz da Larissa por cima da tela gravada; o rosto é opcional. Texto **falado** em itálico; ações de tela entre colchetes.

## Antes de gravar (10 minutos)

1. Terminal 1: `cd area3_mvp/api` e `mvn spring-boot:run`. Espere o log "Started PertoDeMimApplication".
2. Terminal 2: `cd area2_dados`, `python gerar_dados_exemplo.py`, `python etl.py`, `streamlit run dashboard.py` (abre em `localhost:8501`).
3. Terminal 3: `cd area3_mvp/iot_mock` e deixe pronto, **sem executar**: `python simulador.py --api http://localhost:8080 --cenario cenarios/ortopedista_ausente.json --ciclos 30 --intervalo 5`. Ele precisa ficar rodando durante a demonstração: o sinal de cada unidade vale por 2 minutos, e sem sinal fresco o app mostra "Sem confirmação" em todas as unidades (foi o que aconteceu no teste quando o simulador já tinha parado).
4. Navegador: abra `http://localhost:8080`, crie uma conta de teste antes e deixe a tela de triagem aberta (celular em modo responsivo, largura ~390 px).
5. Feche notificações, aumente a fonte do terminal, grave em 1080p.
6. **Faça um ensaio completo** antes da gravação final. O número do dashboard muda a cada geração de dados; leia o que estiver na tela, não o do roteiro.

## Roteiro

### 0:00 a 0:20 · O problema
[Tela: título "PertoDeMim" e o mapa/lista de unidades]

*"Imagine quebrar a perna e ir até a unidade de saúde mais perto de casa. Chegando lá, descobrir que não tem ortopedista hoje. Para quem depende de ônibus e perde um dia de trabalho, essa viagem perdida custa caro. É o que o PertoDeMim quer evitar, em apoio aos objetivos de desenvolvimento sustentável 3, saúde e bem-estar, e 10, redução das desigualdades."*

### 0:20 a 0:45 · A solução
[Tela: fluxo da `docs/ARQUITETURA.md` (diagrama "Visão geral")]

*"O PertoDeMim junta quatro coisas. Uma triagem com inteligência artificial que entende o que a pessoa escreve. Sensores simulados que avisam fila e presença de profissional em cada unidade. Um ranking que indica a unidade certa, não só a mais próxima. E um painel de dados para a gestão ver onde falta cobertura."*

### 0:45 a 1:30 · Demonstração (o momento principal)
[Terminal 3: executar o simulador. Mostrar as linhas de eventos saindo.]

*"Aqui o simulador faz o papel dos sensores: marca que o ortopedista não está na unidade mais próxima."*

[Navegador: digitar "quebrei a perna jogando bola" e enviar]

*"A pessoa escreve do jeito que fala. O app entende: ortopedia, urgência laranja, atendimento em até dez minutos."*

[Mostrar o resultado. Apontar a faixa amarela "A unidade mais próxima (X km) não tem este atendimento agora. Indicamos uma a Y km..." e a unidade indicada com a etiqueta "Profissional presente agora". A faixa só aparece quando a mais próxima realmente não tem o profissional: se não aparecer, o simulador não está rodando ou a localização padrão não cai perto de uma unidade sem ortopedista.]

*"Repare: a unidade mais próxima não tem ortopedista agora. O app indica a que tem, e mostra a diferença de distância, para a pessoa não perder a viagem."*

[Clicar em agendar, escolher um horário, confirmar, mostrar o protocolo]

*"Em dois toques, a consulta está marcada e a pessoa recebe o protocolo."*

[Digitar "estou com dor forte no peito" e enviar]

*"E se o caso for grave, a regra de emergência assume na hora: orienta ligar 192, sem esperar nenhuma resposta de modelo."*

### 1:30 a 1:55 · Inteligência artificial com cuidado
[Tela: diagrama das 3 camadas, ou o código `RespostaLlmParser`]

*"A triagem tem três camadas. Primeiro, regras fixas para emergência. Depois, o classificador, que pode ser um modelo de linguagem. Por fim, uma validação: a resposta do modelo nunca é aceita às cegas, a lista de especialidades é fechada e ele não pode diminuir a gravidade de um caso. O texto digitado é anonimizado antes de ser salvo."*

[OPCIONAL, só se o grupo quiser falar de como usou IA para fazer o projeto: **PREENCHER PELO GRUPO**, com suas palavras e com a verdade. Este trecho foi deixado em branco de propósito.]

### 1:55 a 2:20 · Dados
[Tela: dashboard Streamlit, passando pelas 5 abas]

*"Cada triagem, agendamento e evento dos sensores vira dado. Passa por três camadas, bruta, tratada e analítica, e alimenta cinco indicadores: espera estimada, cobertura de especialidades, demanda, impacto e disponibilidade dos sensores. Neste painel, a simulação mostra [LER O NÚMERO DE KM EVITADOS NA ABA 4] quilômetros evitados. Importante: são dados simulados, com premissas declaradas, não medição real."*

### 2:20 a 2:45 · Qualidade
[Tela: terminal com `python area4_devops/verificar_tudo.py` já executado, ou o arquivo `area4_devops/evidencias/verificacao.md`]

*"Para dar confiança, o projeto tem [LER O TOTAL DE TESTES DO verificacao.md] testes automatizados, de unidade, integração e estrutura, cobertura medida na API, esteira de integração contínua no GitHub e contêineres Docker. Segurança com autenticação, limite de requisições e nenhuma chave guardada no código."*

### 2:45 a 3:00 · Fecho
[Tela: logo/título e o link do repositório]

*"O PertoDeMim ainda usa dados simulados. O próximo passo é conectar com os sistemas reais das unidades. Mas a ideia já funciona: levar a pessoa certa ao lugar certo, na hora certa. Obrigada."*

## Se algo der errado na gravação

| Problema | O que fazer |
|---|---|
| A API demora a subir | Grave primeiro as partes do dashboard e do terminal; regrave a demonstração depois |
| A triagem não indicou ortopedia | Confirme que o simulador rodou **antes**; reinicie a API (o estado volta ao inicial) e repita |
| Limite de requisições (erro 429) | Espere 1 minuto |
| Dashboard sem dados | Rode `python gerar_dados_exemplo.py` e `python etl.py` de novo e atualize a página |
| Número diferente do roteiro | Leia o número que está na tela |

## O que NÃO dizer (para não prometer o que o projeto não faz)

- Não dizer que os dados são reais, nem que há sensores físicos: são simulados.
- Não dizer que o app faz diagnóstico: ele orienta e encaminha.
- Não afirmar números de economia como fato: são resultado de simulação.
- Não dizer que foi testado com Docker ou com um modelo de linguagem real, se isso ainda não foi feito (confirme antes de gravar).

## Onde cada ponto da avaliação aparece no vídeo

| Critério | Trecho |
|---|---|
| IA | 0:45 a 1:30 (triagem ao vivo) e 1:30 a 1:55 (camadas e segurança) |
| Big Data | 1:55 a 2:20 |
| MVP e IoT | 0:20 a 1:30 (simulador, ranking e agendamento) |
| DevOps | 2:20 a 2:45 |
| Pitch (problema, solução, impacto, honestidade) | 0:00 a 0:45 e 2:45 a 3:00 |
