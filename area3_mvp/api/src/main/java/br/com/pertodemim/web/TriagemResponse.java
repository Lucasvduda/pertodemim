package br.com.pertodemim.web;

import br.com.pertodemim.ranking.UnidadeRanking;
import java.util.List;

/**
 * Saída da triagem. {@code unidades} fica vazia em emergência (vermelho): nesse caso o app mostra o 192.
 * {@code kmIndicada}, {@code kmMaisProxima} e {@code maisProximaTinha} permitem mostrar o ganho: quando a unidade mais próxima
 * NÃO tinha o profissional, o app indicou outra e a pessoa evitou uma viagem perdida.
 */
public record TriagemResponse(Long triagemId, String servico, String especialidade, String cbo, String urgencia,
                              int tempoAlvoMin, String orientacao, String perguntaAdicional, boolean emergencia, boolean samu,
                              String telefoneEmergencia, String caminho, String resumo, String aviso,
                              Long unidadeIndicadaId, List<UnidadeRanking> unidades,
                              Double kmIndicada, Double kmMaisProxima, Boolean maisProximaTinha) {
}
