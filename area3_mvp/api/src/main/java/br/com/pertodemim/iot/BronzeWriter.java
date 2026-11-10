package br.com.pertodemim.iot;

import br.com.pertodemim.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Camada bronze do data lake: grava cada evento bruto em JSON Lines, somente anexando (append),
 * particionado por dataset e data: {@code bronze/<dataset>/AAAA-MM-DD/api.jsonl}.
 */
@Component
public class BronzeWriter {

    private static final Logger log = LoggerFactory.getLogger(BronzeWriter.class);

    private final Path base;
    private final ObjectMapper mapper;

    public BronzeWriter(AppProperties props, ObjectMapper mapper) {
        this.base = Path.of(props.getBronze().getDir()).toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    public Path getBase() { return base; }

    /** Anexa um registro. Falha de gravação não derruba a requisição: apenas registra o erro. */
    public synchronized void anexar(String dataset, LocalDate data, Map<String, Object> registro) {
        try {
            Path dir = base.resolve(dataset).resolve(data.toString());
            Files.createDirectories(dir);
            Map<String, Object> linha = new LinkedHashMap<>(registro);
            linha.put("origem", "api");
            String json = mapper.writeValueAsString(linha) + "\n";
            Files.writeString(dir.resolve("api.jsonl"), json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Falha ao gravar no bronze ({}): {}", dataset, e.getMessage());
        }
    }
}
