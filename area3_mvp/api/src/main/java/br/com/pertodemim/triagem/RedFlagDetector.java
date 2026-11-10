package br.com.pertodemim.triagem;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Camada 1 da triagem: sinais de alerta (red flags). Se algum casar, a urgência é VERMELHA e o app
 * orienta ligar para o SAMU (192), sem consultar o LLM e sem ranking. Prioriza sensibilidade
 * (prefere alertar a mais a deixar passar um caso grave); só entende a negação explícita de dor no peito e
 * falta de ar ("não tenho dor no peito").
 */
@Component
public class RedFlagDetector {

    public enum Tipo { EMERGENCIA, SAUDE_MENTAL }

    public record Alerta(Tipo tipo, String descricao, String telefone, String mensagem) {}

    private record Regra(Tipo tipo, String descricao, Pattern padrao) {}

    private static Pattern p(String regex) {
        return Pattern.compile(regex);
    }

    private static final List<Regra> REGRAS = List.of(
            new Regra(Tipo.SAUDE_MENTAL, "ideação suicida",
                    p("(quero|vou|pensando em|penso em|vontade de) (me matar|morrer|acabar com (a )?minha vida|tirar (a )?minha vida)"
                            + "|suicid|nao quero mais viver|nao aguento mais viver|melhor (eu )?morrer")),
            new Regra(Tipo.EMERGENCIA, "dor no peito / infarto",
                    p("(dor|aperto|pressao|queimacao) (forte |intensa )?(no|do|em) (meu )?(peito|torax)|dor toracica|infarto|dor (que )?(vai|irradia|desce) (para|pro|pra) (o )?(braco|maxilar)")),
            new Regra(Tipo.EMERGENCIA, "falta de ar intensa",
                    p("falta de ar|\\bsem ar\\b(?! condicionado)|nao consigo respirar|nao (esta|ta) respirando|dificuldade (para|de|em) respirar|engasg|asfixia|labios roxos|ficou roxo|ficando roxo|cianose")),
            new Regra(Tipo.EMERGENCIA, "desmaio / inconsciência",
                    p("desmai|desacordad|inconsciente|perdeu (a )?consciencia|nao responde|nao acorda|apagou de repente")),
            new Regra(Tipo.EMERGENCIA, "convulsão",
                    p("convuls|ataque epileptico|crise epileptica|tremendo todo e (revirando|babando)")),
            new Regra(Tipo.EMERGENCIA, "sangramento grave",
                    p("sangramento (que )?(nao|n) (para|estanca|cessa)|hemorragia|sangr\\w* (muito|demais|sem parar|intenso|forte)|muito sangue|vomit\\w* sangue|vomito com sangue|tossindo sangue|sangue vivo")),
            new Regra(Tipo.EMERGENCIA, "sinais de AVC",
                    p("\\bavc\\b|derrame|boca torta|rosto torto|rosto caido|fala (enrolada|arrastada)|perdeu a fala|nao consegue falar|fraqueza (de|em) um lado|um lado do corpo|perdi (a )?forca (de|em) um (lado|braco|perna)|paralis\\w* (de )?um lado")),
            new Regra(Tipo.EMERGENCIA, "trauma grave",
                    p("atropel|acidente (grave )?(de|com) (carro|moto|motocicleta|onibus|caminhao|bicicleta)|queda de (grande )?altura|caiu de (um |do |da )?(predio|telhado|andaime|ponte|arvore alta)|baleado|\\btiro\\b|levou (um )?tiro|esfaquead|facada|osso (exposto|aparecendo|para fora)|fratura exposta|traumatismo craniano|bateu (muito )?forte a cabeca|choque eletrico|afogament")),
            new Regra(Tipo.EMERGENCIA, "queimadura grave",
                    p("queimadura (extensa|grave|grande|profunda|no rosto|no corpo todo)|queimou (o |todo o )?(corpo|rosto)|pegou fogo")),
            new Regra(Tipo.EMERGENCIA, "gestante com sinal de alerta",
                    p("(gravida|gestante|gravidez|gestacao)[^.]{0,60}(sangr|perda de liquido|bolsa rompeu|bolsa estourou|contracoes fortes|convuls|nao sente o bebe mexer)"
                            + "|(sangr|perda de liquido|bolsa rompeu|bolsa estourou|contracoes fortes|nao sente o bebe mexer)[^.]{0,60}(gravida|gestante|gravidez|gestacao)")),
            new Regra(Tipo.EMERGENCIA, "intoxicação / overdose",
                    p("overdose|envenen|intoxic|engoliu (veneno|soda|produto de limpeza|muitos remedios|remedio demais)|tomou (veneno|remedio demais|muitos comprimidos)")),
            new Regra(Tipo.EMERGENCIA, "reação alérgica grave",
                    p("anafila|garganta (fechando|inchando|fechou)|lingua inchando|inchaco (na|da) (garganta|lingua)|rosto inchando muito")),
            new Regra(Tipo.EMERGENCIA, "dor de cabeça súbita intensa",
                    p("pior dor de cabeca (da minha vida|que ja tive)|dor de cabeca (muito )?forte (e )?(repentina|de repente|subita)"))
    );

    /**
     * Única negação tratada: "não tenho/sinto ... dor no peito / falta de ar" (e "nem ..." em seguida).
     * É estreita de propósito: só remove a frase negada, então "não tenho dor no peito e desmaiei" continua
     * sendo emergência. Qualquer outra forma de negação continua alertando (prefere alertar a mais).
     */
    private static final String SINTOMA_RESPIRATORIO_OU_PEITO =
            "(?:dor(?: forte)? (?:no|do|em) (?:meu )?(?:peito|torax)|aperto no peito|falta de ar|dificuldade (?:para|de|em) respirar)";
    private static final Pattern NEGADO = Pattern.compile(
            "\\b(?:nao|nunca) (?:tenho|tem|sinto|senti|tive|teve|estou com|esta com) (?:nenhuma? )?"
                    + SINTOMA_RESPIRATORIO_OU_PEITO + "(?: (?:e|ou|nem) (?:nao tenho |nem )?" + SINTOMA_RESPIRATORIO_OU_PEITO + ")*");

    /** Remove do texto já normalizado as frases "não tenho dor no peito / falta de ar". */
    static String semNegacoes(String normalizado) {
        return NEGADO.matcher(normalizado).replaceAll(" ");
    }

    public Optional<Alerta> detectar(String texto) {
        String n = semNegacoes(Texto.normalizar(texto));
        for (Regra r : REGRAS) {
            if (r.padrao().matcher(n).find()) {
                return Optional.of(montar(r));
            }
        }
        return Optional.empty();
    }

    private Alerta montar(Regra r) {
        if (r.tipo() == Tipo.SAUDE_MENTAL) {
            return new Alerta(r.tipo(), r.descricao(), "188",
                    "Você não está sozinho. Ligue agora para o CVV: 188 (gratuito, 24 horas). "
                            + "Se houver risco imediato, ligue 192 (SAMU) ou vá ao pronto atendimento mais próximo.");
        }
        return new Alerta(r.tipo(), r.descricao(), "192",
                "Isso pode ser uma emergência. Ligue agora para o SAMU: 192. Não espere nem vá sozinho se estiver passando mal.");
    }
}
