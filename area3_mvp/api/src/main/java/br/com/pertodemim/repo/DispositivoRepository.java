package br.com.pertodemim.repo;

import br.com.pertodemim.domain.Dispositivo;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DispositivoRepository extends JpaRepository<Dispositivo, Long> {

    @Query("select d from Dispositivo d join fetch d.unidade where d.chaveHash = :hash")
    Optional<Dispositivo> findPorChaveHash(@Param("hash") String hash);
}
