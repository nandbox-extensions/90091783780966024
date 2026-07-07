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

    // admin privileges: owner userId "1" OR chatSettings flags 128/512

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
    public void onMenuCallback(MenuCallback menuCallback) {
        if (menuCallback == null) {
            return;
        }

        String userId = extractUserId(menuCallback);
        if (userId == null) {
            return;
        }

        String appId = extractAppId(menuCallback);
        String menuId = extractMenuId(menuCallback);

        JSONObject doc = new JSONObject();
        doc.put("_id", userId);
        if (appId != null) {
            doc.put("app_id", appId);
        }
        if (menuId != null) {
            doc.put("menu_id", menuId);
        }
        doc.put("saved_at", String.valueOf(System.currentTimeMillis()));

        Object cells = extractCells(menuCallback);
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

        if (startsWithIgnoreCase(text, "/get ") || startsWithIgnoreCase(text, "get ")) {
            String targetUserId = text.substring(text.indexOf(' ') + 1).trim();
            if (targetUserId.length() == 0) {
                sendText(chatId, userId, chatSettings, appId, "Usage: /get <userId>");
                return;
            }
            DatabaseService.getInstance().get(api, targetUserId, TABLE_NAME, Utils.getUniqueId());
            sendText(chatId, userId, chatSettings, appId, "Requested record for userId: " + targetUserId);
            return;
        }

        if (startsWithIgnoreCase(text, "/delete ") || startsWithIgnoreCase(text, "delete ")) {
            String targetUserId2 = text.substring(text.indexOf(' ') + 1).trim();
            if (targetUserId2.length() == 0) {
                sendText(chatId, userId, chatSettings, appId, "Usage: /delete <userId>");
                return;
            }
            DatabaseService.getInstance().delete(api, targetUserId2, TABLE_NAME, Utils.getUniqueId());
            sendText(chatId, userId, chatSettings, appId, "Delete requested for userId: " + targetUserId2);
            return;
        }

        if (equalsIgnoreCase(text, "/get_all") || equalsIgnoreCase(text, "get all") || equalsIgnoreCase(text, "all")
                || equalsIgnoreCase(text, "/delete_all") || equalsIgnoreCase(text, "delete all") || equalsIgnoreCase(text, "del all")) {
            // Not supported by the documented DatabaseService contract in this environment.
            sendText(chatId, userId, chatSettings, appId,
                    "This DatabaseService build supports only: set(doc,id), get(id), delete(id).\n" +
                            "Listing/deleting all records is not available.");
            return;
        }

        String help = "Admin commands:\n" +
                "/get <userId> - get one user's saved menu submission\n" +
                "/delete <userId> - delete one user's record\n" +
                "\nNote: /get_all and /delete_all are not supported in this build.";
        sendText(chatId, userId, chatSettings, appId, help);
    }

    @Override
    public void onExtensionDocResponse(ExtensionDocResponse extensionDocResponse) {
        if (extensionDocResponse == null) {
            return;
        }

        // Keep callback non-empty and safe.
        try {
            JSONObject doc = extensionDocResponse.getDoc();
            if (doc != null) {
                return;
            }
        } catch (Throwable t) {
        }

        try {
            JSONArray docs = extensionDocResponse.getDocs();
            if (docs != null) {
                return;
            }
        } catch (Throwable t2) {
        }
    }

    private boolean isAdmin(IncomingMessage incomingMsg) {
        if (incomingMsg == null) {
            return false;
        }

        // Owner userId (from module_object): "1"
        try {
            if (incomingMsg.getFrom() != null && incomingMsg.getFrom().getId() != null) {
                String fromId = incomingMsg.getFrom().getId();
                if (fromId != null && fromId.equals("1")) {
                    return true;
                }
            }
        } catch (Throwable t) {
        }

        // Privileged chat settings (from module_object privileges): 128, 512
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
        // Use only safe reflection fallbacks without inventing SDK getters.
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

        return null;
    }

    private String extractAppId(MenuCallback menuCallback) {
        try {
            Object a = invokeNoArg(menuCallback, "getAppId");
            if (a != null) {
                String s = String.valueOf(a).trim();
                if (s.length() > 0) {
                    return s;
                }
            }
        } catch (Throwable t) {
        }
        return null;
    }

    private String extractMenuId(MenuCallback menuCallback) {
        try {
            Object m = invokeNoArg(menuCallback, "getMenuId");
            if (m != null) {
                String s = String.valueOf(m).trim();
                if (s.length() > 0) {
                    return s;
                }
            }
        } catch (Throwable t) {
        }
        return null;
    }

    private Object extractCells(MenuCallback menuCallback) {
        try {
            return invokeNoArg(menuCallback, "getCells");
        } catch (Throwable t) {
            return null;
        }
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
}
