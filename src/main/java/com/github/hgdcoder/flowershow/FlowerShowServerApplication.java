package com.github.hgdcoder.flowershow;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.github.hgdcoder.flowershow.persistence.mapper")
@EnableScheduling
@SpringBootApplication
public class FlowerShowServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowerShowServerApplication.class, args);
    }
}
