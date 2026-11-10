package br.com.pertodemim.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CsvSimplesTest {

    private static ByteArrayInputStream csv(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void leCabecalhoELinhas() throws Exception {
        var l = CsvSimples.ler(csv("a,b,c\n1,2,3\n4,5,6\n"));
        assertThat(l).hasSize(2);
        assertThat(l.get(0)).containsEntry("a", "1").containsEntry("c", "3");
        assertThat(l.get(1)).containsEntry("b", "5");
    }

    @Test
    void trataAspasEVirgulasDentroDeAspas() throws Exception {
        var l = CsvSimples.ler(csv("nome,tipo\n\"Hospital, Central \"\"SP\"\"\",HOSPITAL\n"));
        assertThat(l.get(0)).containsEntry("nome", "Hospital, Central \"SP\"").containsEntry("tipo", "HOSPITAL");
    }

    @Test
    void ignoraLinhasVaziasEBom() throws Exception {
        var l = CsvSimples.ler(csv("\uFEFFa,b\n\n1,2\n"));
        assertThat(l).hasSize(1);
        assertThat(l.get(0)).containsEntry("a", "1");
    }

    @Test
    void colunasAusentesViramVazio() throws Exception {
        var l = CsvSimples.ler(csv("a,b,c\n1\n"));
        assertThat(l.get(0)).containsEntry("b", "").containsEntry("c", "");
    }
}
