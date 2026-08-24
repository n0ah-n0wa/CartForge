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
                "event=cache_get_failed cache={} key={} cause={}; reading from PostgreSQL",
                cache.getName(),
                key,
                exception.getClass().getSimpleName());
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn(
                "event=cache_put_failed cache={} key={} cause={}; continuing without caching",
                cache.getName(),
                key,
                exception.getClass().getSimpleName());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn(
                "event=cache_evict_failed cache={} key={} cause={}; attempting full clear",
                cache.getName(),
                key,
                exception.getClass().getSimpleName());
        clearBestEffort(cache);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn(
                "event=cache_clear_failed cache={} cause={}; stale entries may remain until TTL",
                cache.getName(),
                exception.getClass().getSimpleName());
    }

    private static void clearBestEffort(Cache cache) {
        try {
            cache.clear();
        } catch (RuntimeException clearFailed) {
            log.error(
                    "event=cache_clear_failed cache={} cause={}; stale entries may remain until TTL",
                    cache.getName(),
                    clearFailed.getClass().getSimpleName());
        }
    }
}
