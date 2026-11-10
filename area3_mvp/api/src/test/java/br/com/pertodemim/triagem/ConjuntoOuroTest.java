package br.com.pertodemim.triagem;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.Urgencia;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Avalia as regras contra as 40 frases do conjunto-ouro (area1_ia/frases_ouro.csv).
 * Limites mínimos: vermelho com recall de 100% (nunca deixar uma emergência passar), especialidade/serviço
 * e urgência com piso de acerto. O resultado também é gravado em target/ para o relatório da Área 1.
 */
class ConjuntoOuroTest {

    record Caso(int id, String texto, String servico, String cbo, String urgencia) {}

    private static final Path CSV = Path.of("..", "..", "area1_ia", "frases_ouro.csv");

    static List<Caso> carregar() throws IOException {
        List<Caso> casos = new ArrayList<>();
        List<String> linhas = Files.readAllLines(CSV, StandardCharsets.UTF_8);
        for (String l : linhas.subList(1, linhas.size())) {
            if (l.isBlank()) continue;
            String[] c = l.split(",", -1);
            casos.add(new Caso(Integer.parseInt(c[0]), c[1], c[2], c[3], c[4]));
        }
        return casos;
    }

    private final TriagemService servico =
            new TriagemService(new RedFlagDetector(), new RegrasClassificadorFachada().get(), new NenhumProvider(), new Anonimizador());

    /** Pequeno invólucro só para deixar explícito que usamos o classificador padrão. */
    static class RegrasClassificadorFachada {
        RegrasClassificador get() { return new RegrasClassificador(); }
    }

    @Test
    void arquivoTem40FrasesComTodosOsCamposValidos() throws IOException {
        List<Caso> casos = carregar();
        assertThat(casos).hasSize(40);
        assertThat(casos).extracting(Caso::id).doesNotHaveDuplicates();
        for (Caso c : casos) {
            assertThat(c.texto()).isNotBlank();
            assertThat(List.of("saude", "documento", "assistencia_social")).contains(c.servico());
            assertThat(List.of("vermelho", "laranja", "amarelo", "verde", "azul")).contains(c.urgencia());
            if (!c.cbo().isEmpty()) assertThat(Especialidades.contemCbo(c.cbo())).as("cbo do caso %d", c.id()).isTrue();
        }
        assertThat(casos.stream().filter(c -> c.urgencia().equals("vermelho")).count()).isGreaterThanOrEqualTo(7);
        assertThat(casos.stream().map(Caso::servico).distinct()).hasSize(3);
    }

    @Test
    void metricasDasRegrasNoConjuntoOuro() throws IOException {
        List<Caso> casos = carregar();
        int acertoUrg = 0, acertoUrgMaisMenos1 = 0, acertoEsp = 0, totalEsp = 0, acertoServ = 0;
        int vermelhosEsperados = 0, vermelhosAcertados = 0, falsosVermelhos = 0;
        StringBuilder csv = new StringBuilder("id,texto,esperado_servico,obtido_servico,esperado_cbo,obtido_cbo,esperado_urgencia,obtida_urgencia,caminho\n");
        List<String> erros = new ArrayList<>();

        for (Caso c : casos) {
            ResultadoTriagem r = servico.triar(c.texto());
            String servObtido = r.servico().name().toLowerCase(Locale.ROOT);
            String urgObtida = r.urgencia().name().toLowerCase(Locale.ROOT);
            String cboObtido = r.especialidade() == null ? "" : r.especialidade().cbo();

            if (servObtido.equals(c.servico())) acertoServ++;
            if (urgObtida.equals(c.urgencia())) acertoUrg++;
            else erros.add("urgência #" + c.id() + " '" + c.texto() + "': esperado " + c.urgencia() + ", obtido " + urgObtida);
            if (Math.abs(Urgencia.valueOf(urgObtida.toUpperCase(Locale.ROOT)).ordinal()
                    - Urgencia.valueOf(c.urgencia().toUpperCase(Locale.ROOT)).ordinal()) <= 1) acertoUrgMaisMenos1++;
            if (!c.cbo().isEmpty()) {
                totalEsp++;
                if (cboObtido.equals(c.cbo())) acertoEsp++;
                else erros.add("especialidade #" + c.id() + " '" + c.texto() + "': esperado " + c.cbo() + ", obtido " + cboObtido);
            }
            if (c.urgencia().equals("vermelho")) {
                vermelhosEsperados++;
                if (r.urgencia() == Urgencia.VERMELHO) vermelhosAcertados++;
            } else if (r.urgencia() == Urgencia.VERMELHO) {
                falsosVermelhos++;
            }
            csv.append(c.id()).append(",").append(c.texto()).append(",").append(c.servico()).append(",").append(servObtido)
                    .append(",").append(c.cbo()).append(",").append(cboObtido).append(",").append(c.urgencia()).append(",")
                    .append(urgObtida).append(",").append(r.caminho().name().toLowerCase(Locale.ROOT)).append("\n");
        }

        double recallVermelho = (double) vermelhosAcertados / vermelhosEsperados;
        double accUrg = (double) acertoUrg / casos.size();
        double accEsp = (double) acertoEsp / totalEsp;
        double accServ = (double) acertoServ / casos.size();

        String resumo = String.format(Locale.ROOT,
                "frases=%d servico=%.1f%% especialidade=%.1f%% (%d/%d) urgencia_exata=%.1f%% urgencia_+-1=%.1f%% recall_vermelho=%.1f%% (%d/%d) falsos_vermelhos=%d",
                casos.size(), accServ * 100, accEsp * 100, acertoEsp, totalEsp, accUrg * 100,
                100.0 * acertoUrgMaisMenos1 / casos.size(), recallVermelho * 100, vermelhosAcertados, vermelhosEsperados, falsosVermelhos);
        System.out.println("CONJUNTO-OURO REGRAS: " + resumo);
        erros.forEach(e -> System.out.println("  divergência: " + e));

        Path saida = Path.of("target", "conjunto_ouro_regras.csv");
        Files.createDirectories(saida.getParent());
        Files.writeString(saida, csv.toString(), StandardCharsets.UTF_8);
        Files.writeString(Path.of("target", "conjunto_ouro_regras_resumo.txt"), resumo + "\n" + String.join("\n", erros) + "\n",
                StandardCharsets.UTF_8);

        // Segurança primeiro: nenhuma emergência pode escapar.
        assertThat(recallVermelho).as("recall de vermelho").isEqualTo(1.0);
        assertThat(falsosVermelhos).as("falsos vermelhos").isLessThanOrEqualTo(1);
        assertThat(accServ).as("serviço").isGreaterThanOrEqualTo(0.95);
        assertThat(accEsp).as("especialidade").isGreaterThanOrEqualTo(0.80);
        assertThat(accUrg).as("urgência exata").isGreaterThanOrEqualTo(0.70);
        assertThat((double) acertoUrgMaisMenos1 / casos.size()).as("urgência ±1").isGreaterThanOrEqualTo(0.90);
        assertThat(Servico.values()).hasSize(3);
    }
}
