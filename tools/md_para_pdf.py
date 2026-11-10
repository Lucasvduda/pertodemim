"""Converte um arquivo Markdown em PDF (Markdown -> HTML -> Edge/Chrome em modo headless).

    python tools/md_para_pdf.py docs/ROTEIRO_PITCH.md [saida.pdf]

Requer: pip install markdown, e o Microsoft Edge ou o Google Chrome instalado.
Os diagramas Mermaid NÃO são renderizados (aparecem como código); use o .md no GitHub para vê-los.
"""
from __future__ import annotations

import html
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import markdown

NAVEGADORES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    "microsoft-edge", "google-chrome", "chromium",
]
CSS = """
body{font-family:Segoe UI,Arial,sans-serif;max-width:820px;margin:24px auto;color:#14202e;line-height:1.5;font-size:13px}
h1{color:#0b4f8a;font-size:24px}h2{color:#0b4f8a;border-bottom:1px solid #c9d4e0;padding-bottom:4px;font-size:18px}h3{font-size:15px}
table{border-collapse:collapse;width:100%;margin:10px 0}th,td{border:1px solid #c9d4e0;padding:5px 8px;text-align:left;vertical-align:top}
th{background:#eef3f9}code{background:#eef3f9;padding:1px 4px;border-radius:3px;font-size:12px}pre{background:#eef3f9;padding:10px;overflow:auto}
em{color:#0b4f8a}
"""


def achar_navegador() -> str:
    for n in NAVEGADORES:
        if Path(n).exists() or shutil.which(n):
            return n
    raise RuntimeError("Edge ou Chrome não encontrado")


def converter(origem: Path, destino: Path) -> None:
    corpo = markdown.markdown(origem.read_text(encoding="utf-8"), extensions=["tables", "fenced_code"])
    pagina = f"<!doctype html><html lang='pt-BR'><meta charset='utf-8'><title>{html.escape(origem.stem)}</title><style>{CSS}</style><body>{corpo}</body></html>"
    with tempfile.TemporaryDirectory() as tmp:
        arq = Path(tmp) / "doc.html"
        arq.write_text(pagina, encoding="utf-8")
        destino.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([achar_navegador(), "--headless", "--disable-gpu", "--no-pdf-header-footer", f"--print-to-pdf={destino.resolve()}",
                        arq.as_uri()], check=True, capture_output=True, timeout=120)
    if not destino.exists() or destino.stat().st_size == 0:
        raise RuntimeError("o navegador não gerou o PDF")


def main(argv=None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if not args:
        print(__doc__)
        return 2
    origem = Path(args[0])
    destino = Path(args[1]) if len(args) > 1 else origem.with_suffix(".pdf")
    converter(origem, destino)
    print(f"gerado: {destino}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
