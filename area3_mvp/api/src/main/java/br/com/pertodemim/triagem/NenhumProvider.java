package br.com.pertodemim.triagem;

import java.util.Optional;

/** Provedor "rules": nenhum LLM. Padrão do projeto (roda sem chave e sem internet). */
public class NenhumProvider implements LlmProvider {

    @Override
    public String nome() { return "rules"; }

    @Override
    public boolean ativo() { return false; }

    @Override
    public Optional<Classificacao> classificar(String textoAnonimizado) { return Optional.empty(); }
}
