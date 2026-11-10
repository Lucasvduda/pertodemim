package br.com.pertodemim.triagem;

import java.util.Optional;

/** Camada 2 da triagem: classificador baseado em LLM (opcional). */
public interface LlmProvider {

    /** Nome do provedor (rules, groq, gemini, ollama). */
    String nome();

    /** false quando não há LLM configurado: a triagem usa só as regras. */
    boolean ativo();

    /**
     * Classifica o texto (já anonimizado). Devolve vazio em qualquer falha (cota, rede, tempo esgotado,
     * JSON inválido, especialidade fora da lista): o chamador usa as regras como fallback.
     */
    Optional<Classificacao> classificar(String textoAnonimizado);
}
