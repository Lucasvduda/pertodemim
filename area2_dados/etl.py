#!/usr/bin/env python3
"""ETL do PertoDeMim: bronze (JSONL cru) -> silver (limpo, tipado) -> gold (indicadores).

Idempotente: a silver e a gold são recalculadas por inteiro a partir da bronze e deduplicadas por chave
(origem + id do evento). Rodar duas vezes sobre os mesmos dados gera arquivos idênticos.

Linhas inválidas (JSON quebrado, campo faltando, valor fora da faixa) não derrubam o ETL: vão para
silver/rejeitados.csv com o motivo.

Uso:
    python etl.py                       # pastas padrão (bronze/, silver/, gold/, data/)
    python etl.py --custo-km 1.5        # premissa de custo por km
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import pandas as pd

import analitica as an

AQUI = Path(__file__).resolve().parent
FUSO = ZoneInfo("America/Sao_Paulo")
HORAS_OPERACAO = list(range(7, 19))
MIN_POR_PESSOA = 5

TIPOS_IOT = {"fila", "presenca", "heartbeat"}
SERVICOS = {"saude", "documento", "assistencia_social"}
URGENCIAS = {"vermelho", "laranja", "amarelo", "verde", "azul"}
CAMINHOS = {"red_flag", "regras", "llm", "fallback"}
REGIOES = {"Centro", "Norte", "Sul", "Leste", "Oeste"}
CBOS = {"225125", "225130", "225124", "225250", "225270", "225120", "225265", "225275", "225135", "225133", "225285", "225112"}


# ------------------------------------------------------------------ leitura

def ler_bronze(pasta: Path, dataset: str):
    """Lê todos os JSONL de bronze/<dataset>/*/*.jsonl. Devolve (registros, rejeitados)."""
    registros, rejeitados = [], []
    for arq in sorted((pasta / dataset).glob("*/*.jsonl")):
        with open(arq, encoding="utf-8") as f:
            for n, linha in enumerate(f, 1):
                if not linha.strip():
                    continue
                try:
                    r = json.loads(linha)
                    if not isinstance(r, dict):
                        raise ValueError("a linha não é um objeto JSON")
                except (json.JSONDecodeError, ValueError) as e:
                    rejeitados.append({"dataset": dataset, "arquivo": f"{arq.parent.name}/{arq.name}", "linha": n,
                                       "motivo": f"JSON inválido: {e}"[:120]})
                    continue
                r["_arquivo"], r["_linha"] = f"{arq.parent.name}/{arq.name}", n
                registros.append(r)
    return registros, rejeitados


def carregar_dim(pasta: Path):
    unidades = pd.read_csv(pasta / "dim_unidades.csv", dtype={"cnes": "int64"})
    especialidades = pd.read_csv(pasta / "dim_especialidades.csv", dtype={"cnes": "int64", "cbo": "str"})
    return unidades, especialidades


# ------------------------------------------------------------------ validação

def _int(v, minimo=None, maximo=None):
    if isinstance(v, bool) or not isinstance(v, (int, float)) or (isinstance(v, float) and not v.is_integer()):
        raise ValueError("não é inteiro")
    v = int(v)
    if minimo is not None and v < minimo:
        raise ValueError(f"menor que {minimo}")
    if maximo is not None and v > maximo:
        raise ValueError(f"maior que {maximo}")
    return v


def _bool(v):
    if not isinstance(v, bool):
        raise ValueError("não é booleano")
    return v


def _num_ou_none(v, minimo=0.0):
    if v is None:
        return None
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise ValueError("não é número")
    if v < minimo:
        raise ValueError(f"menor que {minimo}")
    return float(v)


def _ts(v):
    if not isinstance(v, str):
        raise ValueError("ts ausente")
    dt = datetime.fromisoformat(v.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def _cabecalho(r, dim_ids):
    origem = r.get("origem")
    if not isinstance(origem, str) or not origem:
        raise ValueError("origem ausente")
    ts = _ts(r.get("ts"))
    local = ts.astimezone(FUSO)
    return origem, ts, local


def validar_iot(r, ids_unidades):
    origem, ts, local = _cabecalho(r, ids_unidades)
    eid = r.get("eventId")
    if not isinstance(eid, str) or not eid or len(eid) > 64:
        raise ValueError("eventId inválido")
    uid = _int(r.get("unidadeId"), 1)
    if uid not in ids_unidades:
        raise ValueError("unidade desconhecida")
    tipo = r.get("tipo")
    if tipo not in TIPOS_IOT:
        raise ValueError("tipo inválido")
    out = {"chave": f"{origem}|{eid}", "event_id": eid, "origem": origem, "unidade_id": uid, "tipo": tipo,
           "ts_utc": ts.strftime("%Y-%m-%dT%H:%M:%SZ"), "data": local.date().isoformat(), "hora": local.hour,
           "pessoas": None, "cbo": None, "presente": None, "online": None, "latencia_ms": None}
    if tipo == "fila":
        out["pessoas"] = _int(r.get("pessoas"), 0, 1000)
    elif tipo == "presenca":
        cbo = r.get("cbo")
        if cbo not in CBOS:
            raise ValueError("cbo inválido")
        out["cbo"], out["presente"] = cbo, _bool(r.get("presente"))
    else:
        out["online"] = _bool(r.get("online"))
        if r.get("latenciaMs") is not None:
            out["latencia_ms"] = _int(r.get("latenciaMs"), 0)
    return out


def validar_triagem(r, ids_unidades):
    origem, ts, local = _cabecalho(r, ids_unidades)
    tid = _int(r.get("triagemId"), 1)
    servico, urgencia, caminho, reg = r.get("servico"), r.get("urgencia"), r.get("caminho"), r.get("regiao")
    if servico not in SERVICOS:
        raise ValueError("serviço inválido")
    if urgencia not in URGENCIAS:
        raise ValueError("urgência inválida")
    if caminho not in CAMINHOS:
        raise ValueError("caminho inválido")
    if reg not in REGIOES:
        raise ValueError("região inválida")
    cbo = r.get("cbo")
    if cbo is not None and cbo not in CBOS:
        raise ValueError("cbo inválido")
    ind = r.get("unidadeIndicadaId")
    if ind is not None:
        ind = _int(ind, 1)
        if ind not in ids_unidades:
            raise ValueError("unidade indicada desconhecida")
    tinha = r.get("maisProximaTinha")
    if tinha is not None:
        tinha = _bool(tinha)
    return {"chave": f"{origem}|{tid}", "triagem_id": tid, "origem": origem, "ts_utc": ts.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "data": local.date().isoformat(), "hora": local.hour, "servico": servico, "cbo": cbo,
            "especialidade": r.get("especialidade"), "urgencia": urgencia, "caminho": caminho, "regiao": reg,
            "unidade_indicada_id": ind, "km_mais_proxima": _num_ou_none(r.get("kmMaisProxima")),
            "km_indicada": _num_ou_none(r.get("kmIndicada")), "mais_proxima_tinha": tinha}


def validar_agendamento(r, ids_unidades):
    origem, ts, local = _cabecalho(r, ids_unidades)
    aid = _int(r.get("agendamentoId"), 1)
    uid = _int(r.get("unidadeId"), 1)
    if uid not in ids_unidades:
        raise ValueError("unidade desconhecida")
    evento = r.get("evento")
    if evento not in ("criado", "cancelado"):
        raise ValueError("evento inválido")
    if r.get("servico") not in SERVICOS:
        raise ValueError("serviço inválido")
    dh = datetime.fromisoformat(str(r.get("dataHora")))
    cbo = r.get("cbo")
    if cbo is not None and cbo not in CBOS:
        raise ValueError("cbo inválido")
    return {"chave": f"{origem}|{aid}|{evento}", "agendamento_id": aid, "origem": origem, "evento": evento,
            "ts_utc": ts.strftime("%Y-%m-%dT%H:%M:%SZ"), "data": local.date().isoformat(), "hora": local.hour,
            "unidade_id": uid, "servico": r.get("servico"), "cbo": cbo, "data_hora_agendada": dh.isoformat(timespec="seconds")}


VALIDADORES = {"iot": validar_iot, "triagens": validar_triagem, "agendamentos": validar_agendamento}


def construir_silver(registros, rejeitados, dataset, ids_unidades):
    """Valida, deduplica por chave e ordena. Devolve (DataFrame, n_duplicados)."""
    linhas, vistos, duplicados = [], set(), 0
    for r in registros:
        try:
            linha = VALIDADORES[dataset](r, ids_unidades)
        except (ValueError, TypeError, KeyError) as e:
            rejeitados.append({"dataset": dataset, "arquivo": r["_arquivo"], "linha": r["_linha"], "motivo": str(e)[:120]})
            continue
        if linha["chave"] in vistos:
            duplicados += 1
            continue
        vistos.add(linha["chave"])
        linhas.append(linha)
    df = pd.DataFrame(linhas)
    if not df.empty:
        df = df.sort_values(["ts_utc", "chave"]).reset_index(drop=True)
        for c in ("pessoas", "latencia_ms", "unidade_indicada_id"):
            if c in df.columns:
                df[c] = df[c].astype("Int64")
    return df, duplicados


# ------------------------------------------------------------------ gold

def _vazio(colunas):
    return pd.DataFrame(columns=colunas)


def gold_espera(iot: pd.DataFrame, unidades: pd.DataFrame) -> pd.DataFrame:
    cols = ["data", "hora", "regiao", "espera_media_min", "espera_p90_min", "amostras"]
    f = iot[iot["tipo"] == "fila"] if not iot.empty else iot
    if f.empty:
        return _vazio(cols)
    f = f.merge(unidades[["cnes", "regiao"]], left_on="unidade_id", right_on="cnes")
    f = f.assign(espera=f["pessoas"].astype(float) * MIN_POR_PESSOA)
    g = f.groupby(["data", "hora", "regiao"]).agg(espera_media_min=("espera", "mean"),
                                                   espera_p90_min=("espera", an.p90),
                                                   amostras=("espera", "size")).reset_index()
    g["espera_media_min"] = g["espera_media_min"].round(2)
    g["espera_p90_min"] = g["espera_p90_min"].round(2)
    return g[cols].sort_values(["data", "hora", "regiao"]).reset_index(drop=True)


NOMES_ESP = {"225125": "Clínico geral", "225130": "Médico de família e comunidade", "225124": "Pediatria",
             "225250": "Ginecologia e obstetrícia", "225270": "Ortopedia e traumatologia", "225120": "Cardiologia",
             "225265": "Oftalmologia", "225275": "Otorrinolaringologia", "225135": "Dermatologia",
             "225133": "Psiquiatria", "225285": "Urologia", "225112": "Neurologia"}


def gold_cobertura(iot: pd.DataFrame, unidades: pd.DataFrame, especialidades: pd.DataFrame) -> pd.DataFrame:
    """Uma unidade conta como 'coberta' no dia se o profissional estava presente em >= 50% das amostras (07h-18h)."""
    cols = ["data", "regiao", "cbo", "especialidade", "unidades_com_servico", "unidades_presentes", "cobertura_pct"]
    p = iot[(iot["tipo"] == "presenca") & (iot["hora"].isin(HORAS_OPERACAO))] if not iot.empty else iot
    if p.empty:
        return _vazio(cols)
    por_unidade = p.assign(presente=p["presente"].astype(bool)).groupby(["data", "unidade_id", "cbo"])["presente"].mean().reset_index()
    por_unidade["coberta"] = por_unidade["presente"] >= 0.5
    por_unidade = por_unidade.merge(unidades[["cnes", "regiao"]], left_on="unidade_id", right_on="cnes")
    presentes = por_unidade.groupby(["data", "regiao", "cbo"])["coberta"].sum().reset_index(name="unidades_presentes")
    com_servico = (especialidades.merge(unidades[["cnes", "regiao"]], on="cnes")
                   .groupby(["regiao", "cbo"]).size().reset_index(name="unidades_com_servico"))
    g = presentes.merge(com_servico, on=["regiao", "cbo"])
    g["cobertura_pct"] = [an.cobertura(a, b) for a, b in zip(g["unidades_presentes"], g["unidades_com_servico"])]
    g["especialidade"] = g["cbo"].map(NOMES_ESP)
    g["unidades_presentes"] = g["unidades_presentes"].astype(int)
    return g[cols].sort_values(["data", "regiao", "cbo"]).reset_index(drop=True)


def _rotulo(servico, cbo, especialidade):
    if servico == "documento":
        return "Documentos"
    if servico == "assistencia_social":
        return "Assistência social"
    return especialidade if isinstance(especialidade, str) and especialidade else "Emergência (sem especialidade)"


def gold_demanda(tri: pd.DataFrame) -> pd.DataFrame:
    cols = ["data", "regiao", "servico", "cbo", "especialidade", "urgencia", "triagens"]
    if tri.empty:
        return _vazio(cols)
    t = tri.assign(cbo=tri["cbo"].fillna(""),
                   especialidade=[_rotulo(s, c, e) for s, c, e in zip(tri["servico"], tri["cbo"], tri["especialidade"])])
    g = t.groupby(["data", "regiao", "servico", "cbo", "especialidade", "urgencia"]).size().reset_index(name="triagens")
    return g[cols].sort_values(["data", "regiao", "servico", "cbo", "urgencia"]).reset_index(drop=True)


def gold_impacto(tri: pd.DataFrame, custo_km: float) -> pd.DataFrame:
    cols = ["data", "regiao", "cbo", "especialidade", "urgencia", "triagens_com_ranking", "mais_proxima_sem_profissional",
            "km_evitados", "custo_evitado_reais", "km_medio_indicada", "km_medio_mais_proxima"]
    t = tri[(tri["servico"] == "saude") & tri["unidade_indicada_id"].notna()] if not tri.empty else tri
    if t.empty:
        return _vazio(cols)
    t = t.assign(
        cbo=t["cbo"].fillna(""),
        sem=(t["mais_proxima_tinha"] == False).astype(int),  # noqa: E712 (None/NaN não conta)
        km=[an.km_evitados(k, bool(x)) for k, x in zip(t["km_mais_proxima"], t["mais_proxima_tinha"])])
    g = t.groupby(["data", "regiao", "cbo", "especialidade", "urgencia"]).agg(
        triagens_com_ranking=("triagem_id", "size"), mais_proxima_sem_profissional=("sem", "sum"),
        km_evitados=("km", "sum"), km_medio_indicada=("km_indicada", "mean"),
        km_medio_mais_proxima=("km_mais_proxima", "mean")).reset_index()
    g["km_evitados"] = g["km_evitados"].round(2)
    g["custo_evitado_reais"] = [an.custo_evitado(k, custo_km) for k in g["km_evitados"]]
    g["km_medio_indicada"] = g["km_medio_indicada"].round(2)
    g["km_medio_mais_proxima"] = g["km_medio_mais_proxima"].round(2)
    return g[cols].sort_values(["data", "regiao", "cbo", "urgencia"]).reset_index(drop=True)


def gold_conectividade(iot: pd.DataFrame, unidades: pd.DataFrame) -> pd.DataFrame:
    cols = ["data", "unidade_id", "unidade", "tipo_unidade", "regiao", "horas_com_heartbeat", "disponibilidade_pct", "latencia_p90_ms"]
    h = iot[(iot["tipo"] == "heartbeat") & (iot["hora"].isin(HORAS_OPERACAO))] if not iot.empty else iot
    if h.empty:
        return _vazio(cols)
    g = h.groupby(["data", "unidade_id"]).agg(horas_com_heartbeat=("hora", "nunique"),
                                               latencia_p90_ms=("latencia_ms", an.p90)).reset_index()
    g["disponibilidade_pct"] = (100.0 * g["horas_com_heartbeat"] / len(HORAS_OPERACAO)).round(2)
    g["latencia_p90_ms"] = g["latencia_p90_ms"].round(1)
    g = g.merge(unidades[["cnes", "nome", "tipo", "regiao"]], left_on="unidade_id", right_on="cnes")
    g = g.rename(columns={"nome": "unidade", "tipo": "tipo_unidade"})
    return g[cols].sort_values(["data", "unidade_id"]).reset_index(drop=True)


def gold_pressao(tri: pd.DataFrame, ag: pd.DataFrame, unidades: pd.DataFrame, especialidades: pd.DataFrame) -> pd.DataFrame:
    cols = ["data", "unidade_id", "unidade", "regiao", "demanda", "capacidade", "indice_pressao"]
    datas = sorted((set(tri["data"]) if not tri.empty else set()) | (set(ag["data"]) if not ag.empty else set()))
    if not datas:
        return _vazio(cols)
    ind = (tri[tri["unidade_indicada_id"].notna()].groupby(["data", "unidade_indicada_id"]).size().rename("n").reset_index()
           .rename(columns={"unidade_indicada_id": "unidade_id"}) if not tri.empty else _vazio(["data", "unidade_id", "n"]))
    criados = (ag[ag["evento"] == "criado"].groupby(["data", "unidade_id"]).size().rename("n").reset_index()
               if not ag.empty else _vazio(["data", "unidade_id", "n"]))
    demanda = pd.concat([ind, criados]).groupby(["data", "unidade_id"])["n"].sum().reset_index() if (len(ind) or len(criados)) else _vazio(["data", "unidade_id", "n"])
    demanda["unidade_id"] = demanda["unidade_id"].astype("int64")
    base = pd.MultiIndex.from_product([datas, unidades["cnes"]], names=["data", "unidade_id"]).to_frame(index=False)
    g = base.merge(demanda, on=["data", "unidade_id"], how="left").fillna({"n": 0})
    cap = especialidades.groupby("cnes")["qtd_profissionais"].sum().mul(an.CAPACIDADE_SLOTS_DIA).rename("capacidade").reset_index()
    g = g.merge(cap, left_on="unidade_id", right_on="cnes", how="left").fillna({"capacidade": 0})
    g = g.merge(unidades[["cnes", "nome", "regiao"]], left_on="unidade_id", right_on="cnes")
    g = g.rename(columns={"nome": "unidade", "n": "demanda"})
    g["demanda"] = g["demanda"].astype(int)
    g["capacidade"] = g["capacidade"].astype(int)
    g["indice_pressao"] = [an.indice_pressao(d, c) for d, c in zip(g["demanda"], g["capacidade"])]
    return g[cols].sort_values(["data", "unidade_id"]).reset_index(drop=True)


# ------------------------------------------------------------------ execução

def gravar_csv(df: pd.DataFrame, caminho: Path):
    caminho.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(caminho, index=False, encoding="utf-8", lineterminator="\n")


def executar(bronze: Path, silver: Path, gold: Path, dim: Path, custo_km: float = an.CUSTO_KM_PADRAO) -> dict:
    if custo_km < 0:
        raise ValueError("custo por km não pode ser negativo")
    unidades, especialidades = carregar_dim(dim)
    ids = set(int(x) for x in unidades["cnes"])
    rejeitados: list[dict] = []
    manifest: dict = {"custo_km": custo_km, "datasets": {}}
    tabelas = {}
    for ds in ("iot", "triagens", "agendamentos"):
        regs, rej = ler_bronze(bronze, ds)
        rejeitados.extend(rej)
        df, dup = construir_silver(regs, rejeitados, ds, ids)
        tabelas[ds] = df
        manifest["datasets"][ds] = {"lidos": len(regs) + len(rej), "aceitos": len(df), "duplicados": dup,
                                    "rejeitados": sum(1 for r in rejeitados if r["dataset"] == ds)}
        gravar_csv(df, silver / {"iot": "iot_eventos.csv", "triagens": "triagens.csv", "agendamentos": "agendamentos.csv"}[ds])
    rej_df = pd.DataFrame(rejeitados, columns=["dataset", "arquivo", "linha", "motivo"])
    gravar_csv(rej_df, silver / "rejeitados.csv")

    iot, tri, ag = tabelas["iot"], tabelas["triagens"], tabelas["agendamentos"]
    # a coluna 'cbo' lida do CSV de volta é texto; aqui ainda é str/None
    saidas = {
        "g_espera.csv": gold_espera(iot, unidades),
        "g_cobertura_especialidade.csv": gold_cobertura(iot, unidades, especialidades),
        "g_demanda.csv": gold_demanda(tri),
        "g_impacto.csv": gold_impacto(tri, custo_km),
        "g_conectividade.csv": gold_conectividade(iot, unidades),
        "g_pressao.csv": gold_pressao(tri, ag, unidades, especialidades),
    }
    manifest["gold"] = {}
    for nome, df in saidas.items():
        gravar_csv(df, gold / nome)
        manifest["gold"][nome] = len(df)
    manifest["rejeitados_total"] = len(rejeitados)
    (silver / "_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    return manifest


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="ETL bronze -> silver -> gold do PertoDeMim")
    p.add_argument("--bronze", default=str(AQUI / "bronze"))
    p.add_argument("--silver", default=str(AQUI / "silver"))
    p.add_argument("--gold", default=str(AQUI / "gold"))
    p.add_argument("--dim", default=str(AQUI / "data"))
    p.add_argument("--custo-km", type=float, default=an.CUSTO_KM_PADRAO, help="premissa de R$ por km (padrão 1,20)")
    a = p.parse_args(argv)
    if not Path(a.bronze).exists():
        print(f"erro: pasta bronze não existe: {a.bronze}. Rode gerar_dados_exemplo.py ou suba a API.", file=sys.stderr)
        return 2
    m = executar(Path(a.bronze), Path(a.silver), Path(a.gold), Path(a.dim), a.custo_km)
    for ds, v in m["datasets"].items():
        print(f"{ds}: lidos {v['lidos']} | aceitos {v['aceitos']} | duplicados {v['duplicados']} | rejeitados {v['rejeitados']}")
    print("gold:", ", ".join(f"{k}={n}" for k, n in m["gold"].items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
