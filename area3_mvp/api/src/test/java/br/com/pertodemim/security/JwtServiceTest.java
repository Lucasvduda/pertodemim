package br.com.pertodemim.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.pertodemim.config.AppProperties;
import br.com.pertodemim.support.TestClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private AppProperties props(String segredo, long minutos) {
        AppProperties p = new AppProperties();
        p.getJwt().setSecret(segredo);
        p.getJwt().setExpiracaoMinutos(minutos);
        return p;
    }

    private static final String SEGREDO = "um-segredo-bem-longo-com-mais-de-32-caracteres!";

    @Test
    void geraEValidaToken() {
        var jwt = new JwtService(props(SEGREDO, 60), new TestClock());
        String token = jwt.gerar(7L, "a@b.com");
        var p = jwt.validar(token);
        assertThat(p).isPresent();
        assertThat(p.get().id()).isEqualTo(7L);
        assertThat(p.get().email()).isEqualTo("a@b.com");
    }

    @Test
    void tokenExpiradoEhRejeitado() {
        var clock = new TestClock();
        var jwt = new JwtService(props(SEGREDO, 60), clock);
        String token = jwt.gerar(7L, "a@b.com");
        clock.avancar(Duration.ofMinutes(59));
        assertThat(jwt.validar(token)).isPresent();
        clock.avancar(Duration.ofMinutes(2));
        assertThat(jwt.validar(token)).isEmpty();
    }

    @Test
    void tokenAdulteradoOuDeOutraChaveEhRejeitado() {
        var jwt = new JwtService(props(SEGREDO, 60), new TestClock());
        var outro = new JwtService(props("outro-segredo-bem-longo-com-mais-de-32-caracteres", 60), new TestClock());
        String token = jwt.gerar(7L, "a@b.com");
        assertThat(jwt.validar(token + "x")).isEmpty();
        assertThat(jwt.validar(outro.gerar(7L, "a@b.com"))).isEmpty();
        String[] partes = token.split("\\.");
        assertThat(jwt.validar(partes[0] + "." + partes[1] + ".assinaturafalsa")).isEmpty();
    }

    @Test
    void lixoNaoQuebra() {
        var jwt = new JwtService(props(SEGREDO, 60), new TestClock());
        assertThat(jwt.validar("")).isEmpty();
        assertThat(jwt.validar("abc")).isEmpty();
        assertThat(jwt.validar("a.b.c")).isEmpty();
    }

    @Test
    void segredoCurtoEhRecusadoNaInicializacao() {
        assertThatThrownBy(() -> new JwtService(props("curto", 60), new TestClock()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32");
        assertThatThrownBy(() -> new JwtService(props(null, 60), new TestClock()))
                .isInstanceOf(IllegalStateException.class);
    }
}
