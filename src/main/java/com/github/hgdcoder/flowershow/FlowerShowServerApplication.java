package com.github.hgdcoder.flowershow;

import java.util.TimeZone;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.github.hgdcoder.flowershow.persistence.mapper")
@EnableScheduling
@SpringBootApplication
public class FlowerShowServerApplication {

    static {
        // The schema stores naive (timezone-less) timestamps and the JDBC layer
        // converts them to/from java.time.Instant using the JVM default zone.
        // Pinning the JVM to UTC keeps stored values, API timestamps and cursor
        // round-trips consistent regardless of the host's timezone.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(FlowerShowServerApplication.class, args);
    }
}
