package com.overdrive.app.server;

import com.overdrive.app.automation.Automations;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * HTTP routes for automations.
 *
 * Endpoints:
 * - GET    /api/automations/list            → List all automations
 * - GET    /api/automations/schema          → Get the automation schema
 * - POST   /api/automations/automation      → Create a new automation
 * - PUT    /api/automations/automation/{id} → Update an existing automation by id
 * - DELETE /api/automations/automation/{id} → Delete an existing automation by id
 * - POST   /api/automations/test/{id}       → Run the actions for an automation by id
 * - POST   /api/automations/disable/{id}    → Disable an existing automation by id
 */
public final class AutomationApiHandler {

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/automations/list") && method.equals("GET")) {
            return getAutomations(out);
        }
        if (path.equals("/api/automations/schema") && method.equals("GET")) {
            return getSchema(out);
        }
        if (path.equals("/api/automations/automation") && method.equals("POST")) {
            return addOrUpdateAutomation(null, body, out);
        }
        if (path.startsWith("/api/automations/automation/") && method.equals("PUT")) {
            String id = path.substring("/api/automations/automation/".length());
            return addOrUpdateAutomation(id, body, out);
        }
        if (path.startsWith("/api/automations/automation/") && method.equals("DELETE")) {
            String id = path.substring("/api/automations/automation/".length());
            return deleteAutomation(id, out);
        }
        if (path.startsWith("/api/automations/test/") && method.equals("POST")) {
            String id = path.substring("/api/automations/test/".length());
            return testAutomation(id, out);
        }
        if (path.startsWith("/api/automations/disable/") && method.equals("POST")) {
            String id = path.substring("/api/automations/disable/".length());
            return disableAutomation(id, body, out);
        }
        return false;
    }

    private static boolean getAutomations(OutputStream out) throws Exception {
        HttpResponse.sendJson(out, Automations.toJson().toString());
        return true;
    }

    private static boolean getSchema(OutputStream out) throws Exception {
        HttpResponse.sendJson(out, Automations.schemaJson().toString());
        return true;
    }

    private static boolean addOrUpdateAutomation(String id, String body, OutputStream out) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendError(out, 400, "Missing body.");
            return true;
        }
        JSONObject json = new JSONObject(body);
        if (Automations.updateAutomation(id, json)) {
            HttpResponse.sendJsonSuccess(out);
        } else {
            HttpResponse.sendError(out, 400, "Invalid automation provided. Check the automation follows the schema");
        }
        return true;
    }

    private static boolean deleteAutomation(String id, OutputStream out) throws Exception {
        if (Automations.deleteAutomation(id)) {
            HttpResponse.sendJsonSuccess(out);
        } else {
            HttpResponse.sendError(out, 400, "Failed to delete automation.");
        }
        return true;
    }

    private static boolean testAutomation(String id, OutputStream out) throws Exception {
        Automations.triggerActions(id, false);
        HttpResponse.sendJsonSuccess(out);
        return true;
    }

    private static boolean disableAutomation(String id, String body, OutputStream out) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendError(out, 400, "Missing body.");
            return true;
        }
        JSONObject json = new JSONObject(body);
        if (Automations.disableAutomation(id, json.optBoolean("disabled", false))) {
            HttpResponse.sendJsonSuccess(out);
        } else {
            HttpResponse.sendError(out, 400, "Invalid automation provided. Check the automation follows the schema");
        }
        return true;
    }
}
