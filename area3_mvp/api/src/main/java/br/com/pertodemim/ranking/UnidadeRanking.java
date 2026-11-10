package br.com.pertodemim.ranking;

/**
 * Unidade no ranking.
 *
 * @param categoria AGORA (profissional/serviço confirmado agora), AGENDA (só com agenda aberta)
 *                  ou SEM_CONFIRMACAO (rebaixada: ausente, sem a especialidade ou offline)
 * @param aviso     motivo da rebaixa ou observação; nulo quando não há
 */
public record UnidadeRanking(Long id, String nome, String tipo, String bairro, String regiao, double lat, double lon,
                             double distanciaKm, Integer filaPessoas, Integer esperaMin, boolean online,
                             boolean disponivelAgora, boolean agendaAberta, Integer qtdProfissionais,
                             String categoria, Double score, String aviso) {
}
