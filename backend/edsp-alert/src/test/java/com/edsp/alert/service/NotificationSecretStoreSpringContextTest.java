package com.edsp.alert.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NotificationSecretStoreSpringContextTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(NotificationSecretStore.class)
        .withPropertyValues("edsp.notification.secret.master-key=");

    @Test
    void createsSecretStoreBeanWithConfiguredMasterKeyValueConstructor() {
        contextRunner.run(context ->
            assertThat(context).hasSingleBean(NotificationSecretStore.class)
        );
    }
}
