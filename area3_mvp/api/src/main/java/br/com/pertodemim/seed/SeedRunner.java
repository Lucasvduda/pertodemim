package br.com.pertodemim.seed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Dispara o seed ao subir a aplicação (desligável com app.seed.habilitado=false). */
@Component
@ConditionalOnProperty(name = "app.seed.habilitado", havingValue = "true", matchIfMissing = true)
public class SeedRunner implements ApplicationRunner {

    private final SeedService seed;

    public SeedRunner(SeedService seed) {
        this.seed = seed;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        seed.carregar();
    }
}
