import csv
import sys
from dataclasses import replace
from pathlib import Path

import pytest

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ))
import simulacao_impacto as si  # noqa: E402


@pytest.fixture(scope="module")
def rede():
    return si.carregar_rede()


def test_rede_carregada(rede):
    unidades, oferta = rede
    assert len(unidades) == 51 and set(oferta) == {u["id"] for u in unidades}
    assert {u["tipo"] for u in unidades} >= {"UPA", "UBS", "HOSPITAL"}
    assert sum("225270" in o for o in oferta.values()) == 8     # 8 unidades com ortopedia (seed)


def test_haversine():
    assert si.haversine_km(-23.55, -46.63, -23.55, -46.63) == 0
    assert si.haversine_km(-23.5505, -46.6333, -22.9068, -43.1729) == pytest.approx(360, abs=5)   # SP -> RJ


def test_simulacao_e_deterministica(rede):
    pr = si.Premissas(n=300)
    assert si.simular(pr, *rede) == si.simular(pr, *rede)
    assert si.simular(pr, *rede) != si.simular(replace(pr, seed=7), *rede)


def test_invariantes(rede):
    r = si.simular(si.Premissas(n=500), *rede)
    assert r["atendidos"] + r["sem_profissional_na_rede"] == 500
    assert r["viagens_perdidas_com_app"] == 0
    for nome, b in r["baselines"].items():
        assert b["viagens_perdidas_sem_app"] == b["perdidas_porque_nao_oferece"] + b["perdidas_porque_ausente"]
        assert 0 <= b["viagens_perdidas_sem_app"] <= r["atendidos"]
        assert b["km_evitados"] >= 0
        assert b["custo_evitado_reais"] == pytest.approx(b["km_evitados"] * 1.20, abs=0.1)   # km é arredondado a 0,1 antes
        assert b["minutos_evitados_deslocamento"] == pytest.approx(b["km_evitados"] / 25 * 60, abs=0.1)


def test_presenca_total_so_perde_viagem_por_falta_de_oferta(rede):
    r = si.simular(si.Premissas(n=500, p_presenca=1.0), *rede)
    assert r["sem_profissional_na_rede"] == 0
    assert all(b["perdidas_porque_ausente"] == 0 for b in r["baselines"].values())


def test_ninguem_presente_ninguem_e_atendido(rede):
    r = si.simular(si.Premissas(n=200, p_presenca=0.0), *rede)
    assert r["atendidos"] == 0 and r["sem_profissional_na_rede"] == 200
    assert all(b["km_evitados"] == 0 and b["pct_viagens_perdidas"] == 0 for b in r["baselines"].values())


def test_custo_proporcional_ao_custo_por_km(rede):
    a = si.simular(si.Premissas(n=300, custo_km=1.0), *rede)["baselines"]["qualquer"]
    b = si.simular(si.Premissas(n=300, custo_km=3.0), *rede)["baselines"]["qualquer"]
    assert b["custo_evitado_reais"] == pytest.approx(3 * a["custo_evitado_reais"], abs=0.05)
    assert a["km_evitados"] == b["km_evitados"]


def test_mais_presenca_reduz_viagens_perdidas_por_ausencia(rede):
    baixa = si.simular(si.Premissas(n=2000, p_presenca=0.3), *rede)["baselines"]["qualquer"]["perdidas_porque_ausente"]
    alta = si.simular(si.Premissas(n=2000, p_presenca=0.95), *rede)["baselines"]["qualquer"]["perdidas_porque_ausente"]
    assert alta < baixa


@pytest.mark.parametrize("campo, valor", [("n", 0), ("p_presenca", 1.5), ("p_presenca", -0.1), ("custo_km", -1), ("velocidade_kmh", 0)])
def test_premissas_invalidas(rede, campo, valor):
    with pytest.raises(ValueError):
        si.simular(replace(si.Premissas(n=10), **{campo: valor}), *rede)


def test_rede_vazia(rede):
    with pytest.raises(ValueError):
        si.simular(si.Premissas(n=10), [], {})


def test_sensibilidade(rede):
    s = si.sensibilidade(si.Premissas(n=200), *rede, repeticoes=3)
    assert len(s) == 6 and {x["baseline"] for x in s} == set(si.BASELINES)
    assert all(x["pct_viagens_perdidas_dp"] >= 0 and 0 <= x["pct_viagens_perdidas_media"] <= 100 for x in s)


def test_cli_grava_relatorio_com_limites_e_csv(tmp_path, capsys):
    assert si.main(["--n", "200", "--saida", str(tmp_path)]) == 0
    md = (tmp_path / "simulacao_impacto.md").read_text(encoding="utf-8")
    assert "não medição real" in md and "Limites desta simulação" in md and "SIMULADO" in md
    linhas = list(csv.DictReader(open(tmp_path / "simulacao_impacto.csv", encoding="utf-8")))
    assert {l["baseline"] for l in linhas} == {"qualquer", "porta_aberta"}


def test_cli_parametro_invalido_retorna_1(tmp_path):
    assert si.main(["--p-presenca", "2", "--saida", str(tmp_path)]) == 1
    assert si.main(["--dim", str(tmp_path / "nao_existe"), "--saida", str(tmp_path)]) == 1
