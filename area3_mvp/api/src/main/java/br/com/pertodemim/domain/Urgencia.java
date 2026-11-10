package br.com.pertodemim.domain;

/** Cores do Sistema Manchester, com o tempo-alvo até a primeira avaliação (minutos). */
public enum Urgencia {
    VERMELHO(0), LARANJA(10), AMARELO(60), VERDE(120), AZUL(240);

    private final int tempoAlvoMin;

    Urgencia(int tempoAlvoMin) { this.tempoAlvoMin = tempoAlvoMin; }

    public int tempoAlvoMin() { return tempoAlvoMin; }

    /** Retorna a mais grave (menor ordinal) entre duas urgências. */
    public static Urgencia maisGrave(Urgencia a, Urgencia b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
