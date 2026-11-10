package br.com.pertodemim.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Relógio mutável para os testes: permite congelar e avançar o tempo (heartbeat, check-in, agenda). */
public class TestClock extends Clock {

    public static final Instant INICIO = Instant.parse("2026-11-12T15:00:00Z"); // 12:00 em São Paulo
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    private volatile Instant agora = INICIO;

    public void reset() { agora = INICIO; }

    public void avancar(Duration d) { agora = agora.plus(d); }

    public void definir(Instant i) { agora = i; }

    @Override
    public ZoneId getZone() { return ZONA; }

    @Override
    public Clock withZone(ZoneId zone) { return this; }

    @Override
    public Instant instant() { return agora; }
}
