"""Camada de dados do dashboard (pura, testável sem Streamlit).

Lê as tabelas gold e calcula os 5 indicadores com filtros de região, especialidade, período e urgência.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from pathlib import Path

import pandas as pd

import analitica as an

AQUI = Path(__file__).resolve().parent
TABELAS = {
    "espera": "g_espera.csv",
    "cobertura": "g_cobertura_especialidade.csv",
    "demanda": "g_demanda.csv",
    "impacto": "g_impacto.csv",
    "conectividade": "g_conectividade.csv",
    "pressao": "g_pressao.csv",
}
URGENCIAS = ["vermelho", "laranja", "amarelo", "verde", "azul"]


class GoldIndisponivel(RuntimeError):
    """Pasta gold ausente ou incompleta."""


@dataclass(frozen=True)
class Filtros:
    regioes: tuple[str, ...] | None = None
    cbos: tuple[str, ...] | None = None
    inicio: date | None = None
    fim: date | None = None
    urgencias: tuple[str, ...] | None = None


def carregar_gold(pasta: Path | str) -> dict[str, pd.DataFrame]:
    pasta = Path(pasta)
    faltando = [n for n in TABELAS.values() if not (pasta / n).exists()]
    if faltando:
        raise GoldIndisponivel(f"Faltam tabelas gold em {pasta}: {', '.join(faltando)}. Rode o etl.py.")
    out = {}
    for chave, nome in TABELAS.items():
        df = pd.read_csv(pasta / nome, dtype={"cbo": "str"}, keep_default_na=False, na_values=[""])
        if "data" in df.columns:
            df["data"] = pd.to_datetime(df["data"]).dt.date
        if "cbo" in df.columns:
            df["cbo"] = df["cbo"].fillna("")
        out[chave] = df
    return out


def filtrar(df: pd.DataFrame, f: Filtros) -> pd.DataFrame:
    """Aplica só os filtros cujas colunas existem na tabela (ex.: 'espera' não tem urgência)."""
    if df.empty:
        return df
    m = pd.Series(True, index=df.index)
    if f.regioes and "regiao" in df.columns:
        m &= df["regiao"].isin(f.regioes)
    if f.cbos and "cbo" in df.columns:
        m &= df["cbo"].isin(f.cbos)
    if f.urgencias and "urgencia" in df.columns:
        m &= df["urgencia"].isin(f.urgencias)
    if f.inicio is not None and "data" in df.columns:
        m &= df["data"] >= f.inicio
    if f.fim is not None and "data" in df.columns:
        m &= df["data"] <= f.fim
    return df[m].reset_index(drop=True)


def opcoes(gold: dict[str, pd.DataFrame]) -> dict:
    cob = gold["cobertura"]
    dem = gold["demanda"]
    datas = sorted(set(dem["data"]) | set(gold["espera"]["data"]))
    esp = (cob[["cbo", "especialidade"]].drop_duplicates().sort_values("especialidade")
           if not cob.empty else pd.DataFrame(columns=["cbo", "especialidade"]))
    return {
        "regioes": sorted(set(dem["regiao"]) | set(gold["espera"]["regiao"])),
        "especialidades": dict(zip(esp["cbo"], esp["especialidade"])),
        "urgencias": [u for u in URGENCIAS if u in set(dem["urgencia"])] or URGENCIAS,
        "inicio": datas[0] if datas else None,
        "fim": datas[-1] if datas else None,
    }


# ---------------------------------------------------------------- indicadores

def ind_espera(g: pd.DataFrame, f: Filtros) -> dict:
    """1. Espera estimada (fila × 5 min): média e p90 por hora e por região."""
    d = filtrar(g, f)
    if d.empty:
        return {"vazio": True, "media_min": None, "p90_min": None, "por_hora": d, "por_regiao": d}
    pesos = d["amostras"]
    media = float((d["espera_media_min"] * pesos).sum() / pesos.sum())
    por_hora = d.groupby("hora").apply(lambda x: pd.Series({
        "espera_media_min": (x["espera_media_min"] * x["amostras"]).sum() / x["amostras"].sum(),
        "espera_p90_min": x["espera_p90_min"].max()}), include_groups=False).reset_index()
    por_regiao = d.groupby("regiao").apply(lambda x: pd.Series({
        "espera_media_min": (x["espera_media_min"] * x["amostras"]).sum() / x["amostras"].sum(),
        "espera_p90_min": x["espera_p90_min"].max()}), include_groups=False).reset_index()
    return {"vazio": False, "media_min": round(media, 1), "p90_min": float(d["espera_p90_min"].max()),
            "por_hora": por_hora.round(2), "por_regiao": por_regiao.round(2)}


def ind_cobertura(g: pd.DataFrame, f: Filtros) -> dict:
    """2. Cobertura de especialidade: % das unidades que têm o profissional presente (≥50% das amostras do dia)."""
    d = filtrar(g, f)
    if d.empty:
        return {"vazio": True, "cobertura_pct": None, "por_especialidade": d, "por_regiao": d, "serie": d}
    def agrega(cols):
        x = d.groupby(cols, as_index=False)[["unidades_presentes", "unidades_com_servico"]].sum()
        x["cobertura_pct"] = [an.cobertura(a, b) for a, b in zip(x["unidades_presentes"], x["unidades_com_servico"])]
        return x
    total = an.cobertura(d["unidades_presentes"].sum(), d["unidades_com_servico"].sum())
    return {"vazio": False, "cobertura_pct": total, "por_especialidade": agrega(["especialidade"]).sort_values("cobertura_pct"),
            "por_regiao": agrega(["regiao"]), "serie": agrega(["data"])}


def ind_demanda(g: pd.DataFrame, f: Filtros, janela: int = 7) -> dict:
    """3. Demanda: triagens por dia (com média móvel), por especialidade e urgência, variação semanal."""
    d = filtrar(g, f)
    if d.empty:
        return {"vazio": True, "total": 0, "serie": d, "por_especialidade": d, "por_urgencia": d, "semanal": d,
                "variacao_ultima_semana_pct": None}
    serie = d.groupby("data", as_index=False)["triagens"].sum().sort_values("data")
    serie["media_movel"] = an.media_movel(serie["triagens"], janela).round(2)
    semanal = an.variacao_semanal(serie, "data", "triagens")
    # só semanas completas (7 dias): comparar semana parcial com inteira dá variação enganosa
    comp = semanal[semanal["completa"]].reset_index(drop=True)
    ultima = None
    if len(comp) >= 2 and comp["total"].iloc[-2] > 0:
        ultima = (comp["total"].iloc[-1] - comp["total"].iloc[-2]) / comp["total"].iloc[-2] * 100
    return {"vazio": False, "total": int(d["triagens"].sum()), "serie": serie,
            "por_especialidade": d.groupby("especialidade", as_index=False)["triagens"].sum().sort_values("triagens", ascending=False),
            "por_urgencia": d.groupby("urgencia", as_index=False)["triagens"].sum()
            .assign(ordem=lambda x: x["urgencia"].map({u: i for i, u in enumerate(URGENCIAS)})).sort_values("ordem").drop(columns="ordem"),
            "semanal": semanal, "variacao_ultima_semana_pct": None if pd.isna(ultima) else float(ultima)}


def ind_impacto(g: pd.DataFrame, f: Filtros) -> dict:
    """4. Impacto (simulado): idas a unidades sem o profissional evitadas, km e R$ evitados."""
    d = filtrar(g, f)
    if d.empty:
        return {"vazio": True, "triagens": 0, "evitadas": 0, "pct_evitadas": None, "km_evitados": 0.0, "custo_evitado": 0.0,
                "por_regiao": d, "serie": d}
    tri, evit = int(d["triagens_com_ranking"].sum()), int(d["mais_proxima_sem_profissional"].sum())
    por_regiao = d.groupby("regiao", as_index=False)[["triagens_com_ranking", "mais_proxima_sem_profissional", "km_evitados",
                                                      "custo_evitado_reais"]].sum()
    serie = d.groupby("data", as_index=False)[["km_evitados", "custo_evitado_reais"]].sum()
    return {"vazio": False, "triagens": tri, "evitadas": evit, "pct_evitadas": round(100.0 * evit / tri, 1) if tri else None,
            "km_evitados": round(float(d["km_evitados"].sum()), 1), "custo_evitado": round(float(d["custo_evitado_reais"].sum()), 2),
            "por_regiao": por_regiao, "serie": serie}


def ind_disponibilidade(conect: pd.DataFrame, pressao: pd.DataFrame, f: Filtros) -> dict:
    """5. Disponibilidade dos sensores (uptime) e índice de pressão das unidades."""
    c, p = filtrar(conect, f), filtrar(pressao, f)
    out = {"vazio": c.empty and p.empty, "disponibilidade_pct": None, "latencia_p90_ms": None, "por_regiao": c, "pressao_top": p,
           "unidades_criticas": 0, "pressao_max": None, "pressao_max_unidade": None}
    if not c.empty:
        out["disponibilidade_pct"] = round(float(c["disponibilidade_pct"].mean()), 1)
        out["latencia_p90_ms"] = float(c["latencia_p90_ms"].max())
        out["por_regiao"] = c.groupby("regiao", as_index=False).agg(disponibilidade_pct=("disponibilidade_pct", "mean"),
                                                                      latencia_p90_ms=("latencia_p90_ms", "max")).round(1)
    if not p.empty:
        agg = p.groupby(["unidade_id", "unidade", "regiao"], as_index=False).agg(demanda=("demanda", "sum"), capacidade=("capacidade", "sum"))
        agg["indice_pressao"] = [an.indice_pressao(a, b) for a, b in zip(agg["demanda"], agg["capacidade"])]
        agg = agg.sort_values("indice_pressao", ascending=False)
        out["pressao_top"] = agg.head(10).reset_index(drop=True)
        out["unidades_criticas"] = int((agg["indice_pressao"] > 0.5).sum())
        validos = agg.dropna(subset=["indice_pressao"])
        if not validos.empty:
            topo = validos.iloc[0]
            out["pressao_max"] = float(topo["indice_pressao"])
            out["pressao_max_unidade"] = str(topo["unidade"])
    return out


def origem_dos_dados(silver: Path | str) -> dict[str, int]:
    """Quantos registros de triagem vieram de cada origem (exemplo x api): para mostrar honestamente no dashboard."""
    arq = Path(silver) / "triagens.csv"
    if not arq.exists():
        return {}
    df = pd.read_csv(arq, usecols=["origem"])
    return {k: int(v) for k, v in df["origem"].value_counts().items()}
