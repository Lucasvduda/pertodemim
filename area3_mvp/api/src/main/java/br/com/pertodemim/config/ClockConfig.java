package br.com.pertodemim.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    public static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    /** Relógio injetável: permite congelar o tempo nos testes (heartbeat, check-in, agendamento). */
    @Bean
    public Clock clock() {
        return Clock.system(ZONA);
    }
}
