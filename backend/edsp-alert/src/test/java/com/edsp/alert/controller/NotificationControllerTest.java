package com.edsp.alert.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.edsp.alert.dto.AlertNotificationSendRequest;
import com.edsp.alert.service.AlertNotificationService;
import com.edsp.alert.service.NotificationService;
import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.validation.Validation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationControllerTest {
    @Test
    void alertNotificationSendRouteUsesStrictAlertPath() throws Exception {
        var controllerMapping = NotificationController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[] {"/api/notifications"}, controllerMapping.value());

        Method send = NotificationController.class.getMethod("sendAlert", AlertNotificationSendRequest.class);
        assertArrayEquals(new String[] {"/alerts/send"}, send.getAnnotation(PostMapping.class).value());

        Method retry = NotificationController.class.getMethod("retryDelivery", long.class, Map.class);
        assertArrayEquals(new String[] {"/deliveries/{id}/retry"}, retry.getAnnotation(PostMapping.class).value());
    }

    @Test
    void deliveriesRouteAcceptsOptionalAlertIdFilter() throws Exception {
        Method listDeliveries = NotificationController.class.getMethod(
            "listDeliveries",
            int.class,
            Long.class,
            String.class,
            String.class,
            Long.class
        );

        assertArrayEquals(new String[] {"/deliveries"}, listDeliveries.getAnnotation(GetMapping.class).value());
        assertEquals("50", listDeliveries.getParameters()[0].getAnnotation(RequestParam.class).defaultValue());
        assertEquals("alertId", listDeliveries.getParameters()[1].getAnnotation(RequestParam.class).value());
        assertEquals(false, listDeliveries.getParameters()[1].getAnnotation(RequestParam.class).required());
        assertEquals("status", listDeliveries.getParameters()[2].getAnnotation(RequestParam.class).value());
        assertEquals(false, listDeliveries.getParameters()[2].getAnnotation(RequestParam.class).required());
        assertEquals("channelType", listDeliveries.getParameters()[3].getAnnotation(RequestParam.class).value());
        assertEquals(false, listDeliveries.getParameters()[3].getAnnotation(RequestParam.class).required());
        assertEquals("channelId", listDeliveries.getParameters()[4].getAnnotation(RequestParam.class).value());
        assertEquals(false, listDeliveries.getParameters()[4].getAnnotation(RequestParam.class).required());
    }

    @Test
    void alertSendRequestRejectsUnknownCreationFields() {
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        var error = assertThrows(
            JsonMappingException.class,
            () -> objectMapper.readValue("""
                {
                  "alertId": 123,
                  "channelId": 456,
                  "title": "must not be accepted"
                }
                """, AlertNotificationSendRequest.class)
        );

        assertEquals(true, error.getMessage().contains("Unexpected field: title"));
    }

    @Test
    void alertSendRequestRequiresAlertIdAndChannelId() {
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var validator = validatorFactory.getValidator();

            var missingAlertId = validator.validate(new AlertNotificationSendRequest(null, 456L));
            var missingChannelId = validator.validate(new AlertNotificationSendRequest(123L, null));

            assertEquals(1, missingAlertId.size());
            assertEquals("alertId", missingAlertId.iterator().next().getPropertyPath().toString());
            assertEquals(1, missingChannelId.size());
            assertEquals("channelId", missingChannelId.iterator().next().getPropertyPath().toString());
        }
    }

    @Test
    void controllerDelegatesAlertSendAndDeliveryFilters() {
        var notificationService = new StubNotificationService();
        var alertNotificationService = new StubAlertNotificationService();
        var controller = new NotificationController(notificationService, alertNotificationService);

        var sent = controller.sendAlert(new AlertNotificationSendRequest(123L, 456L));
        var deliveries = controller.listDeliveries(75, 123L, "failed", "wecom", 456L);
        var retried = controller.retryDelivery(77L, Map.of());

        assertEquals(123L, alertNotificationService.alertId);
        assertEquals(456L, alertNotificationService.channelId);
        assertEquals(77L, alertNotificationService.retryDeliveryId);
        assertEquals("success", sent.data().get("status"));
        assertEquals("failed", retried.data().get("status"));
        assertEquals(75, notificationService.limit);
        assertEquals(123L, notificationService.alertId);
        assertEquals("failed", notificationService.status);
        assertEquals("wecom", notificationService.channelType);
        assertEquals(456L, notificationService.deliveryChannelId);
        assertEquals("delivery", deliveries.data().get(0).get("status"));
    }

    @Test
    void deliveriesHttpRejectsInvalidStatusAndChannelTypeFilters() throws Exception {
        var mvc = mockMvc(new NotificationController(
            new NotificationService(null, null),
            new StubAlertNotificationService()
        ));

        mvc.perform(get("/api/notifications/deliveries")
                .param("status", "pending"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("invalid_delivery_status"));

        mvc.perform(get("/api/notifications/deliveries")
                .param("channelType", "email"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("unsupported_channel"));
    }

    @Test
    void alertSendHttpRejectsExtraFieldsWithInvalidRequestContract() throws Exception {
        var alertNotificationService = new StubAlertNotificationService();
        var mvc = mockMvc(new NotificationController(new StubNotificationService(), alertNotificationService));

        mvc.perform(post("/api/notifications/alerts/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "alertId": 123,
                      "channelId": 456,
                      "title": "must not be accepted"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("invalid_request_contract"));

        assertEquals(null, alertNotificationService.alertId);
    }

    @Test
    void alertSendHttpRejectsMissingRequiredFieldsWithInvalidRequestContract() throws Exception {
        var alertNotificationService = new StubAlertNotificationService();
        var mvc = mockMvc(new NotificationController(new StubNotificationService(), alertNotificationService));

        mvc.perform(post("/api/notifications/alerts/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"alertId\":123}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("invalid_request_contract"));

        assertEquals(null, alertNotificationService.alertId);
    }

    @Test
    void retryDeliveryHttpRejectsAnyBusinessRequestBody() throws Exception {
        var alertNotificationService = new StubAlertNotificationService();
        var mvc = mockMvc(new NotificationController(new StubNotificationService(), alertNotificationService));

        for (var field : List.of("alertId", "channelId", "title", "message", "severity", "payload")) {
            mvc.perform(post("/api/notifications/deliveries/77/retry")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"" + field + "\":\"must not be accepted\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("invalid_request_contract"));
        }

        assertEquals(null, alertNotificationService.retryDeliveryId);
    }

    @Test
    void retryDeliveryHttpAllowsEmptyBodyAndDelegatesByDeliveryIdOnly() throws Exception {
        var alertNotificationService = new StubAlertNotificationService();
        var mvc = mockMvc(new NotificationController(new StubNotificationService(), alertNotificationService));

        mvc.perform(post("/api/notifications/deliveries/77/retry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.deliveryId").value(88));

        assertEquals(77L, alertNotificationService.retryDeliveryId);
    }

    @Test
    void channelUpdateHttpAllowsPartialPayloadAndDelegatesPresentFields() throws Exception {
        var notificationService = new StubNotificationService();
        var mvc = mockMvc(new NotificationController(notificationService, new StubAlertNotificationService()));

        mvc.perform(put("/api/notifications/channels/9")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "renamed",
                      "enabled": false
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(9));

        assertEquals(9L, notificationService.updatedChannelId);
        assertEquals("renamed", notificationService.updateRequest.name());
        assertEquals(false, notificationService.updateRequest.enabled());
        assertEquals(null, notificationService.updateRequest.webhookUrl());
        assertEquals(Set.of("name", "enabled"), notificationService.updateFields);
    }

    @Test
    void channelUpdateHttpAcceptsEndpointUrlAliasForWebhookUrl() throws Exception {
        var notificationService = new StubNotificationService();
        var mvc = mockMvc(new NotificationController(notificationService, new StubAlertNotificationService()));

        mvc.perform(put("/api/notifications/channels/9")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "endpointUrl": "https://hook.example.test/new?token=WEBHOOKTOKEN123456"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        assertEquals("https://hook.example.test/new?token=WEBHOOKTOKEN123456", notificationService.updateRequest.webhookUrl());
        assertEquals(Set.of("webhookUrl", "endpointUrl"), notificationService.updateFields);
    }

    @Test
    void channelUpdateHttpPropagatesBlankEndpointError() throws Exception {
        var notificationService = new StubNotificationService();
        notificationService.updateError = new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_webhook_url");
        var mvc = mockMvc(new NotificationController(notificationService, new StubAlertNotificationService()));

        mvc.perform(put("/api/notifications/channels/9")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "webhookUrl": "   "
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("invalid_webhook_url"));

        assertEquals(Set.of("webhookUrl"), notificationService.updateFields);
    }

    @Test
    void channelUpdateHttpPropagatesChannelTypeImmutableError() throws Exception {
        var notificationService = new StubNotificationService();
        notificationService.updateError = new ResponseStatusException(HttpStatus.BAD_REQUEST, "channel_type_immutable");
        var mvc = mockMvc(new NotificationController(notificationService, new StubAlertNotificationService()));

        mvc.perform(put("/api/notifications/channels/9")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "channelType": "wecom"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("channel_type_immutable"));

        assertEquals(Set.of("channelType"), notificationService.updateFields);
    }

    @Test
    void channelUpdateHttpIgnoresUnknownFieldsWithoutChangingContract() throws Exception {
        var notificationService = new StubNotificationService();
        var mvc = mockMvc(new NotificationController(notificationService, new StubAlertNotificationService()));

        mvc.perform(put("/api/notifications/channels/9")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "renamed",
                      "title": "must not be accepted as a notification payload"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        assertEquals("renamed", notificationService.updateRequest.name());
        assertEquals(Set.of("name"), notificationService.updateFields);
    }

    @Test
    void retryDeliveryHttpPropagatesExplicitRetryErrors() throws Exception {
        var alertNotificationService = new StubAlertNotificationService();
        alertNotificationService.retryError = new ResponseStatusException(HttpStatus.BAD_REQUEST, "delivery_not_retryable");
        var mvc = mockMvc(new NotificationController(new StubNotificationService(), alertNotificationService));

        mvc.perform(post("/api/notifications/deliveries/77/retry"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("delivery_not_retryable"));
    }

    @Test
    void legacySendHttpRejectsDirectNotificationPayloads() throws Exception {
        var mvc = mockMvc(new NotificationController(new StubNotificationService(), new StubAlertNotificationService()));

        mvc.perform(post("/api/notifications/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "channelIds": [1],
                      "title": "must not be accepted",
                      "message": "manual payload must not bypass alerts",
                      "severity": "high"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("use_alert_notification_endpoint"));
    }

    @Test
    void channelTestHttpRejectsDirectNotificationPayloads() throws Exception {
        var mvc = mockMvc(new NotificationController(new StubNotificationService(), new StubAlertNotificationService()));

        mvc.perform(post("/api/notifications/channels/1/test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("use_alert_notification_endpoint"));
    }

    private MockMvc mockMvc(NotificationController controller) {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
            .setValidator(validator)
            .build();
    }

    private static class StubNotificationService extends NotificationService {
        private int limit;
        private Long alertId;
        private String status;
        private String channelType;
        private Long deliveryChannelId;
        private Long updatedChannelId;
        private com.edsp.alert.dto.NotificationChannelRequest updateRequest;
        private Set<String> updateFields;
        private ResponseStatusException updateError;

        StubNotificationService() {
            super(null, null);
        }

        @Override
        public List<Map<String, Object>> listDeliveries(
            int limit,
            Long alertId,
            String status,
            String channelType,
            Long channelId
        ) {
            this.limit = limit;
            this.alertId = alertId;
            this.status = status;
            this.channelType = channelType;
            this.deliveryChannelId = channelId;
            return List.of(Map.of("status", "delivery"));
        }

        @Override
        public Map<String, Object> updateChannel(
            long id,
            com.edsp.alert.dto.NotificationChannelRequest request,
            Set<String> presentFields
        ) {
            this.updatedChannelId = id;
            this.updateRequest = request;
            this.updateFields = presentFields;
            if (updateError != null) {
                throw updateError;
            }
            return Map.of("id", id);
        }
    }

    private static class StubAlertNotificationService extends AlertNotificationService {
        private Long alertId;
        private Long channelId;
        private Long retryDeliveryId;
        private ResponseStatusException retryError;

        StubAlertNotificationService() {
            super(null, null, null);
        }

        @Override
        public Map<String, Object> send(long alertId, long channelId) {
            this.alertId = alertId;
            this.channelId = channelId;
            return Map.of("status", "success");
        }

        @Override
        public Map<String, Object> retryDelivery(long deliveryId) {
            if (retryError != null) {
                throw retryError;
            }
            this.retryDeliveryId = deliveryId;
            return Map.of("deliveryId", 88L, "status", "failed");
        }
    }
}
