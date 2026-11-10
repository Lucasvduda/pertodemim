package br.com.pertodemim.security;

import br.com.pertodemim.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limitação simples de requisições (janela fixa de 1 minuto, em memória, por IP) para a triagem
 * (protege a cota do LLM) e para o login (reduz tentativa de força bruta).
 * Usa o IP da conexão (não confia em X-Forwarded-For).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private record Janela(long minuto, int contagem) {}

    private final Clock clock;
    private final int limiteTriagem;
    private final int limiteLogin;
    private final Map<String, Janela> janelas = new ConcurrentHashMap<>();

    public RateLimitFilter(Clock clock, AppProperties props) {
        this.clock = clock;
        this.limiteTriagem = props.getRateLimit().getTriagemPorMinuto();
        this.limiteLogin = props.getRateLimit().getLoginPorMinuto();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !"POST".equals(req.getMethod()) || limiteDa(req.getRequestURI()) <= 0;
    }

    private int limiteDa(String uri) {
        if ("/api/triagem".equals(uri)) return limiteTriagem;
        if ("/api/auth/login".equals(uri)) return limiteLogin;
        return 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String uri = req.getRequestURI();
        int limite = limiteDa(uri);
        long minuto = clock.millis() / 60_000;
        String chave = uri + "|" + req.getRemoteAddr();
        Janela j = janelas.merge(chave, new Janela(minuto, 1),
                (atual, nova) -> atual.minuto() == minuto ? new Janela(minuto, atual.contagem() + 1) : nova);
        if (j.contagem() > limite) {
            res.setStatus(429);
            res.setHeader("Retry-After", "60");
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"erro\":\"Muitas requisições. Tente novamente em instantes.\"}");
            return;
        }
        if (janelas.size() > 10_000) janelas.entrySet().removeIf(e -> e.getValue().minuto() < minuto);
        chain.doFilter(req, res);
    }
}
