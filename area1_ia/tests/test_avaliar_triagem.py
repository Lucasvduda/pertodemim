"""Testes do avaliador de triagem: conjunto ouro, métricas, cliente HTTP (com servidor falso local) e CLI."""
import csv
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import pytest

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ))
import avaliar_triagem as av  # noqa: E402

OURO = RAIZ / "frases_ouro.csv"
DIM_ESP = RAIZ.parent / "area2_dados" / "data" / "dim_especialidades.csv"


# --------------------------------------------------------------- conjunto ouro (dados)

def test_conjunto_ouro_estrutura():
    casos = av.carregar_casos(OURO)
    assert len(casos) == 40
    assert [c["id"] for c in casos] == [str(i) for i in range(1, 41)]
    assert len({c["texto"] for c in casos}) == 40, "frases repetidas"
    assert {c["servico"] for c in casos} == {"saude", "documento", "assistencia_social"}
    assert sum(c["urgencia"] == "vermelho" for c in casos) >= 8
    for c in casos:
        assert 0 < len(c["texto"]) <= 500
        if c["servico"] != "saude":
            assert c["cbo"] == "", f"serviço administrativo não tem especialidade (caso {c['id']})"
            assert c["urgencia"] != "vermelho"


VALIDACAO = RAIZ / "frases_validacao.csv"


def test_conjunto_de_validacao_e_independente_do_ouro():
    """As 40 frases de validação foram escritas depois do ajuste das regras: não podem repetir as do ouro."""
    val = av.carregar_casos(VALIDACAO)
    ouro = av.carregar_casos(OURO)
    assert len(val) == 40 and len({c["texto"] for c in val}) == 40
    assert {c["texto"].lower() for c in val}.isdisjoint({c["texto"].lower() for c in ouro})
    assert {c["servico"] for c in val} == {"saude", "documento", "assistencia_social"}
    assert sum(c["urgencia"] == "vermelho" for c in val) >= 6
    with open(DIM_ESP, encoding="utf-8", newline="") as f:
        cbos = {r["cbo"] for r in csv.DictReader(f)}
    assert {c["cbo"] for c in val if c["cbo"]} <= cbos


def test_conjunto_ouro_cbos_existem_no_catalogo():
    with open(DIM_ESP, encoding="utf-8", newline="") as f:
        cbos = {r["cbo"] for r in csv.DictReader(f)}
    usados = {c["cbo"] for c in av.carregar_casos(OURO) if c["cbo"]}
    assert usados <= cbos, usados - cbos
    assert len(usados) >= 8, "o conjunto deveria cobrir várias especialidades"


def test_carregar_casos_rejeita_urgencia_invalida_e_arquivo_vazio(tmp_path):
    (tmp_path / "a.csv").write_text("id,texto,servico,cbo,urgencia\n1,x,saude,225125,roxo\n", encoding="utf-8")
    with pytest.raises(ValueError, match="urgência inválida"):
        av.carregar_casos(tmp_path / "a.csv")
    (tmp_path / "b.csv").write_text("id,texto,servico,cbo,urgencia\n", encoding="utf-8")
    with pytest.raises(ValueError, match="vazio"):
        av.carregar_casos(tmp_path / "b.csv")


# --------------------------------------------------------------- métricas

def par(es="saude", os_="saude", ec="225270", oc="225270", eu="laranja", ou="laranja", caminho="regras"):
    return {"esperado_servico": es, "obtido_servico": os_, "esperado_cbo": ec, "obtido_cbo": oc,
            "esperado_urgencia": eu, "obtida_urgencia": ou, "caminho": caminho}


def test_metricas_tudo_certo():
    m = av.calcular_metricas([par(), par(eu="vermelho", ou="vermelho", ec="", oc=""), par(es="documento", os_="documento", ec="", oc="", eu="azul", ou="azul")])
    assert m["servico"] == m["urgencia_exata"] == m["urgencia_mais_menos_1"] == 1.0
    assert m["especialidade"] == 1.0 and m["especialidade_n"] == (1, 1)
    assert m["recall_vermelho"] == 1.0 and m["falsos_vermelhos"] == 0
    assert av.verificar_limites(m) == []


def test_metricas_com_erros():
    pares = [par(), par(ou="verde"), par(oc="225125"), par(os_="documento"), par(eu="amarelo", ou="azul"),
             par(eu="verde", ou="vermelho", ec="", oc="")]
    m = av.calcular_metricas(pares)
    assert m["servico"] == pytest.approx(5 / 6)
    assert m["especialidade_n"] == (4, 5)                         # o caso sem cbo esperado não entra
    assert m["urgencia_exata"] == pytest.approx(3 / 6)             # acertam: 1º, 3º e 4º
    assert m["falsos_vermelhos"] == 1
    assert m["recall_vermelho"] is None                            # não há vermelho esperado


def test_metricas_exata_e_mais_menos_1_calculadas_a_mao():
    pares = [par(eu="laranja", ou="laranja"), par(eu="laranja", ou="amarelo"), par(eu="laranja", ou="azul"), par(eu="azul", ou="verde")]
    m = av.calcular_metricas(pares)
    assert m["urgencia_exata"] == 0.25
    assert m["urgencia_mais_menos_1"] == 0.75                      # laranja->azul é distância 3


def test_recall_vermelho_falha_quando_emergencia_passa():
    m = av.calcular_metricas([par(eu="vermelho", ou="laranja", ec="", oc=""), par(eu="vermelho", ou="vermelho", ec="", oc="")])
    assert m["recall_vermelho"] == 0.5 and m["recall_vermelho_n"] == (1, 2)
    assert any("recall_vermelho" in f for f in av.verificar_limites(m))


def test_limites_apontam_cada_falha():
    m = av.calcular_metricas([par(os_="documento", oc="x", ou="azul")] * 3 + [par(eu="verde", ou="vermelho", ec="", oc="")] * 2)
    falhas = " ".join(av.verificar_limites(m))
    for chave in ("servico", "especialidade", "urgencia_exata", "falsos_vermelhos", "recall_vermelho"):
        assert chave in falhas


def test_metricas_sem_casos_da_erro():
    with pytest.raises(ValueError):
        av.calcular_metricas([])


def test_obtido_urgencia_desconhecida_nao_quebra():
    m = av.calcular_metricas([par(ou="")])
    assert m["urgencia_exata"] == 0.0 and m["urgencia_mais_menos_1"] == 0.0


# --------------------------------------------------------------- cliente HTTP com servidor falso

class Falso(BaseHTTPRequestHandler):
    respostas_triagem: list = []
    chamadas: list = []

    def log_message(self, *a):
        pass

    def _json(self, status, corpo, extra=None):
        dados = json.dumps(corpo).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(dados)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(dados)

    def do_POST(self):
        corpo = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        Falso.chamadas.append((self.path, self.headers.get("Authorization"), corpo))
        if self.path == "/api/auth/register":
            return self._json(201, {"id": 1})
        if self.path == "/api/auth/login":
            return self._json(200, {"token": "tok123"})
        if self.path == "/api/triagem":
            st, resp, extra = Falso.respostas_triagem.pop(0)
            return self._json(st, resp, extra)
        self._json(404, {})


@pytest.fixture()
def servidor():
    Falso.respostas_triagem, Falso.chamadas = [], []
    srv = HTTPServer(("127.0.0.1", 0), Falso)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield f"http://127.0.0.1:{srv.server_port}"
    srv.shutdown()


OK = {"servico": "SAUDE", "cbo": "225270", "urgencia": "LARANJA", "caminho": "REGRAS"}


def test_cliente_autentica_e_envia_token_e_consentimento(servidor):
    Falso.respostas_triagem = [(200, OK, {})]
    c = av.Cliente(servidor, intervalo=0)
    c.autenticar()
    assert c.triar("quebrei a perna") == OK
    caminho, auth, corpo = Falso.chamadas[-1]
    assert caminho == "/api/triagem" and auth == "Bearer tok123"
    assert corpo == {"texto": "quebrei a perna", "consentimento": True}
    assert Falso.chamadas[0][2]["consentimento"] is True and Falso.chamadas[0][2]["email"].endswith("@exemplo.test")


def test_cliente_espera_e_tenta_de_novo_no_429(servidor, monkeypatch):
    dormiu = []
    monkeypatch.setattr(av.time, "sleep", lambda s: dormiu.append(s))
    Falso.respostas_triagem = [(429, {"erro": "limite"}, {"Retry-After": "7"}), (200, OK, {})]
    c = av.Cliente(servidor, intervalo=0)
    c.autenticar()
    assert c.triar("x")["urgencia"] == "LARANJA"
    assert 7.0 in dormiu


def test_cliente_desiste_depois_de_429_repetido(servidor, monkeypatch):
    monkeypatch.setattr(av.time, "sleep", lambda s: None)
    Falso.respostas_triagem = [(429, {}, {"Retry-After": "1"})] * 3
    c = av.Cliente(servidor, intervalo=0)
    c.autenticar()
    with pytest.raises(RuntimeError, match="429"):
        c.triar("x")


def test_cliente_erro_de_servidor_vira_runtime_error(servidor):
    Falso.respostas_triagem = [(500, {"erro": "boom"}, {})]
    c = av.Cliente(servidor, intervalo=0)
    c.autenticar()
    with pytest.raises(RuntimeError, match="500"):
        c.triar("x")


def test_cliente_respeita_o_intervalo_entre_chamadas(servidor, monkeypatch):
    dormiu = []
    monkeypatch.setattr(av.time, "sleep", lambda s: dormiu.append(s))
    Falso.respostas_triagem = [(200, OK, {})] * 2
    c = av.Cliente(servidor, intervalo=5)
    c.autenticar()
    c.triar("a")
    c.triar("b")
    assert any(0 < s <= 5 for s in dormiu)


# --------------------------------------------------------------- CLI de ponta a ponta com a API falsa

def test_cli_gera_relatorio_csv_e_txt(servidor, tmp_path, monkeypatch):
    monkeypatch.setattr(av.time, "sleep", lambda s: None)
    casos = tmp_path / "c.csv"
    casos.write_text("id,texto,servico,cbo,urgencia\n1,quebrei a perna,saude,225270,laranja\n2,convulsão,saude,,vermelho\n", encoding="utf-8")
    Falso.respostas_triagem = [(200, {**OK, "caminho": "LLM"}, {}), (200, {"servico": "SAUDE", "urgencia": "VERMELHO", "caminho": "RED_FLAG"}, {})]
    saida = tmp_path / "res"
    # só 2 frases: os limites de especialidade etc. passam, então o código de saída é 0
    assert av.main(["--api", servidor, "--rotulo", "teste", "--casos", str(casos), "--saida", str(saida), "--intervalo", "0"]) == 0
    txt = (saida / "conjunto_ouro_teste.txt").read_text(encoding="utf-8")
    assert "rótulo: teste" in txt and "recall do vermelho: 100.0% (1/1)" in txt and "todos atingidos" in txt
    linhas = list(csv.DictReader(open(saida / "conjunto_ouro_teste.csv", encoding="utf-8")))
    assert [l["obtida_urgencia"] for l in linhas] == ["laranja", "vermelho"]
    assert linhas[1]["caminho"] == "red_flag"


def test_cli_retorna_3_quando_limite_nao_e_atingido(servidor, tmp_path, monkeypatch):
    monkeypatch.setattr(av.time, "sleep", lambda s: None)
    casos = tmp_path / "c.csv"
    casos.write_text("id,texto,servico,cbo,urgencia\n1,convulsão,saude,,vermelho\n", encoding="utf-8")
    Falso.respostas_triagem = [(200, {"servico": "SAUDE", "urgencia": "AMARELO", "caminho": "REGRAS"}, {})]
    assert av.main(["--api", servidor, "--rotulo", "ruim", "--casos", str(casos), "--saida", str(tmp_path / "r"), "--intervalo", "0"]) == 3


def test_rotulo_de_llm_falha_se_nenhuma_frase_veio_do_llm():
    """Chave inválida (401) faz tudo cair nas regras: o resultado não pode passar como 'LLM'."""
    m = av.calcular_metricas([par(caminho="fallback"), par(eu="vermelho", ou="vermelho", ec="", oc="", caminho="red_flag")])
    assert av.verificar_limites(m) == []
    assert any("nenhuma frase foi respondida pelo LLM" in f for f in av.verificar_limites(m, exige_llm=True))
    m2 = av.calcular_metricas([par(caminho="llm"), par(eu="vermelho", ou="vermelho", ec="", oc="", caminho="red_flag")])
    assert av.verificar_limites(m2, exige_llm=True) == []


def test_cli_rotulo_de_llm_sem_llm_retorna_3_mas_regras_nao(servidor, tmp_path, monkeypatch):
    monkeypatch.setattr(av.time, "sleep", lambda s: None)
    casos = tmp_path / "c.csv"
    casos.write_text("id,texto,servico,cbo,urgencia\n1,quebrei a perna,saude,225270,laranja\n2,convulsão,saude,,vermelho\n", encoding="utf-8")
    base = ["--api", servidor, "--casos", str(casos), "--saida", str(tmp_path / "r"), "--intervalo", "0"]
    vermelho = (200, {"servico": "SAUDE", "urgencia": "VERMELHO", "caminho": "RED_FLAG"}, {})
    Falso.respostas_triagem = [(200, {**OK, "caminho": "FALLBACK"}, {}), vermelho]
    assert av.main(base + ["--rotulo", "openai"]) == 3
    Falso.respostas_triagem = [(200, OK, {}), vermelho]
    assert av.main(base + ["--rotulo", "regras"]) == 0


def test_cli_rotulo_perigoso_e_recusado(tmp_path):
    assert av.main(["--rotulo", "../../etc/passwd", "--saida", str(tmp_path)]) == 2


def test_cli_api_fora_do_ar_retorna_1(tmp_path, capsys):
    assert av.main(["--api", "http://127.0.0.1:9", "--rotulo", "x", "--saida", str(tmp_path), "--intervalo", "0"]) == 1
