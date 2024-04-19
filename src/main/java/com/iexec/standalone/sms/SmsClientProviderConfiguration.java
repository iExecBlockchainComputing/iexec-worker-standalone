package com.iexec.standalone.sms;

import com.iexec.sms.api.SmsClientProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SmsClientProviderConfiguration {

    @Bean
    SmsClientProvider smsClientProvider() {
        return new SmsClientProvider();
    }
}
