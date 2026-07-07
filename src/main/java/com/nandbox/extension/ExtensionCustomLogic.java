package com.nandbox.extension;

import com.nandbox.bots.api.Nandbox;
import com.nandbox.bots.api.NandboxClient;
import com.nandbox.bots.api.data.*;
import com.nandbox.bots.api.inmessages.*;
import com.nandbox.bots.api.util.*;
import com.nandbox.extension.ExtensionAdapter;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;

public class ExtensionCustomLogic extends ExtensionAdapter {

    private Nandbox.Api api;

    private static final String TABLE_NAME = "menu_submissions";

    private static final String OWNER_USER_ID = "1";

    private static final String CMD_GET = "/get";
    private static final String CMD_DELETE = "/delete";
    private static final String CMD_GET_ALL = "/get_all";
    private static final String CMD_DELETE_ALL = "/delete_all";

    private static final String REF_LIST = "LIST";
    private static final String REF_DELETE_ALL = "DELETE_ALL";

    public static void main(String[] args) throws Exception {
        String TOKEN = "";
        Properties properties = new Properties();
        FileInputStream input = null;

        try {
            input = new FileInputStream("config.properties");
            properties.load(input);
            TOKEN = properties.getProperty("Token");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                }
            }
        }

        NandboxClient client = NandboxClient.get();
        client.connect(TOKEN, new ExtensionCustomLogic());
    }

    @Override
    public void onConnect(Nandbox.Api api) {
        this.api = api;
    }

    // Handles all menu/form submissions and saves them using record id = userId.
    // Do NOT add @Override here, because some SDK builds do not declare it in ExtensionAdapter.
    public void onMenuCallback(MenuCallback menuCallback) {
        if (menuCallback == null) {
            return;
        }

        String userId = extractUserId(menuCallback);
        if (userId == null) {
            return;
        }

        String appId = extractStringNoArg(menuCallback, "getAppId");
        String menuId = extractStringNoArg(menuCallback, "getMenuId");

        JSONObject doc = new JSONObject();
        doc.put("_id", userId);
        if (appId != null) {
            doc.put("app_id", appId);
        }
        if (menuId != null) {
            doc.put("menu_id", menuId);
        }
        doc.put("saved_at", String.valueOf(System.currentTimeMillis()));

        Object cells = invokeNoArg(menuCallback, "getCells");
        if (cells != null) {
            try {
                if (cells instanceof JSONArray) {
                    doc.put("cells", (JSONArray) cells);
                } else {
                    doc.put("cells", String.valueOf(cells));
                }
            } catch (Throwable t) {
                doc.put("cells", String.valueOf(cells));
            }
        }

        DatabaseService.getInstance().set(api, doc, TABLE_NAME, userId, Utils.getUniqueId());
    }

    @Override
    public void onReceive(IncomingMessage incomingMsg) {
        if (incomingMsg == null || incomingMsg.getText() == null) {
            return;
        }
        if (incomingMsg.getChat() == null || incomingMsg.getFrom() == null) {
            return;
        }

        String chatId = incomingMsg.getChat().getId();
        String userId = incomingMsg.getFrom().getId();
        String appId = incomingMsg.getAppId();
        Integer chatSettings = incomingMsg.getChatSettings();

        if (chatId == null || userId == null) {
            return;
        }

        String text = incomingMsg.getText().trim();
        if (text.length() == 0) {
            return;
        }

        if (!isAdmin(incomingMsg)) {
            return;
        }

        if (startsWithIgnoreCase(text, CMD_GET + " ") || startsWithIgnoreCase(text, "get ")) {
            String targetUserId = text.substring(text.indexOf(' ') + 1).trim();
            if (targetUserId.length() == 0) {
                sendText(chatId, userId, chatSettings, appId, "Usage: /get <userId>");
                return;
            }
            DatabaseService.getInstance().get(api, targetUserId, TABLE_NAME, buildRef(REF_LIST, chatId, userId));
            return;
        }

        if (startsWithIgnoreCase(text, CMD_DELETE + " ") || startsWithIgnoreCase(text, "delete ")) {
            String targetUserId2 = text.substring(text.indexOf(' ') + 1).trim();
            if (targetUserId2.length() == 0) {
                sendText(chatId, userId, chatSettings, appId, "Usage: /delete <userId>");
                return;
            }
            DatabaseService.getInstance().delete(api, targetUserId2, TABLE_NAME, Utils.getUniqueId());
            sendText(chatId, userId, chatSettings, appId, "Delete requested for userId: " + targetUserId2);
            return;
        }

        if (equalsIgnoreCase(text, CMD_GET_ALL) || equalsIgnoreCase(text, "get all") || equalsIgnoreCase(text, "all")) {
            DatabaseService.getInstance().list(api, TABLE_NAME, buildRef(REF_LIST, chatId, userId));
            return;
        }

        if (equalsIgnoreCase(text, CMD_DELETE_ALL) || equalsIgnoreCase(text, "delete all") || equalsIgnoreCase(text, "del all")) {
            DatabaseService.getInstance().list(api, TABLE_NAME, buildRef(REF_DELETE_ALL, chatId, userId));
            return;
        }

        sendText(chatId, userId, chatSettings, appId, getHelpText());
    }

    @Override
    public void onExtensionDocResponse(ExtensionDocResponse extensionDocResponse) {
        if (extensionDocResponse == null) {
            return;
        }

        String ref = null;
        try {
            ref = extensionDocResponse.getRef();
        } catch (Throwable t) {
        }

        if (ref == null) {
            return;
        }

        String action = refPart(ref, 0);
        String chatId = refPart(ref, 1);
        String adminUserId = refPart(ref, 2);

        if (action == null || chatId == null || adminUserId == null) {
            return;
        }

        JSONArray docs = null;
        try {
            docs = extensionDocResponse.getDocs();
        } catch (Throwable t2) {
        }

        if (REF_LIST.equals(action)) {
            if (docs == null || docs.size() == 0) {
                sendText(chatId, adminUserId, null, null, "No records found.");
                return;
            }
            sendText(chatId, adminUserId, null, null, formatDocsList(docs));
            return;
        }

        if (REF_DELETE_ALL.equals(action)) {
            if (docs == null || docs.size() == 0) {
                sendText(chatId, adminUserId, null, null, "No records to delete.");
                return;
            }

            int deletedScheduled = 0;
            for (int i = 0; i < docs.size(); i++) {
                Object o = docs.get(i);
                if (o instanceof JSONObject) {
                    JSONObject d = (JSONObject) o;
                    String id = safeToString(d.get("_id"));
                    if (id != null) {
                        DatabaseService.getInstance().delete(api, id, TABLE_NAME, Utils.getUniqueId());
                        deletedScheduled++;
                    }
                }
            }

            sendText(chatId, adminUserId, null, null, "Delete all requested. Records scheduled for deletion: " + deletedScheduled);
        }
    }

    private String getHelpText() {
        return "Admin commands:\n" +
                "/get <userId> - get one user's saved menu submission\n" +
                "/get_all - list all saved submissions\n" +
                "/delete <userId> - delete one user's record\n" +
                "/delete_all - delete all records (lists then deletes one-by-one)";
    }

    private boolean isAdmin(IncomingMessage incomingMsg) {
        if (incomingMsg == null) {
            return false;
        }

        try {
            if (incomingMsg.getFrom() != null && incomingMsg.getFrom().getId() != null) {
                String fromId = incomingMsg.getFrom().getId();
                if (fromId != null && fromId.equals(OWNER_USER_ID)) {
                    return true;
                }
            }
        } catch (Throwable t) {
        }

        try {
            if (incomingMsg.getChatSettings() != null) {
                int settings = incomingMsg.getChatSettings().intValue();
                if ((settings & 128) != 0 || (settings & 512) != 0) {
                    return true;
                }
            }
        } catch (Throwable t2) {
        }

        return false;
    }

    private void sendText(String chatId, String userId, Integer chatSettings, String appId, String text) {
        api.sendText(
                chatId,
                text,
                Utils.getUniqueId(),
                null,
                userId,
                new Integer(0),
                Boolean.FALSE,
                chatSettings,
                null,
                null,
                null,
                appId
        );
    }

    private boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.equalsIgnoreCase(b);
    }

    private boolean startsWithIgnoreCase(String text, String prefix) {
        if (text == null || prefix == null) {
            return false;
        }
        if (text.length() < prefix.length()) {
            return false;
        }
        return text.substring(0, prefix.length()).equalsIgnoreCase(prefix);
    }

    private String extractUserId(MenuCallback menuCallback) {
        // MenuCallback getters differ by SDK build; use safe reflection.
        // Prefer getFrom().getId() then getUserId().
        try {
            Object fromObj = invokeNoArg(menuCallback, "getFrom");
            if (fromObj != null) {
                Object idObj = invokeNoArg(fromObj, "getId");
                if (idObj != null) {
                    String s = String.valueOf(idObj).trim();
                    if (s.length() > 0) {
                        return s;
                    }
                }
            }
        } catch (Throwable t) {
        }

        String uid = extractStringNoArg(menuCallback, "getUserId");
        if (uid != null) {
            return uid;
        }

        return null;
    }

    private String extractStringNoArg(Object obj, String method) {
        Object o = invokeNoArg(obj, method);
        if (o == null) {
            return null;
        }
        String s = null;
        try {
            s = String.valueOf(o);
        } catch (Throwable t) {
            return null;
        }
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.length() == 0) {
            return null;
        }
        return s;
    }

    private Object invokeNoArg(Object target, String method) {
        if (target == null || method == null) {
            return null;
        }
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(method, new Class[0]);
            return m.invoke(target, new Object[0]);
        } catch (Throwable t) {
            return null;
        }
    }

    private String buildRef(String action, String chatId, String adminUserId) {
        // action:chatId:adminUserId:unique
        return action + ":" + safeRefPart(chatId) + ":" + safeRefPart(adminUserId) + ":" + Utils.getUniqueId();
    }

    private String safeRefPart(String s) {
        if (s == null) {
            return "";
        }
        // avoid ':' in ref parts
        int idx = s.indexOf(':');
        if (idx < 0) {
            return s;
        }
        return s.replace(':', '_');
    }

    private String refPart(String ref, int index) {
        if (ref == null) {
            return null;
        }
        int p1 = ref.indexOf(':');
        if (p1 < 0) {
            return null;
        }
        int p2 = ref.indexOf(':', p1 + 1);
        if (p2 < 0) {
            return null;
        }
        int p3 = ref.indexOf(':', p2 + 1);
        if (p3 < 0) {
            return null;
        }

        if (index == 0) {
            return ref.substring(0, p1);
        }
        if (index == 1) {
            return ref.substring(p1 + 1, p2);
        }
        if (index == 2) {
            return ref.substring(p2 + 1, p3);
        }
        return null;
    }

    private String safeToString(Object o) {
        if (o == null) {
            return null;
        }
        String s = null;
        try {
            s = String.valueOf(o);
        } catch (Throwable t) {
            return null;
        }
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (s.length() == 0) {
            return null;
        }
        return s;
    }

    private String formatDocsList(JSONArray docs) {
        if (docs == null || docs.size() == 0) {
            return "No records found.";
        }

        StringBuffer sb = new StringBuffer();
        sb.append("Records (");
        sb.append(docs.size());
        sb.append("):\n");

        int max = docs.size();
        if (max > 50) {
            max = 50;
        }

        for (int i = 0; i < max; i++) {
            Object o = docs.get(i);
            if (o instanceof JSONObject) {
                JSONObject d = (JSONObject) o;
                String id = safeToString(d.get("_id"));
                sb.append(i + 1);
                sb.append(") userId=");
                sb.append(id == null ? "?" : id);

                String menuId = safeToString(d.get("menu_id"));
                if (menuId != null) {
                    sb.append(" menu_id=");
                    sb.append(menuId);
                }

                String savedAt = safeToString(d.get("saved_at"));
                if (savedAt != null) {
                    sb.append(" saved_at=");
                    sb.append(savedAt);
                }

                sb.append("\n");
            } else {
                sb.append(i + 1);
                sb.append(") ");
                sb.append(String.valueOf(o));
                sb.append("\n");
            }
        }

        if (docs.size() > max) {
            sb.append("... showing first ");
            sb.append(max);
            sb.append(" records\n");
        }

        return sb.toString();
    }
}
