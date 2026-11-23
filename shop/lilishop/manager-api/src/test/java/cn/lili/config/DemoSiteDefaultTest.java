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
 * 轻游自用、无演示用户：基础配置不得开启演示站点拦截，否则 @DemoSite 写接口在任何环境都返回 4001。
 * test 资源里另有同名 application.yml，故按模块目录直接读 main 资源。
 */
class DemoSiteDefaultTest {

    @Test
    void baseConfigDisablesDemoSite() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("base", new FileSystemResource("src/main/resources/application.yml")).forEach(sources::addLast);

        SystemSettingProperties system = new Binder(ConfigurationPropertySources.from(sources))
                .bind("lili.system", SystemSettingProperties.class)
                .orElseGet(SystemSettingProperties::new);

        assertThat(system.getIsDemoSite()).isFalse();
    }
}
