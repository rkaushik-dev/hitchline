package io.github.rkaushikdev.hitchline.http;

import io.github.rkaushikdev.hitchline.config.Config;
import io.github.rkaushikdev.hitchline.config.LogLevel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves one {@link HttpApp} over an embedded servlet container. The application's routes, error
 * mapping, lifecycle filter and converters are handed to Spring as they are, so the server answers
 * exactly as the application does under test. Nothing is found by scanning, and Spring's own error
 * page is switched off, so a request no route takes reaches the application's 404.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = ErrorMvcAutoConfiguration.class)
public class ApiApplication {

    /**
     * Starts serving {@code app} on the configuration's host and port; port 0 picks a free one. The
     * caller closes the returned context.
     */
    public static ConfigurableApplicationContext serve(HttpApp app, Config config) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server.address", config.host());
        properties.put("server.port", config.port());
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.web.resources.add-mappings", false);
        properties.put("spring.mvc.problemdetails.enabled", false);
        properties.put("logging.level.io.github.rkaushikdev.hitchline", loggingLevel(config.logLevel()));

        return new SpringApplicationBuilder(ApiApplication.class)
                .initializers(context -> {
                    var beans = context.getBeanFactory();
                    beans.registerSingleton("httpApp", app);
                    beans.registerSingleton("problemAdvice", app.errors());
                    List<Object> routes = app.routes();
                    for (int index = 0; index < routes.size(); index += 1) {
                        beans.registerSingleton("route" + index, routes.get(index));
                    }
                })
                .properties(properties)
                .registerShutdownHook(false)
                .run();
    }

    /** The port a started context is listening on. */
    public static int portOf(ConfigurableApplicationContext context) {
        return ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private static String loggingLevel(LogLevel level) {
        return level == LogLevel.SILENT ? "OFF" : level.wire().toUpperCase(Locale.ROOT);
    }

    /** The application's lifecycle around every request. */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> hitchlineLifecycle(HttpApp app) {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(app.lifecycle());
        registration.setOrder(0);
        return registration;
    }

    /** The application's converters, in place of Spring's defaults. */
    @Bean
    WebMvcConfigurer hitchlineConverters(HttpApp app) {
        return new WebMvcConfigurer() {
            @Override
            public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
                converters.clear();
                converters.addAll(app.converters());
            }
        };
    }
}
