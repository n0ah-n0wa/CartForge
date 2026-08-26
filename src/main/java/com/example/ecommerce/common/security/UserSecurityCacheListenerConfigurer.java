package com.example.ecommerce.common.security;

import com.example.ecommerce.user.entity.UserSecurityCacheListener;
import org.springframework.stereotype.Component;

@Component
class UserSecurityCacheListenerConfigurer {

    UserSecurityCacheListenerConfigurer(AccountSecurityService accountSecurityService) {
        UserSecurityCacheListener.setAccountSecurityService(accountSecurityService);
    }
}
