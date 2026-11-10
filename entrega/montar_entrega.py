"""Monta o .zip da entrega (código sem lixo e sem segredos).

    python entrega/montar_entrega.py [--saida entrega/PertoDeMim.zip]

Recusa gerar o .zip se a varredura achar algo parecido com chave de API ou chave privada.
Fica de fora: .git, target/, caches, ambientes virtuais, .env, dados gerados (bronze/silver/gold), buffers e logs.
O PDF e o .txt da entrega (grupo, RM, integrantes, link do pitch) são montados à mão: veja MODELO_entrega.txt.
"""
from __future__ import annotations

import argparse
import fnmatch
import sys
import zipfile
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RAIZ / "area4_devops"))
import segredos  # noqa: E402

PASTAS_FORA = {".git", "target", "__pycache__", ".pytest_cache", ".venv", "venv", "node_modules", ".idea", ".vscode",
               "bronze", "silver", "gold", "buffer"}
PADROES_FORA = ["*.pyc", "*.log", ".env", "*.pem", "*.key", "credenciais*", "*.zip"]


def arquivos_para_o_zip(raiz: Path = RAIZ) -> list[Path]:
    saida = []
    for p in sorted(raiz.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(raiz)
        if any(parte in PASTAS_FORA for parte in rel.parts):
            continue
        if any(fnmatch.fnmatch(p.name, pad) for pad in PADROES_FORA):
            continue
        saida.append(p)
    return saida


def montar(destino: Path, raiz: Path = RAIZ) -> list[Path]:
    achados = segredos.varrer(raiz)
    if achados:
        raise RuntimeError("possível segredo no projeto, zip NÃO gerado: " + "; ".join(f"{a}:{n} ({t})" for a, n, t in achados[:10]))
    arquivos = arquivos_para_o_zip(raiz)
    destino.parent.mkdir(parents=True, exist_ok=True)
    if destino.exists():
        destino.unlink()
    with zipfile.ZipFile(destino, "w", zipfile.ZIP_DEFLATED) as z:
        for p in arquivos:
            z.write(p, Path("pertodemim") / p.relative_to(raiz))
    return arquivos


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--saida", default=str(RAIZ / "entrega" / "PertoDeMim.zip"))
    a = p.parse_args(argv)
    try:
        arquivos = montar(Path(a.saida))
    except RuntimeError as e:
        print(f"erro: {e}", file=sys.stderr)
        return 1
    print(f"{len(arquivos)} arquivos em {a.saida}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
