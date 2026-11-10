"""Avalia a triagem de uma API em execução contra o conjunto de frases rotuladas (frases_ouro.csv).

Uso (a API precisa estar no ar; o modo da triagem vem da própria API: LLM_PROVIDER=rules | groq | gemini | ollama):

    python avaliar_triagem.py --api http://localhost:8080 --rotulo regras
    python avaliar_triagem.py --api http://localhost:8080 --rotulo groq

Gera resultados/conjunto_ouro_<rotulo>.csv e .txt. Só usa a biblioteca padrão.

As métricas são as mesmas do teste automatizado ConjuntoOuroTest (Java), para os números poderem ser comparados.
Atenção ao ler os números: as regras foram ajustadas depois de conhecer estas 40 frases (a execução anterior ao
ajuste está salva em resultados/conjunto_ouro_regras_1a_execucao.*). Por isso o número das regras é otimista.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter
from pathlib import Path

AQUI = Path(__file__).resolve().parent
ORDEM_URGENCIA = ["vermelho", "laranja", "amarelo", "verde", "azul"]
LIMITES = {"recall_vermelho": 1.0, "servico": 0.95, "especialidade": 0.80, "urgencia_exata": 0.70, "urgencia_mais_menos_1": 0.90}


def carregar_casos(arquivo: Path) -> list[dict]:
    with open(arquivo, encoding="utf-8", newline="") as f:
        casos = list(csv.DictReader(f))
    if not casos:
        raise ValueError(f"{arquivo} está vazio")
    for c in casos:
        if c["urgencia"] not in ORDEM_URGENCIA:
            raise ValueError(f"urgência inválida no caso {c['id']}: {c['urgencia']}")
    return casos


def _norm(v) -> str:
    return "" if v is None else str(v).strip().lower()


def calcular_metricas(pares: list[dict]) -> dict:
    """pares: dicts com esperado_servico/obtido_servico, esperado_cbo/obtido_cbo, esperado_urgencia/obtida_urgencia."""
    n = len(pares)
    if n == 0:
        raise ValueError("sem casos")
    serv = sum(p["esperado_servico"] == p["obtido_servico"] for p in pares)
    exata = sum(p["esperado_urgencia"] == p["obtida_urgencia"] for p in pares)
    mm1 = sum(abs(ORDEM_URGENCIA.index(p["esperado_urgencia"]) - ORDEM_URGENCIA.index(p["obtida_urgencia"])) <= 1
              for p in pares if p["obtida_urgencia"] in ORDEM_URGENCIA)
    com_cbo = [p for p in pares if p["esperado_cbo"]]
    esp = sum(p["esperado_cbo"] == p["obtido_cbo"] for p in com_cbo)
    verm_esp = [p for p in pares if p["esperado_urgencia"] == "vermelho"]
    verm_ok = sum(p["obtida_urgencia"] == "vermelho" for p in verm_esp)
    falsos = sum(p["esperado_urgencia"] != "vermelho" and p["obtida_urgencia"] == "vermelho" for p in pares)
    return {
        "frases": n,
        "servico": serv / n,
        "especialidade": (esp / len(com_cbo)) if com_cbo else None,
        "especialidade_n": (esp, len(com_cbo)),
        "urgencia_exata": exata / n,
        "urgencia_mais_menos_1": mm1 / n,
        "recall_vermelho": (verm_ok / len(verm_esp)) if verm_esp else None,
        "recall_vermelho_n": (verm_ok, len(verm_esp)),
        "falsos_vermelhos": falsos,
        "caminhos": dict(Counter(p.get("caminho", "") for p in pares)),
    }


def verificar_limites(m: dict, exige_llm: bool = False) -> list[str]:
    """Lista de limites que NÃO foram atingidos (vazia = tudo ok).

    Com exige_llm=True (rótulo diferente de "regras"), também falha se nenhuma frase foi respondida
    pelo LLM: isso acontece com chave inválida (401) ou provedor fora do ar, e o resultado seria só
    o das regras com outro nome.
    """
    falhas = []
    if exige_llm:
        n_llm = sum(v for k, v in m.get("caminhos", {}).items() if str(k).lower() == "llm")
        if n_llm == 0:
            falhas.append("nenhuma frase foi respondida pelo LLM (chave inválida, provedor fora do ar ou LLM_PROVIDER=rules)")
    for chave, minimo in LIMITES.items():
        v = m.get(chave)
        if v is None or v < minimo:
            falhas.append(f"{chave}: {'n/d' if v is None else f'{v:.1%}'} < {minimo:.0%}")
    if m["falsos_vermelhos"] > 1:
        falhas.append(f"falsos_vermelhos: {m['falsos_vermelhos']} > 1")
    return falhas


# ---------------------------------------------------------------- cliente HTTP

class Cliente:
    def __init__(self, base: str, intervalo: float = 2.1, timeout: float = 30.0):
        self.base = base.rstrip("/")
        self.intervalo = intervalo
        self.timeout = timeout
        self.token = None
        self._ultimo = 0.0

    def _req(self, caminho: str, corpo: dict, auth: bool = True) -> tuple[int, dict, dict]:
        dados = json.dumps(corpo).encode("utf-8")
        cab = {"Content-Type": "application/json"}
        if auth and self.token:
            cab["Authorization"] = f"Bearer {self.token}"
        req = urllib.request.Request(self.base + caminho, data=dados, headers=cab, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return r.status, json.loads(r.read() or b"{}"), dict(r.headers)
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                return e.code, json.loads(raw or b"{}"), dict(e.headers)
            except json.JSONDecodeError:
                return e.code, {"erro": raw.decode("utf-8", "replace")[:200]}, dict(e.headers)

    def autenticar(self):
        email = f"avaliador-{uuid.uuid4().hex[:8]}@exemplo.test"
        senha = uuid.uuid4().hex
        st, corpo, _ = self._req("/api/auth/register", {"nome": "Avaliador", "email": email, "senha": senha, "consentimento": True}, auth=False)
        if st != 201:
            raise RuntimeError(f"registro falhou ({st}): {corpo}")
        st, corpo, _ = self._req("/api/auth/login", {"email": email, "senha": senha}, auth=False)
        if st != 200:
            raise RuntimeError(f"login falhou ({st}): {corpo}")
        self.token = corpo["token"]

    def triar(self, texto: str, tentativas: int = 3) -> dict:
        for _ in range(tentativas):
            espera = self.intervalo - (time.monotonic() - self._ultimo)
            if espera > 0:
                time.sleep(espera)
            self._ultimo = time.monotonic()
            st, corpo, cab = self._req("/api/triagem", {"texto": texto, "consentimento": True})
            if st == 200:
                return corpo
            if st == 429:
                time.sleep(float(cab.get("Retry-After", 60)))
                continue
            raise RuntimeError(f"triagem falhou ({st}): {corpo}")
        raise RuntimeError("limite de requisições persistente (429)")


def avaliar(cliente, casos: list[dict], progresso=None) -> list[dict]:
    pares = []
    for i, c in enumerate(casos, 1):
        r = cliente.triar(c["texto"])
        pares.append({
            "id": c["id"], "texto": c["texto"],
            "esperado_servico": _norm(c["servico"]), "obtido_servico": _norm(r.get("servico")),
            "esperado_cbo": _norm(c["cbo"]), "obtido_cbo": _norm(r.get("cbo")),
            "esperado_urgencia": _norm(c["urgencia"]), "obtida_urgencia": _norm(r.get("urgencia")),
            "caminho": _norm(r.get("caminho")),
        })
        if progresso:
            progresso(i, len(casos))
    return pares


def relatorio(rotulo: str, m: dict, falhas: list[str], pares: list[dict]) -> str:
    def pct(v):
        return "n/d" if v is None else f"{v:.1%}"
    linhas = [
        f"Avaliação da triagem no conjunto ouro, rótulo: {rotulo}",
        f"frases: {m['frases']}",
        f"serviço: {pct(m['servico'])}",
        f"especialidade: {pct(m['especialidade'])} ({m['especialidade_n'][0]}/{m['especialidade_n'][1]})",
        f"urgência exata: {pct(m['urgencia_exata'])}",
        f"urgência ±1 nível: {pct(m['urgencia_mais_menos_1'])}",
        f"recall do vermelho: {pct(m['recall_vermelho'])} ({m['recall_vermelho_n'][0]}/{m['recall_vermelho_n'][1]})",
        f"falsos vermelhos: {m['falsos_vermelhos']}",
        f"caminhos usados: {m['caminhos']}",
        "limites mínimos: " + ("todos atingidos" if not falhas else "NÃO atingidos: " + "; ".join(falhas)),
        "",
        "Erros de urgência e especialidade:",
    ]
    for p in pares:
        if p["esperado_urgencia"] != p["obtida_urgencia"]:
            linhas.append(f"  urgência #{p['id']} '{p['texto']}': esperado {p['esperado_urgencia']}, obtido {p['obtida_urgencia']}")
        if p["esperado_cbo"] and p["esperado_cbo"] != p["obtido_cbo"]:
            linhas.append(f"  especialidade #{p['id']} '{p['texto']}': esperado {p['esperado_cbo']}, obtido {p['obtido_cbo']}")
    return "\n".join(linhas) + "\n"


def gravar(pasta: Path, rotulo: str, pares: list[dict], texto: str):
    pasta.mkdir(parents=True, exist_ok=True)
    campos = ["id", "texto", "esperado_servico", "obtido_servico", "esperado_cbo", "obtido_cbo", "esperado_urgencia", "obtida_urgencia", "caminho"]
    with open(pasta / f"conjunto_ouro_{rotulo}.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=campos)
        w.writeheader()
        w.writerows(pares)
    (pasta / f"conjunto_ouro_{rotulo}.txt").write_text(texto, encoding="utf-8")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--api", default="http://localhost:8080")
    p.add_argument("--rotulo", required=True, help="nome do modo avaliado (ex.: regras, groq); vira parte do nome do arquivo")
    p.add_argument("--casos", default=str(AQUI / "frases_ouro.csv"))
    p.add_argument("--saida", default=str(AQUI / "resultados"))
    p.add_argument("--intervalo", type=float, default=2.1, help="segundos entre chamadas (limite padrão da API: 30/min)")
    a = p.parse_args(argv)
    if not a.rotulo.replace("_", "").replace("-", "").isalnum():
        print("rótulo deve ter só letras, números, - e _", file=sys.stderr)
        return 2
    try:
        casos = carregar_casos(Path(a.casos))
        cli = Cliente(a.api, a.intervalo)
        cli.autenticar()
        pares = avaliar(cli, casos, lambda i, n: print(f"\r{i}/{n}", end="", flush=True))
        print()
    except (OSError, RuntimeError, ValueError) as e:
        print(f"erro: {e}", file=sys.stderr)
        return 1
    m = calcular_metricas(pares)
    falhas = verificar_limites(m, exige_llm=not a.rotulo.lower().startswith(("regras", "rules")))
    texto = relatorio(a.rotulo, m, falhas, pares)
    gravar(Path(a.saida), a.rotulo, pares, texto)
    print(texto)
    return 0 if not falhas else 3


if __name__ == "__main__":
    sys.exit(main())
