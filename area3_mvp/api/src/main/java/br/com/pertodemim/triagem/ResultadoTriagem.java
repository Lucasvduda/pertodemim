package br.com.pertodemim.triagem;

import br.com.pertodemim.domain.Caminho;
import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.Urgencia;

/**
 * Resultado da triagem (antes do ranking de unidades).
 *
 * @param telefone 192 ou 188 quando for emergência / saúde mental; null nos demais casos
 */
public record ResultadoTriagem(Servico servico, Especialidade especialidade, Urgencia urgencia, String orientacao,
                               String perguntaAdicional, Caminho caminho, String telefone, String textoAnonimizado) {

    public boolean emergencia() {
        return urgencia == Urgencia.VERMELHO;
    }
}
