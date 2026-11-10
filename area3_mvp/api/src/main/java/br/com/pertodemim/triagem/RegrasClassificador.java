package br.com.pertodemim.triagem;

import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.Urgencia;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Classificador por palavras-chave (dicionário). Sempre disponível: é o padrão do projeto e o
 * fallback quando o LLM falha. Trabalha sobre o texto normalizado (minúsculas, sem acento).
 */
@Component
public class RegrasClassificador {

    private record Escalada(Pattern padrao, Urgencia urgencia) {}

    private record Regra(String nome, Servico servico, Especialidade esp, Pattern padrao, Urgencia base,
                         int prioridade, List<Escalada> escaladas, String pergunta) {}

    private static final Pattern FORTE = Pattern.compile(
            "(muito|bem|extremamente) forte|insuportavel|nao aguento|piorando|cada vez pior|dor intensa|muita dor|dor forte");
    private static final Pattern ROTINA = Pattern.compile(
            "(ha|faz|tem) (meses|anos|muito tempo)|de rotina|so (pra|para) (ver|consultar|rever)|nao e urgente|sem pressa");

    private static final String PERGUNTA_PADRAO = "Pode contar um pouco mais sobre o que você está sentindo ou precisando?";

    private static final List<Regra> REGRAS = new ArrayList<>();

    private static Pattern p(String regex) {
        return Pattern.compile(regex);
    }

    private static Escalada esc(String regex, Urgencia u) {
        return new Escalada(p(regex), u);
    }

    private static void add(String nome, Servico s, Especialidade e, String regex, Urgencia base, int prio,
                            String pergunta, Escalada... escaladas) {
        REGRAS.add(new Regra(nome, s, e, p(regex), base, prio, List.of(escaladas), pergunta));
    }

    static {
        add("documento", Servico.DOCUMENTO, null,
                "\\b(rg|identidade|certidao|segunda via|carteira de trabalho|ctps|titulo de eleitor|cartao (do )?sus|cartao nacional de saude|cpf|passaporte|cnh|carteira de motorista|comprovante de residencia|documentos?)\\b",
                Urgencia.AZUL, 80, null);
        add("assistencia social", Servico.ASSISTENCIA_SOCIAL, null,
                "assistente social|assistencia social|\\bcras\\b|\\bcreas\\b|bolsa familia|cadunico|cadastro unico|cesta basica|auxilio (brasil|emergencial|aluguel)|beneficio|\\bbpc\\b|\\bloas\\b|visita (domiciliar|da assistencia|do cras)|idoso (sozinho|abandonado|sem cuidador|precisando de cuidado)|situacao de rua|morador de rua|passando fome|sem comida|nao tenho o que comer|violencia (domestica|contra)|abandono|maus[- ]tratos|trabalho infantil|sem moradia|despejo"
                        + "|\\bfome\\b|(falta|faltou|faltando) comida|sem (renda|emprego|dinheiro)|desempreg|\\baluguel\\b|\\bcreche\\b|\\bauxilio\\b",
                Urgencia.AZUL, 80, null,
                esc("passando fome|\\bfome\\b|(falta|faltou|faltando) comida|sem comida|nao tenho o que comer|sem (renda|emprego|dinheiro)|desempreg|violencia|situacao de rua|abandonad|maus[- ]tratos|despejo|apanhand", Urgencia.AMARELO));

        add("trauma ortopedico", Servico.SAUDE, Especialidades.ORTOPEDIA,
                "(quebr(ei|ou|ado|ada|ar|aram)|fratur|fissur(a|ei)|trinc(ou|ei)|torci|torceu|torcao|entorse|luxa|desloc(ou|ei)|esmag|machuc(ou|ei)|bati)[^.]{0,40}(perna|braco|\\bpe\\b|mao|tornozelo|punho|clavicula|costela|dedo|osso|joelho|ombro|cotovelo|coxa|quadril|bacia|calcanhar|canela)"
                        + "|(perna|braco|\\bpe\\b|mao|tornozelo|punho|dedo|joelho|ombro|clavicula|costela)[^.]{0,20}(torto|torta|entortou|quebrou|quebrado|quebrada|deformad\\w*)"
                        + "|fratura|entorse|luxacao|osso quebrado|caiu[^.]{0,40}(perna|braco|\\bpe\\b|mao|joelho|tornozelo|ombro|quadril)"
                        + "|nao consigo (apoiar|pisar|andar)[^.]{0,20}(perna|\\bpe\\b)?",
                Urgencia.AMARELO, 90, "Você consegue apoiar o pé no chão ou mexer o local machucado?",
                esc("quebr|fratur|osso|deformad|torto|torta|nao consigo (apoiar|pisar|andar)|inchou muito|ficou roxo", Urgencia.LARANJA));
        add("pediatria", Servico.SAUDE, Especialidades.PEDIATRIA,
                "\\b(bebe|bebes|recem[- ]nascid\\w*|crianca|criancas|pediatr\\w*|menininh[oa]|filhinh[oa])\\b|(meu|minha) (filho|filha|fio|fia|neto|neta|sobrinho|sobrinha|cacula)|vacina (do|da) (bebe|crianca|meu filho|minha filha)",
                Urgencia.VERDE, 60, "Qual a idade da criança e há quantos dias está assim?",
                esc("febre|vomit|diarreia|desidrat|nao (quer )?(mamar|comer)|prostrad|molinho|chiado|tosse (forte|seca)|dor de ouvido", Urgencia.AMARELO),
                esc("vacina|vacinacao|carteirinha|puericultura|peso e altura", Urgencia.AZUL));
        add("ginecologia", Servico.SAUDE, Especialidades.GINECOLOGIA,
                "gravida|gestante|gravidez|gestacao|pre[- ]?natal|menstrua|regra (atrasada|atrasou|desceu)|corrimento|preventivo|papanicolau|colpo|nodulo (na|no) (mama|seio)|menopausa|anticoncepcional|\\bdiu\\b|ultrassom obstetrico|colica menstrual|ginecolog\\w*|obstetr\\w*|mamografia|cisto no ovario|ovario|\\butero\\b|vagina|vaginal",
                Urgencia.VERDE, 55, "Há quantas semanas de gravidez ou desde quando você sente isso?",
                esc("dor (forte|intensa)|muita dor|dor no baixo ventre", Urgencia.AMARELO),
                esc("preventivo|papanicolau|mamografia|anticoncepcional|\\bdiu\\b|de rotina", Urgencia.AZUL));
        add("oftalmologia", Servico.SAUDE, Especialidades.OFTALMOLOGIA,
                "\\bolhos?\\b|visao|enxerg\\w*|vista (embacada|turva|escura)|oculos|grau (do|dos)|conjuntivite|lacrimej\\w*|cisco|tercol|catarata|glaucoma|oftalm\\w*|pupila|palpebra",
                Urgencia.VERDE, 50, "A visão piorou de repente ou foi após uma pancada ou produto no olho?",
                esc("perdi a visao|perdendo a visao|nao enxergo (nada|mais)|visao (turva|escura) de repente|entrou (produto|acido|quimico|veneno|agua sanitaria)|pancada no olho|dor (forte|intensa) no olho|enxergando (vulto|clarao|mancha)|cortina", Urgencia.LARANJA),
                esc("olho vermelho|vermelho e ardendo|ardendo|ardencia|dor no olho|secrecao|remela", Urgencia.AMARELO));
        add("otorrinolaringologia", Servico.SAUDE, Especialidades.OTORRINO,
                "ouvido|otite|surdez|nao escuto|escuto mal|ouvindo mal|zumbido|amigdala|sinusite|\\bnariz\\b|rinite|rouquidao|adenoide|sangramento nasal|nariz sangrando|ronco|apneia|catarro (no|na) (nariz|garganta)",
                Urgencia.VERDE, 50, null,
                esc("dor (forte|intensa|insuportavel)|dor de ouvido (forte|intensa)|secrecao|\\bpus\\b|febre|sangr", Urgencia.AMARELO));
        add("dermatologia", Servico.SAUDE, Especialidades.DERMATOLOGIA,
                "\\bpele\\b|mancha|coceira|alergia (na|de) pele|espinha|\\bacne\\b|micose|verruga|ferida (que )?(nao cicatriza|abrindo)|queda de cabelo|\\bpinta\\b|sarna|piolho|urticaria|caroco (na|no) (pele|corpo)|dermat\\w*|eczema|psoriase|caspa|assadura|unha encravada|frieira|brotoeja|empola|bolinhas? (na|no|pelo) (pele|corpo)",
                Urgencia.VERDE, 50, null,
                esc("inchad\\w*|vermelh\\w* (e )?quente|\\bpus\\b|febre|espalhando|bolhas|dor (forte|intensa)|celulite|erisipela|sangr\\w*|cresceu|mudou de (cor|forma|tamanho)", Urgencia.AMARELO),
                esc("espinha|\\bacne\\b|caspa|verruga|queda de cabelo", Urgencia.AZUL));
        add("psiquiatria", Servico.SAUDE, Especialidades.PSIQUIATRIA,
                "ansiedad\\w*|ansios\\w*|depress\\w*|panico|insonia|nao (consigo|estou conseguindo) dormir|tristeza|\\btriste\\b|sem vontade de nada|desanimad\\w*|desanimo|psiquiatr\\w*|psicolog\\w*|estresse|estress\\w*|medo de tudo|remedio controlado|alucin\\w*|ouvindo vozes|ouco vozes|vozes na cabeca|bipolar|esquizo\\w*|crise de choro|nao tenho vontade de nada|burnout|compulsao|\\btoc\\b|dependencia (quimica|de alcool|de drogas)|alcoolismo|usando drogas|\\bcrack\\b",
                Urgencia.VERDE, 50, "Há quanto tempo você se sente assim e isso atrapalha o dia a dia?",
                esc("crise de (panico|ansiedade)|agress\\w*|nao saio da cama", Urgencia.AMARELO),
                esc("alucin\\w*|vozes|paranoi\\w*|perseguid\\w*", Urgencia.LARANJA));
        add("urologia", Servico.SAUDE, Especialidades.UROLOGIA,
                "urina|urinar|xixi|ardencia|bexiga|prostata|testiculo|escroto|pedra no rim|calculo|colica renal|\\brins?\\b|infeccao urinaria|impoten\\w*|disfuncao eretil|\\bpenis\\b|vasectomia|fimose|jato fraco|incontinencia|urologi\\w*",
                Urgencia.VERDE, 50, null,
                esc("colica renal|pedra no rim|calculo|dor (forte|intensa)|dor ao urinar|ardencia|infeccao urinaria|sangue na urina|febre|testiculo (inchado|doendo)|dor (no|nos) testiculo", Urgencia.AMARELO),
                esc("nao consigo (urinar|fazer xixi)|torcao testicular|dor (forte|intensa)? ?(no|nos) testiculo", Urgencia.LARANJA));
        add("neurologia", Servico.SAUDE, Especialidades.NEUROLOGIA,
                "dor de cabeca|cefaleia|enxaqueca|tontura|vertigem|formigamento|dormencia|dormente|tremor|epilepsia|esquecimento|esquecendo|perda de memoria|neurolog\\w*|nevralgia|desequilibrio|labirintite",
                Urgencia.VERDE, 50, "A dor ou o sintoma começou de repente ou vem piorando aos poucos?",
                esc("formigamento|dormencia|vertigem|visao dupla|desequilibrio|esquecendo|tontura (forte|constante|ha dias)|dor de cabeca (forte|intensa|todo dia|ha dias)", Urgencia.AMARELO));
        add("cardiologia", Servico.SAUDE, Especialidades.CARDIOLOGIA,
                "pressao (alta|baixa|subiu|descontrolada)|hipertens\\w*|palpitac\\w*|coracao (acelerad\\w*|dispar\\w*|batendo)|taquicardia|arritmia|colesterol|cardiolog\\w*|marca[- ]?passo|inchaco nas pernas|batimento",
                Urgencia.VERDE, 50, "Você sente tontura, falta de ar ou dor no peito junto?",
                esc("palpitac\\w*|acelerad\\w*|dispar\\w*|tontura|dor de cabeca|visao turva|18 por|20 por|muito alta|batimento", Urgencia.AMARELO));
        add("ortopedia (dor ou queixa crônica)", Servico.SAUDE, Especialidades.ORTOPEDIA,
                "joelho|coluna|dor nas costas|lombar|hernia de disco|tendinite|tendao|ombro|artrose|artrite|reumat\\w*|bursite|cervical|dor (no|na) (quadril|perna|pe|braco|mao|punho)|cotovelo|torticolo|ciatic\\w*|dor muscular|dor nas juntas|juntas",
                Urgencia.VERDE, 50, "A dor começou depois de uma queda ou batida?");
        add("clínico geral", Servico.SAUDE, Especialidades.CLINICO,
                "febre|gripe|resfriad\\w*|tosse|garganta|diarreia|vomit\\w*|enjoo|nausea|dor de barriga|dor (no|de) estomago|estomago|azia|gastrite|diabet\\w*|acucar (alto|no sangue)|glicemia|covid|dengue|virose|mal[- ]estar|cansaco|fraqueza|indisposicao|dor no corpo|corpo (todo )?doendo|infeccao|inchaco|anemia|exame de sangue|dor abdominal|barriga|coriza|catarro",
                Urgencia.VERDE, 30, "Há quantos dias você está assim e teve febre?",
                esc("febre (alta|de 39|de 40|ha dias|persistente)|febre[^.]{0,30}dor no corpo|dor no corpo[^.]{0,30}febre|dor de barriga[^.]{0,30}(enjoo|nausea|vomit\\w*|febre)|(enjoo|nausea|vomit\\w*)[^.]{0,30}dor de barriga|vomit\\w* (muito|toda hora|varias vezes)|diarreia (forte|intensa|ha dias)|desidrat\\w*|dengue|dor (forte|intensa) (na|de) barriga|dor abdominal (forte|intensa)|manchas vermelhas|dor atras dos olhos|nao consigo comer|sem urinar", Urgencia.AMARELO));
        add("médico de família (rotina)", Servico.SAUDE, Especialidades.FAMILIA,
                "rotina|check[- ]?up|renovar (a )?receita|receita (acabou|de remedio)|acompanhamento (da familia|medico)|consulta de rotina|meu posto|agente de saude|saude da familia|encaminhamento|pedir exame|atestado",
                Urgencia.AZUL, 20, null);
    }

    public Classificacao classificar(String texto) {
        String n = RedFlagDetector.semNegacoes(Texto.normalizar(texto));
        Regra escolhida = null;
        for (Regra r : REGRAS) {
            if (r.padrao().matcher(n).find() && (escolhida == null || r.prioridade() > escolhida.prioridade())) {
                escolhida = r;
            }
        }
        if (escolhida == null) {
            return new Classificacao(Servico.SAUDE, Especialidades.CLINICO, Urgencia.VERDE,
                    Orientacoes.montar(Servico.SAUDE, Especialidades.CLINICO, Urgencia.VERDE), PERGUNTA_PADRAO, true);
        }
        Urgencia u = escolhida.base();
        boolean escalou = false;
        Urgencia severa = null;
        boolean leve = false;
        for (Escalada e : escolhida.escaladas()) {
            if (!e.padrao().matcher(n).find()) continue;
            if (e.urgencia() == Urgencia.AZUL) leve = true;
            else severa = severa == null ? e.urgencia() : Urgencia.maisGrave(severa, e.urgencia());
        }
        if (severa != null) {
            u = Urgencia.maisGrave(u, severa);
            escalou = true;
        } else if (leve) {
            u = Urgencia.AZUL;
        }
        if (escolhida.servico() == Servico.SAUDE) {
            if (FORTE.matcher(n).find() && u.ordinal() >= Urgencia.AMARELO.ordinal()) {
                u = Urgencia.values()[u.ordinal() - 1];
                escalou = true;
            } else if (!escalou && ROTINA.matcher(n).find() && u == Urgencia.VERDE) {
                u = Urgencia.AZUL;
            }
        }
        Especialidade esp = escolhida.servico() == Servico.SAUDE ? escolhida.esp() : null;
        return new Classificacao(escolhida.servico(), esp, u, Orientacoes.montar(escolhida.servico(), esp, u),
                escolhida.pergunta(), false);
    }
}
