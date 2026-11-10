package br.com.pertodemim.domain;

/** Qual camada da triagem produziu a resposta. */
public enum Caminho {
    RED_FLAG, REGRAS, LLM, FALLBACK
}
