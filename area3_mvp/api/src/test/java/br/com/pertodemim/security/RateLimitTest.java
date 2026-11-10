package br.com.pertodemim.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.pertodemim.support.IntegrationTestBase;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/** Limite de requisições com valores baixos (contexto próprio). */
@SpringBootTest(properties = {"app.rate-limit.login-por-minuto=3", "app.rate-limit.triagem-por-minuto=2"})
class RateLimitTest extends IntegrationTestBase {

    private int login() throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.com\",\"senha\":\"senha12345\"}")).andReturn().getResponse().getStatus();
    }

    @Test
    void loginAcimaDoLimiteRetorna429ComRetryAfterEVoltaNoMinutoSeguinte() throws Exception {
        clock.avancar(Duration.ofHours(1)); // janela nova, independente dos outros testes
        for (int i = 0; i < 3; i++) org.assertj.core.api.Assertions.assertThat(login()).isEqualTo(401);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"senha\":\"senha12345\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
        clock.avancar(Duration.ofSeconds(61));
        org.assertj.core.api.Assertions.assertThat(login()).isEqualTo(401);
    }

    @Test
    void triagemAcimaDoLimiteRetorna429MesmoSemToken() throws Exception {
        clock.avancar(Duration.ofHours(2));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/triagem").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/triagem").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests());
    }
}
