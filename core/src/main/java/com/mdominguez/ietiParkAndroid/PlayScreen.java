package com.mdominguez.ietiParkAndroid;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

import java.util.List;

public class PlayScreen extends ScreenAdapter {
    private static final float FIXED_STEP_SECONDS = 1f / 30f;
    private static final int PLAYER_SLOTS = 8;
    private static final float TOUCH_CONTROL_MARGIN = 30f;
    private static final float JOYSTICK_BASE_RADIUS = 78f;
    private static final float JOYSTICK_KNOB_RADIUS = 30f;
    private static final float JOYSTICK_CAPTURE_RADIUS = 126f;
    private static final float ACTION_BUTTON_RADIUS = 58f;
    private static final float TOUCH_AXIS_DEAD_ZONE = 0.18f;
    private static final int MAX_TOUCH_POINTS = 20;
    private static final Color HUD = Color.valueOf("FFFFFF");
    private static final Color PANEL = Color.valueOf("07140ACC");
    private static final Color STROKE = Color.valueOf("7EE5A4CC");
    private static final Color ACCENT = Color.valueOf("35FF74DD");

    private final GameApp game;
    private final int levelIndex;
    private final String nickname;
    private final LevelData levelData;
    private final OrthographicCamera camera = new OrthographicCamera();
    private final OrthographicCamera hudCamera = new OrthographicCamera();
    private final Viewport viewport;
    private final Viewport hudViewport = new ScreenViewport(hudCamera);
    private final LevelRenderer levelRenderer = new LevelRenderer();
    private final Array<LevelRenderer.SpriteRuntimeState> spriteRuntimeStates = new Array<>();
    private final Array<RuntimeTransform> layerRuntimeStates = new Array<>();
    private final boolean[] layerVisibilityStates;
    private final ObjectMap<String, String> animationIdByName = new ObjectMap<>();
    private final ObjectMap<Integer, Integer> playerSlotByCat = new ObjectMap<>();
    private final FloatArray animationElapsed = new FloatArray();
    private final GameInputState inputState = new GameInputState();
    private final Vector2 joystickCenter = new Vector2();
    private final Vector2 joystickKnobOffset = new Vector2();
    private final Vector2 actionButtonCenter = new Vector2();
    private final Vector2 hudTouchPoint = new Vector2();
    private final Vector3 hudTouchPoint3 = new Vector3();
    private final GlyphLayout layout = new GlyphLayout();
    private final Rectangle backButton = new Rectangle();

    private int firstPlayerSpriteIndex;
    private int joystickPointer = -1;
    private int actionPointer = -1;
    private float sendAccumulator = 0f;
    private boolean previousJumpHeld = false;

    public PlayScreen(GameApp game, int levelIndex) {
        this(game, levelIndex, GameSession.get().getRequestedNickname());
    }

    public PlayScreen(GameApp game, int levelIndex, String nickname) {
        this.game = game;
        this.levelIndex = levelIndex;
        this.nickname = GameSession.sanitizeNickname(nickname);
        this.levelData = LevelLoader.loadLevel(levelIndex);
        this.layerVisibilityStates = buildInitialLayerVisibility(levelData);
        this.viewport = new FitViewport(levelData.viewportWidth, levelData.viewportHeight, camera);
        buildAnimationIndex();
        hideEditorCats();
        addPlayerSlots();
        initializeRuntimeStates();
        viewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), false);
        hudViewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        updateTouchControlLayout();
        updateBackButtonBounds();
        applyInitialCamera();
    }

    @Override public void show() {
        Gdx.input.setInputProcessor(null);
        Gdx.input.setOnscreenKeyboardVisible(false);
        AndroidHardwareInputBridge.setCaptureEnabled(isAndroidRuntime());
        if (!GameSession.get().isConnected()) GameSession.get().connect(nickname);
    }

    @Override public void hide() {
        AndroidHardwareInputBridge.setCaptureEnabled(false);
    }

    @Override public void render(float delta) {
        updateInput();
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || handleBackButton()) {
            GameSession.get().disconnect();
            game.setScreen(new MenuScreen(game));
            return;
        }

        sendAccumulator += Math.max(0f, delta);
        if (sendAccumulator >= FIXED_STEP_SECONDS || inputState.jumpPressed) {
            sendAccumulator = 0f;
            GameSession.get().sendInput(inputState.moveX, inputState.jumpPressed, inputState.jumpHeld);
        }

        applyNetworkState(delta);
        updateStaticCamera();
        viewport.apply();
        ScreenUtils.clear(levelData.backgroundColor);
        SpriteBatch batch = game.getBatch();
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        levelRenderer.render(levelData, game.getAssetManager(), batch, camera, spriteRuntimeStates, layerVisibilityStates, layerRuntimeStates);
        batch.end();
        renderHud();
    }

    private void applyNetworkState(float delta) {
        List<GameSession.PlayerState> players = GameSession.get().snapshotPlayers();
        for (int i = firstPlayerSpriteIndex; i < firstPlayerSpriteIndex + PLAYER_SLOTS && i < spriteRuntimeStates.size; i++) {
            spriteRuntimeStates.get(i).visible = false;
        }
        GameSession.WorldState world = GameSession.get().snapshotWorld();
        applyWorldSprites(world);

        float dt = Math.max(0f, delta);
        for (GameSession.PlayerState p : players) {
            if (p.cat < 1 || p.cat > PLAYER_SLOTS) continue;
            Integer slotIndexObj = playerSlotByCat.get(p.cat);
            if (slotIndexObj == null) continue;
            int slotIndex = slotIndexObj;
            LevelRenderer.SpriteRuntimeState state = spriteRuntimeStates.get(slotIndex);
            state.visible = true;
            state.worldX = p.x;
            state.worldY = p.y;
            state.flipX = !p.facingRight;
            String animName = (p.anim == null || p.anim.isEmpty() ? "idle" : p.anim) + "_cat" + p.cat;
            applyAnimation(slotIndex, animName, dt);
        }
    }

    private void applyWorldSprites(GameSession.WorldState world) {
        for (int i = 0; i < levelData.sprites.size && i < spriteRuntimeStates.size; i++) {
            LevelData.LevelSprite sprite = levelData.sprites.get(i);
            String type = normalize(sprite.type + " " + sprite.name);
            LevelRenderer.SpriteRuntimeState state = spriteRuntimeStates.get(i);
            if (type.contains("potion")) state.visible = !world.potionTaken;
            if (type.contains("tree")) state.visible = !world.doorOpen;
        }
    }

    private void applyAnimation(int spriteIndex, String animationName, float dt) {
        String id = animationIdByName.get(normalize(animationName));
        LevelRenderer.SpriteRuntimeState state = spriteRuntimeStates.get(spriteIndex);
        if (id == null) return;
        LevelData.AnimationClip clip = levelData.animationClips.get(id);
        if (clip == null) return;
        state.animationId = id;
        state.texturePath = clip.texturePath;
        state.frameWidth = clip.frameWidth;
        state.frameHeight = clip.frameHeight;
        state.anchorX = clip.anchorX;
        state.anchorY = clip.anchorY;
        float elapsed = animationElapsed.get(spriteIndex) + dt;
        animationElapsed.set(spriteIndex, elapsed);
        int total = totalFrames(state.texturePath, state.frameWidth, state.frameHeight);
        int start = Math.max(0, Math.min(total - 1, clip.startFrame));
        int end = Math.max(start, Math.min(total - 1, clip.endFrame));
        int span = Math.max(1, end - start + 1);
        int frame = start + ((int)(elapsed * Math.max(1f, clip.fps)) % span);
        state.frameIndex = frame;
    }

    private int totalFrames(String path, int fw, int fh) {
        if (path == null || fw <= 0 || fh <= 0 || !game.getAssetManager().isLoaded(path, Texture.class)) return 1;
        Texture t = game.getAssetManager().get(path, Texture.class);
        return Math.max(1, (t.getWidth() / fw) * (t.getHeight() / fh));
    }

    private void updateInput() {
        inputState.reset();
        if (isAndroidRuntime()) {
            if (AndroidHardwareInputBridge.isLeftPressed()) inputState.moveX -= 1f;
            if (AndroidHardwareInputBridge.isRightPressed()) inputState.moveX += 1f;
            inputState.jumpHeld = AndroidHardwareInputBridge.isJumpHeld();
            inputState.jumpPressed = AndroidHardwareInputBridge.consumeJumpQueued();
        } else {
            if (Gdx.input.isKeyPressed(Input.Keys.LEFT) || Gdx.input.isKeyPressed(Input.Keys.A)) inputState.moveX -= 1f;
            if (Gdx.input.isKeyPressed(Input.Keys.RIGHT) || Gdx.input.isKeyPressed(Input.Keys.D)) inputState.moveX += 1f;
            inputState.jumpHeld = Gdx.input.isKeyPressed(Input.Keys.SPACE) || Gdx.input.isKeyPressed(Input.Keys.UP) || Gdx.input.isKeyPressed(Input.Keys.W);
            inputState.jumpPressed = inputState.jumpHeld && !previousJumpHeld;
        }
        applyTouchInput();
        previousJumpHeld = inputState.jumpHeld;
    }

    private void applyTouchInput() {
        if (!shouldShowTouchControls()) return;
        updateTouchControlLayout();
        if (!isPointerStillActive(joystickPointer)) { joystickPointer = -1; joystickKnobOffset.setZero(); }
        if (!isPointerStillActive(actionPointer)) actionPointer = -1;
        for (int pointer = 0; pointer < MAX_TOUCH_POINTS; pointer++) {
            if (!Gdx.input.isTouched(pointer) || pointer == joystickPointer || pointer == actionPointer) continue;
            hudViewport.unproject(hudTouchPoint3.set(Gdx.input.getX(pointer), Gdx.input.getY(pointer), 0));
            hudTouchPoint.set(hudTouchPoint3.x, hudTouchPoint3.y);
            if (joystickPointer < 0 && hudTouchPoint.dst(joystickCenter) <= JOYSTICK_CAPTURE_RADIUS) joystickPointer = pointer;
            else if (actionPointer < 0 && hudTouchPoint.dst(actionButtonCenter) <= ACTION_BUTTON_RADIUS) actionPointer = pointer;
        }
        if (joystickPointer >= 0) {
            hudViewport.unproject(hudTouchPoint3.set(Gdx.input.getX(joystickPointer), Gdx.input.getY(joystickPointer), 0));
            joystickKnobOffset.set(hudTouchPoint3.x - joystickCenter.x, hudTouchPoint3.y - joystickCenter.y);
            if (joystickKnobOffset.len() > JOYSTICK_BASE_RADIUS) joystickKnobOffset.setLength(JOYSTICK_BASE_RADIUS);
            float axis = joystickKnobOffset.x / JOYSTICK_BASE_RADIUS;
            inputState.moveX = Math.abs(axis) < TOUCH_AXIS_DEAD_ZONE ? inputState.moveX : axis;
        }
        boolean actionHeld = actionPointer >= 0;
        inputState.jumpPressed = inputState.jumpPressed || (actionHeld && !previousJumpHeld);
        inputState.jumpHeld = inputState.jumpHeld || actionHeld;
    }

    private void renderHud() {
        hudViewport.apply();
        ShapeRenderer shapes = game.getShapeRenderer();
        shapes.setProjectionMatrix(hudCamera.combined);
        if (shouldShowTouchControls()) {
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(PANEL); shapes.circle(joystickCenter.x, joystickCenter.y, JOYSTICK_BASE_RADIUS, 48);
            shapes.setColor(ACCENT); shapes.circle(joystickCenter.x + joystickKnobOffset.x, joystickCenter.y + joystickKnobOffset.y, JOYSTICK_KNOB_RADIUS, 32);
            shapes.setColor(PANEL); shapes.circle(actionButtonCenter.x, actionButtonCenter.y, ACTION_BUTTON_RADIUS, 48);
            shapes.end();
            shapes.begin(ShapeRenderer.ShapeType.Line);
            shapes.setColor(STROKE); shapes.circle(joystickCenter.x, joystickCenter.y, JOYSTICK_BASE_RADIUS, 48); shapes.circle(actionButtonCenter.x, actionButtonCenter.y, ACTION_BUTTON_RADIUS, 48);
            shapes.end();
        }
        SpriteBatch batch = game.getBatch();
        BitmapFont font = game.getFont();
        batch.setProjectionMatrix(hudCamera.combined);
        batch.begin();
        font.getData().setScale(1.25f); font.setColor(HUD);
        font.draw(batch, "< MENU", backButton.x, backButton.y + backButton.height - 8);
        font.draw(batch, GameSession.get().getMyNickname() + "  " + GameSession.get().getStatus(), 18, hudViewport.getWorldHeight() - 18);
        List<GameSession.PlayerState> players = GameSession.get().snapshotPlayers();
        float y = hudViewport.getWorldHeight() - 48;
        for (GameSession.PlayerState p : players) { font.draw(batch, "cat" + p.cat + " " + p.nickname, 18, y); y -= 22; }
        font.getData().setScale(1f); font.setColor(Color.WHITE);
        batch.end();
    }

    private boolean handleBackButton() {
        if (!Gdx.input.justTouched()) return false;
        hudViewport.unproject(hudTouchPoint3.set(Gdx.input.getX(), Gdx.input.getY(), 0));
        return backButton.contains(hudTouchPoint3.x, hudTouchPoint3.y);
    }

    private void buildAnimationIndex() {
        for (ObjectMap.Entry<String, LevelData.AnimationClip> e : levelData.animationClips) {
            if (e.value != null && e.value.name != null) animationIdByName.put(normalize(e.value.name), e.value.id);
        }
    }

    private void hideEditorCats() {
        for (int i = 0; i < levelData.sprites.size; i++) {
            LevelData.LevelSprite s = levelData.sprites.get(i);
            if (normalize(s.type + " " + s.name).contains("cat")) {
                // handled through runtime state after it is built
            }
        }
    }

    private void addPlayerSlots() {
        firstPlayerSpriteIndex = levelData.sprites.size;
        for (int cat = 1; cat <= PLAYER_SLOTS; cat++) {
            String animId = animationIdByName.get(normalize("idle_cat" + cat));
            String texture = "levels/media/idle_cat" + cat + ".png";
            float w = 16, h = 16, ax = 0.5f, ay = 0.7f;
            LevelData.AnimationClip clip = animId == null ? null : levelData.animationClips.get(animId);
            if (clip != null) { texture = clip.texturePath; w = clip.frameWidth; h = clip.frameHeight; ax = clip.anchorX; ay = clip.anchorY; }
            levelData.sprites.add(new LevelData.LevelSprite("player_cat" + cat, "player", 0f, -200, -200, w, h, ax, ay, false, false, 0, texture, animId));
            playerSlotByCat.put(cat, firstPlayerSpriteIndex + cat - 1);
        }
    }

    private void initializeRuntimeStates() {
        spriteRuntimeStates.clear(); animationElapsed.clear();
        for (int i = 0; i < levelData.sprites.size; i++) {
            LevelData.LevelSprite s = levelData.sprites.get(i);
            boolean visible = !normalize(s.type + " " + s.name).contains("cat") || i >= firstPlayerSpriteIndex;
            spriteRuntimeStates.add(new LevelRenderer.SpriteRuntimeState(s.frameIndex, s.anchorX, s.anchorY, s.x, s.y, visible, s.flipX, s.flipY, Math.max(1, Math.round(s.width)), Math.max(1, Math.round(s.height)), s.texturePath, s.animationId));
            animationElapsed.add(0f);
        }
        layerRuntimeStates.clear();
        for (int i = 0; i < levelData.layers.size; i++) layerRuntimeStates.add(new RuntimeTransform(levelData.layers.get(i).x, levelData.layers.get(i).y));
    }

    private boolean[] buildInitialLayerVisibility(LevelData level) {
        boolean[] visible = new boolean[level.layers.size];
        for (int i = 0; i < visible.length; i++) visible[i] = level.layers.get(i).visible;
        return visible;
    }

    private void applyInitialCamera() {
        updateStaticCamera();
    }

    /**
     * Camera fija: siempre muestra el viewport completo del nivel.
     * No sigue al jugador porque en este juego se debe ver todo el mapa.
     */
    private void updateStaticCamera() {
        camera.setToOrtho(false);
        camera.position.set(
            levelData.viewportX + levelData.viewportWidth / 2f,
            levelData.worldHeight - (levelData.viewportY + levelData.viewportHeight / 2f),
            0f
        );
        camera.update();
    }

    private void updateTouchControlLayout() {
        float w = hudViewport.getWorldWidth(), h = hudViewport.getWorldHeight();
        joystickCenter.set(TOUCH_CONTROL_MARGIN + JOYSTICK_BASE_RADIUS, TOUCH_CONTROL_MARGIN + JOYSTICK_BASE_RADIUS);
        actionButtonCenter.set(w - TOUCH_CONTROL_MARGIN - ACTION_BUTTON_RADIUS, TOUCH_CONTROL_MARGIN + ACTION_BUTTON_RADIUS);
    }

    private void updateBackButtonBounds() { backButton.set(14, hudViewport.getWorldHeight() - 56, 140, 44); }
    private boolean shouldShowTouchControls() { return isAndroidRuntime() || Gdx.input.isPeripheralAvailable(Input.Peripheral.MultitouchScreen); }
    private boolean isAndroidRuntime() { return Gdx.app.getType() == Application.ApplicationType.Android; }
    private boolean isPointerStillActive(int pointer) { return pointer >= 0 && pointer < MAX_TOUCH_POINTS && Gdx.input.isTouched(pointer); }
    private String normalize(String v) { return v == null ? "" : v.trim().toLowerCase(); }

    @Override public void resize(int width, int height) { viewport.update(width, height, false); hudViewport.update(width, height, true); updateTouchControlLayout(); updateBackButtonBounds(); }
}
