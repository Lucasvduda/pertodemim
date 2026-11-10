import math
import sys
from pathlib import Path

import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import analitica as an  # noqa: E402


def test_cobertura():
    assert an.cobertura(3, 4) == 75.0
    assert an.cobertura(0, 5) == 0.0
    assert an.cobertura(5, 5) == 100.0
    assert an.cobertura(1, 3) == 33.33


def test_cobertura_sem_unidades_nao_divide_por_zero():
    assert an.cobertura(0, 0) == 0.0
    assert an.cobertura(2, 0) == 0.0
    assert an.cobertura(2, None) == 0.0


def test_indice_pressao():
    assert an.indice_pressao(9, 18) == 0.5
    assert an.indice_pressao(0, 18) == 0.0
    assert an.indice_pressao(36, 18) == 2.0


def test_indice_pressao_sem_capacidade_e_nan():
    assert math.isnan(an.indice_pressao(5, 0))
    assert math.isnan(an.indice_pressao(5, -1))
    assert math.isnan(an.indice_pressao(5, None))


def test_media_movel():
    s = pd.Series([1, 2, 3, 4, 5, 6, 7, 8])
    m = an.media_movel(s, 3)
    assert m.tolist() == [1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0]
    assert an.media_movel(pd.Series([10]), 7).tolist() == [10.0]
    assert an.media_movel(pd.Series([], dtype=float), 7).empty


def test_media_movel_janela_invalida():
    with pytest.raises(ValueError):
        an.media_movel(pd.Series([1, 2]), 0)


def test_p90():
    assert an.p90(range(1, 11)) == pytest.approx(9.1)
    assert an.p90([5]) == 5.0
    assert an.p90([1, 1, 1, 1]) == 1.0
    assert math.isnan(an.p90([]))
    assert an.p90([1, None, 3]) == pytest.approx(2.8)  # ignora nulos


def test_variacao_semanal():
    # semana ISO 44 (26/10 a 01/11) e 45 (02/11 a 08/11) de 2026
    df = pd.DataFrame({"data": ["2026-10-26", "2026-10-30", "2026-11-02", "2026-11-05", "2026-11-06"],
                       "v": [10, 10, 15, 15, 30]})
    r = an.variacao_semanal(df, "data", "v")
    assert r["semana"].tolist() == ["2026-S44", "2026-S45"]
    assert r["total"].tolist() == [20.0, 60.0]
    assert pd.isna(r["variacao_pct"].iloc[0])
    assert r["variacao_pct"].iloc[1] == 200.0


def test_variacao_semanal_marca_semana_incompleta():
    dias = pd.date_range("2026-10-26", "2026-11-09").strftime("%Y-%m-%d")   # S44 e S45 inteiras + 1 dia de S46
    r = an.variacao_semanal(pd.DataFrame({"data": dias, "v": 1}), "data", "v")
    assert r["dias"].tolist() == [7, 7, 1]
    assert r["completa"].tolist() == [True, True, False]


def test_variacao_semanal_anterior_zero_e_vazio():
    df = pd.DataFrame({"data": ["2026-10-26", "2026-11-02"], "v": [0, 5]})
    assert pd.isna(an.variacao_semanal(df, "data", "v")["variacao_pct"].iloc[1])
    assert an.variacao_semanal(pd.DataFrame({"data": [], "v": []}), "data", "v").empty


def test_variacao_semanal_cruza_o_ano():
    df = pd.DataFrame({"data": ["2026-12-28", "2027-01-04"], "v": [10, 5]})
    r = an.variacao_semanal(df, "data", "v")
    assert r["semana"].tolist() == ["2026-S53", "2027-S01"]
    assert r["variacao_pct"].iloc[1] == -50.0


def test_km_evitados():
    assert an.km_evitados(3.0, False) == 6.0       # ida e volta perdidas
    assert an.km_evitados(3.0, True) == 0.0        # a mais próxima tinha: nada evitado
    assert an.km_evitados(None, False) == 0.0
    assert an.km_evitados(float("nan"), False) == 0.0
    assert an.km_evitados(0.0, False) == 0.0


def test_custo_evitado():
    assert an.custo_evitado(10, 1.2) == 12.0
    assert an.custo_evitado(10) == 12.0  # padrão R$ 1,20/km
    assert an.custo_evitado(0, 5) == 0.0
    assert an.custo_evitado(7.5, 2) == 15.0


def test_custo_evitado_negativo_e_recusado():
    with pytest.raises(ValueError):
        an.custo_evitado(10, -1)
