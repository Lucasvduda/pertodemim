package br.com.pertodemim.triagem;

import br.com.pertodemim.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Escolhe o provedor de LLM a partir de {@code app.llm.provider} (variável LLM_PROVIDER). */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmProvider llmProvider(AppProperties props, ObjectMapper mapper) {
        AppProperties.Llm llm = props.getLlm();
        String escolhido = llm.getProvider() == null ? "rules" : llm.getProvider().trim().toLowerCase();
        Duration timeout = Duration.ofMillis(llm.getTimeoutMs());
        switch (escolhido) {
            case "groq":
                return remoto("groq", llm.getGroq(), true, timeout, mapper);
            case "gemini":
                return remoto("gemini", llm.getGemini(), true, timeout, mapper);
            case "openai":
                return remoto("openai", llm.getOpenai(), true, timeout, mapper);
            case "ollama":
                return remoto("ollama", llm.getOllama(), false, timeout, mapper);
            case "rules":
                log.info("Triagem em modo 'rules' (sem LLM externo).");
                return new NenhumProvider();
            default:
                log.warn("LLM_PROVIDER='{}' desconhecido; usando 'rules'.", escolhido);
                return new NenhumProvider();
        }
    }

    private LlmProvider remoto(String nome, AppProperties.Remote cfg, boolean exigeChave, Duration timeout,
                               ObjectMapper mapper) {
        if (exigeChave && (cfg.getApiKey() == null || cfg.getApiKey().isBlank())) {
            log.warn("Provedor '{}' escolhido, mas a chave de API não está definida; usando 'rules'.", nome);
            return new NenhumProvider();
        }
        log.info("Triagem com LLM '{}' (modelo {}), com fallback por regras.", nome, cfg.getModel());
        return new OpenAiCompatProvider(nome, cfg.getBaseUrl(), cfg.getModel(), cfg.getApiKey(), timeout, mapper);
    }
}
