package br.com.pertodemim.seed;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Leitor de CSV mínimo (cabeçalho + linhas, aspas duplas e vírgulas dentro de aspas). */
final class CsvSimples {

    private CsvSimples() {}

    static List<Map<String, String>> ler(InputStream in) throws IOException {
        List<Map<String, String>> linhas = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String cab = r.readLine();
            if (cab == null) return linhas;
            if (cab.startsWith("\uFEFF")) cab = cab.substring(1);
            List<String> colunas = dividir(cab);
            String linha;
            while ((linha = r.readLine()) != null) {
                if (linha.isBlank()) continue;
                List<String> v = dividir(linha);
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 0; i < colunas.size(); i++) m.put(colunas.get(i), i < v.size() ? v.get(i) : "");
                linhas.add(m);
            }
        }
        return linhas;
    }

    static List<String> dividir(String linha) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean aspas = false;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (aspas) {
                if (c == '"') {
                    if (i + 1 < linha.length() && linha.charAt(i + 1) == '"') { sb.append('"'); i++; }
                    else aspas = false;
                } else sb.append(c);
            } else if (c == '"') aspas = true;
            else if (c == ',') { out.add(sb.toString()); sb.setLength(0); }
            else sb.append(c);
        }
        out.add(sb.toString());
        return out;
    }
}
