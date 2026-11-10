package br.com.pertodemim.triagem;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Remove identificadores do texto da queixa antes de gravar ou enviar a qualquer LLM
 * (LGPD: minimização; dados de saúde são sensíveis, art. 11).
 */
@Component
public class Anonimizador {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern CPF = Pattern.compile("\\b\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}\\b");
    private static final Pattern TELEFONE = Pattern.compile("(\\+?55\\s?)?(\\(?\\d{2}\\)?\\s?)?9?\\d{4}[-\\s]?\\d{4}\\b");
    private static final Pattern CEP = Pattern.compile("\\b\\d{5}-?\\d{3}\\b");
    private static final Pattern NUMEROS_LONGOS = Pattern.compile("\\b\\d{7,}\\b");
    private static final Pattern NOME = Pattern.compile(
            "(?iu)\\b(meu nome (?:é|e)|me chamo|sou (?:o|a)|chamo-me)\\s+[\\p{L}]+(?:\\s+[\\p{L}]+){0,2}");
    /**
     * Endereço: a palavra de logradouro (sem diferenciar maiúsculas) seguida de um nome próprio com
     * maiúsculas ("Rua das Flores"), de preposição + uma palavra ("rua das flores") ou de palavras
     * seguidas de número ("rua tal 123"). Não pode engolir o resto da frase: "caiu na rua e está
     * desacordado" não é endereço (isso apagava sinais de emergência).
     */
    private static final Pattern ENDERECO = Pattern.compile(
            "(?u)\\b(?i:rua|avenida|av\\.|travessa|alameda|estrada)\\s+(?:"
                    + "(?:d[aeo]s?\\s+)?\\p{Lu}[\\p{L}0-9.]*(?:\\s+(?:d[aeo]s?\\s+)?\\p{Lu}[\\p{L}0-9.]*){0,3}"
                    + "|(?i:d[aeo]s?)\\s+\\p{L}+"
                    + "|\\p{L}+(?:\\s+\\p{L}+){0,2}?(?=,?\\s*(?:(?i:n)[º°o.]*\\s*)?\\d)"
                    + ")(?:,?\\s*(?:(?i:n)[º°o.]*\\s*)?\\d+)?");

    public String anonimizar(String texto) {
        if (texto == null) return "";
        String t = texto;
        t = EMAIL.matcher(t).replaceAll("[EMAIL]");
        t = CPF.matcher(t).replaceAll("[CPF]");
        t = CEP.matcher(t).replaceAll("[CEP]");
        t = TELEFONE.matcher(t).replaceAll("[TEL]");
        t = NUMEROS_LONGOS.matcher(t).replaceAll("[NUM]");
        t = NOME.matcher(t).replaceAll("[NOME]");
        t = ENDERECO.matcher(t).replaceAll("[ENDERECO]");
        return t.trim();
    }
}
