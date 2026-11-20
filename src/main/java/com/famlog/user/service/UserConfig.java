package com.famlog.user.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ ConsentProperties.class, AccountProperties.class })
public class UserConfig {
}
