package br.com.pertodemim.web;

import br.com.pertodemim.domain.Usuario;
import br.com.pertodemim.repo.UsuarioRepository;
import br.com.pertodemim.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record RegistroRequest(
            @NotBlank @Size(min = 2, max = 120) String nome,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(min = 8, max = 72, message = "A senha deve ter de 8 a 72 caracteres") String senha,
            @NotNull @AssertTrue(message = "É necessário aceitar o termo de consentimento (LGPD)") Boolean consentimento) {}

    public record LoginRequest(
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(max = 72) String senha) {}

    private final UsuarioRepository usuarios;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final Clock clock;
    /** Hash descartável: gasta o mesmo tempo de BCrypt quando o e-mail não existe (evita enumeração por tempo). */
    private final String hashFalso;

    public AuthController(UsuarioRepository usuarios, PasswordEncoder encoder, JwtService jwt, Clock clock) {
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.jwt = jwt;
        this.clock = clock;
        this.hashFalso = encoder.encode("senha-falsa-para-igualar-tempo");
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registrar(@Valid @RequestBody RegistroRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (usuarios.existsByEmail(email)) throw ApiException.conflito("Este e-mail já está cadastrado");
        try {
            Usuario u = usuarios.save(new Usuario(req.nome().trim(), email, encoder.encode(req.senha()),
                    clock.instant(), clock.instant()));
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("id", u.getId(), "nome", u.getNome(), "email", u.getEmail()));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflito("Este e-mail já está cadastrado");
        }
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        Usuario u = usuarios.findByEmail(email).orElse(null);
        boolean ok = encoder.matches(req.senha(), u == null ? hashFalso : u.getSenhaHash());
        if (u == null || !ok) throw ApiException.naoAutorizado("E-mail ou senha inválidos");
        return Map.of("token", jwt.gerar(u.getId(), u.getEmail()), "tipo", "Bearer",
                "expiraEmSegundos", jwt.segundosDeValidade(), "nome", u.getNome());
    }
}
