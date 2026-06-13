package com.vertex.profile.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Enables Spring's cache abstraction. The backing store is chosen by profile:
 * <ul>
 *   <li>dev (default): an in-process {@code simple} cache — runs with only a JDK.</li>
 *   <li>postgres/docker: Redis, with TTL from {@code spring.cache.redis.time-to-live}.</li>
 * </ul>
 * The caching itself (cache-aside via {@code @Cacheable}/{@code @CacheEvict}) lives on
 * {@code ProfileService} and is identical regardless of store.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
