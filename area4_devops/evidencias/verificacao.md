# Verificação completa (10/11/2026 20:56)

| Suíte | Resultado | Testes | Falhas | Erros |
|---|---|---|---|---|
| API Java (mvn verify) | OK | 223 | 0 | 0 |
| Python (area1_ia) | OK | 39 | 0 | 0 |
| Python (area2_dados) | OK | 86 | 0 | 0 |
| Python (area3_mvp/iot_mock) | OK | 46 | 0 | 0 |
| Python (area4_devops) | OK | 80 | 0 | 0 |

**Total de testes executados: 474**

Cobertura Jacoco (API Java (mvn verify)): instruções 95.2%, linhas 94.8%, ramos 84.9%

Observação: a cobertura é da API Java. As partes em Python têm testes, mas a cobertura delas não é medida aqui.
Não testado nesta verificação: construção das imagens Docker (exige Docker). A chamada real a um LLM exige chave e é feita à parte: area1_ia/resultados/RESUMO_LLM_REAL.md.
