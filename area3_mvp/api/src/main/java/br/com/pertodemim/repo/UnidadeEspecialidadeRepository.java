package br.com.pertodemim.repo;

import br.com.pertodemim.domain.UnidadeEspecialidade;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UnidadeEspecialidadeRepository extends JpaRepository<UnidadeEspecialidade, Long> {

    @Query("select ue from UnidadeEspecialidade ue join fetch ue.unidade where ue.cbo = :cbo")
    List<UnidadeEspecialidade> findPorCbo(@Param("cbo") String cbo);

    @Query("select ue from UnidadeEspecialidade ue where ue.unidade.id = :unidadeId order by ue.especialidade")
    List<UnidadeEspecialidade> findPorUnidade(@Param("unidadeId") Long unidadeId);

    @Query("select ue from UnidadeEspecialidade ue where ue.unidade.id = :unidadeId and ue.cbo = :cbo")
    Optional<UnidadeEspecialidade> findPorUnidadeECbo(@Param("unidadeId") Long unidadeId, @Param("cbo") String cbo);
}
