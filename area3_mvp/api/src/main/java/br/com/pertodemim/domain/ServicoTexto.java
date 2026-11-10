package br.com.pertodemim.domain;

import java.util.Locale;

/** Conversão do texto "saude | documento | assistencia_social" (contrato da API) para {@link Servico}. */
public final class ServicoTexto {

    private ServicoTexto() {}

    public static Servico parse(String s) {
        if (s == null || s.isBlank()) return Servico.SAUDE;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "saude", "saúde" -> Servico.SAUDE;
            case "documento", "documentos" -> Servico.DOCUMENTO;
            case "assistencia_social", "assistência_social" -> Servico.ASSISTENCIA_SOCIAL;
            default -> throw new IllegalArgumentException("servico inválido: " + s);
        };
    }

    public static String texto(Servico s) {
        return s.name().toLowerCase(Locale.ROOT);
    }
}
