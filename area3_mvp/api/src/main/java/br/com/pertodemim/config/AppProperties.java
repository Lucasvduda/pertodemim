package br.com.pertodemim.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Propriedades da aplicação (prefixo {@code app}). */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Iot iot = new Iot();
    private final Bronze bronze = new Bronze();
    private final Seed seed = new Seed();
    private final RateLimit rateLimit = new RateLimit();
    private final Llm llm = new Llm();

    public Jwt getJwt() { return jwt; }
    public Iot getIot() { return iot; }
    public Bronze getBronze() { return bronze; }
    public Seed getSeed() { return seed; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Llm getLlm() { return llm; }

    public static class Jwt {
        private String secret;
        private long expiracaoMinutos = 60;
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getExpiracaoMinutos() { return expiracaoMinutos; }
        public void setExpiracaoMinutos(long v) { this.expiracaoMinutos = v; }
    }

    public static class Iot {
        private String chaveSemente;
        private int heartbeatMaxMinutos = 2;
        private int presencaMaxHoras = 4;
        private int minutosPorPessoaNaFila = 5;
        public String getChaveSemente() { return chaveSemente; }
        public void setChaveSemente(String v) { this.chaveSemente = v; }
        public int getHeartbeatMaxMinutos() { return heartbeatMaxMinutos; }
        public void setHeartbeatMaxMinutos(int v) { this.heartbeatMaxMinutos = v; }
        public int getPresencaMaxHoras() { return presencaMaxHoras; }
        public void setPresencaMaxHoras(int v) { this.presencaMaxHoras = v; }
        public int getMinutosPorPessoaNaFila() { return minutosPorPessoaNaFila; }
        public void setMinutosPorPessoaNaFila(int v) { this.minutosPorPessoaNaFila = v; }
    }

    public static class Bronze {
        private String dir = "bronze";
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
    }

    public static class Seed {
        private boolean habilitado = true;
        private boolean estadoInicialFresco = true;
        public boolean isHabilitado() { return habilitado; }
        public void setHabilitado(boolean v) { this.habilitado = v; }
        public boolean isEstadoInicialFresco() { return estadoInicialFresco; }
        public void setEstadoInicialFresco(boolean v) { this.estadoInicialFresco = v; }
    }

    public static class RateLimit {
        private int triagemPorMinuto = 30;
        private int loginPorMinuto = 10;
        public int getTriagemPorMinuto() { return triagemPorMinuto; }
        public void setTriagemPorMinuto(int v) { this.triagemPorMinuto = v; }
        public int getLoginPorMinuto() { return loginPorMinuto; }
        public void setLoginPorMinuto(int v) { this.loginPorMinuto = v; }
    }

    public static class Llm {
        private String provider = "rules";
        private int timeoutMs = 8000;
        private final Remote groq = new Remote();
        private final Remote gemini = new Remote();
        private final Remote ollama = new Remote();
        private final Remote openai = new Remote();
        public Remote getOpenai() { return openai; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int v) { this.timeoutMs = v; }
        public Remote getGroq() { return groq; }
        public Remote getGemini() { return gemini; }
        public Remote getOllama() { return ollama; }
    }

    public static class Remote {
        private String baseUrl;
        private String model;
        private String apiKey = "";
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
    }
}
