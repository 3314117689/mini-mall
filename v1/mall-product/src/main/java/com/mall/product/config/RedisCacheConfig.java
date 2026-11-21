package com.mall.product.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Redis 缓存管理器配置（L5）。
 *
 * 为什么要有这个类：加 @EnableCaching 后框架会自动给一个默认的缓存管理器，
 * 但默认配置有两处不适合本项目——
 * 1. 值用 JDK 序列化：redis-cli 里看是一堆乱码，还要求实体必须实现 Serializable；
 * 2. 永不过期：商品改价后旧值一直留在 Redis 里，谁也说不清是不是新数据。
 * 所以自己声明一个 RedisCacheManager Bean，覆盖默认配置。
 */
@Configuration
public class RedisCacheConfig {

	@Bean
	public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
		RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
				// 缓存 30 分钟后自动过期；到期后再查询就回源查 MySQL 并重新写入
				.entryTtl(Duration.ofMinutes(30))
				// 值用 JSON 序列化存进 Redis（Jackson 3 版序列化器，配 Boot 4）。
				// enableDefaultTyping：JSON 里多存一个 "@class" 字段记下对象类型，
				// 反序列化时才知道这个 JSON 该转回 Product 而不是 Map；
				// enableSpringCacheNullValueSupport：允许把 null 也写进缓存（L5-4 空值缓存防穿透），
				// 框架会用一个 NullValue 标记来存，而不是真去序列化 null
				.serializeValuesWith(RedisSerializationContext.SerializationPair
						.fromSerializer(GenericJacksonJsonRedisSerializer.create(b -> b
								.enableDefaultTyping(BasicPolymorphicTypeValidator.builder()
										.allowIfBaseType(Object.class)
										.build())
								.enableSpringCacheNullValueSupport())));
		return RedisCacheManager.builder(connectionFactory)
				.cacheDefaults(config)
				.build();
	}
}
