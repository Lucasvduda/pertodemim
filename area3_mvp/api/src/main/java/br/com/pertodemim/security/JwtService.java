package br.com.pertodemim.security;

import br.com.pertodemim.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/** Emite e valida tokens JWT (HS256) com expiração curta. */
@Service
public class JwtService {

    private final SecretKey chave;
    private final Duration validade;
    private final Clock clock;

    public JwtService(AppProperties props, Clock clock) {
        String segredo = props.getJwt().getSecret();
        if (segredo == null || segredo.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.jwt.secret precisa ter pelo menos 32 caracteres (defina JWT_SECRET)");
        }
        this.chave = Keys.hmacShaKeyFor(segredo.getBytes(StandardCharsets.UTF_8));
        this.validade = Duration.ofMinutes(props.getJwt().getExpiracaoMinutos());
        this.clock = clock;
    }

    public String gerar(Long usuarioId, String email) {
        Instant agora = clock.instant();
        return Jwts.builder()
                .subject(email)
                .claim("uid", usuarioId)
                .issuedAt(Date.from(agora))
                .expiration(Date.from(agora.plus(validade)))
                .signWith(chave)
                .compact();
    }

    /** Devolve o principal se o token for válido e não estiver expirado; vazio caso contrário. */
    public Optional<UsuarioPrincipal> validar(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(chave).clock(() -> Date.from(clock.instant()))
                    .build().parseSignedClaims(token).getPayload();
            Number uid = c.get("uid", Number.class);
            if (uid == null || c.getSubject() == null) return Optional.empty();
            return Optional.of(new UsuarioPrincipal(uid.longValue(), c.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long segundosDeValidade() {
        return validade.toSeconds();
    }
}
