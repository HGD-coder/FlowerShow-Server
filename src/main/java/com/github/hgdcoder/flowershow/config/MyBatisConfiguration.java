package com.github.hgdcoder.flowershow.config;

import java.util.Properties;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MyBatisConfiguration {

    @Bean
    DatabaseIdProvider databaseIdProvider() {
        VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
        Properties aliases = new Properties();
        aliases.setProperty("PostgreSQL", "postgresql");
        aliases.setProperty("H2", "h2");
        provider.setProperties(aliases);
        return provider;
    }
}
