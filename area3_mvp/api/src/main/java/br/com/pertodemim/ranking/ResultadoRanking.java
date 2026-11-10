package br.com.pertodemim.ranking;

import java.util.List;

/**
 * Resultado do ranking.
 *
 * @param indicadaId        unidade no topo (null se nenhuma elegível)
 * @param kmIndicada        distância até a unidade indicada
 * @param kmMaisProxima     distância até a unidade mais próxima de qualquer tipo
 * @param maisProximaTinha  se a unidade mais próxima teria o profissional/serviço agora
 */
public record ResultadoRanking(List<UnidadeRanking> unidades, Long indicadaId, Double kmIndicada,
                               Double kmMaisProxima, Boolean maisProximaTinha) {

    public static ResultadoRanking vazio() {
        return new ResultadoRanking(List.of(), null, null, null, null);
    }
}
