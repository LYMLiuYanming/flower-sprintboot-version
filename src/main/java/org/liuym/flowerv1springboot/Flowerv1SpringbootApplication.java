package org.liuym.flowerv1springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @author liuym
 */
@SpringBootApplication
@EnableJpaAuditing
@EnableScheduling
@EnableCaching
public class Flowerv1SpringbootApplication {

    public static void main(String[] args) {
        SpringApplication.run(Flowerv1SpringbootApplication.class, args);
    }

}
