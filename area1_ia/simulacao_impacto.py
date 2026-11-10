"""Simulação de impacto: 1.000 pessoas procurando atendimento, SEM o app x COM o app.

NÃO é medição real. É uma simulação com premissas declaradas, todas editáveis na linha de comando:

  Com o app: a pessoa já é indicada à unidade mais próxima que tem o profissional presente (é o que o ranking faz).
  Sem o app (dois comportamentos testados):
      qualquer      -> vai à unidade mais próxima, de qualquer tipo;
      porta_aberta  -> vai à UPA, pronto-socorro ou hospital mais próximo.
  Se a unidade escolhida não oferece a especialidade ou o profissional não está presente naquele momento, a pessoa
  perde a viagem (ida e volta = 2 x distância) e depois vai à unidade certa.

Premissas padrão (todas discutíveis, por isso há análise de sensibilidade):
  - moradores distribuídos ao redor das 51 unidades reais do CNES (ruído gaussiano de ~3 km);
  - a especialidade procurada segue a distribuição PESOS_ESPECIALIDADE (parecida com a de uma rede básica);
  - em um dado momento o profissional de uma unidade que oferece a especialidade está presente com probabilidade p (padrão 70%);
  - custo de R$ 1,20 por km; velocidade média de deslocamento 25 km/h;
  - emergências (vermelho) ficam fora: o app manda ligar 192, não indica unidade.

Uso:  python simulacao_impacto.py [--n 1000] [--seed 2026] [--p-presenca 0.7] [--custo-km 1.2] [--saida resultados]
"""
from __future__ import annotations

import argparse
import csv
import math
import random
import statistics
import sys
from dataclasses import dataclass, replace
from pathlib import Path

AQUI = Path(__file__).resolve().parent
DIM = AQUI.parent / "area2_dados" / "data"

PESOS_ESPECIALIDADE = {  # CBO -> peso relativo
    "225125": 30, "225130": 12, "225124": 14, "225250": 8, "225270": 10, "225120": 5,
    "225265": 4, "225275": 4, "225135": 4, "225133": 5, "225285": 2, "225112": 2,
}
PORTA_ABERTA = {"UPA", "PRONTO_SOCORRO", "HOSPITAL"}
BASELINES = {
    "qualquer": "vai à unidade mais próxima, de qualquer tipo",
    "porta_aberta": "vai à UPA, pronto-socorro ou hospital mais próximo (comportamento comum de quem não sabe onde há o profissional)",
}


@dataclass(frozen=True)
class Premissas:
    n: int = 1000
    seed: int = 2026
    p_presenca: float = 0.70
    custo_km: float = 1.20
    velocidade_kmh: float = 25.0
    ruido_graus: float = 0.03

    def validar(self):
        if self.n < 1:
            raise ValueError("n deve ser >= 1")
        if not 0.0 <= self.p_presenca <= 1.0:
            raise ValueError("p_presenca deve estar entre 0 e 1")
        if self.custo_km < 0 or self.velocidade_kmh <= 0 or self.ruido_graus < 0:
            raise ValueError("custo_km >= 0, velocidade_kmh > 0 e ruido_graus >= 0")


def haversine_km(lat1, lon1, lat2, lon2) -> float:
    r = 6371.0088
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi, dl = p2 - p1, math.radians(lon2 - lon1)
    h = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def carregar_rede(dim: Path = DIM) -> tuple[list[dict], dict[int, set[str]]]:
    with open(dim / "dim_unidades.csv", encoding="utf-8", newline="") as f:
        unidades = [{"id": int(r["cnes"]), "lat": float(r["lat"]), "lon": float(r["lon"]), "tipo": r["tipo"]} for r in csv.DictReader(f)]
    oferta: dict[int, set[str]] = {u["id"]: set() for u in unidades}
    with open(dim / "dim_especialidades.csv", encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            if int(r["cnes"]) in oferta:
                oferta[int(r["cnes"])].add(r["cbo"])
    return unidades, oferta


def _zerado() -> dict:
    return {"perdidas": 0, "nao_oferece": 0, "ausente": 0, "km_extra": 0.0}


def simular(pr: Premissas, unidades: list[dict], oferta: dict[int, set[str]]) -> dict:
    pr.validar()
    if not unidades:
        raise ValueError("rede vazia")
    rng = random.Random(pr.seed)
    cbos = list(PESOS_ESPECIALIDADE)
    pesos = [PESOS_ESPECIALIDADE[c] for c in cbos]
    tipo = {u["id"]: u["tipo"] for u in unidades}
    sem_rede = atendidos = 0
    km_com_app = 0.0
    acc = {k: _zerado() for k in BASELINES}
    for _ in range(pr.n):
        base = rng.choice(unidades)
        lat, lon = rng.gauss(base["lat"], pr.ruido_graus), rng.gauss(base["lon"], pr.ruido_graus)
        cbo = rng.choices(cbos, weights=pesos)[0]
        ordenadas = sorted((haversine_km(lat, lon, u["lat"], u["lon"]), u["id"]) for u in unidades)
        presente: dict[int, bool] = {}

        def tem(uid: int) -> bool:
            if cbo not in oferta[uid]:
                return False
            if uid not in presente:
                presente[uid] = rng.random() < pr.p_presenca
            return presente[uid]

        certa = next(((d, uid) for d, uid in ordenadas if tem(uid)), None)
        if certa is None:
            sem_rede += 1  # ninguém na rede tem o profissional agora: igual com e sem o app
            continue
        atendidos += 1
        km_com_app += certa[0]
        destinos = {"qualquer": ordenadas[0],
                    "porta_aberta": next((x for x in ordenadas if tipo[x[1]] in PORTA_ABERTA), ordenadas[0])}
        for nome, (d, uid) in destinos.items():
            if not tem(uid):
                a = acc[nome]
                a["perdidas"] += 1
                a["nao_oferece" if cbo not in oferta[uid] else "ausente"] += 1
                a["km_extra"] += 2 * d
    out = {"n": pr.n, "atendidos": atendidos, "sem_profissional_na_rede": sem_rede, "km_com_app": round(km_com_app, 1),
           "viagens_perdidas_com_app": 0, "baselines": {}}
    for nome, a in acc.items():
        minutos = a["km_extra"] / pr.velocidade_kmh * 60
        out["baselines"][nome] = {
            "viagens_perdidas_sem_app": a["perdidas"],
            "perdidas_porque_nao_oferece": a["nao_oferece"],
            "perdidas_porque_ausente": a["ausente"],
            "pct_viagens_perdidas": round(100 * a["perdidas"] / atendidos, 1) if atendidos else 0.0,
            "km_evitados": round(a["km_extra"], 1),
            "custo_evitado_reais": round(a["km_extra"] * pr.custo_km, 2),
            "minutos_evitados_deslocamento": round(minutos, 1),
            "minutos_evitados_por_pessoa_atendida": round(minutos / atendidos, 2) if atendidos else 0.0,
        }
    return out


def sensibilidade(pr: Premissas, unidades, oferta, ps=(0.5, 0.7, 0.9), repeticoes: int = 20) -> list[dict]:
    """Repete a simulação com seeds diferentes para cada p e devolve média e desvio padrão por baseline."""
    linhas = []
    for p in ps:
        rs = [simular(replace(pr, p_presenca=p, seed=pr.seed + i), unidades, oferta) for i in range(repeticoes)]
        for nome in BASELINES:
            pct = [r["baselines"][nome]["pct_viagens_perdidas"] for r in rs]
            km = [r["baselines"][nome]["km_evitados"] for r in rs]
            linhas.append({"baseline": nome, "p_presenca": p, "repeticoes": repeticoes,
                           "pct_viagens_perdidas_media": round(statistics.mean(pct), 1),
                           "pct_viagens_perdidas_dp": round(statistics.pstdev(pct), 1),
                           "km_evitados_media": round(statistics.mean(km), 1), "km_evitados_dp": round(statistics.pstdev(km), 1)})
    return linhas


def relatorio_md(pr: Premissas, res: dict, sens: list[dict]) -> str:
    L = [
        "# Simulação de impacto (resultado de simulação, não medição real)", "",
        "## Premissas", "",
        f"- Pessoas simuladas: {pr.n} (semente {pr.seed}, reprodutível)",
        f"- Probabilidade de o profissional estar presente na unidade que oferece a especialidade: {pr.p_presenca:.0%}",
        f"- Custo por km: R$ {pr.custo_km:.2f} | velocidade média: {pr.velocidade_kmh:.0f} km/h",
        "- Moradores distribuídos ao redor das 51 unidades reais do CNES; especialidades com pesos de rede básica; "
        "especialidades oferecidas por unidade: catálogo simulado do seed",
        "- Com o app: vai direto à unidade mais próxima que tem o profissional presente",
        "- Sem o app, dois comportamentos (a diferença entre eles é grande e precisa ser lida junto com o resultado):",
        *[f"  - `{k}`: {v}" for k, v in BASELINES.items()],
        "- Se a unidade escolhida não tem o profissional, a pessoa perde a ida e volta (2 x distância)",
        "- Emergências (vermelho) ficam fora: o app orienta ligar 192", "",
        "## Resultado (uma execução)", "",
        f"- Pessoas atendidas na rede: {res['atendidos']} (sem profissional em nenhuma unidade: {res['sem_profissional_na_rede']})", ""]
    for nome, b in res["baselines"].items():
        L += [f"### Sem o app, baseline `{nome}`", "",
              f"- Viagens perdidas: {b['viagens_perdidas_sem_app']} ({b['pct_viagens_perdidas']}%); com o app: 0 por construção",
              f"  - porque a unidade não oferece a especialidade: {b['perdidas_porque_nao_oferece']}",
              f"  - porque o profissional não estava presente: {b['perdidas_porque_ausente']}",
              f"- Km evitados: {b['km_evitados']} | custo evitado: R$ {b['custo_evitado_reais']:.2f}",
              f"- Deslocamento evitado: {b['minutos_evitados_deslocamento']} min no total "
              f"({b['minutos_evitados_por_pessoa_atendida']} min por pessoa atendida)", ""]
    L += ["## Sensibilidade à premissa mais incerta (presença do profissional)", "",
          "| baseline | p presença | repetições | viagens perdidas % (média ± dp) | km evitados (média ± dp) |", "|---|---|---|---|---|"]
    for s in sens:
        L.append(f"| {s['baseline']} | {s['p_presenca']:.0%} | {s['repeticoes']} | {s['pct_viagens_perdidas_media']} ± {s['pct_viagens_perdidas_dp']} "
                 f"| {s['km_evitados_media']} ± {s['km_evitados_dp']} |")
    L += ["", "## Limites desta simulação", "",
          "- A maior parte das viagens perdidas vem de a unidade não oferecer a especialidade, não da ausência do profissional. "
          "Quais especialidades cada unidade oferece é SIMULADO no seed (as unidades e coordenadas são reais, o catálogo de especialidades não), "
          "então esse percentual alto é consequência do catálogo simulado e não deve ser apresentado como fato sobre a rede real.",
          "- \"Com o app: 0 viagens perdidas\" supõe que a informação de presença do app está correta. Na prática ela pode estar defasada "
          "(o ranking rebaixa unidades sem confirmação recente).",
          "- Não considera trânsito, transporte público, lotação nem a fila: só distância.",
          "- A probabilidade de presença, a distribuição das especialidades e o comportamento sem o app são premissas, não dados medidos.",
          "- Use a faixa entre os dois baselines e a tabela de sensibilidade, não um número isolado."]
    return "\n".join(L) + "\n"


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--n", type=int, default=1000)
    p.add_argument("--seed", type=int, default=2026)
    p.add_argument("--p-presenca", type=float, default=0.70)
    p.add_argument("--custo-km", type=float, default=1.20)
    p.add_argument("--velocidade-kmh", type=float, default=25.0)
    p.add_argument("--dim", default=str(DIM))
    p.add_argument("--saida", default=str(AQUI / "resultados"))
    a = p.parse_args(argv)
    pr = Premissas(a.n, a.seed, a.p_presenca, a.custo_km, a.velocidade_kmh)
    try:
        pr.validar()
        unidades, oferta = carregar_rede(Path(a.dim))
        res = simular(pr, unidades, oferta)
        sens = sensibilidade(pr, unidades, oferta)
    except (ValueError, OSError, KeyError) as e:
        print(f"erro: {e}", file=sys.stderr)
        return 1
    saida = Path(a.saida)
    saida.mkdir(parents=True, exist_ok=True)
    texto = relatorio_md(pr, res, sens)
    (saida / "simulacao_impacto.md").write_text(texto, encoding="utf-8")
    linhas = [{"baseline": k, "n": res["n"], "atendidos": res["atendidos"], **v} for k, v in res["baselines"].items()]
    with open(saida / "simulacao_impacto.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(linhas[0]))
        w.writeheader()
        w.writerows(linhas)
    print(texto)
    return 0


if __name__ == "__main__":
    sys.exit(main())
