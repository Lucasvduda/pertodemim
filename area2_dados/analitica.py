"""Funções analíticas do PertoDeMim (fórmulas documentadas, puras e testáveis).

Todas trabalham com números/Series do pandas e não dependem de rede nem de arquivos.

Fórmulas
--------
cobertura (%)            = unidades_presentes / unidades_com_servico * 100
índice de pressão        = demanda / capacidade        (> 1 significa mais demanda do que vagas no dia)
média móvel (janela n)   = média dos últimos n valores (com menos de n no início, usa os disponíveis)
p90                      = percentil 90 (interpolação linear)
variação semanal (%)     = (soma da semana ISO - soma da semana anterior) / soma da semana anterior * 100
km evitados              = 2 * km até a unidade mais próxima, quando ela NÃO tinha o profissional (ida e volta perdidas)
custo evitado (R$)       = km evitados * custo por km (premissa editável)
"""
from __future__ import annotations

import math

import pandas as pd

CUSTO_KM_PADRAO = 1.20  # R$/km: premissa editável (combustível/transporte), não é dado medido
CAPACIDADE_SLOTS_DIA = 18  # horários de 30 min entre 08:00 e 16:30 (mesma grade da API de agendamento)


def cobertura(unidades_presentes: float, unidades_com_servico: float) -> float:
    """Percentual de unidades (que oferecem o serviço) com o profissional presente. 0 se não houver unidades."""
    if unidades_com_servico is None or unidades_com_servico <= 0:
        return 0.0
    return round(100.0 * unidades_presentes / unidades_com_servico, 2)


def indice_pressao(demanda: float, capacidade: float) -> float:
    """Demanda dividida pela capacidade. Sem capacidade (0) devolve NaN: a pressão não é definida."""
    if capacidade is None or capacidade <= 0:
        return math.nan
    return round(demanda / capacidade, 4)


def media_movel(serie: pd.Series, janela: int = 7) -> pd.Series:
    if janela < 1:
        raise ValueError("janela deve ser >= 1")
    return serie.rolling(window=janela, min_periods=1).mean()


def p90(valores) -> float:
    s = pd.Series(list(valores), dtype="float64").dropna()
    if s.empty:
        return math.nan
    return float(s.quantile(0.9))


def variacao_semanal(df: pd.DataFrame, col_data: str, col_valor: str) -> pd.DataFrame:
    """Soma por semana ISO e variação percentual contra a semana anterior (NaN se a anterior for 0 ou não existir).

    `dias` = quantos dias distintos existem na semana; `completa` = tem os 7. Comparar semana parcial com semana
    inteira dá variação enganosa, por isso quem exibe "variação da última semana" deve usar só semanas completas.
    """
    if df.empty:
        return pd.DataFrame(columns=["semana", "total", "variacao_pct", "dias", "completa"])
    d = pd.to_datetime(df[col_data])
    iso = d.dt.isocalendar()
    base = pd.DataFrame({"ano": iso["year"].astype(int), "sem": iso["week"].astype(int), "v": df[col_valor].astype(float),
                         "dia": d.dt.normalize()})
    sem = (base.groupby(["ano", "sem"], as_index=False).agg(v=("v", "sum"), dias=("dia", "nunique"))
           .sort_values(["ano", "sem"]).reset_index(drop=True))
    sem["semana"] = sem["ano"].astype(str) + "-S" + sem["sem"].astype(str).str.zfill(2)
    sem["completa"] = sem["dias"] >= 7
    anterior = sem["v"].shift(1)
    sem["variacao_pct"] = ((sem["v"] - anterior) / anterior.where(anterior > 0) * 100).round(2)
    return sem.rename(columns={"v": "total"})[["semana", "total", "variacao_pct", "dias", "completa"]]


def km_evitados(km_mais_proxima: float, mais_proxima_tinha: bool) -> float:
    """Ida e volta que a pessoa teria perdido indo à unidade mais próxima, que não tinha o profissional."""
    if mais_proxima_tinha or km_mais_proxima is None or (isinstance(km_mais_proxima, float) and math.isnan(km_mais_proxima)):
        return 0.0
    return round(2.0 * float(km_mais_proxima), 2)


def custo_evitado(km: float, custo_por_km: float = CUSTO_KM_PADRAO) -> float:
    if custo_por_km < 0:
        raise ValueError("custo por km não pode ser negativo")
    return round(km * custo_por_km, 2)
