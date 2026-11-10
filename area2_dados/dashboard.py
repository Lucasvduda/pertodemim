"""Dashboard do PertoDeMim (Streamlit + Plotly): 5 indicadores com filtros.

Rodar:  streamlit run dashboard.py
Lê as tabelas em gold/ (gere com etl.py). Variáveis: PDM_GOLD, PDM_SILVER.
"""
from __future__ import annotations

import os
from pathlib import Path

import plotly.express as px
import streamlit as st

import dados_dashboard as dd

AQUI = Path(__file__).resolve().parent
GOLD = Path(os.getenv("PDM_GOLD", AQUI / "gold"))
SILVER = Path(os.getenv("PDM_SILVER", AQUI / "silver"))

st.set_page_config(page_title="PertoDeMim · Indicadores", page_icon="●", layout="wide")
st.title("PertoDeMim · Indicadores")
st.caption("Dados de unidades: CNES (amostra). Fila, presença de profissional, conexão e demanda são simulados; "
           "os números de impacto são resultado de simulação com premissas declaradas, não medição real.")


@st.cache_data(show_spinner=False)
def carregar(pasta: str):
    return dd.carregar_gold(pasta)


try:
    gold = carregar(str(GOLD))
except dd.GoldIndisponivel as e:
    st.error(str(e))
    st.stop()

op = dd.opcoes(gold)

with st.sidebar:
    st.header("Filtros")
    regioes = st.multiselect("Região", op["regioes"], default=[], placeholder="Todas")
    esp_sel = st.multiselect("Especialidade", list(op["especialidades"].keys()), default=[],
                             format_func=lambda c: op["especialidades"][c], placeholder="Todas")
    periodo = st.date_input("Período", value=(op["inicio"], op["fim"]), min_value=op["inicio"], max_value=op["fim"])
    urg = st.multiselect("Urgência", op["urgencias"], default=[], placeholder="Todas")
    st.caption("A urgência filtra demanda e impacto. Espera, cobertura e disponibilidade não têm essa dimensão.")
    origem = dd.origem_dos_dados(SILVER)
    if origem:
        total = sum(origem.values())
        st.markdown("**Origem das triagens**")
        for k, v in origem.items():
            st.write(f"{k}: {v} ({100 * v / total:.0f}%)")

ini, fim = (periodo if isinstance(periodo, tuple) and len(periodo) == 2 else (op["inicio"], op["fim"]))
f = dd.Filtros(tuple(regioes) or None, tuple(esp_sel) or None, ini, fim, tuple(urg) or None)

t1, t2, t3, t4, t5 = st.tabs(["1. Espera", "2. Cobertura", "3. Demanda", "4. Impacto", "5. Disponibilidade"])

with t1:
    r = dd.ind_espera(gold["espera"], f)
    if r["vazio"]:
        st.info("Sem dados para os filtros escolhidos.")
    else:
        a, b = st.columns(2)
        a.metric("Espera média estimada", f"{r['media_min']:.0f} min")
        b.metric("Espera p90 (pior 10%)", f"{r['p90_min']:.0f} min")
        st.plotly_chart(px.line(r["por_hora"], x="hora", y=["espera_media_min", "espera_p90_min"], markers=True,
                                labels={"value": "minutos", "hora": "hora do dia", "variable": ""}), width='stretch')
        st.plotly_chart(px.bar(r["por_regiao"], x="regiao", y="espera_media_min", labels={"espera_media_min": "espera média (min)"}),
                        width='stretch')
    st.caption("Fórmula: espera = pessoas na fila × 5 min. p90 = percentil 90.")

with t2:
    r = dd.ind_cobertura(gold["cobertura"], f)
    if r["vazio"]:
        st.info("Sem dados para os filtros escolhidos.")
    else:
        st.metric("Cobertura média", f"{r['cobertura_pct']:.0f}%")
        st.plotly_chart(px.bar(r["por_especialidade"], x="cobertura_pct", y="especialidade", orientation="h",
                               labels={"cobertura_pct": "% das unidades com o profissional"}), width='stretch')
        st.plotly_chart(px.line(r["serie"], x="data", y="cobertura_pct", markers=True), width='stretch')
    st.caption("Cobertura = unidades com o profissional presente ÷ unidades que oferecem a especialidade. "
               "Uma unidade conta como coberta no dia se o profissional estava presente em pelo menos 50% das amostras entre 07h e 18h.")

with t3:
    r = dd.ind_demanda(gold["demanda"], f)
    if r["vazio"]:
        st.info("Sem dados para os filtros escolhidos.")
    else:
        a, b = st.columns(2)
        a.metric("Triagens no período", r["total"])
        v = r["variacao_ultima_semana_pct"]
        b.metric("Variação da última semana", "sem base" if v is None else f"{v:+.1f}%")
        st.plotly_chart(px.line(r["serie"], x="data", y=["triagens", "media_movel"], markers=True,
                                labels={"value": "triagens", "variable": ""}), width='stretch')
        c, d = st.columns(2)
        c.plotly_chart(px.bar(r["por_especialidade"], x="triagens", y="especialidade", orientation="h"), width='stretch')
        d.plotly_chart(px.pie(r["por_urgencia"], names="urgencia", values="triagens"), width='stretch')
    st.caption("Média móvel de 7 dias. Variação semanal = (semana − semana anterior) ÷ semana anterior, usando só semanas completas (7 dias).")

with t4:
    r = dd.ind_impacto(gold["impacto"], f)
    if r["vazio"]:
        st.info("Sem dados para os filtros escolhidos.")
    else:
        a, b, c = st.columns(3)
        a.metric("Idas evitadas a unidade sem o profissional", f"{r['evitadas']} de {r['triagens']}",
                 f"{r['pct_evitadas']:.0f}%" if r["pct_evitadas"] is not None else None)
        b.metric("Km evitados", f"{r['km_evitados']:.0f} km")
        c.metric("Custo evitado (premissa)", f"R$ {r['custo_evitado']:.2f}")
        st.plotly_chart(px.bar(r["por_regiao"], x="regiao", y="km_evitados"), width='stretch')
        st.plotly_chart(px.line(r["serie"], x="data", y="km_evitados", markers=True), width='stretch')
    st.caption("Km evitados = 2 × distância até a unidade mais próxima, quando ela NÃO tinha o profissional. "
               "R$ = km × custo por km (premissa editável no etl.py, padrão R$ 1,20). Resultado de simulação, não medição.")

with t5:
    r = dd.ind_disponibilidade(gold["conectividade"], gold["pressao"], f)
    if r["vazio"]:
        st.info("Sem dados para os filtros escolhidos.")
    else:
        a, b, c, d = st.columns(4)
        a.metric("Disponibilidade dos sensores", "sem dados" if r["disponibilidade_pct"] is None else f"{r['disponibilidade_pct']:.0f}%")
        b.metric("Latência p90 (pior unidade)", "sem dados" if r["latencia_p90_ms"] is None else f"{r['latencia_p90_ms']:.0f} ms")
        c.metric("Unidades com pressão > 0,5", r["unidades_criticas"])
        d.metric("Maior índice de pressão", "sem dados" if r["pressao_max"] is None else f"{r['pressao_max']:.2f}",
                 help=r["pressao_max_unidade"])
        if len(r["por_regiao"]):
            st.plotly_chart(px.bar(r["por_regiao"], x="regiao", y="disponibilidade_pct"), width='stretch')
        if len(r["pressao_top"]):
            st.subheader("Unidades com maior índice de pressão")
            st.dataframe(r["pressao_top"], hide_index=True, width='stretch')
    st.caption("Disponibilidade = horas (de 12, entre 07h e 18h) com heartbeat ÷ 12. Pressão = (indicações + agendamentos) ÷ (profissionais × 18 horários).")
