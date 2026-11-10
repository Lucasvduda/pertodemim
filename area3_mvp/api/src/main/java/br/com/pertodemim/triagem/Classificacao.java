package br.com.pertodemim.triagem;

import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.Urgencia;

/**
 * Saída de um classificador (regras ou LLM).
 *
 * @param especialidade só preenchida quando {@code servico == SAUDE}
 * @param generica      true quando as regras não reconheceram nada e devolveram o padrão
 */
public record Classificacao(Servico servico, Especialidade especialidade, Urgencia urgencia, String orientacao,
                            String perguntaAdicional, boolean generica) {

    public Classificacao(Servico servico, Especialidade especialidade, Urgencia urgencia, String orientacao,
                         String perguntaAdicional) {
        this(servico, especialidade, urgencia, orientacao, perguntaAdicional, false);
    }
}
