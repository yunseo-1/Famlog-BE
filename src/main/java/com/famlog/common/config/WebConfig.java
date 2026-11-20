package com.famlog.common.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.famlog.common.security.AuthUserArgumentResolver;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    /** 관리자 페이지·웹 계정 삭제 안내 (정적 파일 static/…/index.html) */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/admin").setViewName("redirect:/admin/");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
        // 웹 계정 삭제 안내 (WEB-01, Google Play 요구)
        registry.addViewController("/account-deletion").setViewName("forward:/account-deletion/index.html");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AuthUserArgumentResolver());
    }
}
