package br.com.pertodemim.web;

import br.com.pertodemim.security.UsuarioPrincipal;
import br.com.pertodemim.triagem.TriagemOrquestrador;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/triagem")
public class TriagemController {

    private final TriagemOrquestrador orquestrador;

    public TriagemController(TriagemOrquestrador orquestrador) {
        this.orquestrador = orquestrador;
    }

    @PostMapping
    public TriagemResponse triar(@AuthenticationPrincipal UsuarioPrincipal usuario,
                                 @Valid @RequestBody TriagemRequest req) {
        return orquestrador.executar(usuario.id(), req);
    }
}
