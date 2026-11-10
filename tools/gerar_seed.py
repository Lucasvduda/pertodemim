"""Gera os arquivos de seed (unidades, especialidades, serviços) de forma determinística.

Entrada : tools/cnes_raw.json  (amostra real do CNES, coletada pela API de dados abertos)
Saída   : area3_mvp/api/src/main/resources/seed/*.csv  e  area2_dados/data/dim_*.csv

As unidades (nome, tipo, bairro, latitude, longitude) vêm do CNES. Os profissionais por
unidade e os serviços de documento/assistência social são SINTÉTICOS (semente fixa), porque
o projeto não carrega nomes nem CPF de profissionais (LGPD) e não integra sistemas do SUS.
"""
import csv
import json
import math
import random
import shutil
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
CENTRO = (-23.5505, -46.6333)  # Praça da Sé
SEMENTE = 2026

TIPOS = {2: "UBS", 5: "HOSPITAL", 73: "UPA", 4: "POLICLINICA", 20: "PRONTO_SOCORRO"}

ESPECIALIDADES = {
    "225125": "Clínico geral",
    "225130": "Médico de família e comunidade",
    "225124": "Pediatria",
    "225250": "Ginecologia e obstetrícia",
    "225270": "Ortopedia e traumatologia",
    "225120": "Cardiologia",
    "225265": "Oftalmologia",
    "225275": "Otorrinolaringologia",
    "225135": "Dermatologia",
    "225133": "Psiquiatria",
    "225285": "Urologia",
    "225112": "Neurologia",
}

# (probabilidade de existir, mínimo, máximo de profissionais) por tipo de unidade
PERFIL = {
    "UBS": {"225125": (1, 1, 3), "225130": (0.8, 1, 2), "225124": (0.6, 1, 1), "225250": (0.6, 1, 1),
            "225135": (0.2, 1, 1), "225133": (0.25, 1, 1), "225270": (0.05, 1, 1)},
    "HOSPITAL": {"225125": (1, 2, 5), "225124": (0.9, 1, 3), "225250": (0.8, 1, 3), "225270": (0.5, 1, 2),
                 "225120": (0.6, 1, 2), "225112": (0.4, 1, 1), "225265": (0.3, 1, 1), "225275": (0.3, 1, 1),
                 "225285": (0.4, 1, 1), "225133": (0.3, 1, 1), "225135": (0.2, 1, 1)},
    "UPA": {"225125": (1, 2, 4), "225124": (0.9, 1, 2), "225270": (0.25, 1, 1), "225120": (0.2, 1, 1)},
    "PRONTO_SOCORRO": {"225125": (1, 2, 4), "225124": (0.7, 1, 2), "225270": (0.25, 1, 1), "225120": (0.2, 1, 1)},
    "POLICLINICA": {"225125": (1, 1, 2), "225135": (0.6, 1, 1), "225265": (0.6, 1, 1), "225275": (0.5, 1, 1),
                    "225285": (0.5, 1, 1), "225270": (0.4, 1, 1), "225120": (0.4, 1, 1), "225133": (0.4, 1, 1)},
}


def haversine_km(lat1, lon1, lat2, lon2):
    r = 6371.0088
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def regiao(lat, lon):
    if haversine_km(lat, lon, *CENTRO) < 3:
        return "Centro"
    dlat, dlon = lat - CENTRO[0], lon - CENTRO[1]
    if abs(dlat) >= abs(dlon):
        return "Norte" if dlat > 0 else "Sul"
    return "Leste" if dlon > 0 else "Oeste"


def turno(texto):
    t = (texto or "").upper()
    if "NOITE" in t:
        return "24h/integral" if "MANHA" in t and "TARDE" in t else "noite"
    if "MANHA" in t and "TARDE" in t:
        return "manhã e tarde"
    return "diurno"


def main():
    bruto = json.loads((RAIZ / "tools" / "cnes_raw.json").read_text(encoding="utf-8"))
    vistos, unidades = set(), []
    for x in bruto:
        cnes = str(x["codigo_cnes"])
        if cnes in vistos or x["codigo_tipo_unidade"] not in TIPOS:
            continue
        vistos.add(cnes)
        lat = round(float(x["latitude_estabelecimento_decimo_grau"]), 6)
        lon = round(float(x["longitude_estabelecimento_decimo_grau"]), 6)
        nome = (x.get("nome_fantasia") or x.get("nome_razao_social") or "").strip().title()
        unidades.append({
            "cnes": cnes, "nome": nome, "tipo": TIPOS[x["codigo_tipo_unidade"]],
            "bairro": (x.get("bairro_estabelecimento") or "").strip().title(),
            "regiao": regiao(lat, lon), "lat": lat, "lon": lon, "atende_sus": "true",
            "turno": turno(x.get("descricao_turno_atendimento")),
        })
    unidades.sort(key=lambda u: int(u["cnes"]))

    rnd = random.Random(SEMENTE)
    espec, serv = [], []
    for u in unidades:
        for cbo, (prob, mn, mx) in PERFIL[u["tipo"]].items():
            if rnd.random() <= prob:
                espec.append({"cnes": u["cnes"], "cbo": cbo, "especialidade": ESPECIALIDADES[cbo],
                              "qtd_profissionais": rnd.randint(mn, mx), "agenda_aberta": "false"})
        if u["tipo"] in ("UBS", "POLICLINICA") and rnd.random() < 0.45:
            serv.append({"cnes": u["cnes"], "tipo": "DOCUMENTO"})
        if u["tipo"] == "UBS" and rnd.random() < 0.4:
            serv.append({"cnes": u["cnes"], "tipo": "ASSISTENCIA_SOCIAL"})
    # garante ao menos uma unidade de cada serviço não médico
    for tipo in ("DOCUMENTO", "ASSISTENCIA_SOCIAL"):
        if not any(s["tipo"] == tipo for s in serv):
            serv.append({"cnes": unidades[0]["cnes"], "tipo": tipo})

    destinos = [RAIZ / "area3_mvp" / "api" / "src" / "main" / "resources" / "seed", RAIZ / "area2_dados" / "data"]
    for d in destinos:
        d.mkdir(parents=True, exist_ok=True)
    arquivos = {
        "unidades.csv": (unidades, ["cnes", "nome", "tipo", "bairro", "regiao", "lat", "lon", "atende_sus", "turno"]),
        "especialidades.csv": (espec, ["cnes", "cbo", "especialidade", "qtd_profissionais", "agenda_aberta"]),
        "servicos.csv": (serv, ["cnes", "tipo"]),
    }
    for nome, (linhas, cols) in arquivos.items():
        base = destinos[0] / nome
        with base.open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=cols, lineterminator="\n")
            w.writeheader()
            w.writerows(linhas)
        shutil.copyfile(base, destinos[1] / f"dim_{nome}")
    ort = sum(1 for e in espec if e["cbo"] == "225270")
    print(f"unidades={len(unidades)} especialidades={len(espec)} servicos={len(serv)} com_ortopedia={ort}")


if __name__ == "__main__":
    main()
