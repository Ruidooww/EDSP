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
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationControllerTest {
    @Test
    void alertNotificationSendRouteUsesStrictAlertPath() throws Exception {
        var controllerMapping = NotificationController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[] {"/api/notifications"}, controllerMapping.value());

        Method send = NotificationController.class.getMethod("sendAlert", AlertNotificationSendRequest.class);
        assertArrayEquals(new String[] {"/alerts/send"}, send.getAnnotation(PostMapping.class).value());
    }

    @Test
    void deliveriesRouteAcceptsOptionalAlertIdFilter() throws Exception {
        Method listDeliveries = NotificationController.class.getMethod("listDeliveries", int.class, Long.class);

        assertArrayEquals(new String[] {"/deliveries"}, listDeliveries.getAnnotation(GetMapping.class).value());
        assertEquals("50", listDeliveries.getParameters()[0].getAnnotation(RequestParam.class).defaultValue());
        assertEquals("alertId", listDeliveries.getParameters()[1].getAnnotation(RequestParam.class).value());
        assertEquals(false, listDeliveries.getParameters()[1].getAnnotation(RequestParam.class).required());
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
        var deliveries = controller.listDeliveries(75, 123L);

        assertEquals(123L, alertNotificationService.alertId);
        assertEquals(456L, alertNotificationService.channelId);
        assertEquals("success", sent.data().get("status"));
        assertEquals(75, notificationService.limit);
        assertEquals(123L, notificationService.alertId);
        assertEquals("delivery", deliveries.data().get(0).get("status"));
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

        StubNotificationService() {
            super(null, null);
        }

        @Override
        public List<Map<String, Object>> listDeliveries(int limit, Long alertId) {
            this.limit = limit;
            this.alertId = alertId;
            return List.of(Map.of("status", "delivery"));
        }
    }

    private static class StubAlertNotificationService extends AlertNotificationService {
        private Long alertId;
        private Long channelId;

        StubAlertNotificationService() {
            super(null, null, null);
        }

        @Override
        public Map<String, Object> send(long alertId, long channelId) {
            this.alertId = alertId;
            this.channelId = channelId;
            return Map.of("status", "success");
        }
    }
}
