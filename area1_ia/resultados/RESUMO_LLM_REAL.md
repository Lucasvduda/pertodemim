Provedor: Gemini pelo plano gratuito (endpoint compatível com a OpenAI). A chave foi passada só por variável de ambiente
do processo da API e não foi gravada em nenhum arquivo. O plano gratuito tem cota pequena por modelo e por minuto, então
várias frases caíram no fallback por regras (isso aparece na coluna `caminho` dos CSV). Todas as execuções ficaram salvas:

| Execução | Modelo | Frases pelo LLM | Fallback | Serviço | Especialidade | Urgência exata | ±1 nível | Recall vermelho | Falsos vermelhos |
|---|---|---|---|---|---|---|---|---|---|
| 1 | gemini-3.5-flash (2,1 s entre chamadas) | 13 | 19 (429 e 503) | 100% | 100% | 87,5% | 95,0% | 100% | 2 |
| 2 | gemini-3.5-flash (12 s entre chamadas) | 3 | 29 (cota esgotada) | 100% | 100% | 87,5% | 97,5% | 100% | 1 |
| 3 | gemini-3-flash-preview (timeout 8 s) | 17 | 15 (429 e timeout) | 100% | 96,2% | 85,0% | 100% | 100% | 0 |
| **4** | **gemini-3.1-flash-lite** | **31** | **1** | 100% | 92,3% | **75,0%** | 95,0% | 100% | **4** |
| regras (referência) | sem LLM | 0 | 0 | 100% | 100% | 92,5% | 97,5% | 100% | 0 |

Leitura honesta: só a execução 4 mede o LLM de verdade (31 de 40 frases; as outras 8 são emergências tratadas pelo detector de regras, sem LLM).
Nessa execução o LLM ficou mais cauteloso que as regras: classificou 4 frases como vermelho que o gabarito marca como laranja ou amarelo
(fratura, atraso menstrual com dor, cisco no olho), e por isso o limite "no máximo 1 falso vermelho" **não foi atingido**. É um erro para o lado
seguro (excesso de cautela, nunca falta), mas é um resultado real e fica registrado assim. As regras continuam melhores nesse conjunto, que foi usado
para ajustá-las. A execução 3 teve só 17 frases pelo LLM e a execução 1 só 13, por isso não servem de comparação definitiva.
O recall do vermelho é 100% em todas as execuções porque os sinais de emergência são tratados pelo detector de regras, nunca pelo LLM.
