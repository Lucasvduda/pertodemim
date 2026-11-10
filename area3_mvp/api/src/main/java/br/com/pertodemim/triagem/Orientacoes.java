package br.com.pertodemim.triagem;

import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.Urgencia;

/** Textos de orientação em linguagem simples. O app orienta e encaminha; não faz diagnóstico. */
public final class Orientacoes {

    private Orientacoes() {}

    public static String montar(Servico servico, Especialidade esp, Urgencia urgencia) {
        return switch (servico) {
            case DOCUMENTO -> "Você precisa de atendimento para emissão de documento. "
                    + "Veja abaixo as unidades que oferecem esse serviço e agende um horário.";
            case ASSISTENCIA_SOCIAL -> urgencia.ordinal() <= Urgencia.AMARELO.ordinal()
                    ? "Sua situação merece atenção rápida da assistência social. "
                    + "Veja as unidades que fazem esse atendimento e agende o quanto antes."
                    : "Você precisa de atendimento ou visita da assistência social. "
                    + "Veja as unidades que oferecem o serviço e agende um horário.";
            case SAUDE -> saude(esp, urgencia);
        };
    }

    private static String saude(Especialidade esp, Urgencia urgencia) {
        String quem = esp == null ? "um médico" : esp.nome();
        String base = "Pelo que você descreveu, o atendimento indicado é: " + quem + ". ";
        return base + switch (urgencia) {
            case VERMELHO -> "Ligue agora para o SAMU: 192.";
            case LARANJA -> "É importante ser atendido logo. Procure agora uma unidade que tenha esse profissional "
                    + "(pronto atendimento ou UPA) e não espere por agendamento.";
            case AMARELO -> "Procure atendimento ainda hoje em uma unidade que tenha esse profissional.";
            case VERDE -> "Você pode ir a uma unidade que tenha esse profissional ou agendar uma consulta.";
            case AZUL -> "Não é urgente. Você pode agendar uma consulta para os próximos dias.";
        };
    }
}
