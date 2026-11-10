package br.com.pertodemim.triagem;

import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.Urgencia;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Camada 3: valida a resposta do LLM como entrada NÃO confiável. Só aceita JSON no formato esperado,
 * com serviço e urgência válidos e especialidade dentro da lista fechada.
 */
public final class RespostaLlmParser {

    private static final Pattern CERCA = Pattern.compile("^```(?:json)?\\s*|\\s*```$");
    private static final int MAX_PERGUNTA = 160;

    private RespostaLlmParser() {}

    public static Optional<Classificacao> parse(String conteudo, ObjectMapper mapper) {
        if (conteudo == null || conteudo.isBlank()) return Optional.empty();
        try {
            String json = CERCA.matcher(conteudo.trim()).replaceAll("").trim();
            JsonNode n = mapper.readTree(json);
            if (n == null || !n.isObject()) return Optional.empty();

            Servico servico = servico(texto(n, "servico")).orElse(null);
            Urgencia urgencia = urgencia(texto(n, "urgencia")).orElse(null);
            if (servico == null || urgencia == null) return Optional.empty();

            Especialidade esp = null;
            if (servico == Servico.SAUDE) {
                esp = Especialidades.porNome(texto(n, "especialidade")).orElse(null);
                if (esp == null) return Optional.empty(); // fora da lista fechada
            }
            String pergunta = perguntaSegura(texto(n, "perguntaAdicional"));
            // O texto de orientação do modelo NÃO é usado: o app usa textos próprios e controlados.
            return Optional.of(new Classificacao(servico, esp, urgencia,
                    Orientacoes.montar(servico, esp, urgencia), pergunta));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static Optional<Servico> servico(String s) {
        return switch (Texto.normalizar(s).replace(' ', '_')) {
            case "saude" -> Optional.of(Servico.SAUDE);
            case "documento", "documentos" -> Optional.of(Servico.DOCUMENTO);
            case "assistencia_social" -> Optional.of(Servico.ASSISTENCIA_SOCIAL);
            default -> Optional.empty();
        };
    }

    private static Optional<Urgencia> urgencia(String s) {
        try {
            return Optional.of(Urgencia.valueOf(Texto.normalizar(s).toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String perguntaSegura(String p) {
        if (p == null) return null;
        String t = p.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.length() > MAX_PERGUNTA) return null;
        String low = t.toLowerCase(Locale.ROOT);
        if (low.contains("http") || low.contains("www.") || low.contains("<")) return null;
        return t;
    }
}
