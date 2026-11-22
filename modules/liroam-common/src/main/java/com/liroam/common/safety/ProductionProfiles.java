package com.liroam.common.safety;

import java.util.Arrays;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** 哪些 profile 算生产：{@code liroam.safety.production-profiles}，默认 prod、staging。 */
public final class ProductionProfiles {

    public static final String PROPERTY = "liroam.safety.production-profiles";
    public static final String DEFAULT = "prod,staging";

    private ProductionProfiles() {
    }

    public static boolean isProduction(Environment env) {
        String[] names = Arrays.stream(env.getProperty(PROPERTY, DEFAULT).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        return names.length > 0 && env.acceptsProfiles(Profiles.of(names));
    }
}
