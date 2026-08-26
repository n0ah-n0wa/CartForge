package com.example.ecommerce.user.entity;

import com.example.ecommerce.common.security.AccountSecurityService;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;

/**
 * Evicts JWT account-security cache entries when user role or enabled state
 * changes so demotion/disable takes effect on the next authenticated request.
 */
public class UserSecurityCacheListener {

    private static AccountSecurityService accountSecurityService;

    public static void setAccountSecurityService(AccountSecurityService service) {
        accountSecurityService = service;
    }

    @PostUpdate
    @PostPersist
    void evictSecurityCache(User user) {
        if (accountSecurityService != null && user.getId() != null) {
            accountSecurityService.evict(user.getId());
        }
    }
}
