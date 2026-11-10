package br.com.pertodemim;

import br.com.pertodemim.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class PertoDeMimApplication {
    public static void main(String[] args) {
        SpringApplication.run(PertoDeMimApplication.class, args);
    }
}
