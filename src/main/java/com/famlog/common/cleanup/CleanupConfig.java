package com.famlog.common.cleanup;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CleanupProperties.class)
public class CleanupConfig {
}
