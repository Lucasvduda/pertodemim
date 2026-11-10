"""Roda TODOS os testes do projeto e grava um relatório de evidência.

    python area4_devops/verificar_tudo.py            # Java (Maven) + Python (4 pastas)
    python area4_devops/verificar_tudo.py --sem-java # só Python

Saída: area4_devops/evidencias/verificacao.md (tabela com testes, falhas e cobertura Jacoco).
Código de saída 0 só se tudo passar. Só usa a biblioteca padrão.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
API = RAIZ / "area3_mvp" / "api"
PASTAS_PY = ["area1_ia", "area2_dados", "area3_mvp/iot_mock", "area4_devops"]


def executar(cmd: list[str], cwd: Path) -> tuple[int, str]:
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def contar_surefire(pasta: Path) -> dict:
    tot = {"testes": 0, "falhas": 0, "erros": 0, "ignorados": 0}
    for arq in pasta.glob("TEST-*.xml"):
        r = ET.parse(arq).getroot()
        tot["testes"] += int(r.get("tests", 0))
        tot["falhas"] += int(r.get("failures", 0))
        tot["erros"] += int(r.get("errors", 0))
        tot["ignorados"] += int(r.get("skipped", 0))
    return tot


def cobertura_jacoco(csv_path: Path) -> dict | None:
    if not csv_path.exists():
        return None
    s = {"INSTRUCTION": [0, 0], "BRANCH": [0, 0], "LINE": [0, 0]}
    with open(csv_path, encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            for k in s:
                s[k][0] += int(r[f"{k}_MISSED"])
                s[k][1] += int(r[f"{k}_COVERED"])
    return {k.lower(): (100.0 * c / (m + c) if m + c else 0.0) for k, (m, c) in s.items()}


def rodar_java() -> dict:
    mvn = shutil.which("mvn") or shutil.which("mvn.cmd")
    cmd = [mvn, "-B", "-q", "verify"] if mvn else [str(API / ("mvnw.cmd" if sys.platform == "win32" else "mvnw")), "-B", "-q", "verify"]
    codigo, saida = executar(cmd, API)
    t = contar_surefire(API / "target" / "surefire-reports")
    cob = cobertura_jacoco(API / "target" / "site" / "jacoco" / "jacoco.csv")
    return {"nome": "API Java (mvn verify)", "ok": codigo == 0 and t["falhas"] == 0 and t["erros"] == 0 and t["testes"] > 0,
            "detalhe": t, "cobertura": cob, "saida": saida}


def rodar_pytest(pasta: str) -> dict:
    codigo, saida = executar([sys.executable, "-m", "pytest", "-q", "-p", "no:cacheprovider"], RAIZ / pasta)
    m = re.search(r"(\d+) passed", saida)
    f = re.search(r"(\d+) failed", saida)
    e = re.search(r"(\d+) error", saida)
    t = {"testes": int(m.group(1)) if m else 0, "falhas": int(f.group(1)) if f else 0, "erros": int(e.group(1)) if e else 0}
    return {"nome": f"Python ({pasta})", "ok": codigo == 0 and t["testes"] > 0, "detalhe": t, "cobertura": None, "saida": saida}


def relatorio(resultados: list[dict]) -> str:
    L = [f"# Verificação completa ({dt.datetime.now().strftime('%d/%m/%Y %H:%M')})", "",
         "| Suíte | Resultado | Testes | Falhas | Erros |", "|---|---|---|---|---|"]
    total = 0
    for r in resultados:
        d = r["detalhe"]
        total += d["testes"]
        L.append(f"| {r['nome']} | {'OK' if r['ok'] else 'FALHOU'} | {d['testes']} | {d['falhas']} | {d['erros']} |")
    L += ["", f"**Total de testes executados: {total}**", ""]
    for r in resultados:
        c = r["cobertura"]
        if c:
            L.append(f"Cobertura Jacoco ({r['nome']}): instruções {c['instruction']:.1f}%, linhas {c['line']:.1f}%, ramos {c['branch']:.1f}%")
    L += ["", "Observação: a cobertura é da API Java. As partes em Python têm testes, mas a cobertura delas não é medida aqui.",
          "Não testado nesta verificação: construção das imagens Docker e chamada real a um provedor de LLM (exigem Docker e chave)."]
    return "\n".join(L) + "\n"


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--sem-java", action="store_true")
    a = p.parse_args(argv)
    resultados = []
    if not a.sem_java:
        print("Rodando Maven verify (pode levar alguns minutos)...", flush=True)
        resultados.append(rodar_java())
    for pasta in PASTAS_PY:
        print(f"Rodando pytest em {pasta}...", flush=True)
        resultados.append(rodar_pytest(pasta))
    texto = relatorio(resultados)
    saida = RAIZ / "area4_devops" / "evidencias"
    saida.mkdir(parents=True, exist_ok=True)
    (saida / "verificacao.md").write_text(texto, encoding="utf-8")
    print(texto)
    for r in resultados:
        if not r["ok"]:
            print(f"----- saída de {r['nome']} -----\n{r['saida'][-3000:]}", file=sys.stderr)
    return 0 if all(r["ok"] for r in resultados) else 1


if __name__ == "__main__":
    sys.exit(main())
