"""Camada de dados do dashboard: carregamento, filtros, indicadores e casos vazios."""
import sys
from datetime import date
from pathlib import Path

import pandas as pd
import pytest

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ))
import dados_dashboard as dd  # noqa: E402


def csv(pasta: Path, nome: str, cols, linhas):
    pasta.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(linhas, columns=cols).to_csv(pasta / nome, index=False)


@pytest.fixture()
def gold(tmp_path):
    g = tmp_path / "gold"
    csv(g, "g_espera.csv", ["data", "regiao", "hora", "amostras", "espera_media_min", "espera_p90_min"], [
        ["2026-11-08", "Sul", 12, 2, 10.0, 15.0], ["2026-11-08", "Sul", 13, 2, 20.0, 30.0], ["2026-11-09", "Norte", 12, 4, 30.0, 50.0]])
    csv(g, "g_cobertura_especialidade.csv", ["data", "regiao", "cbo", "especialidade", "unidades_com_servico", "unidades_presentes", "cobertura_pct"], [
        ["2026-11-08", "Sul", "225270", "Ortopedia", 4, 1, 25.0], ["2026-11-09", "Sul", "225270", "Ortopedia", 4, 3, 75.0],
        ["2026-11-09", "Norte", "225124", "Pediatria", 2, 2, 100.0]])
    csv(g, "g_demanda.csv", ["data", "regiao", "cbo", "especialidade", "urgencia", "triagens"], [
        ["2026-11-02", "Sul", "225270", "Ortopedia", "laranja", 10], ["2026-11-09", "Sul", "225270", "Ortopedia", "laranja", 30],
        ["2026-11-09", "Norte", "225124", "Pediatria", "verde", 5], ["2026-11-09", "Sul", "", "Emergência", "vermelho", 2]])
    csv(g, "g_impacto.csv", ["data", "regiao", "triagens_com_ranking", "mais_proxima_sem_profissional", "km_evitados", "custo_evitado_reais"], [
        ["2026-11-08", "Sul", 10, 4, 20.0, 24.0], ["2026-11-09", "Norte", 10, 1, 5.0, 6.0]])
    csv(g, "g_conectividade.csv", ["data", "unidade_id", "unidade", "tipo_unidade", "regiao", "horas_com_heartbeat", "disponibilidade_pct", "latencia_p90_ms"], [
        ["2026-11-09", 1, "A", "UPA", "Sul", 12, 100.0, 200.0], ["2026-11-09", 2, "B", "UBS", "Sul", 6, 50.0, 800.0]])
    csv(g, "g_pressao.csv", ["data", "unidade_id", "unidade", "regiao", "demanda", "capacidade", "indice_pressao"], [
        ["2026-11-09", 1, "A", "Sul", 40, 36, 1.111], ["2026-11-09", 2, "B", "Sul", 2, 36, 0.056], ["2026-11-09", 3, "C", "Norte", 20, 36, 0.556]])
    return dd.carregar_gold(g)


def test_carregar_gold_pasta_incompleta_da_erro_claro(tmp_path):
    (tmp_path / "gold").mkdir()
    with pytest.raises(dd.GoldIndisponivel, match="etl.py"):
        dd.carregar_gold(tmp_path / "gold")


def test_carregar_gold_pasta_inexistente(tmp_path):
    with pytest.raises(dd.GoldIndisponivel):
        dd.carregar_gold(tmp_path / "nada")


def test_carregar_preserva_cbo_como_texto_e_data_como_date(gold):
    assert gold["demanda"]["cbo"].tolist()[0] == "225270"
    assert gold["demanda"]["cbo"].tolist()[-1] == ""            # emergência: sem NaN
    assert isinstance(gold["demanda"]["data"].iloc[0], date)


def test_opcoes(gold):
    o = dd.opcoes(gold)
    assert o["regioes"] == ["Norte", "Sul"]
    assert o["especialidades"] == {"225270": "Ortopedia", "225124": "Pediatria"}
    assert o["urgencias"] == ["vermelho", "laranja", "verde"]
    assert (o["inicio"], o["fim"]) == (date(2026, 11, 2), date(2026, 11, 9))


def test_filtrar_combina_filtros(gold):
    d = gold["demanda"]
    assert len(dd.filtrar(d, dd.Filtros())) == 4
    assert len(dd.filtrar(d, dd.Filtros(regioes=("Sul",)))) == 3
    assert len(dd.filtrar(d, dd.Filtros(regioes=("Sul",), cbos=("225270",)))) == 2
    assert len(dd.filtrar(d, dd.Filtros(urgencias=("vermelho",)))) == 1
    assert len(dd.filtrar(d, dd.Filtros(inicio=date(2026, 11, 9)))) == 3
    assert len(dd.filtrar(d, dd.Filtros(fim=date(2026, 11, 2)))) == 1
    assert dd.filtrar(d, dd.Filtros(regioes=("Leste",))).empty


def test_filtrar_ignora_filtro_de_coluna_inexistente(gold):
    # 'espera' não tem cbo nem urgência: esses filtros não podem zerar a tabela
    assert len(dd.filtrar(gold["espera"], dd.Filtros(cbos=("225270",), urgencias=("azul",)))) == 3


def test_filtrar_tabela_vazia_nao_quebra():
    assert dd.filtrar(pd.DataFrame(), dd.Filtros(regioes=("Sul",))).empty


def test_ind_espera(gold):
    r = dd.ind_espera(gold["espera"], dd.Filtros())
    assert not r["vazio"]
    assert r["media_min"] == pytest.approx((10 * 2 + 20 * 2 + 30 * 4) / 8, abs=0.1)   # ponderada pelas amostras
    assert r["p90_min"] == 50.0
    assert set(r["por_regiao"]["regiao"]) == {"Sul", "Norte"}
    assert sorted(r["por_hora"]["hora"]) == [12, 13]
    assert dd.ind_espera(gold["espera"], dd.Filtros(regioes=("Sul",)))["media_min"] == 15.0


def test_ind_cobertura(gold):
    r = dd.ind_cobertura(gold["cobertura"], dd.Filtros())
    assert r["cobertura_pct"] == pytest.approx(100 * 6 / 10, abs=0.01)
    assert r["por_especialidade"]["cobertura_pct"].is_monotonic_increasing
    orto = dd.ind_cobertura(gold["cobertura"], dd.Filtros(cbos=("225270",), inicio=date(2026, 11, 9)))
    assert orto["cobertura_pct"] == 75.0


def test_ind_demanda(gold):
    r = dd.ind_demanda(gold["demanda"], dd.Filtros())
    assert r["total"] == 47
    assert r["por_urgencia"]["urgencia"].tolist() == ["vermelho", "laranja", "verde"]      # ordem clínica
    assert r["por_especialidade"].iloc[0]["especialidade"] == "Ortopedia"
    assert "media_movel" in r["serie"].columns
    assert len(r["semanal"]) == 2
    # as duas semanas têm 1 dia de dado cada: incompletas, então não há variação confiável
    assert r["variacao_ultima_semana_pct"] is None


def _demanda_com_semanas(valores_por_semana):
    """Uma linha por dia, 7 dias por semana (S44=26/10 a 01/11, S45=02/11 a 08/11)."""
    linhas = []
    for i, v in enumerate(valores_por_semana):
        for d in range(7):
            dia = (pd.Timestamp("2026-10-26") + pd.Timedelta(days=7 * i + d)).date().isoformat()
            linhas.append([dia, "Sul", "225270", "Ortopedia", "laranja", v])
    return pd.DataFrame(linhas, columns=["data", "regiao", "cbo", "especialidade", "urgencia", "triagens"]).assign(
        data=lambda x: pd.to_datetime(x["data"]).dt.date)


def test_variacao_usa_apenas_semanas_completas():
    df = _demanda_com_semanas([10, 20])
    assert dd.ind_demanda(df, dd.Filtros())["variacao_ultima_semana_pct"] == 100.0
    # acrescenta 1 dia de uma 3ª semana (parcial, valor baixo): não pode virar "queda"
    extra = pd.DataFrame([["2026-11-09", "Sul", "225270", "Ortopedia", "laranja", 1]], columns=df.columns).assign(
        data=lambda x: pd.to_datetime(x["data"]).dt.date)
    r = dd.ind_demanda(pd.concat([df, extra], ignore_index=True), dd.Filtros())
    assert r["variacao_ultima_semana_pct"] == 100.0


def test_variacao_sem_duas_semanas_completas_ou_base_zero():
    assert dd.ind_demanda(_demanda_com_semanas([10]), dd.Filtros())["variacao_ultima_semana_pct"] is None
    assert dd.ind_demanda(_demanda_com_semanas([0, 5]), dd.Filtros())["variacao_ultima_semana_pct"] is None


def test_ind_demanda_um_so_dia_nao_tem_base_de_comparacao(gold):
    r = dd.ind_demanda(gold["demanda"], dd.Filtros(inicio=date(2026, 11, 9)))
    assert r["variacao_ultima_semana_pct"] is None and r["total"] == 37


def test_ind_impacto(gold):
    r = dd.ind_impacto(gold["impacto"], dd.Filtros())
    assert (r["triagens"], r["evitadas"], r["pct_evitadas"]) == (20, 5, 25.0)
    assert r["km_evitados"] == 25.0 and r["custo_evitado"] == 30.0


def test_ind_disponibilidade(gold):
    r = dd.ind_disponibilidade(gold["conectividade"], gold["pressao"], dd.Filtros())
    assert r["disponibilidade_pct"] == 75.0 and r["latencia_p90_ms"] == 800.0
    assert r["unidades_criticas"] == 2                    # 1.111 e 0.556 > 0.5
    assert r["pressao_top"].iloc[0]["unidade"] == "A"
    assert r["pressao_max"] == pytest.approx(1.111, abs=1e-3) and r["pressao_max_unidade"] == "A"
    so_norte = dd.ind_disponibilidade(gold["conectividade"], gold["pressao"], dd.Filtros(regioes=("Norte",)))
    assert so_norte["disponibilidade_pct"] is None and so_norte["unidades_criticas"] == 1 and not so_norte["vazio"]


@pytest.mark.parametrize("filtro", [dd.Filtros(regioes=("Leste",)), dd.Filtros(inicio=date(2030, 1, 1))])
def test_todos_os_indicadores_lidam_com_filtro_sem_resultado(gold, filtro):
    assert dd.ind_espera(gold["espera"], filtro)["vazio"]
    assert dd.ind_cobertura(gold["cobertura"], filtro)["vazio"]
    assert dd.ind_demanda(gold["demanda"], filtro)["vazio"]
    assert dd.ind_impacto(gold["impacto"], filtro)["vazio"]
    assert dd.ind_disponibilidade(gold["conectividade"], gold["pressao"], filtro)["vazio"]


def test_origem_dos_dados(tmp_path):
    assert dd.origem_dos_dados(tmp_path) == {}
    pd.DataFrame({"origem": ["api", "exemplo", "exemplo"]}).to_csv(tmp_path / "triagens.csv", index=False)
    assert dd.origem_dos_dados(tmp_path) == {"exemplo": 2, "api": 1}
