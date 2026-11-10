"""Testes do ETL: validação, deduplicação, idempotência, fuso horário e cada tabela gold com valores conhecidos."""
import hashlib
import json
import sys
from pathlib import Path

import pandas as pd
import pytest

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ))
import etl  # noqa: E402

DIM = RAIZ / "data"
# duas unidades reais da região Sul (997730 UPA, 2081970 HOSPITAL) e uma da Leste
U1, U2 = 997730, 2081970
ORTO, PED = "225270", "225124"


def escrever(bronze: Path, dataset: str, dia: str, nome: str, linhas):
    pasta = bronze / dataset / dia
    pasta.mkdir(parents=True, exist_ok=True)
    with open(pasta / nome, "a", encoding="utf-8") as f:
        for l in linhas:
            f.write((l if isinstance(l, str) else json.dumps(l, ensure_ascii=False)) + "\n")


def fila(eid, uid, pessoas, ts="2026-11-09T15:00:00Z", origem="api"):
    return {"eventId": eid, "unidadeId": uid, "tipo": "fila", "ts": ts, "pessoas": pessoas, "origem": origem}


def presenca(eid, uid, cbo, presente, ts="2026-11-09T15:00:00Z", origem="api"):
    return {"eventId": eid, "unidadeId": uid, "tipo": "presenca", "ts": ts, "cbo": cbo, "presente": presente, "origem": origem}


def heartbeat(eid, uid, lat=100, ts="2026-11-09T15:00:00Z", origem="api"):
    return {"eventId": eid, "unidadeId": uid, "tipo": "heartbeat", "ts": ts, "online": True, "latenciaMs": lat, "origem": origem}


def triagem(tid, **kw):
    base = {"triagemId": tid, "ts": "2026-11-09T15:00:00Z", "servico": "saude", "cbo": ORTO, "especialidade": "Ortopedia e traumatologia",
            "urgencia": "laranja", "caminho": "regras", "regiao": "Sul", "unidadeIndicadaId": U2, "kmMaisProxima": 3.0,
            "kmIndicada": 8.0, "maisProximaTinha": False, "textoAnonimizado": "quebrei a perna", "origem": "api"}
    base.update(kw)
    return base


def agendamento(aid, uid=U2, evento="criado", ts="2026-11-09T16:00:00Z"):
    return {"agendamentoId": aid, "protocolo": f"PDM-{aid}", "evento": evento, "ts": ts, "unidadeId": uid, "servico": "saude",
            "cbo": ORTO, "dataHora": "2026-11-12T09:00:00", "status": "confirmado", "origem": "api"}


@pytest.fixture()
def pastas(tmp_path):
    return tmp_path / "bronze", tmp_path / "silver", tmp_path / "gold"


def rodar(pastas, custo=1.2):
    b, s, g = pastas
    b.mkdir(parents=True, exist_ok=True)
    return etl.executar(b, s, g, DIM, custo)


def ler(pasta, nome):
    return pd.read_csv(pasta / nome, dtype={"cbo": "str"})


# ------------------------------------------------------------ validação e rejeição

def test_aceita_eventos_validos_dos_tres_tipos(pastas):
    b, s, g = pastas
    escrever(b, "iot", "2026-11-09", "api.jsonl", [fila("e1", U1, 4), presenca("e2", U1, PED, True), heartbeat("e3", U1)])
    m = rodar(pastas)
    assert m["datasets"]["iot"] == {"lidos": 3, "aceitos": 3, "duplicados": 0, "rejeitados": 0}
    iot = ler(s, "iot_eventos.csv")
    assert set(iot["tipo"]) == {"fila", "presenca", "heartbeat"}
    assert iot.loc[iot["tipo"] == "fila", "pessoas"].iloc[0] == 4


@pytest.mark.parametrize("linha, trecho", [
    ("isso não é json", "JSON inválido"),
    ("[1, 2, 3]", "objeto"),
    ({"unidadeId": U1, "tipo": "fila", "ts": "2026-11-09T15:00:00Z", "pessoas": 1, "origem": "api"}, "eventId"),
    (fila("x", 999999999, 1), "unidade desconhecida"),
    (fila("x", U1, -3), "menor que 0"),
    (fila("x", U1, 5000), "maior que 1000"),
    (fila("x", U1, 2.5), "inteiro"),
    (fila("x", U1, "muitas"), "inteiro"),
    ({**fila("x", U1, 1), "tipo": "foguete"}, "tipo inválido"),
    (presenca("x", U1, "999999", True), "cbo inválido"),
    (presenca("x", U1, ORTO, "sim"), "booleano"),
    ({**heartbeat("x", U1), "online": "yes"}, "booleano"),
    ({**fila("x", U1, 1), "ts": "ontem"}, ""),
    ({**fila("x", U1, 1), "ts": None}, "ts"),
    ({**fila("x", U1, 1), "origem": ""}, "origem"),
    ({k: v for k, v in fila("x", U1, 1).items() if k != "origem"}, "origem"),
])
def test_linha_invalida_vai_para_rejeitados_sem_derrubar_o_etl(pastas, linha, trecho):
    b, s, g = pastas
    escrever(b, "iot", "2026-11-09", "api.jsonl", [fila("ok", U1, 1), linha])
    m = rodar(pastas)
    assert m["datasets"]["iot"]["aceitos"] == 1
    assert m["datasets"]["iot"]["rejeitados"] == 1
    rej = ler(s, "rejeitados.csv")
    assert len(rej) == 1 and rej["dataset"].iloc[0] == "iot" and rej["linha"].iloc[0] == 2
    assert trecho in rej["motivo"].iloc[0]


def test_rejeita_triagem_e_agendamento_invalidos(pastas):
    b, s, g = pastas
    escrever(b, "triagens", "2026-11-09", "api.jsonl", [
        triagem(1), triagem(2, urgencia="roxo"), triagem(3, servico="x"), triagem(4, regiao="Marte"),
        triagem(5, kmMaisProxima=-1), triagem(6, unidadeIndicadaId=1), triagem(7, caminho="magia"), triagem(8, cbo="1")])
    escrever(b, "agendamentos", "2026-11-09", "api.jsonl", [agendamento(1), agendamento(2, uid=1), agendamento(3, evento="x")])
    m = rodar(pastas)
    assert m["datasets"]["triagens"]["aceitos"] == 1 and m["datasets"]["triagens"]["rejeitados"] == 7
    assert m["datasets"]["agendamentos"]["aceitos"] == 1 and m["datasets"]["agendamentos"]["rejeitados"] == 2
    assert m["rejeitados_total"] == 9


def test_linha_em_branco_e_ignorada(pastas):
    b, s, g = pastas
    escrever(b, "iot", "2026-11-09", "api.jsonl", [fila("a", U1, 1), "", "   ", fila("b", U1, 2)])
    assert rodar(pastas)["datasets"]["iot"]["lidos"] == 2


# ------------------------------------------------------------ deduplicação e idempotência

def test_duplicados_na_mesma_origem_sao_removidos(pastas):
    b, s, g = pastas
    escrever(b, "iot", "2026-11-09", "api.jsonl", [fila("a", U1, 1), fila("a", U1, 1)])
    escrever(b, "iot", "2026-11-10", "api.jsonl", [fila("a", U1, 1, ts="2026-11-09T15:00:00Z")])  # reenvio em outro arquivo
    m = rodar(pastas)
    assert m["datasets"]["iot"]["aceitos"] == 1 and m["datasets"]["iot"]["duplicados"] == 2


def test_mesmo_id_em_origens_diferentes_nao_colide(pastas):
    b, s, g = pastas
    escrever(b, "triagens", "2026-11-09", "api.jsonl", [triagem(1)])
    escrever(b, "triagens", "2026-11-09", "exemplo.jsonl", [triagem(1, origem="exemplo")])
    assert rodar(pastas)["datasets"]["triagens"]["aceitos"] == 2


def sha(pasta: Path):
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(pasta.glob("*")) if p.name != "_manifest.json"}


def test_etl_e_idempotente_arquivos_identicos_ao_rodar_de_novo(pastas):
    b, s, g = pastas
    escrever(b, "iot", "2026-11-09", "api.jsonl", [fila("a", U1, 4), fila("b", U2, 8), presenca("c", U2, ORTO, True), heartbeat("d", U1)])
    escrever(b, "triagens", "2026-11-09", "api.jsonl", [triagem(1), triagem(2, kmMaisProxima=5.0)])
    escrever(b, "agendamentos", "2026-11-09", "api.jsonl", [agendamento(1)])
    rodar(pastas)
    antes = (sha(s), sha(g))
    rodar(pastas)
    rodar(pastas)
    assert (sha(s), sha(g)) == antes


def test_rodar_depois_de_chegar_dado_novo_so_acrescenta(pastas):
    b, s, g = pastas
    escrever(b, "iot", "2026-11-09", "api.jsonl", [fila("a", U1, 4)])
    rodar(pastas)
    escrever(b, "iot", "2026-11-09", "api.jsonl", [fila("a", U1, 4), fila("b", U1, 6)])
    m = rodar(pastas)
    assert m["datasets"]["iot"]["aceitos"] == 2 and m["datasets"]["iot"]["duplicados"] == 1


def test_bronze_vazio_gera_tabelas_vazias_com_cabecalho(pastas):
    b, s, g = pastas
    m = rodar(pastas)
    assert all(v["aceitos"] == 0 for v in m["datasets"].values())
    for nome in ("g_espera.csv", "g_cobertura_especialidade.csv", "g_demanda.csv", "g_impacto.csv", "g_conectividade.csv", "g_pressao.csv"):
        df = ler(g, nome)
        assert df.empty and len(df.columns) >= 5, nome


def test_fuso_horario_vira_data_local_de_sao_paulo(pastas):
    b, s, g = pastas
    # 02:30 UTC do dia 10 = 23:30 do dia 09 em São Paulo (UTC-3)
    escrever(b, "iot", "2026-11-09", "api.jsonl", [fila("a", U1, 1, ts="2026-11-10T02:30:00Z")])
    rodar(pastas)
    r = ler(s, "iot_eventos.csv").iloc[0]
    assert r["data"] == "2026-11-09" and r["hora"] == 23 and r["ts_utc"] == "2026-11-10T02:30:00Z"


def test_ts_sem_fuso_e_tratado_como_utc(pastas):
    b, s, g = pastas
    escrever(b, "iot", "2026-11-09", "api.jsonl", [fila("a", U1, 1, ts="2026-11-09T15:00:00")])
    rodar(pastas)
    assert ler(s, "iot_eventos.csv").iloc[0]["hora"] == 12


# ------------------------------------------------------------ gold com valores conhecidos

def test_gold_espera_media_e_p90(pastas):
    b, s, g = pastas
    # duas unidades da região Sul, mesma hora local (12h): filas 2 e 4 -> esperas 10 e 20 min
    escrever(b, "iot", "2026-11-09", "api.jsonl", [fila("a", U1, 2), fila("b", U2, 4)])
    rodar(pastas)
    r = ler(g, "g_espera.csv")
    assert len(r) == 1
    linha = r.iloc[0]
    assert (linha["regiao"], linha["hora"], linha["amostras"]) == ("Sul", 12, 2)
    assert linha["espera_media_min"] == 15.0
    assert linha["espera_p90_min"] == pytest.approx(19.0)


def test_gold_cobertura(pastas):
    b, s, g = pastas
    # ortopedia não existe em U1/U2; usa pediatria (as duas têm): U1 presente em 2/2, U2 presente em 0/2
    escrever(b, "iot", "2026-11-09", "api.jsonl", [
        presenca("a", U1, PED, True, ts="2026-11-09T14:00:00Z"), presenca("b", U1, PED, True, ts="2026-11-09T15:00:00Z"),
        presenca("c", U2, PED, False, ts="2026-11-09T14:00:00Z"), presenca("d", U2, PED, False, ts="2026-11-09T15:00:00Z")])
    rodar(pastas)
    r = ler(g, "g_cobertura_especialidade.csv")
    sul = r[(r["regiao"] == "Sul") & (r["cbo"] == PED)].iloc[0]
    assert sul["unidades_presentes"] == 1
    assert sul["cobertura_pct"] == pytest.approx(100.0 * 1 / sul["unidades_com_servico"], abs=0.01)
    assert sul["especialidade"] == "Pediatria"


def test_gold_cobertura_ignora_horas_fora_do_expediente(pastas):
    b, s, g = pastas
    # 03:00 UTC = 00:00 local: fora de 07h-18h, não entra na cobertura
    escrever(b, "iot", "2026-11-09", "api.jsonl", [presenca("a", U1, PED, True, ts="2026-11-09T03:00:00Z")])
    rodar(pastas)
    assert ler(g, "g_cobertura_especialidade.csv").empty


def test_gold_impacto_km_e_custo(pastas):
    b, s, g = pastas
    escrever(b, "triagens", "2026-11-09", "api.jsonl", [
        triagem(1, kmMaisProxima=3.0, maisProximaTinha=False),   # evita 6 km
        triagem(2, kmMaisProxima=4.0, maisProximaTinha=True),    # nada evitado
        triagem(3, kmMaisProxima=1.5, maisProximaTinha=False),   # evita 3 km
        triagem(4, unidadeIndicadaId=None, kmMaisProxima=None, kmIndicada=None, maisProximaTinha=None, urgencia="vermelho",
                cbo=None, especialidade=None, caminho="red_flag"),  # emergência: fora do impacto
    ])
    rodar(pastas, custo=2.0)
    r = ler(g, "g_impacto.csv")
    assert r["triagens_com_ranking"].sum() == 3
    assert r["mais_proxima_sem_profissional"].sum() == 2
    assert r["km_evitados"].sum() == pytest.approx(9.0)
    assert r["custo_evitado_reais"].sum() == pytest.approx(18.0)


def test_gold_demanda_rotula_emergencia_documento_e_social(pastas):
    b, s, g = pastas
    escrever(b, "triagens", "2026-11-09", "api.jsonl", [
        triagem(1), triagem(2, servico="documento", cbo=None, especialidade=None, urgencia="azul", unidadeIndicadaId=None),
        triagem(3, servico="assistencia_social", cbo=None, especialidade=None, urgencia="azul", unidadeIndicadaId=None),
        triagem(4, cbo=None, especialidade=None, urgencia="vermelho", caminho="red_flag", unidadeIndicadaId=None)])
    rodar(pastas)
    r = ler(g, "g_demanda.csv")
    assert set(r["especialidade"]) == {"Ortopedia e traumatologia", "Documentos", "Assistência social", "Emergência (sem especialidade)"}
    assert r["triagens"].sum() == 4


def test_gold_conectividade(pastas):
    b, s, g = pastas
    # heartbeat em 3 horas distintas do dia (12h, 13h, 14h locais), latências 100/200/300
    escrever(b, "iot", "2026-11-09", "api.jsonl", [heartbeat("a", U1, 100, ts="2026-11-09T15:00:00Z"),
                                                    heartbeat("b", U1, 200, ts="2026-11-09T16:00:00Z"),
                                                    heartbeat("c", U1, 300, ts="2026-11-09T17:00:00Z"),
                                                    heartbeat("d", U1, 50, ts="2026-11-09T15:30:00Z")])  # mesma hora de "a"
    rodar(pastas)
    r = ler(g, "g_conectividade.csv").iloc[0]
    assert r["horas_com_heartbeat"] == 3
    assert r["disponibilidade_pct"] == 25.0
    assert r["regiao"] == "Sul" and r["tipo_unidade"] == "UPA"


def test_gold_pressao(pastas):
    b, s, g = pastas
    escrever(b, "triagens", "2026-11-09", "api.jsonl", [triagem(1), triagem(2), triagem(3, unidadeIndicadaId=U1)])
    escrever(b, "agendamentos", "2026-11-09", "api.jsonl", [agendamento(1), agendamento(2, evento="cancelado")])
    rodar(pastas)
    r = ler(g, "g_pressao.csv")
    u2 = r[r["unidade_id"] == U2].iloc[0]
    assert u2["demanda"] == 3          # 2 indicações + 1 agendamento criado (o cancelamento não conta)
    dim_esp = pd.read_csv(DIM / "dim_especialidades.csv")
    assert u2["capacidade"] == dim_esp.loc[dim_esp["cnes"] == U2, "qtd_profissionais"].sum() * 18
    assert u2["indice_pressao"] == pytest.approx(u2["demanda"] / u2["capacidade"], abs=1e-3)
    assert r[r["unidade_id"] == U1].iloc[0]["demanda"] == 1
    assert len(r) == 51  # uma linha por unidade no dia


def test_custo_negativo_e_recusado(pastas):
    with pytest.raises(ValueError):
        rodar(pastas, custo=-1)


# ------------------------------------------------------------ CLI

def test_cli_sem_bronze_retorna_2(tmp_path, capsys):
    assert etl.main(["--bronze", str(tmp_path / "nao_existe"), "--silver", str(tmp_path / "s"), "--gold", str(tmp_path / "g")]) == 2


def test_cli_ok(tmp_path, capsys):
    b = tmp_path / "b"
    escrever(b, "iot", "2026-11-09", "api.jsonl", [fila("a", U1, 1)])
    assert etl.main(["--bronze", str(b), "--silver", str(tmp_path / "s"), "--gold", str(tmp_path / "g")]) == 0
    assert "iot: lidos 1" in capsys.readouterr().out
