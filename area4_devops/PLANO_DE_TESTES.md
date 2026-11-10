# Plano de testes

Cada requisito aponta para os arquivos de teste que o verificam. Os números atuais de testes e a cobertura estão em `evidencias/verificacao.md` (gerado por `python area4_devops/verificar_tudo.py`). Um teste em `area4_devops/tests` confere que todo arquivo citado aqui existe.

Legenda de tipo: **U** unitário, **I** integração (Spring + MockMvc, banco H2), **E** estrutura/contrato, **P** propriedade ou invariante, **S** segurança.

Abreviação: `J` = `area3_mvp/api/src/test/java/br/com/pertodemim`.

| ID | Requisito | Tipo | Arquivos de teste |
|---|---|---|---|
| T01 | Sinais de emergência nunca passam e não chamam o LLM; ideação suicida orienta 188 | U | `J/triagem/RedFlagDetectorTest.java`, `J/triagem/TriagemServiceTest.java` |
| T02 | Classificador por regras: serviço, especialidade e urgência; gírias e erros de digitação | U | `J/triagem/RegrasClassificadorTest.java`, `J/triagem/EspecialidadesTest.java` |
| T03 | Saída do LLM é não confiável: JSON inválido, especialidade fora da lista, urgência rebaixada, texto injetado | U/S | `J/triagem/RespostaLlmParserTest.java`, `J/triagem/OpenAiCompatProviderTest.java` |
| T04 | Anonimização antes de salvar ou enviar ao LLM | U/S | `J/triagem/AnonimizadorTest.java` |
| T05 | Qualidade da triagem em 40 frases rotuladas (recall do vermelho = 100%, limites mínimos por métrica) | P | `J/triagem/ConjuntoOuroTest.java`, `area1_ia/tests/test_avaliar_triagem.py` |
| T06 | Ranking de unidades: elegibilidade, ordem, raio, rebaixadas, sem elegível, distância | U/I | `J/ranking/RankingServiceTest.java`, `J/ranking/GeoTest.java`, `J/web/ApiIntegrationTest.java` |
| T07 | Ingestão IoT: autenticação por chave, idempotência, validação, concorrência, bronze | I/S | `J/iot/IotControllerTest.java`, `J/iot/IotKeysTest.java`, `area3_mvp/iot_mock/tests/test_simulador.py` |
| T08 | Autenticação e segurança: JWT, BCrypt, cabeçalhos, rate limit, erros sem stack trace, rotas protegidas | I/S | `J/security/JwtServiceTest.java`, `J/security/RateLimitTest.java`, `J/web/ApiIntegrationTest.java` |
| T09 | Agendamento: horários, capacidade, conflito (409), cancelamento só do dono | I | `J/web/ApiIntegrationTest.java` |
| T10 | Seed: 51 unidades, especialidades, serviços, dispositivos, determinismo | U | `J/seed/SeedTest.java`, `J/seed/CsvSimplesTest.java` |
| T11 | Front-end: estrutura, acessibilidade básica, sem `innerHTML`, sem CDN | E | `J/web/FrontEstruturaTest.java` |
| T12 | Simulador IoT: chaves idênticas às do Java, cenários, buffer e reenvio | U/P | `area3_mvp/iot_mock/tests/test_simulador.py` |
| T13 | ETL: validação, rejeitados, deduplicação, idempotência, fuso horário, tabelas gold com valores calculados à mão | U/P | `area2_dados/tests/test_etl.py`, `area2_dados/tests/test_analitica.py` |
| T14 | Gerador de dados e pipeline completo (determinismo, só origem `exemplo`, invariantes) | P | `area2_dados/tests/test_gerador_e_pipeline.py` |
| T15 | Dashboard: indicadores, filtros, casos vazios; execução real do Streamlit (AppTest) | U/I | `area2_dados/tests/test_dados_dashboard.py`, `area2_dados/tests/test_dashboard_app.py` |
| T16 | Simulação de impacto: determinismo, invariantes, premissas inválidas, sensibilidade | P | `area1_ia/tests/test_simulacao_impacto.py` |
| T17 | Estrutura do repositório, ausência de segredos, `.env`, compose/CI/Dockerfile coerentes | E/S | `area4_devops/tests/test_estrutura.py` |

| T18 | Sondagem ponta a ponta na API em execução: rotas protegidas, JWT adulterado e `alg=none`, validação, corpo de 2 MB, IoT sem chave, cabeçalhos, endpoints de administração fechados, agendamento alheio, 429 por força bruta | S | `area4_devops/sondagem_api.py` (manual, com a API no ar; 24 verificações) |

## Casos de borda que têm teste

- Triagem (regressões achadas pelo conjunto de validação): "rua e está desacordado" não é endereço, os sinais de emergência são procurados no texto original (a anonimização não pode escondê-los), "não tenho dor no peito nem falta de ar" não alerta, mas "não tenho dor no peito e desmaiei" alerta.
- Triagem: texto vazio, só espaços, 500 e 501 caracteres, sem consentimento, JSON inválido, latitude e raio fora do intervalo.
- Evento IoT: data no futuro, valor negativo, campos ausentes, tipo desconhecido, CBO fora da lista ou que a unidade não oferece, chave de outra unidade ou tipo, mesmo `eventId` enviado em paralelo.
- Ranking: nenhuma unidade elegível (mostra as mais próximas com aviso), heartbeat expirado, check-in antigo, fila desatualizada, unidade fora do raio, sem unidades cadastradas.
- Agendamento: horário no passado, fora da grade de 30 min, antes das 08:00, depois de 16:30, depois de 90 dias, unidade inexistente, especialidade que a unidade não tem, vaga lotada, cancelamento inexistente.
- ETL: linha em branco, JSON quebrado, duplicatas entre arquivos, mesma chave em origens diferentes, bronze vazio ou inexistente.
- Dashboard: filtro sem resultado, semana incompleta (a variação semanal só usa semanas completas), gold ausente.

## O que NÃO é testado automaticamente (e por quê)

| Item | Motivo | Como verificar manualmente |
|---|---|---|
| Construção das imagens Docker e `docker compose up` | Docker não estava instalado onde o projeto foi desenvolvido | `docker compose --profile demo up --build`; o CI faz `docker compose config` e `build` |
| Chamada real a um LLM (OpenAI, Groq, Gemini, Ollama) | Exige chave válida ou modelo local. Uma tentativa com uma chave da OpenAI devolveu 401 (chave inválida ou revogada); nesse caso a API caiu nas regras e o avaliador, ao ver zero respostas do LLM, passou a falhar em vez de gravar o resultado como se fosse do LLM | definir `LLM_PROVIDER` e a chave por variável de ambiente (ver `area1_ia/rodar_avaliacao_llm.ps1`, que pede a chave de forma oculta) e rodar `area1_ia/avaliar_triagem.py --rotulo openai`; o parser e o cliente HTTP têm teste com resposta simulada |
| Qualidade da triagem fora das frases do ajuste | O conjunto ouro foi usado no ajuste das regras (número otimista). O conjunto `frases_validacao.csv` era independente na primeira execução (resultado em `area1_ia/resultados/conjunto_validacao_1a_execucao_antes_das_correcoes.txt`), mas as falhas que ele achou foram corrigidas, então já não é independente | o grupo escrever um terceiro conjunto com frases de pessoas reais do público |
| Concorrência no agendamento | A checagem de capacidade não é atômica (limitação declarada) | ver README, seção de limitações |
| Carga e desempenho | Fora do escopo do MVP | n/a |
