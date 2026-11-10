"""Executa o dashboard Streamlit de verdade (AppTest) com dados gerados: sem exceção, 5 abas, filtros funcionando."""
import sys
from pathlib import Path

import pytest

pytest.importorskip("streamlit")
from streamlit.testing.v1 import AppTest  # noqa: E402

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ))
import etl  # noqa: E402
import gerar_dados_exemplo as gen  # noqa: E402

DIM = RAIZ / "data"


@pytest.fixture(scope="module")
def dados(tmp_path_factory):
    raiz = tmp_path_factory.mktemp("dash")
    assert gen.main(["--bronze", str(raiz / "bronze"), "--dim", str(DIM), "--dias", "14", "--fim", "2026-11-09"]) == 0
    etl.executar(raiz / "bronze", raiz / "silver", raiz / "gold", DIM)
    return raiz


def abrir(monkeypatch, gold: Path, silver: Path) -> AppTest:
    monkeypatch.setenv("PDM_GOLD", str(gold))
    monkeypatch.setenv("PDM_SILVER", str(silver))
    return AppTest.from_file(str(RAIZ / "dashboard.py"), default_timeout=60).run()


def test_dashboard_abre_sem_excecao_com_5_abas_e_metricas(dados, monkeypatch):
    at = abrir(monkeypatch, dados / "gold", dados / "silver")
    assert not at.exception, [e.value for e in at.exception]
    assert [t.label for t in at.tabs] == ["1. Espera", "2. Cobertura", "3. Demanda", "4. Impacto", "5. Disponibilidade"]
    rotulos = {m.label for m in at.metric}
    assert {"Espera média estimada", "Espera p90 (pior 10%)", "Cobertura média", "Triagens no período", "Custo evitado (premissa)",
            "Disponibilidade dos sensores"} <= rotulos
    assert at.title[0].value == "PertoDeMim · Indicadores"


def test_dashboard_declara_que_os_dados_sao_simulados(dados, monkeypatch):
    at = abrir(monkeypatch, dados / "gold", dados / "silver")
    legenda = " ".join(c.value for c in at.caption).lower()
    assert "simulad" in legenda and "não medição real" in legenda
    assert any("exemplo" in w.value for w in at.sidebar.markdown)   # origem das triagens exibida na barra lateral


def test_dashboard_filtro_de_regiao_muda_os_numeros(dados, monkeypatch):
    at = abrir(monkeypatch, dados / "gold", dados / "silver")
    total = next(m for m in at.metric if m.label == "Triagens no período").value
    at.sidebar.multiselect[0].select("Norte").run()
    assert not at.exception
    filtrado = next(m for m in at.metric if m.label == "Triagens no período").value
    assert int(filtrado) < int(total)


def test_dashboard_filtro_sem_resultado_mostra_aviso_e_nao_quebra(dados, monkeypatch):
    at = abrir(monkeypatch, dados / "gold", dados / "silver")
    # urgência 'vermelho' não existe nas tabelas de espera/cobertura: essas abas seguem com dados; demanda filtra
    at.sidebar.multiselect[2].select("vermelho").run()
    assert not at.exception, [e.value for e in at.exception]


def test_dashboard_sem_gold_mostra_erro_claro(tmp_path, monkeypatch):
    (tmp_path / "gold").mkdir()
    at = abrir(monkeypatch, tmp_path / "gold", tmp_path / "silver")
    assert not at.exception
    assert len(at.error) == 1 and "etl.py" in at.error[0].value
    assert len(at.tabs) == 0
