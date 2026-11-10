# Simulação de impacto (resultado de simulação, não medição real)

## Premissas

- Pessoas simuladas: 1000 (semente 2026, reprodutível)
- Probabilidade de o profissional estar presente na unidade que oferece a especialidade: 70%
- Custo por km: R$ 1.20 | velocidade média: 25 km/h
- Moradores distribuídos ao redor das 51 unidades reais do CNES; especialidades com pesos de rede básica; especialidades oferecidas por unidade: catálogo simulado do seed
- Com o app: vai direto à unidade mais próxima que tem o profissional presente
- Sem o app, dois comportamentos (a diferença entre eles é grande e precisa ser lida junto com o resultado):
  - `qualquer`: vai à unidade mais próxima, de qualquer tipo
  - `porta_aberta`: vai à UPA, pronto-socorro ou hospital mais próximo (comportamento comum de quem não sabe onde há o profissional)
- Se a unidade escolhida não tem o profissional, a pessoa perde a ida e volta (2 x distância)
- Emergências (vermelho) ficam fora: o app orienta ligar 192

## Resultado (uma execução)

- Pessoas atendidas na rede: 998 (sem profissional em nenhuma unidade: 2)

### Sem o app, baseline `qualquer`

- Viagens perdidas: 634 (63.5%); com o app: 0 por construção
  - porque a unidade não oferece a especialidade: 489
  - porque o profissional não estava presente: 145
- Km evitados: 3069.3 | custo evitado: R$ 3683.15
- Deslocamento evitado: 7366.3 min no total (7.38 min por pessoa atendida)

### Sem o app, baseline `porta_aberta`

- Viagens perdidas: 667 (66.8%); com o app: 0 por construção
  - porque a unidade não oferece a especialidade: 528
  - porque o profissional não estava presente: 139
- Km evitados: 4518.0 | custo evitado: R$ 5421.59
- Deslocamento evitado: 10843.2 min no total (10.86 min por pessoa atendida)

## Sensibilidade à premissa mais incerta (presença do profissional)

| baseline | p presença | repetições | viagens perdidas % (média ± dp) | km evitados (média ± dp) |
|---|---|---|---|---|
| qualquer | 50% | 20 | 73.5 ± 1.1 | 3516.5 ± 83.6 |
| porta_aberta | 50% | 20 | 74.8 ± 1.3 | 4790.5 ± 131.1 |
| qualquer | 70% | 20 | 63.6 ± 1.2 | 3068.0 ± 84.3 |
| porta_aberta | 70% | 20 | 65.6 ± 1.3 | 4292.1 ± 142.0 |
| qualquer | 90% | 20 | 53.4 ± 1.3 | 2570.9 ± 73.8 |
| porta_aberta | 90% | 20 | 55.9 ± 1.4 | 3671.0 ± 127.1 |

## Limites desta simulação

- A maior parte das viagens perdidas vem de a unidade não oferecer a especialidade, não da ausência do profissional. Quais especialidades cada unidade oferece é SIMULADO no seed (as unidades e coordenadas são reais, o catálogo de especialidades não), então esse percentual alto é consequência do catálogo simulado e não deve ser apresentado como fato sobre a rede real.
- "Com o app: 0 viagens perdidas" supõe que a informação de presença do app está correta. Na prática ela pode estar defasada (o ranking rebaixa unidades sem confirmação recente).
- Não considera trânsito, transporte público, lotação nem a fila: só distância.
- A probabilidade de presença, a distribuição das especialidades e o comportamento sem o app são premissas, não dados medidos.
- Use a faixa entre os dois baselines e a tabela de sensibilidade, não um número isolado.
