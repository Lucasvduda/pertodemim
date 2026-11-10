# PertoDeMim

Aplicativo para comunidades carentes (ODS 3, saúde e bem-estar, e ODS 10, redução das desigualdades) encontrarem o serviço público certo, perto e com o profissional presente. Quem quebra a perna precisa de um ortopedista: o app faz a triagem do que a pessoa descreve, indica a especialidade e a urgência, mostra as unidades próximas **que têm esse profissional agora** e permite agendar consulta, pedir documentos ou assistência social.

Projeto da Global Solution 2026/2 (CitizenTech), FIAP.

> **Dados simulados.** As unidades e coordenadas são reais (CNES, 51 unidades de São Paulo). Fila, presença de profissional, conexão dos sensores, demanda, a oferta de especialidades por unidade e os números de impacto são **simulados**. Nenhum número deste projeto é medição real.

## As quatro áreas e onde cada uma roda

| Área | O que é | Pasta | Como rodar (seção) |
|---|---|---|---|
| 1. IA | Triagem em 3 camadas (red flags, classificador por regras ou LLM, validação) + avaliação + simulação de impacto | `area3_mvp/api` (código), `area1_ia` (avaliação) | [Triagem e IA](#triagem-e-ia) |
| 2. Big Data | Medalhão bronze, silver e gold com pandas + dashboard Streamlit com 5 indicadores | `area2_dados` | [Dados e dashboard](#dados-e-dashboard) |
| 3. MVP + IoT | API Spring Boot + front web + simulador IoT em Python (sem hardware) | `area3_mvp` | [Rodar a API e o simulador](#rodar-a-api-e-o-simulador) |
| 4. DevOps | Docker, compose, CI, testes automatizados, segurança | `area4_devops`, `.github`, `docker-compose.yml` | [Testes](#testes) e [Docker](#docker) |

```
pertodemim/
├── area1_ia/        frases_ouro.csv, avaliar_triagem.py, simulacao_impacto.py, resultados/
├── area2_dados/     gerar_dados_exemplo.py, etl.py, analitica.py, dashboard.py, data/ (dimensões)
├── area3_mvp/
│   ├── api/         Spring Boot 3.5 (Java 17), front em src/main/resources/static
│   └── iot_mock/    simulador.py, cenarios/*.json
├── area4_devops/    Dockerfile.python, verificar_tudo.py, PLANO_DE_TESTES.md, SEGURANCA.md, evidencias/
├── docs/            ARQUITETURA.md, ROTEIRO_PITCH.md
├── entrega/         modelo do .txt e script que monta o .zip
├── tools/           gerar_seed.py (gera o seed a partir de tools/cnes_raw.json), md_para_pdf.py
└── docker-compose.yml, .env.example, .github/workflows/ci.yml
```

## Rodar a API e o simulador

Requisitos: Java 17 e Maven 3.9 (ou use o `mvnw` incluído), Python 3.12.

```bash
# 1) API + front (http://localhost:8080). Banco H2 em memória, seed de 51 unidades carregado ao subir.
cd area3_mvp/api
mvn spring-boot:run            # ou ./mvnw spring-boot:run

# 2) Simulador IoT (outro terminal): marca o ortopedista como ausente nas unidades do cenário
cd area3_mvp/iot_mock
pip install -r requirements.txt
python simulador.py --api http://localhost:8080 --cenario cenarios/ortopedista_ausente.json --ciclos 3 --intervalo 5
python simulador.py --dry-run --cenario cenarios/normal.json     # só mostra os eventos, sem enviar
```

Abra `http://localhost:8080`, aceite o termo, crie uma conta e use "quebrei a perna" na triagem. Cenários disponíveis: `normal`, `ortopedista_ausente`, `fila_longa`, `posto_offline`, `pico_demanda`.

A API grava cada evento IoT, triagem e agendamento em `area2_dados/bronze/` (JSONL), que é a entrada do ETL.

## Triagem e IA

Três camadas, nesta ordem:

1. **Detector de sinais de emergência** (regras fixas). Se achar (dor no peito, convulsão, sangramento intenso, ideação suicida...), responde vermelho com orientação de ligar 192 (188 para ideação suicida) e **não chama o LLM**.
2. **Classificador**: `LLM_PROVIDER=rules` (padrão, sem chave, sem internet) ou um LLM compatível com a API da OpenAI (`groq`, `gemini`, `openai`, `ollama`).
3. **Validação**: a saída do LLM é tratada como não confiável. A lista de especialidades é fechada, o texto de orientação vem do app (nunca do LLM), qualquer falha cai nas regras, e o LLM não pode rebaixar uma urgência laranja ou pior detectada pelas regras.

O texto digitado é anonimizado (CPF, telefone, e-mail, CEP, nomes e endereços) antes de ser salvo ou enviado a um LLM.

```bash
# usar um LLM (opcional): chave gratuita do provedor, só no ambiente, nunca no código
export LLM_PROVIDER=groq GROQ_API_KEY=...        # PowerShell: $env:LLM_PROVIDER="groq"; $env:GROQ_API_KEY="..."
# avaliar a triagem da API em execução contra as 40 frases rotuladas
cd area1_ia && python avaliar_triagem.py --api http://localhost:8080 --rotulo regras
# simulação de impacto (1.000 pessoas, sem x com o app), com análise de sensibilidade
python simulacao_impacto.py
```

Leitura honesta dos números de avaliação: as regras foram ajustadas depois de conhecer as 40 frases do conjunto ouro, então o resultado delas nesse conjunto é otimista. A primeira execução, antes do ajuste, está em `area1_ia/resultados/conjunto_ouro_regras_1a_execucao.txt`. Por isso existe um segundo conjunto, `area1_ia/frases_validacao.csv` (40 frases novas, escritas depois do ajuste): a primeira execução nele achou duas falhas de segurança, já corrigidas e com teste (a anonimização apagava "rua e está desacordado" como se fosse endereço, e "não tenho dor no peito" disparava emergência). O registro está em `area1_ia/resultados/conjunto_validacao_1a_execucao_antes_das_correcoes.txt`. Depois das correções esse conjunto deixou de ser independente. O resultado com LLM vem de rodar `area1_ia/avaliar_triagem.py` com um provedor configurado (`openai`, `groq`, `gemini` ou `ollama`); o script falha se nenhuma frase for respondida pelo LLM, para não confundir com o resultado das regras. Já foi feito com o Gemini (plano gratuito): ver `area1_ia/resultados/RESUMO_LLM_REAL.md` e `area1_ia/demo_llm.py`.

## Dados e dashboard

```bash
cd area2_dados
pip install -r requirements.txt
python gerar_dados_exemplo.py        # 21 dias de histórico simulado (origem "exemplo")
python etl.py                        # bronze -> silver -> gold (idempotente)
streamlit run dashboard.py           # http://localhost:8501
```

O ETL junta o histórico de exemplo com o que a API gravou (origem `api`) e mostra a proporção na barra lateral do dashboard. Linhas inválidas vão para `silver/rejeitados.csv` com o motivo. Os 5 indicadores: espera estimada, cobertura de especialidade, demanda (com média móvel e variação semanal), impacto simulado (km e R$ evitados) e disponibilidade dos sensores com índice de pressão das unidades.

## Contrato IoT (sem hardware)

`POST /api/iot/eventos`, cabeçalho `X-Device-Key` (uma chave por unidade e tipo de evento). Corpo comum: `eventId` (idempotência), `unidadeId`, `tipo` (`fila`, `presenca`, `heartbeat`), `ts` (ISO-8601 UTC). Campos por tipo: `pessoas` (fila), `cbo` e `presente` (presença), `online` e `latenciaMs` (heartbeat).

Respostas: 201 evento novo, 200 duplicado (mesmo `eventId`), 400 validação, 401 sem chave ou chave inválida, 403 chave de outra unidade/tipo. A chave é derivada com HMAC-SHA256 a partir de `IOT_KEY_SEED` e só o hash SHA-256 é guardado. O simulador faz o mesmo cálculo; veja `area3_mvp/iot_mock/simulador.py` e `IotKeysTest`.

## Endpoints principais

| Método e caminho | Autenticação | Função |
|---|---|---|
| `POST /api/auth/register`, `/api/auth/login` | não | cadastro com consentimento LGPD e login (JWT) |
| `POST /api/triagem` | JWT | triagem + ranking de unidades |
| `GET /api/unidades?servico=&especialidade=&lat=&lon=&raioKm=` | JWT | ranking de unidades |
| `GET /api/unidades/{id}/painel` | JWT | painel simulado da unidade |
| `GET /api/agendamentos/horarios`, `POST /api/agendamentos`, `GET /api/agendamentos/me`, `DELETE /api/agendamentos/{id}` | JWT | agendamento |
| `POST /api/iot/eventos` | `X-Device-Key` | ingestão de eventos IoT simulados |
| `GET /actuator/health` | não | saúde do serviço |

## Docker

```bash
cp .env.example .env                       # opcional; sem ele valem os padrões de desenvolvimento
docker compose up --build api              # só a API e o front
docker compose --profile demo up --build   # API + simulador + ETL + dashboard (http://localhost:8501)
docker compose --profile dados run --rm etl
```

**Atenção:** os Dockerfiles, o `docker-compose.yml` e o workflow de CI foram escritos e revisados, mas **não foram executados** no ambiente de desenvolvimento (sem Docker instalado). O primeiro `docker compose build` e a primeira execução do GitHub Actions são o teste real. O `compose` valida a estrutura com `docker compose config -q` no CI.

## Testes

```bash
python area4_devops/verificar_tudo.py            # roda tudo e grava area4_devops/evidencias/verificacao.md
cd area3_mvp/api && mvn verify                   # Java: testes + cobertura Jacoco (target/site/jacoco)
cd area2_dados && python -m pytest -q            # idem em area1_ia, area3_mvp/iot_mock, area4_devops
```

O plano de testes por requisito está em [`area4_devops/PLANO_DE_TESTES.md`](area4_devops/PLANO_DE_TESTES.md) e a política de segurança em [`area4_devops/SEGURANCA.md`](area4_devops/SEGURANCA.md).

## Segurança, em resumo

JWT sem estado com BCrypt, cabeçalhos CSP/Referrer-Policy/X-Frame-Options, limite de requisições (429) em login e triagem, validação de entrada (400), erros padronizados sem stack trace, chaves de dispositivo só com hash armazenado, segredos só por variável de ambiente, varredura de segredos nos testes. Detalhes e limites em `SEGURANCA.md`.

## Limitações conhecidas

- Presença de profissional, fila e conexão são simuladas; não há integração com sistemas reais das unidades.
- A checagem de capacidade do agendamento não é atômica: duas pessoas no mesmo instante podem ocupar a última vaga (há teste do caso sequencial, não do concorrente).
- O limite de requisições é em memória, por instância.
- O H2 em memória perde os dados ao reiniciar (há driver PostgreSQL opcional por variáveis `SPRING_DATASOURCE_*`).
- A triagem não substitui avaliação médica; o app diz isso na interface.
- Docker não foi testado (não há Docker na máquina de desenvolvimento).
- A chamada real a LLM foi feita com o Gemini do plano gratuito (cota pequena: várias frases caíram no fallback por regras nas primeiras execuções). Só a melhor execução (31 de 40 frases pelo LLM) mede o modelo: serviço 100%, urgência exata 75%, 4 falsos vermelhos (excesso de cautela, acima do limite de 1). Detalhes e todas as execuções em `area1_ia/resultados/RESUMO_LLM_REAL.md`.

## Uso de IA generativa

<!-- PREENCHER PELO GRUPO: descreva com suas palavras, e com verdade, como a IA generativa foi usada neste projeto (ferramentas, em quê, o que o grupo revisou, testou e decidiu). Este texto não foi escrito pelo assistente de propósito. -->

_(a preencher pelo grupo)_

## Integrantes

_(a preencher: nome e RM de cada integrante)_

## Fontes e créditos

- Unidades de saúde: CNES/DATASUS (amostra de 51 unidades de São Paulo em `tools/cnes_raw.json`).
- Classificação de risco: protocolo de Manchester (tempos-alvo: vermelho 0, laranja 10, amarelo 60, verde 120, azul 240 minutos). Códigos de ocupação: CBO.
- Padrões reaproveitados de projetos anteriores do grupo: autenticação de dispositivo IoT por chave (PulseLink Beacon) e dashboard em Python (SmartAgro). Nenhum arquivo de credencial foi copiado.
