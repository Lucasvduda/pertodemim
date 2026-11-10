#!/usr/bin/env python3
"""Simulador dos sensores do PertoDeMim (IoT mock, sem hardware).

Gera eventos de fila, presença de profissional e heartbeat de cada unidade e envia para
POST /api/iot/eventos com a chave do dispositivo (X-Device-Key).

- Cenários em JSON (cenarios/*.json): normal, ortopedista_ausente, fila_longa, posto_offline, pico_demanda.
- Eventos idempotentes: o eventId é determinístico por execução/ciclo/unidade/tipo, então reenviar
  não duplica nada.
- Se a API estiver fora do ar, os eventos vão para um buffer local (JSONL) e são reenviados no ciclo seguinte.
- A chave de cada dispositivo é derivada da semente (IOT_KEY_SEED), igual ao Java (HMAC-SHA256),
  então não existe arquivo de credenciais.

Uso:
    python simulador.py --cenario cenarios/normal.json --ciclos 3 --intervalo 5
    python simulador.py --cenario cenarios/ortopedista_ausente.json --api http://localhost:8080
    python simulador.py --cenario cenarios/normal.json --dry-run
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import hmac
import json
import math
import os
import random
import sys
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

AQUI = Path(__file__).resolve().parent
DADOS_PADRAO = AQUI.parent.parent / "area2_dados" / "data"
CENTRO = (-23.5505, -46.6333)  # Praça da Sé (mesmo centro do ranking da API)
TIPOS = ("fila", "presenca", "heartbeat")
ESPECIALIDADES_CBO = {
    "225125", "225130", "225124", "225250", "225270", "225120",
    "225265", "225275", "225135", "225133", "225285", "225112",
}


class ErroCenario(ValueError):
    """Cenário inválido."""


# ---------------------------------------------------------------- chaves

def derivar_chave(semente: str, unidade_id: int, tipo: str) -> str:
    """Mesma regra do Java (IotKeys.derivar): 'pdm_' + HMAC_SHA256(semente, '<id>:<tipo>')[:32]."""
    msg = f"{int(unidade_id)}:{tipo.lower()}".encode("utf-8")
    return "pdm_" + hmac.new(semente.encode("utf-8"), msg, hashlib.sha256).hexdigest()[:32]


# ---------------------------------------------------------------- dados

def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0088
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


@dataclass
class Dados:
    unidades: list[dict[str, Any]]
    especialidades: dict[int, list[dict[str, Any]]] = field(default_factory=dict)
    _cache: dict[str, list[int]] = field(default_factory=dict, repr=False)

    def ranking_por_distancia(self, cbo: str) -> list[int]:
        """Ids das unidades que têm a especialidade, da mais próxima do centro para a mais distante."""
        if cbo in self._cache:
            return self._cache[cbo]
        com = [u for u in self.unidades if any(e["cbo"] == cbo for e in self.especialidades.get(u["cnes"], []))]
        com.sort(key=lambda u: (haversine_km(CENTRO[0], CENTRO[1], u["lat"], u["lon"]), u["cnes"]))
        self._cache[cbo] = [u["cnes"] for u in com]
        return self._cache[cbo]


def carregar_dados(pasta: Path | str = DADOS_PADRAO) -> Dados:
    pasta = Path(pasta)
    unidades: list[dict[str, Any]] = []
    with open(pasta / "dim_unidades.csv", encoding="utf-8", newline="") as f:
        for l in csv.DictReader(f):
            unidades.append({**l, "cnes": int(l["cnes"]), "lat": float(l["lat"]), "lon": float(l["lon"])})
    esp: dict[int, list[dict[str, Any]]] = {}
    with open(pasta / "dim_especialidades.csv", encoding="utf-8", newline="") as f:
        for l in csv.DictReader(f):
            esp.setdefault(int(l["cnes"]), []).append({**l, "cnes": int(l["cnes"])})
    if not unidades:
        raise ErroCenario("dim_unidades.csv vazio")
    return Dados(unidades, esp)


# ---------------------------------------------------------------- cenário

_EFEITOS = {"presente", "offline", "fila", "fila_fixa", "latencia_ms"}
_FILTROS = {"unidade_id", "especialidade", "rank_distancia", "tipo_unidade", "regiao", "todas"}


def validar_cenario(c: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(c, dict):
        raise ErroCenario("cenário deve ser um objeto JSON")
    for chave in ("nome", "base", "regras"):
        if chave not in c:
            raise ErroCenario(f"falta o campo '{chave}'")
    base = c["base"]
    for chave in ("prob_presenca", "fila_media", "fila_desvio", "latencia_ms"):
        if chave not in base:
            raise ErroCenario(f"base sem '{chave}'")
    if not 0 <= base["prob_presenca"] <= 1:
        raise ErroCenario("prob_presenca deve ficar entre 0 e 1")
    if base["fila_media"] < 0 or base["fila_desvio"] < 0 or base["latencia_ms"] < 0:
        raise ErroCenario("fila e latência não podem ser negativas")
    if not isinstance(c["regras"], list):
        raise ErroCenario("'regras' deve ser uma lista")
    for i, r in enumerate(c["regras"]):
        q = r.get("quando")
        if not isinstance(q, dict) or not q:
            raise ErroCenario(f"regra {i}: falta 'quando'")
        desconhecidos = set(q) - _FILTROS
        if desconhecidos:
            raise ErroCenario(f"regra {i}: filtro desconhecido {sorted(desconhecidos)}")
        if "rank_distancia" in q and "especialidade" not in q:
            raise ErroCenario(f"regra {i}: rank_distancia exige especialidade")
        if "especialidade" in q and q["especialidade"] not in ESPECIALIDADES_CBO:
            raise ErroCenario(f"regra {i}: cbo desconhecido {q['especialidade']}")
        efeitos = set(r) - {"quando", "descricao"}
        if not efeitos or not efeitos <= _EFEITOS:
            raise ErroCenario(f"regra {i}: efeitos inválidos {sorted(efeitos)}")
    return c


def carregar_cenario(caminho: Path | str) -> dict[str, Any]:
    with open(caminho, encoding="utf-8") as f:
        try:
            return validar_cenario(json.load(f))
        except json.JSONDecodeError as e:
            raise ErroCenario(f"JSON inválido: {e}") from e


def _lista(v: Any) -> list[Any]:
    return list(v) if isinstance(v, (list, tuple, set)) else [v]


def _casa(quando: dict[str, Any], unidade: dict[str, Any], cbo: str | None, dados: Dados) -> bool:
    if quando.get("todas"):
        return True
    if "unidade_id" in quando and unidade["cnes"] not in _lista(quando["unidade_id"]):
        return False
    if "tipo_unidade" in quando and unidade["tipo"] not in _lista(quando["tipo_unidade"]):
        return False
    if "regiao" in quando and unidade["regiao"] not in _lista(quando["regiao"]):
        return False
    if "especialidade" in quando:
        alvo = quando["especialidade"]
        if cbo is not None and cbo != alvo:
            return False
        ranking = dados.ranking_por_distancia(alvo)
        if unidade["cnes"] not in ranking:
            return False
        if "rank_distancia" in quando and (ranking.index(unidade["cnes"]) + 1) not in _lista(quando["rank_distancia"]):
            return False
    return True


# ---------------------------------------------------------------- geração

@dataclass
class Evento:
    unidade_id: int
    tipo: str
    corpo: dict[str, Any]


def _event_id(run_id: str, ciclo: int, unidade_id: int, tipo: str, cbo: str | None) -> str:
    chave = f"{run_id}|{ciclo}|{unidade_id}|{tipo}|{cbo or ''}"
    return "sim-" + uuid.uuid5(uuid.NAMESPACE_URL, chave).hex[:32]


def gerar_ciclo(cenario: dict[str, Any], dados: Dados, rng: random.Random, ts: datetime, run_id: str,
                ciclo: int) -> list[Evento]:
    """Eventos de um ciclo para todas as unidades (as 'offline' não enviam nada)."""
    base = cenario["base"]
    carimbo = ts.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    eventos: list[Evento] = []
    for u in dados.unidades:
        cnes = u["cnes"]
        offline = False
        fila_media, fila_desvio, fila_fixa = base["fila_media"], base["fila_desvio"], None
        latencia = base["latencia_ms"]
        for r in cenario["regras"]:
            if not _casa(r["quando"], u, None, dados):
                continue
            # efeitos por unidade (offline, fila, latência) só valem se o filtro não depender de uma
            # especialidade específica OU se a unidade casa com ela
            if "offline" in r:
                offline = bool(r["offline"])
            if "fila" in r:
                fila_media, fila_desvio, fila_fixa = r["fila"]["media"], r["fila"].get("desvio", 0), None
            if "fila_fixa" in r:
                fila_fixa = int(r["fila_fixa"])
            if "latencia_ms" in r:
                latencia = r["latencia_ms"]
        if offline:
            continue

        pessoas = fila_fixa if fila_fixa is not None else max(0, int(round(rng.gauss(fila_media, fila_desvio))))
        lat_ms = max(0, int(round(rng.gauss(latencia, latencia * 0.2))))
        eventos.append(Evento(cnes, "heartbeat", {
            "eventId": _event_id(run_id, ciclo, cnes, "heartbeat", None), "unidadeId": cnes, "tipo": "heartbeat",
            "ts": carimbo, "online": True, "latenciaMs": lat_ms}))
        eventos.append(Evento(cnes, "fila", {
            "eventId": _event_id(run_id, ciclo, cnes, "fila", None), "unidadeId": cnes, "tipo": "fila",
            "ts": carimbo, "pessoas": pessoas}))
        for e in dados.especialidades.get(cnes, []):
            cbo = e["cbo"]
            presente = rng.random() < base["prob_presenca"]
            for r in cenario["regras"]:
                if "presente" in r and _casa(r["quando"], u, cbo, dados):
                    presente = bool(r["presente"])
            eventos.append(Evento(cnes, "presenca", {
                "eventId": _event_id(run_id, ciclo, cnes, "presenca", cbo), "unidadeId": cnes, "tipo": "presenca",
                "ts": carimbo, "cbo": cbo, "presente": presente}))
    return eventos


# ---------------------------------------------------------------- envio

@dataclass
class Resumo:
    enviados: int = 0       # 201
    duplicados: int = 0     # 200
    rejeitados: int = 0     # 4xx definitivos
    adiados: int = 0        # foram para o buffer (API fora do ar ou 5xx/429)
    reenviados: int = 0     # saíram do buffer com sucesso

    def somar(self, outro: "Resumo") -> None:
        for k in vars(self):
            setattr(self, k, getattr(self, k) + getattr(outro, k))


class Enviador:
    """Envia eventos com retry curto e buffer local em JSONL."""

    def __init__(self, api: str, semente: str, buffer: Path, sessao: Any = None, tentativas: int = 2,
                 espera_s: float = 0.2, timeout_s: float = 5.0) -> None:
        import requests  # import tardio: os testes de geração não precisam da rede

        self.url = api.rstrip("/") + "/api/iot/eventos"
        self.semente = semente
        self.buffer = Path(buffer)
        self.sessao = sessao or requests.Session()
        self.tentativas = tentativas
        self.espera_s = espera_s
        self.timeout_s = timeout_s

    def _post(self, ev: Evento) -> int | None:
        """Status HTTP, ou None se não conseguiu falar com a API."""
        cabecalhos = {"X-Device-Key": derivar_chave(self.semente, ev.unidade_id, ev.tipo)}
        for i in range(self.tentativas):
            try:
                r = self.sessao.post(self.url, json=ev.corpo, headers=cabecalhos, timeout=self.timeout_s)
                if r.status_code < 500 and r.status_code != 429:
                    return r.status_code
            except Exception:  # rede fora, timeout etc.
                pass
            time.sleep(self.espera_s * (2 ** i))
        return None

    def _guardar(self, ev: Evento) -> None:
        self.buffer.parent.mkdir(parents=True, exist_ok=True)
        with open(self.buffer, "a", encoding="utf-8") as f:
            f.write(json.dumps({"unidade_id": ev.unidade_id, "tipo": ev.tipo, "corpo": ev.corpo}, ensure_ascii=False) + "\n")

    def pendentes(self) -> int:
        if not self.buffer.exists():
            return 0
        with open(self.buffer, encoding="utf-8") as f:
            return sum(1 for l in f if l.strip())

    def reenviar_buffer(self) -> Resumo:
        r = Resumo()
        if not self.buffer.exists():
            return r
        with open(self.buffer, encoding="utf-8") as f:
            linhas = [l for l in f if l.strip()]
        self.buffer.unlink()
        for l in linhas:
            d = json.loads(l)
            ev = Evento(d["unidade_id"], d["tipo"], d["corpo"])
            st = self._post(ev)
            if st in (200, 201):
                r.reenviados += 1
            elif st is None:
                self._guardar(ev)
                r.adiados += 1
            else:
                r.rejeitados += 1
        return r

    def enviar(self, eventos: Iterable[Evento]) -> Resumo:
        r = self.reenviar_buffer()
        indisponivel = False
        for ev in eventos:
            if indisponivel:  # não insiste: guarda o resto do ciclo no buffer
                self._guardar(ev)
                r.adiados += 1
                continue
            st = self._post(ev)
            if st == 201:
                r.enviados += 1
            elif st == 200:
                r.duplicados += 1
            elif st is None:
                indisponivel = True
                self._guardar(ev)
                r.adiados += 1
            else:
                r.rejeitados += 1
        return r


# ---------------------------------------------------------------- CLI

def executar(args: argparse.Namespace, saida=print) -> int:
    try:
        cenario = carregar_cenario(args.cenario)
        dados = carregar_dados(args.dados)
    except (ErroCenario, OSError) as e:
        print(f"erro: {e}", file=sys.stderr)
        return 2
    rng = random.Random(args.seed)
    run_id = args.run_id or uuid.uuid4().hex[:8]
    enviador = None
    if not args.dry_run:
        enviador = Enviador(args.api, args.semente, Path(args.buffer))
    total = Resumo()
    saida(f"cenário '{cenario['nome']}': {len(dados.unidades)} unidades, run {run_id}")
    for ciclo in range(args.ciclos):
        ts = datetime.now(timezone.utc)
        eventos = gerar_ciclo(cenario, dados, rng, ts, run_id, ciclo)
        if enviador is None:
            saida(f"ciclo {ciclo + 1}/{args.ciclos}: {len(eventos)} eventos gerados (dry-run)")
        else:
            r = enviador.enviar(eventos)
            total.somar(r)
            saida(f"ciclo {ciclo + 1}/{args.ciclos}: {len(eventos)} eventos | novos {r.enviados} duplicados {r.duplicados} "
                  f"rejeitados {r.rejeitados} adiados {r.adiados} reenviados {r.reenviados}")
        if ciclo < args.ciclos - 1:
            time.sleep(args.intervalo)
    if enviador is not None:
        saida(f"total: {vars(total)} | pendentes no buffer: {enviador.pendentes()}")
        return 0 if total.rejeitados == 0 else 1
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Simulador de sensores do PertoDeMim (sem hardware)")
    p.add_argument("--api", default=os.getenv("PDM_API", "http://localhost:8080"))
    p.add_argument("--cenario", default=str(AQUI / "cenarios" / "normal.json"))
    p.add_argument("--dados", default=str(DADOS_PADRAO), help="pasta com dim_unidades.csv e dim_especialidades.csv")
    p.add_argument("--semente", default=os.getenv("IOT_KEY_SEED", "dev-only-iot-seed"),
                   help="semente das chaves (igual ao IOT_KEY_SEED da API)")
    p.add_argument("--ciclos", type=int, default=1)
    p.add_argument("--intervalo", type=float, default=30.0, help="segundos entre ciclos (o heartbeat vale por 2 min)")
    p.add_argument("--seed", type=int, default=2026, help="semente do gerador aleatório (reprodutível)")
    p.add_argument("--run-id", default=None)
    p.add_argument("--buffer", default=str(AQUI / "buffer" / "pendentes.jsonl"))
    p.add_argument("--dry-run", action="store_true", help="só gera, não envia")
    args = p.parse_args(argv)
    if args.ciclos < 1:
        p.error("--ciclos deve ser >= 1")
    if args.intervalo < 0:
        p.error("--intervalo não pode ser negativo")
    return executar(args)


if __name__ == "__main__":
    sys.exit(main())
