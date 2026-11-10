package br.com.pertodemim.triagem;

import br.com.pertodemim.domain.Caminho;
import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.Urgencia;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Triagem em 3 camadas:
 * 1) red flags (regras) → vermelho/192, nunca depende do LLM;
 * 2) classificador (LLM, se configurado, ou regras);
 * 3) validação da resposta e fallback por regras (sempre disponível).
 */
@Service
public class TriagemService {

    private final RedFlagDetector redFlags;
    private final RegrasClassificador regras;
    private final LlmProvider provider;
    private final Anonimizador anonimizador;

    public TriagemService(RedFlagDetector redFlags, RegrasClassificador regras, LlmProvider provider,
                          Anonimizador anonimizador) {
        this.redFlags = redFlags;
        this.regras = regras;
        this.provider = provider;
        this.anonimizador = anonimizador;
    }

    public String providerAtivo() {
        return provider.nome();
    }

    public ResultadoTriagem triar(String texto) {
        String anon = anonimizador.anonimizar(texto);

        // Sinais de emergência são procurados no texto ORIGINAL (a análise é local, nada sai do servidor):
        // a anonimização pode apagar palavras da frase e esconder a gravidade.
        Optional<RedFlagDetector.Alerta> alerta = redFlags.detectar(texto);
        if (alerta.isPresent()) {
            RedFlagDetector.Alerta a = alerta.get();
            return new ResultadoTriagem(Servico.SAUDE, null, Urgencia.VERMELHO, a.mensagem(), null,
                    Caminho.RED_FLAG, a.telefone(), anon);
        }

        Classificacao porRegras = regras.classificar(anon);
        if (!provider.ativo()) {
            return montar(porRegras, Caminho.REGRAS, anon);
        }
        Optional<Classificacao> porLlm = provider.classificar(anon);
        if (porLlm.isEmpty()) {
            return montar(porRegras, Caminho.FALLBACK, anon);
        }
        return montar(combinar(porLlm.get(), porRegras), Caminho.LLM, anon);
    }

    /**
     * Segurança: se as regras reconheceram algo de urgência alta (laranja ou pior), o LLM não pode
     * rebaixar essa urgência.
     */
    private Classificacao combinar(Classificacao llm, Classificacao regras) {
        if (regras.generica() || regras.urgencia().ordinal() > Urgencia.LARANJA.ordinal()) return llm;
        Urgencia u = Urgencia.maisGrave(llm.urgencia(), regras.urgencia());
        if (u == llm.urgencia()) return llm;
        return new Classificacao(llm.servico(), llm.especialidade(), u,
                Orientacoes.montar(llm.servico(), llm.especialidade(), u), llm.perguntaAdicional());
    }

    private ResultadoTriagem montar(Classificacao c, Caminho caminho, String anon) {
        if (c.urgencia() == Urgencia.VERMELHO) { // o LLM pode apontar gravidade que as regras não viram
            return new ResultadoTriagem(c.servico(), c.especialidade(), Urgencia.VERMELHO,
                    "Isso pode ser uma emergência. Ligue agora para o SAMU: 192.", c.perguntaAdicional(),
                    caminho, "192", anon);
        }
        return new ResultadoTriagem(c.servico(), c.especialidade(), c.urgencia(), c.orientacao(),
                c.perguntaAdicional(), caminho, null, anon);
    }
}
