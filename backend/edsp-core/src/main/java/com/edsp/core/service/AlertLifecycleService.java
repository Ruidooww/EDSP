package com.edsp.core.service;

import com.edsp.core.dto.AlertLifecycleRequest;
import com.edsp.core.support.CoreRequestSupport;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class AlertLifecycleService {
    private static final String STATUS_OPEN = "open";
    private static final String STATUS_ACKNOWLEDGED = "acknowledged";
    private static final String STATUS_CLOSED = "closed";

    private final AlertLifecycleRepository repository;
    private final CoreRequestSupport support;

    public AlertLifecycleService(AlertLifecycleRepository repository, CoreRequestSupport support) {
        this.repository = repository;
        this.support = support;
    }

    public Map<String, Object> acknowledge(long alertId, AlertLifecycleRequest request) {
        var alert = requireAlert(alertId);
        var status = status(alert);
        if (STATUS_CLOSED.equals(status)) {
            throw badRequest("alert_closed");
        }
        if (!STATUS_OPEN.equals(status)) {
            throw badRequest("alert_not_open");
        }
        return repository.acknowledge(alertId, status, operatorName(request), note(request));
    }

    public Map<String, Object> assign(long alertId, AlertLifecycleRequest request) {
        var alert = requireAlert(alertId);
        var status = status(alert);
        if (STATUS_CLOSED.equals(status)) {
            throw badRequest("alert_closed");
        }
        if (!STATUS_OPEN.equals(status) && !STATUS_ACKNOWLEDGED.equals(status)) {
            throw badRequest("alert_status_not_lifecycle_managed");
        }
        var assignee = support.stringOrNull(request == null ? null : request.assignee());
        if (assignee == null) {
            throw badRequest("assignee_required");
        }
        return repository.assign(alertId, status, operatorName(request), assignee, note(request));
    }

    public Map<String, Object> close(long alertId, AlertLifecycleRequest request) {
        var alert = requireAlert(alertId);
        var status = status(alert);
        if (STATUS_CLOSED.equals(status)) {
            throw badRequest("alert_closed");
        }
        if (!STATUS_OPEN.equals(status) && !STATUS_ACKNOWLEDGED.equals(status)) {
            throw badRequest("alert_status_not_lifecycle_managed");
        }
        var closeNote = support.stringOrNull(request == null ? null : request.note());
        if (closeNote == null) {
            throw badRequest("close_note_required");
        }
        return repository.close(alertId, status, operatorName(request), closeNote);
    }

    public List<Map<String, Object>> timeline(long alertId) {
        requireAlert(alertId);
        return repository.timeline(alertId);
    }

    private Map<String, Object> requireAlert(long alertId) {
        var alert = repository.findAlert(alertId);
        if (alert == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert_not_found");
        }
        return alert;
    }

    private String operatorName(AlertLifecycleRequest request) {
        var operatorName = support.stringOrNull(request == null ? null : request.operatorName());
        return operatorName == null ? "admin" : operatorName;
    }

    private String note(AlertLifecycleRequest request) {
        return support.stringOrNull(request == null ? null : request.note());
    }

    private String status(Map<String, Object> alert) {
        return String.valueOf(alert.get("status"));
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
