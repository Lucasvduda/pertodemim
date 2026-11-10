package br.com.pertodemim.repo;

import br.com.pertodemim.domain.Servico;
import br.com.pertodemim.domain.ServicoUnidade;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServicoUnidadeRepository extends JpaRepository<ServicoUnidade, Long> {

    @Query("select s from ServicoUnidade s join fetch s.unidade where s.tipo = :tipo and s.ativo = true")
    List<ServicoUnidade> findAtivosPorTipo(@Param("tipo") Servico tipo);

    @Query("select count(s) > 0 from ServicoUnidade s where s.unidade.id = :unidadeId and s.tipo = :tipo and s.ativo = true")
    boolean existeAtivo(@Param("unidadeId") Long unidadeId, @Param("tipo") Servico tipo);
}
