"""Gerador de dados de exemplo + pipeline completo (gerador -> ETL -> camada do dashboard)."""
import hashlib
import json
import sys
from pathlib import Path

import pandas as pd
import pytest

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ))
import dados_dashboard as dd  # noqa: E402
import etl  # noqa: E402
import gerar_dados_exemplo as gen  # noqa: E402

DIM = RAIZ / "data"


def gerar(destino: Path, dias=5, fim="2026-11-09", seed=2026):
    assert gen.main(["--bronze", str(destino), "--dim", str(DIM), "--dias", str(dias), "--fim", fim, "--seed", str(seed)]) == 0


def hashes(pasta: Path):
    return {str(p.relative_to(pasta)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(pasta.rglob("*")) if p.is_file()}


def test_gerador_e_deterministico_com_mesma_seed(tmp_path):
    gerar(tmp_path / "a")
    gerar(tmp_path / "b")
    assert hashes(tmp_path / "a") == hashes(tmp_path / "b")


def test_gerador_muda_com_outra_seed(tmp_path):
    gerar(tmp_path / "a", seed=1)
    gerar(tmp_path / "b", seed=2)
    assert hashes(tmp_path / "a") != hashes(tmp_path / "b")


def test_gerador_estrutura_e_origem_exemplo(tmp_path):
    gerar(tmp_path)
    assert {p.name for p in tmp_path.iterdir()} == {"iot", "triagens", "agendamentos"}
    dias = sorted(p.name for p in (tmp_path / "iot").iterdir())
    assert dias == ["2026-11-05", "2026-11-06", "2026-11-07", "2026-11-08", "2026-11-09"]
    for arq in tmp_path.rglob("*.jsonl"):
        assert arq.name == "exemplo.jsonl"
        for linha in arq.read_text(encoding="utf-8").splitlines():
            assert json.loads(linha)["origem"] == "exemplo"


def test_gerador_nunca_marca_dado_como_api(tmp_path):
    gerar(tmp_path)
    for arq in tmp_path.rglob("*.jsonl"):
        assert '"origem": "api"' not in arq.read_text(encoding="utf-8")


def test_gerador_nao_apaga_dado_real_da_api(tmp_path):
    api = tmp_path / "iot" / "2026-11-09"
    api.mkdir(parents=True)
    (api / "api.jsonl").write_text('{"eventId":"real"}\n', encoding="utf-8")
    gerar(tmp_path)
    assert (api / "api.jsonl").read_text(encoding="utf-8") == '{"eventId":"real"}\n'


def test_gerador_padrao_cobre_ao_menos_duas_semanas_completas(tmp_path):
    # 21 dias terminando em qualquer dia da semana sempre contêm 2 semanas ISO completas
    for fim in ("2026-11-02", "2026-11-05", "2026-11-09"):
        pasta = tmp_path / fim
        assert gen.main(["--bronze", str(pasta), "--dim", str(DIM), "--fim", fim]) == 0
        assert len(list((pasta / "iot").iterdir())) == 21
        dias = pd.to_datetime(sorted(p.name for p in (pasta / "iot").iterdir()))
        semanas = pd.Series(dias.isocalendar().week.values).value_counts()
        assert (semanas == 7).sum() >= 2


def test_gerador_rejeita_dias_invalidos(tmp_path):
    with pytest.raises((SystemExit, ValueError)):
        gen.main(["--bronze", str(tmp_path), "--dim", str(DIM), "--dias", "0", "--fim", "2026-11-09"])


@pytest.fixture(scope="module")
def pipeline(tmp_path_factory):
    raiz = tmp_path_factory.mktemp("pipe")
    gerar(raiz / "bronze", dias=21)
    manifesto = etl.executar(raiz / "bronze", raiz / "silver", raiz / "gold", DIM)
    return raiz, manifesto


def test_dados_gerados_passam_na_validacao_do_etl(pipeline):
    _, m = pipeline
    assert m["rejeitados_total"] == 0
    assert all(v["duplicados"] == 0 and v["aceitos"] > 0 for v in m["datasets"].values())


def test_pipeline_gold_tem_todas_as_tabelas_preenchidas(pipeline):
    raiz, _ = pipeline
    gold = dd.carregar_gold(raiz / "gold")
    assert set(gold) == set(dd.TABELAS)
    assert all(len(df) > 0 for df in gold.values())


def test_pipeline_so_origem_exemplo_no_silver(pipeline):
    raiz, _ = pipeline
    assert dd.origem_dos_dados(raiz / "silver") == {"exemplo": int(pd.read_csv(raiz / "silver" / "triagens.csv").shape[0])}


def test_pipeline_surto_de_demanda_aparece_na_ultima_semana(pipeline):
    raiz, _ = pipeline
    gold = dd.carregar_gold(raiz / "gold")
    r = dd.ind_demanda(gold["demanda"], dd.Filtros())
    assert r["variacao_ultima_semana_pct"] is not None and r["variacao_ultima_semana_pct"] > 0
    ultimos = dd.filtrar(gold["demanda"], dd.Filtros(inicio=max(gold["demanda"]["data"]) - pd.Timedelta(days=4)))
    antes = dd.filtrar(gold["demanda"], dd.Filtros(fim=max(gold["demanda"]["data"]) - pd.Timedelta(days=5)))
    por_dia = lambda d, e: d[d["especialidade"] == e].groupby("data")["triagens"].sum().mean()  # noqa: E731
    assert por_dia(ultimos, "Pediatria") > por_dia(antes, "Pediatria")


def test_pipeline_invariantes_de_consistencia(pipeline):
    raiz, _ = pipeline
    gold = dd.carregar_gold(raiz / "gold")
    cob = gold["cobertura"]
    assert cob["cobertura_pct"].between(0, 100).all()
    assert (cob["unidades_presentes"] <= cob["unidades_com_servico"]).all()
    esp = gold["espera"]
    assert (esp["espera_p90_min"] >= esp["espera_media_min"] - 1e-9).all() and (esp["espera_media_min"] >= 0).all()
    imp = gold["impacto"]
    assert (imp["mais_proxima_sem_profissional"] <= imp["triagens_com_ranking"]).all()
    assert (imp["km_evitados"] >= 0).all() and (imp["custo_evitado_reais"] >= 0).all()
    np_ = gold["conectividade"]
    assert np_["disponibilidade_pct"].between(0, 100).all()
    assert (gold["pressao"]["capacidade"] >= 0).all()
    # triagens do silver = soma da demanda gold
    tri = pd.read_csv(raiz / "silver" / "triagens.csv")
    assert gold["demanda"]["triagens"].sum() == len(tri)


def test_pipeline_idempotente_com_dados_gerados(pipeline, tmp_path):
    raiz, _ = pipeline
    antes = hashes(raiz / "gold")
    etl.executar(raiz / "bronze", raiz / "silver", raiz / "gold", DIM)
    assert hashes(raiz / "gold") == antes
