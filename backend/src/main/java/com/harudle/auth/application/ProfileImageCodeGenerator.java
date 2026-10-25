package com.harudle.auth.application;

import com.harudle.auth.domain.policy.ProfileImageCodePolicy;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class ProfileImageCodeGenerator {

    public int generate() {
        return ThreadLocalRandom.current().nextInt(
                ProfileImageCodePolicy.MIN_CODE,
                ProfileImageCodePolicy.MAX_CODE + 1
        );
    }
}
