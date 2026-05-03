package com.mdominguez.ietiParkAndroid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GameSession {
    public static final String SERVER_URL = "wss://pico2.ieti.site";
    public static final int MAX_PLAYERS = 8;

    private static final GameSession INSTANCE = new GameSession();

    public static GameSession get() { return INSTANCE; }

    public static final class PlayerState {
        public String id;
        public String nickname;
        public int cat;
        public float x;
        public float y;
        public float vx;
        public float vy;
        public String anim;
        public boolean facingRight;
        public boolean grounded;
        public boolean viewer;
        public boolean hasPotion;

        public PlayerState copy() {
            PlayerState p = new PlayerState();
            p.id = id;
            p.nickname = nickname;
            p.cat = cat;
            p.x = x;
            p.y = y;
            p.vx = vx;
            p.vy = vy;
            p.anim = anim;
            p.facingRight = facingRight;
            p.grounded = grounded;
            p.viewer = viewer;
            p.hasPotion = hasPotion;
            return p;
        }
    }

    public static final class WorldState {
        public boolean potionTaken;
        public boolean doorOpen;
        public String potionCarrierId = "";
        public float potionX = 157f;
        public float potionY = 160f;
        public float doorX = 262f;
        public float doorY = 153f;
        public float doorWidth = 48f;
        public float doorHeight = 80f;
    }

    private final LinkedHashMap<String, PlayerState> players = new LinkedHashMap<>();
    private final WorldState world = new WorldState();
    private String requestedNickname = "";
    private String myNickname = "";
    private String myId = "";
    private int myCat = 1;
    private String status = "Desconectado";
    private boolean connected;
    private boolean viewerMode;
    private GameWebSocketClient client;

    private GameSession() {}

    public synchronized void setRequestedNickname(String nickname) {
        requestedNickname = sanitizeNickname(nickname);
    }

    public synchronized String getRequestedNickname() { return requestedNickname; }
    public synchronized String getMyNickname() { return myNickname == null || myNickname.isEmpty() ? requestedNickname : myNickname; }
    public synchronized String getMyId() { return myId; }
    public synchronized int getMyCat() { return myCat; }
    public synchronized String getMyCatColor() { return catColor(myCat); }
    public synchronized boolean isConnected() { return connected; }
    public synchronized String getStatus() { return status; }

    public synchronized void connect(String nickname) {
        setRequestedNickname(nickname);
        disconnect();
        viewerMode = false;
        status = "Conectando a " + SERVER_URL;
        client = new GameWebSocketClient(SERVER_URL, requestedNickname, false);
        client.connectAsync();
    }

    public synchronized void connectAsViewer() {
        if (client != null && viewerMode) return;
        disconnect();
        viewerMode = true;
        status = "Mirando sala";
        client = new GameWebSocketClient(SERVER_URL, "viewer", true);
        client.connectAsync();
    }

    public synchronized void disconnect() {
        if (client != null) {
            client.closeGracefully();
            client = null;
        }
        connected = false;
        viewerMode = false;
        myId = "";
        myNickname = "";
        players.clear();
        status = "Desconectado";
    }

    public synchronized void sendInput(float moveX, boolean jumpPressed, boolean jumpHeld) {
        if (client != null && connected) {
            client.sendInput(moveX, jumpPressed, jumpHeld);
        }
    }

    synchronized void onConnected() {
        connected = true;
        status = "Conectado. Esperando JOIN_OK...";
    }

    synchronized void onDisconnected(String reason) {
        connected = false;
        status = reason == null || reason.isEmpty() ? "Desconectado" : reason;
    }

    synchronized void onJoinOk(String id, String nickname, int cat) {
        if (cat <= 0) {
            viewerMode = true;
            status = "Mirando sala";
            return;
        }
        viewerMode = false;
        myId = id;
        myNickname = nickname;
        myCat = Math.max(1, Math.min(MAX_PLAYERS, cat));
        status = "En sala como " + myNickname;
    }

    synchronized void onPlayerList(List<PlayerState> list) {
        players.clear();
        for (PlayerState p : list) {
            if (p != null && !p.viewer && p.id != null) {
                players.put(p.id, p.copy());
            }
        }
    }

    synchronized void onWorldState(WorldState ws) {
        if (ws == null) return;
        world.potionTaken = ws.potionTaken;
        world.doorOpen = ws.doorOpen;
        world.potionCarrierId = ws.potionCarrierId == null ? "" : ws.potionCarrierId;
        world.potionX = ws.potionX;
        world.potionY = ws.potionY;
        world.doorX = ws.doorX;
        world.doorY = ws.doorY;
        world.doorWidth = ws.doorWidth;
        world.doorHeight = ws.doorHeight;
    }

    public synchronized List<PlayerState> snapshotPlayers() {
        ArrayList<PlayerState> copy = new ArrayList<>();
        for (Map.Entry<String, PlayerState> entry : players.entrySet()) {
            copy.add(entry.getValue().copy());
        }
        return copy;
    }

    public synchronized WorldState snapshotWorld() {
        WorldState w = new WorldState();
        w.potionTaken = world.potionTaken;
        w.doorOpen = world.doorOpen;
        w.potionCarrierId = world.potionCarrierId;
        w.potionX = world.potionX;
        w.potionY = world.potionY;
        w.doorX = world.doorX;
        w.doorY = world.doorY;
        w.doorWidth = world.doorWidth;
        w.doorHeight = world.doorHeight;
        return w;
    }

    public static String catColor(int cat) {
        switch (cat) {
            case 1: return "lila";
            case 2: return "rojo";
            case 3: return "turquesa";
            case 4: return "amarillo";
            case 5: return "verde";
            case 6: return "azul oscuro";
            case 7: return "naranja";
            case 8: return "azul claro";
            default: return "sin color";
        }
    }

    public static String sanitizeNickname(String value) {
        if (value == null) return "Player";
        String clean = value.trim();
        if (clean.isEmpty()) clean = "Player";
        if (clean.length() > 16) clean = clean.substring(0, 16);
        return clean.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }
}
