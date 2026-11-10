"""Testes do simulador IoT: chaves (compatíveis com o Java), cenários, geração e envio com buffer."""
import json
import random
import sys
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import pytest

AQUI = Path(__file__).resolve().parent
RAIZ = AQUI.parent
sys.path.insert(0, str(RAIZ))

import simulador as sim  # noqa: E402

SEMENTE = "test-seed"
TS = datetime(2026, 11, 12, 15, 0, tzinfo=timezone.utc)
ORTOPEDIA = "225270"


@pytest.fixture(scope="module")
def dados():
    return sim.carregar_dados()


def cenario(nome):
    return sim.carregar_cenario(RAIZ / "cenarios" / f"{nome}.json")


# ---------------------------------------------------------------- chaves

def test_chaves_iguais_as_do_java():
    # mesmos valores de referência do IotKeysTest.java
    assert sim.derivar_chave("test-seed", 466611, "fila") == "pdm_4a31547e537a501780c6df0141b47e13"
    assert sim.derivar_chave("test-seed", 466611, "presenca") == "pdm_7e625be49f0ccd62faa1f2bb75725286"
    assert sim.derivar_chave("test-seed", 466611, "heartbeat") == "pdm_25e97cee1d6c17c08e910ea732ff9e1c"
    assert sim.derivar_chave("dev-only-iot-seed", 466611, "fila") == "pdm_1c8896dfe134121fb6b4be53283db20c"


def test_chaves_diferem_por_unidade_tipo_e_semente():
    base = sim.derivar_chave("s", 1, "fila")
    assert base != sim.derivar_chave("s", 2, "fila")
    assert base != sim.derivar_chave("s", 1, "presenca")
    assert base != sim.derivar_chave("x", 1, "fila")
    assert sim.derivar_chave("s", 1, "FILA") == base  # tipo é normalizado


# ---------------------------------------------------------------- dados

def test_dados_reais_do_seed(dados):
    assert len(dados.unidades) == 51
    assert sum(len(v) for v in dados.especialidades.values()) == 170
    assert len(dados.ranking_por_distancia(ORTOPEDIA)) == 8


def test_ranking_por_distancia_e_ordenado(dados):
    ids = dados.ranking_por_distancia(ORTOPEDIA)
    por_id = {u["cnes"]: u for u in dados.unidades}
    dist = [sim.haversine_km(*sim.CENTRO, por_id[i]["lat"], por_id[i]["lon"]) for i in ids]
    assert dist == sorted(dist)


def test_haversine():
    assert sim.haversine_km(0, 0, 0, 0) == 0
    assert sim.haversine_km(0, 0, 1, 0) == pytest.approx(111.2, abs=0.3)


# ---------------------------------------------------------------- cenários

@pytest.mark.parametrize("nome", ["normal", "ortopedista_ausente", "fila_longa", "posto_offline", "pico_demanda"])
def test_cenarios_do_projeto_sao_validos(nome):
    c = cenario(nome)
    assert c["nome"] == nome
    assert c.get("descricao")


def test_cinco_cenarios_exigidos_existem():
    nomes = {p.stem for p in (RAIZ / "cenarios").glob("*.json")}
    assert {"normal", "ortopedista_ausente", "fila_longa", "posto_offline", "pico_demanda"} <= nomes


BASE = {"prob_presenca": 0.5, "fila_media": 5, "fila_desvio": 2, "latencia_ms": 100}


@pytest.mark.parametrize("c, trecho", [
    ({"base": BASE, "regras": []}, "nome"),
    ({"nome": "x", "regras": []}, "base"),
    ({"nome": "x", "base": BASE}, "regras"),
    ({"nome": "x", "base": {**BASE, "prob_presenca": 1.5}, "regras": []}, "prob_presenca"),
    ({"nome": "x", "base": {**BASE, "fila_media": -1}, "regras": []}, "negativas"),
    ({"nome": "x", "base": {"prob_presenca": 0.5}, "regras": []}, "fila_media"),
    ({"nome": "x", "base": BASE, "regras": {}}, "lista"),
    ({"nome": "x", "base": BASE, "regras": [{"presente": True}]}, "quando"),
    ({"nome": "x", "base": BASE, "regras": [{"quando": {"foguete": 1}, "presente": True}]}, "filtro"),
    ({"nome": "x", "base": BASE, "regras": [{"quando": {"rank_distancia": [1]}, "presente": True}]}, "especialidade"),
    ({"nome": "x", "base": BASE, "regras": [{"quando": {"especialidade": "000000"}, "presente": True}]}, "cbo"),
    ({"nome": "x", "base": BASE, "regras": [{"quando": {"todas": True}}]}, "efeitos"),
    ({"nome": "x", "base": BASE, "regras": [{"quando": {"todas": True}, "magia": 1}]}, "efeitos"),
    ([], "objeto"),
])
def test_cenario_invalido_e_recusado(c, trecho):
    with pytest.raises(sim.ErroCenario) as e:
        sim.validar_cenario(c)
    assert trecho in str(e.value)


def test_arquivo_json_quebrado(tmp_path):
    p = tmp_path / "x.json"
    p.write_text("{ isso nao e json", encoding="utf-8")
    with pytest.raises(sim.ErroCenario):
        sim.carregar_cenario(p)


# ---------------------------------------------------------------- geração

def gerar(nome, dados, seed=2026, ciclo=0, run="t"):
    return sim.gerar_ciclo(cenario(nome), dados, random.Random(seed), TS, run, ciclo)


def test_normal_gera_heartbeat_fila_e_presenca_de_todas(dados):
    ev = gerar("normal", dados)
    por_tipo = {t: [e for e in ev if e.tipo == t] for t in sim.TIPOS}
    assert len(por_tipo["heartbeat"]) == 51
    assert len(por_tipo["fila"]) == 51
    assert len(por_tipo["presenca"]) == 170


def test_formato_dos_eventos_segue_o_contrato_da_api(dados):
    for e in gerar("normal", dados):
        c = e.corpo
        assert set(c) >= {"eventId", "unidadeId", "tipo", "ts"}
        assert c["tipo"] == e.tipo and c["unidadeId"] == e.unidade_id
        assert len(c["eventId"]) <= 64
        assert c["ts"] == "2026-11-12T15:00:00Z"
        if e.tipo == "fila":
            assert isinstance(c["pessoas"], int) and c["pessoas"] >= 0
        elif e.tipo == "presenca":
            assert c["cbo"] in sim.ESPECIALIDADES_CBO and isinstance(c["presente"], bool)
        else:
            assert c["online"] is True and isinstance(c["latenciaMs"], int) and c["latenciaMs"] >= 0
        json.dumps(c)  # serializável


def test_geracao_e_reprodutivel_e_ids_sao_deterministicos(dados):
    a = [e.corpo for e in gerar("normal", dados, seed=1)]
    b = [e.corpo for e in gerar("normal", dados, seed=1)]
    assert a == b
    c = [e.corpo for e in gerar("normal", dados, seed=2)]
    assert a != c


def test_event_ids_unicos_no_ciclo_e_mudam_a_cada_ciclo_e_execucao(dados):
    ids0 = [e.corpo["eventId"] for e in gerar("normal", dados, ciclo=0)]
    assert len(ids0) == len(set(ids0))
    ids1 = [e.corpo["eventId"] for e in gerar("normal", dados, ciclo=1)]
    assert not set(ids0) & set(ids1)
    ids_outra = [e.corpo["eventId"] for e in gerar("normal", dados, ciclo=0, run="outra")]
    assert not set(ids0) & set(ids_outra)
    # mesmo ciclo/execução: mesmos ids (reenvio não duplica)
    assert ids0 == [e.corpo["eventId"] for e in gerar("normal", dados, ciclo=0)]


def test_ortopedista_ausente_no_primeiro_e_presente_no_segundo_e_terceiro(dados):
    rank = dados.ranking_por_distancia(ORTOPEDIA)
    ev = gerar("ortopedista_ausente", dados)
    presenca = {e.unidade_id: e.corpo["presente"] for e in ev if e.tipo == "presenca" and e.corpo["cbo"] == ORTOPEDIA}
    assert presenca[rank[0]] is False
    assert presenca[rank[1]] is True and presenca[rank[2]] is True


def test_posto_offline_nao_envia_nada(dados):
    rank = dados.ranking_por_distancia(ORTOPEDIA)
    ev = gerar("posto_offline", dados)
    assert not [e for e in ev if e.unidade_id == rank[0]]
    assert [e for e in ev if e.unidade_id == rank[1]]
    assert len({e.unidade_id for e in ev}) == 50


def test_fila_longa_fixa_40_nas_duas_primeiras(dados):
    rank = dados.ranking_por_distancia(ORTOPEDIA)
    filas = {e.unidade_id: e.corpo["pessoas"] for e in gerar("fila_longa", dados) if e.tipo == "fila"}
    assert filas[rank[0]] == 40 and filas[rank[1]] == 40


def test_pico_de_demanda_tem_fila_media_maior_que_o_normal(dados):
    def media(nome):
        v = [e.corpo["pessoas"] for e in gerar(nome, dados) if e.tipo == "fila"]
        return sum(v) / len(v)
    assert media("pico_demanda") > 2 * media("normal")


def test_prob_presenca_extremas(dados):
    c = cenario("normal")
    c["base"]["prob_presenca"] = 0.0
    assert not any(e.corpo["presente"] for e in sim.gerar_ciclo(c, dados, random.Random(1), TS, "t", 0) if e.tipo == "presenca")
    c["base"]["prob_presenca"] = 1.0
    assert all(e.corpo["presente"] for e in sim.gerar_ciclo(c, dados, random.Random(1), TS, "t", 0) if e.tipo == "presenca")


def test_fila_nunca_negativa_mesmo_com_desvio_grande(dados):
    c = cenario("normal")
    c["base"].update(fila_media=0, fila_desvio=50)
    for e in sim.gerar_ciclo(c, dados, random.Random(3), TS, "t", 0):
        if e.tipo == "fila":
            assert e.corpo["pessoas"] >= 0


# ---------------------------------------------------------------- envio (API falsa)

class ApiFalsa(BaseHTTPRequestHandler):
    vistos: set = set()
    modo = "ok"          # ok | fora | erro500 | 429
    chamadas = 0

    def log_message(self, *a):  # silencioso
        pass

    def do_POST(self):
        cls = type(self)
        cls.chamadas += 1
        corpo = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if cls.modo == "erro500":
            return self._resp(500, {"erro": "x"})
        if cls.modo == "429":
            return self._resp(429, {"erro": "x"})
        esperada = sim.derivar_chave(SEMENTE, corpo["unidadeId"], corpo["tipo"])
        chave = self.headers.get("X-Device-Key")
        if chave is None:
            return self._resp(401, {})
        if chave != esperada:
            return self._resp(403, {})
        if corpo["eventId"] in cls.vistos:
            return self._resp(200, {"duplicado": True})
        cls.vistos.add(corpo["eventId"])
        return self._resp(201, {"duplicado": False})

    def _resp(self, codigo, corpo):
        dados = json.dumps(corpo).encode()
        self.send_response(codigo)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(dados)))
        self.end_headers()
        self.wfile.write(dados)


@pytest.fixture()
def api():
    ApiFalsa.vistos = set()
    ApiFalsa.modo = "ok"
    ApiFalsa.chamadas = 0
    srv = HTTPServer(("127.0.0.1", 0), ApiFalsa)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield f"http://127.0.0.1:{srv.server_port}"
    srv.shutdown()
    srv.server_close()


def enviador(api_url, tmp_path, semente=SEMENTE):
    return sim.Enviador(api_url, semente, tmp_path / "buf.jsonl", tentativas=2, espera_s=0.01, timeout_s=2)


def poucos(dados, n=6):
    return gerar("normal", dados)[:n]


def test_envio_ok_e_reenvio_idempotente(api, dados, tmp_path):
    ev = poucos(dados)
    env = enviador(api, tmp_path)
    r1 = env.enviar(ev)
    assert r1.enviados == len(ev) and r1.duplicados == 0
    r2 = env.enviar(ev)  # mesmos eventIds: a API responde 200 (duplicado)
    assert r2.duplicados == len(ev) and r2.enviados == 0
    assert env.pendentes() == 0


def test_chave_errada_e_rejeitada_sem_ir_para_o_buffer(api, dados, tmp_path):
    env = enviador(api, tmp_path, semente="outra-semente")
    r = env.enviar(poucos(dados))
    assert r.rejeitados == 6 and r.enviados == 0 and r.adiados == 0
    assert env.pendentes() == 0


def test_api_fora_do_ar_guarda_no_buffer_e_reenvia_depois(api, dados, tmp_path):
    ev = poucos(dados)
    env_fora = enviador("http://127.0.0.1:9", tmp_path)  # porta fechada
    r = env_fora.enviar(ev)
    assert r.adiados == len(ev) and r.enviados == 0
    assert env_fora.pendentes() == len(ev)
    # a API volta: mesmo buffer, novo enviador apontando para o servidor
    env = enviador(api, tmp_path)
    r2 = env.enviar([])
    assert r2.reenviados == len(ev)
    assert env.pendentes() == 0


def test_erro_500_tenta_de_novo_e_depois_guarda(api, dados, tmp_path):
    ApiFalsa.modo = "erro500"
    env = enviador(api, tmp_path)
    r = env.enviar(poucos(dados, 3))
    assert r.adiados == 3
    assert ApiFalsa.chamadas >= 2  # houve retry
    assert env.pendentes() == 3


def test_429_tambem_vai_para_o_buffer(api, dados, tmp_path):
    ApiFalsa.modo = "429"
    env = enviador(api, tmp_path)
    assert env.enviar(poucos(dados, 2)).adiados == 2


def test_buffer_se_recupera_apos_500(api, dados, tmp_path):
    ApiFalsa.modo = "erro500"
    env = enviador(api, tmp_path)
    env.enviar(poucos(dados, 4))
    ApiFalsa.modo = "ok"
    r = env.enviar(poucos(dados, 4)[:0])
    assert r.reenviados == 4 and env.pendentes() == 0


# ---------------------------------------------------------------- CLI

def test_cli_dry_run_retorna_zero(capsys):
    assert sim.main(["--cenario", str(RAIZ / "cenarios" / "normal.json"), "--dry-run", "--ciclos", "2", "--intervalo", "0"]) == 0
    saida = capsys.readouterr().out
    assert "cenário 'normal'" in saida and "dry-run" in saida


def test_cli_cenario_inexistente_retorna_2(capsys):
    assert sim.main(["--cenario", "nao_existe.json", "--dry-run"]) == 2


def test_cli_argumentos_invalidos():
    with pytest.raises(SystemExit):
        sim.main(["--ciclos", "0", "--dry-run"])
    with pytest.raises(SystemExit):
        sim.main(["--intervalo", "-1", "--dry-run"])


def test_cli_envia_para_a_api_falsa(api, tmp_path, capsys):
    rc = sim.main(["--api", api, "--semente", SEMENTE, "--cenario", str(RAIZ / "cenarios" / "ortopedista_ausente.json"),
                   "--ciclos", "1", "--buffer", str(tmp_path / "b.jsonl"), "--run-id", "cli"])
    assert rc == 0
    assert len(ApiFalsa.vistos) == 51 * 2 + 170
