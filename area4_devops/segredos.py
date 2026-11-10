"""Varredura simples de segredos em arquivos de texto (chaves de API, chaves privadas, atribuições literais).

Usada pelos testes de estrutura e pelo script que monta o .zip da entrega. Só biblioteca padrão.
Não substitui uma ferramenta dedicada (gitleaks, trufflehog); pega os erros mais comuns.
"""
from __future__ import annotations

import re
from pathlib import Path

PADROES = {
    "chave Groq": re.compile(r"gsk_[A-Za-z0-9]{20,}"),
    "chave Google/Gemini": re.compile(r"AIza[0-9A-Za-z_\-]{30,}"),
    "chave estilo OpenAI": re.compile(r"\bsk-[A-Za-z0-9_\-]{32,}"),
    "token GitHub": re.compile(r"\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}"),
    "chave AWS": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "chave privada": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY"),
    "credencial literal": re.compile(
        r"""(?ix)\b(api[_-]?key|secret|passwd|password|senha|token)\b["']?\s*[:=]\s*["'][A-Za-z0-9+/_\-]{20,}["']"""),
}
PASTAS_IGNORADAS = {".git", "target", "__pycache__", ".pytest_cache", ".venv", "venv", "node_modules", ".idea", ".vscode",
                    "bronze", "silver", "gold", "buffer", "evidencias"}
EXTENSOES_BINARIAS = {".png", ".jpg", ".jpeg", ".gif", ".pdf", ".zip", ".jar", ".class", ".ico", ".woff", ".woff2", ".pyc", ".gz"}
TAMANHO_MAX = 2_000_000


def varrer_texto(texto: str) -> list[tuple[int, str]]:
    achados = []
    for n, linha in enumerate(texto.splitlines(), 1):
        for nome, rx in PADROES.items():
            if rx.search(linha):
                achados.append((n, nome))
    return achados


def varrer(raiz: Path) -> list[tuple[str, int, str]]:
    achados = []
    for p in sorted(Path(raiz).rglob("*")):
        if not p.is_file() or any(parte in PASTAS_IGNORADAS for parte in p.relative_to(raiz).parts):
            continue
        if p.suffix.lower() in EXTENSOES_BINARIAS or p.stat().st_size > TAMANHO_MAX:
            continue
        try:
            texto = p.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for n, nome in varrer_texto(texto):
            achados.append((str(p.relative_to(raiz)).replace("\\", "/"), n, nome))
    return achados
