package br.com.pertodemim.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.pertodemim.support.IntegrationTestBase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Teste de estrutura do front-end: telas, acessibilidade básica e regras da CSP (sem script inline, sem CDN). */
class FrontEstruturaTest extends IntegrationTestBase {

    private static final Path STATIC = Path.of("src", "main", "resources", "static");

    private static String ler(String nome) throws IOException {
        return Files.readString(STATIC.resolve(nome), StandardCharsets.UTF_8);
    }

    @Test
    void paginaInicialEPublicaEServidaComCsp() throws Exception {
        mvc.perform(get("/")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PertoDeMim")))
                .andExpect(header().exists("Content-Security-Policy"));
        mvc.perform(get("/app.js")).andExpect(status().isOk());
        mvc.perform(get("/styles.css")).andExpect(status().isOk());
    }

    @Test
    void temTodasAsTelasDoFluxo() throws Exception {
        String html = ler("index.html");
        for (String tela : new String[]{"entrada", "triagem", "emergencia", "resultado", "unidades", "agendar", "agendamentos", "painel"}) {
            assertThat(html).as("tela " + tela).contains("id=\"tela-" + tela + "\"");
        }
        assertThat(html).contains("tel:192").contains("192 (SAMU)");
    }

    @Test
    void htmlSemScriptInlineSemHandlersInlineESemRecursoExterno() throws Exception {
        String html = ler("index.html");
        // único <script> permitido: o do próprio domínio, sem corpo
        var scripts = Pattern.compile("<script\\b[^>]*>([\\s\\S]*?)</script>").matcher(html);
        int total = 0;
        while (scripts.find()) {
            total++;
            assertThat(scripts.group(0)).contains("src=\"/app.js\"");
            assertThat(scripts.group(1).trim()).isEmpty();
        }
        assertThat(total).isEqualTo(1);
        assertThat(html).doesNotContainPattern("\\son[a-z]+\\s*=");   // onclick=, onload=...
        assertThat(html).doesNotContain("javascript:");
        assertThat(html).doesNotContainPattern("(src|href)=\"https?://");
        assertThat(ler("styles.css")).doesNotContain("@import").doesNotContainPattern("url\\(\\s*['\"]?https?:");
    }

    @Test
    void jsNaoUsaInnerHtmlNemEvalNemDocumentWrite() throws Exception {
        String js = ler("app.js");
        assertThat(js).doesNotContain("innerHTML").doesNotContain("outerHTML").doesNotContain("insertAdjacentHTML")
                .doesNotContain("eval(").doesNotContain("document.write").doesNotContain("new Function");
        assertThat(js).doesNotContainPattern("https?://");
    }

    @Test
    void acessibilidadeBasica() throws Exception {
        String html = ler("index.html");
        assertThat(html).contains("<html lang=\"pt-BR\"").contains("name=\"viewport\"").contains("class=\"pular\"");
        assertThat(html).contains("aria-live").contains("role=\"alert\"");
        // todo campo de formulário tem rótulo associado
        var campos = Pattern.compile("<(input|select|textarea)\\b[^>]*\\sid=\"([^\"]+)\"[^>]*>").matcher(html);
        while (campos.find()) {
            String id = campos.group(2);
            if (campos.group(0).contains("type=\"checkbox\"")) continue; // checkbox fica dentro de <label>
            assertThat(html).as("rótulo de " + id).contains("for=\"" + id + "\"");
        }
        // imagens (se houver) com alt
        var imgs = Pattern.compile("<img\\b[^>]*>").matcher(html);
        while (imgs.find()) assertThat(imgs.group()).contains("alt=");
    }

    @Test
    void avisoDeLimitacoesVisivelNoRodape() throws Exception {
        String html = ler("index.html");
        assertThat(html).contains("simulados").contains("não faz diagnóstico");
    }
}
