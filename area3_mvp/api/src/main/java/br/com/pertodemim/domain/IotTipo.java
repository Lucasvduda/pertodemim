package br.com.pertodemim.domain;

public enum IotTipo {
    FILA, PRESENCA, HEARTBEAT;

    /** Converte o texto do contrato do evento ("fila", "presenca", "heartbeat"). */
    public static IotTipo de(String texto) {
        if (texto == null) throw new IllegalArgumentException("tipo é obrigatório");
        return switch (texto.trim().toLowerCase()) {
            case "fila" -> FILA;
            case "presenca", "presença" -> PRESENCA;
            case "heartbeat" -> HEARTBEAT;
            default -> throw new IllegalArgumentException("tipo de evento inválido: " + texto);
        };
    }
}
