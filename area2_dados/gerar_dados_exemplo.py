#!/usr/bin/env python3
"""Gera dados SINTÉTICOS de exemplo na camada bronze (origem "exemplo").

Serve para o ETL e o dashboard terem histórico (21 dias por padrão, 3 semanas completas) sem depender de uso real.
Nada aqui é dado real de pacientes: o modelo é simples, com semente fixa (reprodutível) e as
premissas estão no código. Os eventos reais da API ficam em arquivos separados (api.jsonl).

Formato idêntico ao gravado pela API:  bronze/<dataset>/<AAAA-MM-DD>/exemplo.jsonl
datasets: iot, triagens, agendamentos

Uso:
    python gerar_dados_exemplo.py                 # 21 dias até ontem
    python gerar_dados_exemplo.py --dias 7 --fim 2026-11-09
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import random
import shutil
import sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

AQUI = Path(__file__).resolve().parent
CENTRO = (-23.5505, -46.6333)
FUSO = ZoneInfo("America/Sao_Paulo")
HORAS = range(7, 19)  # 07:00 a 18:00 (12 amostras por dia)
MIN_POR_PESSOA = 5
RAIO_KM = 40.0

# peso da demanda por especialidade e urgência típica (vermelho nunca tem especialidade/ranking)
ESPECIALIDADES = {
    "225125": ("Clínico geral", 28), "225130": ("Médico de família e comunidade", 10), "225124": ("Pediatria", 14),
    "225250": ("Ginecologia e obstetrícia", 8), "225270": ("Ortopedia e traumatologia", 10), "225120": ("Cardiologia", 5),
    "225265": ("Oftalmologia", 5), "225275": ("Otorrinolaringologia", 4), "225135": ("Dermatologia", 5),
    "225133": ("Psiquiatria", 5), "225285": ("Urologia", 3), "225112": ("Neurologia", 3),
}
FRASES = {
    "225125": "estou com febre e dor no corpo", "225130": "preciso renovar a receita", "225124": "meu filho está com febre",
    "225250": "quero começar o pré-natal", "225270": "quebrei a perna", "225120": "meu coração dispara",
    "225265": "meu olho está vermelho", "225275": "dor de ouvido", "225135": "manchas na pele coçando",
    "225133": "estou muito ansioso", "225285": "dor ao urinar", "225112": "dor de cabeça forte e tontura",
}
URGENCIAS = ["laranja", "amarelo", "verde", "azul"]
PESO_URG = {"225270": [30, 40, 25, 5], "225124": [8, 40, 40, 12], "225125": [5, 30, 55, 10]}
PESO_URG_PADRAO = [4, 25, 55, 16]


def haversine_km(lat1, lon1, lat2, lon2):
    r = 6371.0088
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def regiao(lat, lon):
    """Mesma regra da API (Geo.regiao)."""
    if haversine_km(lat, lon, *CENTRO) < 3:
        return "Centro"
    dlat, dlon = lat - CENTRO[0], lon - CENTRO[1]
    if abs(dlat) >= abs(dlon):
        return "Norte" if dlat > 0 else "Sul"
    return "Leste" if dlon > 0 else "Oeste"


def carregar_dim(pasta: Path):
    with open(pasta / "dim_unidades.csv", encoding="utf-8", newline="") as f:
        unidades = [{**l, "cnes": int(l["cnes"]), "lat": float(l["lat"]), "lon": float(l["lon"])} for l in csv.DictReader(f)]
    esp: dict[int, list[dict]] = {}
    with open(pasta / "dim_especialidades.csv", encoding="utf-8", newline="") as f:
        for l in csv.DictReader(f):
            esp.setdefault(int(l["cnes"]), []).append({**l, "qtd": int(l["qtd_profissionais"]),
                                                       "agenda": l["agenda_aberta"].strip().lower() == "true"})
    with open(pasta / "dim_servicos.csv", encoding="utf-8", newline="") as f:
        serv: dict[int, set[str]] = {}
        for l in csv.DictReader(f):
            serv.setdefault(int(l["cnes"]), set()).add(l["tipo"])
    return unidades, esp, serv


def iso_utc(dt_local: datetime) -> str:
    return dt_local.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class Gerador:
    def __init__(self, pasta_dim: Path, seed: int = 2026):
        self.rng = random.Random(seed)
        self.unidades, self.esp, self.serv = carregar_dim(pasta_dim)
        self.triagem_id = 0
        self.agendamento_id = 0
        self.linhas: dict[tuple[str, str], list[dict]] = {}  # (dataset, data local) -> registros

    def _emitir(self, dataset: str, dia: date, registro: dict):
        registro["origem"] = "exemplo"
        self.linhas.setdefault((dataset, dia.isoformat()), []).append(registro)

    # ------------------------------------------------------------ estado simulado do dia
    def _estado_hora(self, dia: date, hora: int, queda: dict[int, set[int]]):
        rng = self.rng
        fim_semana = dia.weekday() >= 5
        estado = {}
        for u in self.unidades:
            cnes = u["cnes"]
            online = hora not in queda.get(cnes, set())
            base = {"UPA": 22, "HOSPITAL": 18, "PRONTO_SOCORRO": 25, "POLICLINICA": 8, "UBS": 6}.get(u["tipo"], 8)
            pico = 1.6 if 9 <= hora <= 11 else (0.7 if hora >= 17 else 1.0)
            fila = max(0, int(round(rng.gauss(base * pico * (0.6 if fim_semana else 1.0), base * 0.25))))
            lat = max(20, int(round(rng.gauss(180, 60))))
            presentes = {}
            for e in self.esp.get(cnes, []):
                p = 0.9 if not (hora in (12, 13) or hora >= 17) else 0.55
                if fim_semana:
                    p *= 0.7
                presentes[e["cbo"]] = rng.random() < p
            estado[cnes] = {"online": online, "fila": fila, "lat": lat, "presentes": presentes}
        return estado

    # ------------------------------------------------------------ ranking (mesma fórmula da API)
    def _indicada(self, estado, cbo, lat, lon):
        elegiveis = []
        for u in self.unidades:
            st = estado[u["cnes"]]
            e = next((x for x in self.esp.get(u["cnes"], []) if x["cbo"] == cbo), None)
            d = haversine_km(lat, lon, u["lat"], u["lon"])
            if st["online"] and e is not None and (st["presentes"].get(cbo) or e["agenda"]) and d <= RAIO_KM:
                elegiveis.append((u, d, st, bool(st["presentes"].get(cbo))))
        nearest = min(self.unidades, key=lambda u: haversine_km(lat, lon, u["lat"], u["lon"]))
        dn = haversine_km(lat, lon, nearest["lat"], nearest["lon"])
        e_n = next((x for x in self.esp.get(nearest["cnes"], []) if x["cbo"] == cbo), None)
        stn = estado[nearest["cnes"]]
        tinha = bool(stn["online"] and e_n is not None and (stn["presentes"].get(cbo) or e_n["agenda"]))
        if not elegiveis:
            return None, None, round(dn, 2), tinha
        dmax = max(x[1] for x in elegiveis) or 1
        emax = max(x[2]["fila"] * MIN_POR_PESSOA for x in elegiveis) or 1

        def score(x):
            return 0.5 * x[1] / dmax + 0.3 * (x[2]["fila"] * MIN_POR_PESSOA) / emax + 0.2 * min(1.0, x[2]["lat"] / 2000)

        agora = sorted([x for x in elegiveis if x[3]], key=score)
        agenda = sorted([x for x in elegiveis if not x[3]], key=score)
        melhor = (agora + agenda)[0]
        return melhor[0]["cnes"], round(melhor[1], 2), round(dn, 2), tinha

    # ------------------------------------------------------------ geração
    def gerar(self, fim: date, dias: int):
        rng = self.rng
        inicio = fim - timedelta(days=dias - 1)
        for i in range(dias):
            dia = inicio + timedelta(days=i)
            surto = 1.4 if i >= dias - 5 else 1.0  # últimos 5 dias: mais clínico/pediatria (variação semanal visível)
            queda = {}
            for u in self.unidades:
                if rng.random() < 0.06:  # ~6% das unidades-dia têm 1 a 3 horas sem conexão
                    ini = rng.choice(list(HORAS))
                    queda[u["cnes"]] = set(range(ini, ini + rng.randint(1, 3)))
            for hora in HORAS:
                estado = self._estado_hora(dia, hora, queda)
                ts = datetime(dia.year, dia.month, dia.day, hora, 0, tzinfo=FUSO)
                self._iot(dia, ts, estado)
                self._triagens(dia, hora, ts, estado, surto)

    def _iot(self, dia, ts, estado):
        for u in self.unidades:
            st = estado[u["cnes"]]
            if not st["online"]:
                continue
            c = u["cnes"]
            carimbo = iso_utc(ts)
            self._emitir("iot", dia, {"eventId": f"ex-{c}-hb-{carimbo}", "unidadeId": c, "tipo": "heartbeat", "ts": carimbo,
                                      "online": True, "latenciaMs": st["lat"]})
            self._emitir("iot", dia, {"eventId": f"ex-{c}-fila-{carimbo}", "unidadeId": c, "tipo": "fila", "ts": carimbo,
                                      "pessoas": st["fila"]})
            for e in self.esp.get(c, []):
                self._emitir("iot", dia, {"eventId": f"ex-{c}-pr-{e['cbo']}-{carimbo}", "unidadeId": c, "tipo": "presenca",
                                          "ts": carimbo, "cbo": e["cbo"], "presente": bool(st["presentes"][e["cbo"]])})

    def _triagens(self, dia, hora, ts, estado, surto):
        rng = self.rng
        pico = 1.5 if 8 <= hora <= 11 else 1.0
        fim_semana = dia.weekday() >= 5
        taxa = 14 * pico * (0.6 if fim_semana else 1.0)
        n = max(0, int(round(rng.gauss(taxa, math.sqrt(taxa)))))
        cbos = list(ESPECIALIDADES)
        for k in range(n):
            self.triagem_id += 1
            minuto = rng.randint(0, 59)
            t = ts + timedelta(minutes=minuto, seconds=rng.randint(0, 59))
            ref = rng.choice(self.unidades)
            lat = ref["lat"] + rng.uniform(-0.02, 0.02)
            lon = ref["lon"] + rng.uniform(-0.02, 0.02)
            reg = regiao(lat, lon)
            sorteio = rng.random()
            base = {"triagemId": self.triagem_id, "ts": iso_utc(t), "regiao": reg}
            if sorteio < 0.04:  # emergência: nunca passa por LLM nem tem ranking
                self._emitir("triagens", dia, {**base, "servico": "saude", "cbo": None, "especialidade": None,
                                               "urgencia": "vermelho", "caminho": "red_flag", "unidadeIndicadaId": None,
                                               "kmMaisProxima": None, "kmIndicada": None, "maisProximaTinha": None,
                                               "textoAnonimizado": "dor no peito e falta de ar"})
                continue
            if sorteio < 0.13:
                servico = "documento"
            elif sorteio < 0.19:
                servico = "assistencia_social"
            else:
                servico = "saude"
            if servico != "saude":
                nome = "segunda via de documento" if servico == "documento" else "preciso de ajuda do CRAS"
                self._emitir("triagens", dia, {**base, "servico": servico, "cbo": None, "especialidade": None,
                                               "urgencia": "azul", "caminho": "regras", "unidadeIndicadaId": None,
                                               "kmMaisProxima": None, "kmIndicada": None, "maisProximaTinha": None,
                                               "textoAnonimizado": nome})
                continue
            pesos = [ESPECIALIDADES[c][1] * (surto if c in ("225125", "225124") else 1.0) for c in cbos]
            cbo = rng.choices(cbos, weights=pesos)[0]
            urg = rng.choices(URGENCIAS, weights=PESO_URG.get(cbo, PESO_URG_PADRAO))[0]
            ind, km_ind, km_prox, tinha = self._indicada(estado, cbo, lat, lon)
            self._emitir("triagens", dia, {**base, "servico": "saude", "cbo": cbo, "especialidade": ESPECIALIDADES[cbo][0],
                                           "urgencia": urg, "caminho": "fallback" if rng.random() < 0.02 else "regras",
                                           "unidadeIndicadaId": ind, "kmMaisProxima": km_prox, "kmIndicada": km_ind,
                                           "maisProximaTinha": tinha, "textoAnonimizado": FRASES[cbo]})
            if ind is not None and rng.random() < 0.3:
                self._agendar(dia, t, ind, cbo)

    def _agendar(self, dia, t, unidade_id, cbo):
        rng = self.rng
        self.agendamento_id += 1
        alvo = dia + timedelta(days=rng.randint(1, 14))
        slot = datetime(alvo.year, alvo.month, alvo.day, 8, 0) + timedelta(minutes=30 * rng.randint(0, 17))
        protocolo = f"PDM-{dia.strftime('%Y%m%d')}-{self.agendamento_id:06d}"
        base = {"agendamentoId": self.agendamento_id, "protocolo": protocolo, "unidadeId": unidade_id, "servico": "saude",
                "cbo": cbo, "dataHora": slot.isoformat(timespec="seconds")}
        self._emitir("agendamentos", dia, {**base, "evento": "criado", "ts": iso_utc(t), "status": "confirmado"})
        if rng.random() < 0.10:
            t2 = t + timedelta(hours=rng.randint(1, 30))
            self._emitir("agendamentos", t2.astimezone(FUSO).date(),
                         {**base, "evento": "cancelado", "ts": iso_utc(t2), "status": "cancelado"})

    # ------------------------------------------------------------ saída
    def gravar(self, bronze: Path):
        # remove só os arquivos "exemplo.jsonl" (nunca os da API) para a geração ser repetível
        for p in bronze.glob("*/*/exemplo.jsonl"):
            p.unlink()
        for (dataset, dia), regs in self.linhas.items():
            pasta = bronze / dataset / dia
            pasta.mkdir(parents=True, exist_ok=True)
            with open(pasta / "exemplo.jsonl", "w", encoding="utf-8", newline="\n") as f:
                for r in sorted(regs, key=lambda x: (x.get("ts", ""), json.dumps(x, sort_keys=True))):
                    f.write(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n")
        return sum(len(v) for v in self.linhas.values())


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Gera dados sintéticos de exemplo na camada bronze")
    p.add_argument("--bronze", default=str(AQUI / "bronze"))
    p.add_argument("--dim", default=str(AQUI / "data"))
    p.add_argument("--dias", type=int, default=21)
    p.add_argument("--fim", default=None, help="último dia (AAAA-MM-DD); padrão: ontem")
    p.add_argument("--seed", type=int, default=2026)
    a = p.parse_args(argv)
    if a.dias < 1 or a.dias > 120:
        p.error("--dias deve ficar entre 1 e 120")
    fim = date.fromisoformat(a.fim) if a.fim else date.today() - timedelta(days=1)
    g = Gerador(Path(a.dim), a.seed)
    g.gerar(fim, a.dias)
    total = g.gravar(Path(a.bronze))
    print(f"{total} registros de exemplo em {a.bronze} ({a.dias} dias até {fim}), triagens={g.triagem_id}, agendamentos={g.agendamento_id}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
