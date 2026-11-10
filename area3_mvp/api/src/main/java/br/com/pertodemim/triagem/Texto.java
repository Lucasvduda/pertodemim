package br.com.pertodemim.triagem;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Utilitários de texto: normalização para comparação (minúsculas, sem acento). */
public final class Texto {

    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
    private static final Pattern ESPACOS = Pattern.compile("\\s+");

    private Texto() {}

    public static String normalizar(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        n = MARCAS.matcher(n).replaceAll("");
        n = n.toLowerCase(Locale.ROOT);
        return ESPACOS.matcher(n).replaceAll(" ").trim();
    }
}
