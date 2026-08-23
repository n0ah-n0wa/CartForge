package com.example.ecommerce.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Fail-open cache errors so PostgreSQL remains authoritative. On eviction
 * failure, attempts a full cache clear so stale catalog entries are not served
 * until TTL expiry when Redis is only partially available.
 */
public final class CatalogCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(CatalogCacheErrorHandler.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn(
                "Cache GET failed; reading from PostgreSQL. cache={}, key={}: {}",
                cache.getName(),
                key,
                exception.toString());
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn(
                "Cache PUT failed; continuing without caching. cache={}, key={}: {}",
                cache.getName(),
                key,
                exception.toString());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn(
                "Cache EVICT failed; attempting full clear. cache={}, key={}: {}",
                cache.getName(),
                key,
                exception.toString());
        clearBestEffort(cache);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn(
                "Cache CLEAR failed; stale entries may remain until TTL. cache={}: {}",
                cache.getName(),
                exception.toString());
    }

    private static void clearBestEffort(Cache cache) {
        try {
            cache.clear();
        } catch (RuntimeException clearFailed) {
            log.error(
                    "Cache CLEAR also failed after EVICT error; stale entries may remain until TTL. cache={}: {}",
                    cache.getName(),
                    clearFailed.toString());
        }
    }
}
