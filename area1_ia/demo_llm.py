"""Demonstração com LLM real: manda poucas frases à API e grava a resposta completa (entrada e saída).

    python demo_llm.py --api http://localhost:8080 --rotulo gemini-3.1-flash-lite

A API precisa estar no ar com LLM_PROVIDER diferente de "rules" (a chave fica só no ambiente da API).
Usa poucas chamadas de propósito (provedores gratuitos têm cota pequena). Grava resultados/demo_llm_real.json.
Falha (exit 3) se nenhuma resposta veio do LLM, para não passar o resultado das regras como se fosse do LLM.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

from avaliar_triagem import Cliente

FRASES = [
    "caí da moto ontem e meu tornozelo está muito inchado, não consigo apoiar o pé",
    "meu filho de 3 anos está com febre e tosse há dois dias",
    "perdi o emprego, estou sem renda e sem comida em casa",
]


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--api", default="http://localhost:8080")
    p.add_argument("--rotulo", required=True, help="nome do provedor/modelo, só para registrar")
    p.add_argument("--saida", default=str(Path(__file__).parent / "resultados" / "demo_llm_real.json"))
    a = p.parse_args(argv)
    c = Cliente(a.api, intervalo=8.0, timeout=60.0)
    c.autenticar()
    itens = []
    for f in FRASES:
        r = c.triar(f)
        itens.append({"entrada": f, "saida": r})
        print(f"[{r.get('caminho')}] {r.get('urgencia')} / {r.get('servico')} / {r.get('especialidade')}")
    Path(a.saida).parent.mkdir(parents=True, exist_ok=True)
    Path(a.saida).write_text(json.dumps(
        {"rotulo": a.rotulo, "executado_em": datetime.now().isoformat(timespec="seconds"), "itens": itens},
        ensure_ascii=False, indent=2), encoding="utf-8")
    if not any(str(i["saida"].get("caminho", "")).lower() == "llm" for i in itens):
        print("nenhuma resposta veio do LLM", file=sys.stderr)
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
