package br.com.pertodemim.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exige o cabeçalho X-Device-Key em POST /api/iot/eventos ANTES de qualquer validação do corpo,
 * para que quem não tem chave receba 401 e não descubra detalhes do contrato (400).
 */
public class DeviceKeyHeaderFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !("POST".equals(req.getMethod()) && "/api/iot/eventos".equals(req.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String chave = req.getHeader("X-Device-Key");
        if (chave == null || chave.isBlank()) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"erro\":\"Chave do dispositivo ausente\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
