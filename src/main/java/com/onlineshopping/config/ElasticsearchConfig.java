package com.onlineshopping.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

@Configuration
@Profile("!test")
@EnableElasticsearchRepositories(basePackages = "com.onlineshopping.repository.es")
public class ElasticsearchConfig {
}
