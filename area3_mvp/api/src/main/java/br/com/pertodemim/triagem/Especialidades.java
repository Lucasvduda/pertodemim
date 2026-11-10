package br.com.pertodemim.triagem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lista fechada de especialidades da v1. O LLM só pode escolher uma destas. */
public final class Especialidades {

    public static final Especialidade CLINICO = new Especialidade("225125", "Clínico geral");
    public static final Especialidade FAMILIA = new Especialidade("225130", "Médico de família e comunidade");
    public static final Especialidade PEDIATRIA = new Especialidade("225124", "Pediatria");
    public static final Especialidade GINECOLOGIA = new Especialidade("225250", "Ginecologia e obstetrícia");
    public static final Especialidade ORTOPEDIA = new Especialidade("225270", "Ortopedia e traumatologia");
    public static final Especialidade CARDIOLOGIA = new Especialidade("225120", "Cardiologia");
    public static final Especialidade OFTALMOLOGIA = new Especialidade("225265", "Oftalmologia");
    public static final Especialidade OTORRINO = new Especialidade("225275", "Otorrinolaringologia");
    public static final Especialidade DERMATOLOGIA = new Especialidade("225135", "Dermatologia");
    public static final Especialidade PSIQUIATRIA = new Especialidade("225133", "Psiquiatria");
    public static final Especialidade UROLOGIA = new Especialidade("225285", "Urologia");
    public static final Especialidade NEUROLOGIA = new Especialidade("225112", "Neurologia");

    private static final List<Especialidade> TODAS = List.of(CLINICO, FAMILIA, PEDIATRIA, GINECOLOGIA, ORTOPEDIA,
            CARDIOLOGIA, OFTALMOLOGIA, OTORRINO, DERMATOLOGIA, PSIQUIATRIA, UROLOGIA, NEUROLOGIA);

    private static final Map<String, Especialidade> POR_NOME = new LinkedHashMap<>();
    private static final Map<String, Especialidade> POR_CBO = new LinkedHashMap<>();

    static {
        for (Especialidade e : TODAS) {
            POR_NOME.put(Texto.normalizar(e.nome()), e);
            POR_CBO.put(e.cbo(), e);
        }
    }

    private Especialidades() {}

    public static List<Especialidade> todas() { return TODAS; }

    public static Optional<Especialidade> porNome(String nome) {
        return Optional.ofNullable(POR_NOME.get(Texto.normalizar(nome)));
    }

    /**
     * Busca tolerante para filtros digitados por pessoas (ex.: "ortopedia" acha "Ortopedia e traumatologia").
     * Exige pelo menos 3 letras e que exista exatamente uma especialidade que comece com o termo.
     * Não é usada para validar a resposta do LLM (essa continua estrita, por {@link #porNome}).
     */
    public static Optional<Especialidade> porNomeAproximado(String termo) {
        Optional<Especialidade> exata = porNome(termo);
        if (exata.isPresent()) return exata;
        String t = Texto.normalizar(termo);
        if (t.length() < 3) return Optional.empty();
        List<Especialidade> achadas = TODAS.stream()
                .filter(e -> Texto.normalizar(e.nome()).startsWith(t)).toList();
        return achadas.size() == 1 ? Optional.of(achadas.get(0)) : Optional.empty();
    }

    public static Optional<Especialidade> porCbo(String cbo) {
        return Optional.ofNullable(POR_CBO.get(cbo));
    }

    public static boolean contemCbo(String cbo) {
        return POR_CBO.containsKey(cbo);
    }
}
