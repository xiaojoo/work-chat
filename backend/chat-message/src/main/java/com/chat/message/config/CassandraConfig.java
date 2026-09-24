package com.chat.message.config;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;

@Configuration
public class CassandraConfig {

    @Value("${cassandra.contact-points:localhost}")
    private String contactPoints;

    @Value("${cassandra.port:9042}")
    private int port;

    @Value("${cassandra.local-datacenter:dc1}")
    private String localDatacenter;

    @Value("${cassandra.keyspace:chat}")
    private String keyspace;

    @Bean
    public CqlSession cqlSession() {
        CqlSessionBuilder builder = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(contactPoints, port))
                .withLocalDatacenter(localDatacenter);

        if (keyspace != null && !keyspace.isEmpty()) {
            builder.withKeyspace(keyspace);
        }

        return builder.build();
    }
}
