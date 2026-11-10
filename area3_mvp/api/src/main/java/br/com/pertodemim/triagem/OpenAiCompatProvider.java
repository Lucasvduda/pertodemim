package br.com.pertodemim.triagem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Provedor genérico para APIs compatíveis com a OpenAI ({@code POST {base}/chat/completions}):
 * Groq, Gemini (endpoint de compatibilidade) e Ollama. Usa o RestClient do Spring, sem biblioteca extra.
 * Qualquer falha devolve vazio (o chamador cai nas regras). Nunca registra o texto do cidadão nem a chave.
 */
public class OpenAiCompatProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatProvider.class);

    private final String nome;
    private final String model;
    private final RestClient http;
    private final ObjectMapper mapper;

    public OpenAiCompatProvider(String nome, String baseUrl, String model, String apiKey, Duration timeout,
                                ObjectMapper mapper) {
        this.nome = nome;
        this.model = model;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) timeout.toMillis());
        rf.setReadTimeout((int) timeout.toMillis());
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(rf)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public String nome() { return nome; }

    @Override
    public boolean ativo() { return true; }

    @Override
    public Optional<Classificacao> classificar(String textoAnonimizado) {
        try {
            String conteudo;
            try {
                conteudo = chamar(textoAnonimizado, formatoJsonSchema());
            } catch (HttpClientErrorException.BadRequest e) {
                // Alguns modelos não aceitam json_schema: tenta de novo em modo json_object.
                conteudo = chamar(textoAnonimizado, Map.of("type", "json_object"));
            }
            Optional<Classificacao> r = RespostaLlmParser.parse(conteudo, mapper);
            if (r.isEmpty()) log.warn("LLM {}: resposta inválida ou fora da lista fechada; usando regras", nome);
            return r;
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("LLM {}: limite de requisições (429); usando regras", nome);
        } catch (Exception e) {
            log.warn("LLM {}: falha ({}); usando regras", nome, e.getClass().getSimpleName());
        }
        return Optional.empty();
    }

    private Map<String, Object> formatoJsonSchema() {
        Map<String, Object> js = new LinkedHashMap<>();
        js.put("name", "triagem");
        js.put("strict", true);
        js.put("schema", PromptTriagem.schema());
        return Map.of("type", "json_schema", "json_schema", js);
    }

    private String chamar(String texto, Map<String, Object> responseFormat) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("messages", PromptTriagem.mensagens(texto));
        body.put("response_format", responseFormat);
        JsonNode r = http.post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return r == null ? "" : r.at("/choices/0/message/content").asText("");
    }
}
