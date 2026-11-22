package com.mall.product.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * L9-4：加载库存扣减 Lua 脚本。
 * 脚本只在启动时读一次，之后每次扣减把 key 和数量传给 Redis 执行。
 */
@Configuration
public class RedisScriptConfig {

    @Bean
    public DefaultRedisScript<Long> stockDeductScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/stock-deduct.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
