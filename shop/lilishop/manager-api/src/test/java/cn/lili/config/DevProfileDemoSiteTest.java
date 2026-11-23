package cn.lili.config;

import cn.lili.common.properties.SystemSettingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本机开发（profile dev）不得开启演示站点拦截，否则 @DemoSite 写接口全部返回 4001。
 * test 资源里另有同名 application.yml，故按模块目录直接读 main 资源。
 */
class DevProfileDemoSiteTest {

    @Test
    void devProfileDisablesDemoSite() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MutablePropertySources sources = new MutablePropertySources();
        loader.load("dev", new FileSystemResource("src/main/resources/application-dev.yml")).forEach(sources::addLast);
        loader.load("base", new FileSystemResource("src/main/resources/application.yml")).forEach(sources::addLast);

        SystemSettingProperties system = new Binder(ConfigurationPropertySources.from(sources))
                .bind("lili.system", SystemSettingProperties.class)
                .orElseGet(SystemSettingProperties::new);

        assertThat(system.getIsDemoSite()).isFalse();
    }
}
