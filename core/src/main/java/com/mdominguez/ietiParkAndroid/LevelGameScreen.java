package com.mdominguez.ietiParkAndroid;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
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

class LevelGameScreen extends ScreenAdapter {
    private static final float FIXED_STEP_SECONDS = 1f / 30f;
    private static final int PLAYER_SLOTS = 8;
    private static final float TOUCH_CONTROL_MARGIN = 30f;
    private static final float JOYSTICK_BASE_RADIUS = 78f;
    private static final float JOYSTICK_KNOB_RADIUS = 30f;
    private static final float JOYSTICK_CAPTURE_RADIUS = 126f;
    private static final float ACTION_BUTTON_RADIUS = 58f;
    private static final float TOUCH_AXIS_DEAD_ZONE = 0.18f;
    private static final int MAX_TOUCH_POINTS = 20;
    private static final float PLAYER_DRAW_SIZE = 32f;
    private static final float CAT_SOURCE_BASE_SIZE = 16f;
    private static final float CARRIED_POTION_SIZE = 20f;
    // El spritesheet de la poción es grande; en el mundo la dibujamos más pequeña.
    private static final float POTION_FLOOR_SIZE = 24f;
    private static final float TREE_DRAW_SIZE = 100f;
    private static final float TREE_ALIVE_FPS = 4f;
    private static final Color HUD = Color.BLACK;
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
    private int carriedPotionSpriteIndex = -1;
    private int treeSpriteIndex = -1;
    private int buttonSpriteIndex = -1;
    private boolean buttonAnimationFinished = false;
    private float buttonAnimationTime = 0f;
    private int treeSourceSpriteIndex = -1;
    private boolean treeWasOpening = false;
    private boolean treeAnimationFinished = false;
    private float treeAnimationTime = 0f;
    private int joystickPointer = -1;
    private int actionPointer = -1;
    private float sendAccumulator = 0f;
    private boolean previousJumpHeld = false;

    public LevelGameScreen(GameApp game, int levelIndex) {
        this(game, levelIndex, GameSession.get().getRequestedNickname());
    }

    public LevelGameScreen(GameApp game, int levelIndex, String nickname) {
        this.game = game;
        this.levelIndex = levelIndex;
        this.nickname = GameSession.sanitizeNickname(nickname);
        this.levelData = LevelLoader.loadLevel(levelIndex);
        this.layerVisibilityStates = buildInitialLayerVisibility(levelData);
        this.viewport = new FitViewport(levelData.viewportWidth, levelData.viewportHeight, camera);
        buildAnimationIndex();
        hideEditorCats();
        addPlayerSlots();
        addSmallPotionSprite();
        addLevel2ButtonSprite();
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
        // El cambio de nivel lo gestiona PlayScreen, no cada nivel por separado.
        updateCamera();
        viewport.apply();
        ScreenUtils.clear(levelData.backgroundColor);
        SpriteBatch batch = game.getBatch();
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        levelRenderer.render(levelData, game.getAssetManager(), batch, camera, spriteRuntimeStates, layerVisibilityStates, layerRuntimeStates);
        drawPotionOverCarrier(batch);
        batch.end();
        renderLevel2DynamicObjects();
        renderHud();
    }

    private void applyNetworkState(float delta) {
        List<GameSession.PlayerState> players = GameSession.get().snapshotPlayers();
        for (int i = firstPlayerSpriteIndex; i < firstPlayerSpriteIndex + PLAYER_SLOTS && i < spriteRuntimeStates.size; i++) {
            spriteRuntimeStates.get(i).visible = false;
        }
        GameSession.WorldState world = GameSession.get().snapshotWorld();

        float dt = Math.max(0f, delta);
        // 1) Pintamos los jugadores que el servidor dice que están en la sala.
        for (GameSession.PlayerState p : players) {
            if (p.level != levelIndex) continue;
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

        // 2) Pintamos el mundo: árbol fijo, poción y botón/plataforma del nivel 1.
        applyPotionAndTree(world, players);
        applyLevel2ButtonVisual(world);
    }

    private void applyPotionAndTree(GameSession.WorldState world, List<GameSession.PlayerState> players) {
        if (world == null) return;

        // La poción grande del suelo solo se ve si está disponible.
        // Si alguien la lleva, se oculta aquí y se dibuja pequeña sobre el gato en drawPotionOverCarrier().
        boolean potionOnFloor = !world.potionTaken && !world.potionConsumed && findPotionCarrier(world, players) == null;

        for (int i = 0; i < levelData.sprites.size && i < spriteRuntimeStates.size; i++) {
            LevelData.LevelSprite sprite = levelData.sprites.get(i);
            String text = spriteSearchText(sprite);
            LevelRenderer.SpriteRuntimeState state = spriteRuntimeStates.get(i);

            if (isPotionSprite(text) && i != carriedPotionSpriteIndex && i != buttonSpriteIndex) {
                state.visible = potionOnFloor;
                if (potionOnFloor) {
                    state.worldX = world.potionX;
                    state.worldY = world.potionY;
                    applyWorldAnimation(i, potionAnimationNames(), Gdx.graphics.getDeltaTime(), POTION_FLOOR_SIZE, POTION_FLOOR_SIZE);
                }
            }

            if (isTreeSprite(text)) {
                treeSpriteIndex = i;
                treeSourceSpriteIndex = i;
            }
        }

        updateTreeAnimation(world);

        if (carriedPotionSpriteIndex >= 0 && carriedPotionSpriteIndex < spriteRuntimeStates.size) {
            spriteRuntimeStates.get(carriedPotionSpriteIndex).visible = false;
        }
    }

    private void updateTreeAnimation(GameSession.WorldState world) {
        if (treeSpriteIndex < 0 || treeSpriteIndex >= spriteRuntimeStates.size) return;

        LevelRenderer.SpriteRuntimeState state = spriteRuntimeStates.get(treeSpriteIndex);
        state.visible = true;
        state.worldX = world.doorX + world.doorWidth * 0.5f;
        state.worldY = world.doorY + world.doorHeight * 0.5f;

        if (world.doorOpen) {
            if (!treeWasOpening) {
                treeWasOpening = true;
                treeAnimationFinished = false;
                treeAnimationTime = 0f;
                animationElapsed.set(treeSpriteIndex, 0f);
            }
            playTreeAliveOnce(treeSpriteIndex);
        } else {
            treeWasOpening = false;
            treeAnimationFinished = false;
            treeAnimationTime = 0f;
            applyWorldAnimation(treeSpriteIndex, treeDeadAnimationNames(), Gdx.graphics.getDeltaTime(), TREE_DRAW_SIZE, TREE_DRAW_SIZE);
        }
    }

    private void playTreeAliveOnce(int spriteIndex) {
        String id = findAnimationId(treeAliveAnimationNames());
        if (id == null) {
            // Si no se encuentra la animación de curación, mantenemos visible el árbol al tamaño correcto.
            LevelRenderer.SpriteRuntimeState fallback = spriteRuntimeStates.get(spriteIndex);
            fallback.visible = true;
            fallback.drawWidth = TREE_DRAW_SIZE;
            fallback.drawHeight = TREE_DRAW_SIZE;
            return;
        }

        LevelData.AnimationClip clip = levelData.animationClips.get(id);
        if (clip == null) return;

        LevelRenderer.SpriteRuntimeState state = spriteRuntimeStates.get(spriteIndex);
        state.visible = true;
        state.animationId = id;
        state.texturePath = clip.texturePath;
        state.frameWidth = clip.frameWidth;
        state.frameHeight = clip.frameHeight;
        state.drawWidth = TREE_DRAW_SIZE;
        state.drawHeight = TREE_DRAW_SIZE;
        state.anchorX = 0.5f;
        state.anchorY = 0.5f;

        int total = totalFrames(state.texturePath, state.frameWidth, state.frameHeight);
        int start = Math.max(0, Math.min(total - 1, clip.startFrame));
        int end = Math.max(start, Math.min(total - 1, clip.endFrame));

        if (!treeAnimationFinished) {
            treeAnimationTime += Gdx.graphics.getDeltaTime();
            int frameOffset = (int)(treeAnimationTime * TREE_ALIVE_FPS);
            int frame = start + frameOffset;
            if (frame >= end) {
                frame = end;
                treeAnimationFinished = true;
            }
            state.frameIndex = frame;
        } else {
            state.frameIndex = end;
        }
    }

    private void applyLevel2ButtonVisual(GameSession.WorldState world) {
        if (levelIndex != 1 || world == null || buttonSpriteIndex < 0 || buttonSpriteIndex >= spriteRuntimeStates.size) return;

        LevelRenderer.SpriteRuntimeState state = spriteRuntimeStates.get(buttonSpriteIndex);
        state.visible = world.buttonVisible;
        if (!world.buttonVisible) return;

        state.worldX = world.buttonX + world.buttonWidth * 0.5f;
        state.worldY = world.buttonY + world.buttonHeight * 0.5f;

        float size = Math.max(24f, Math.max(world.buttonWidth, world.buttonHeight));
        if (world.buttonActive) {
            playButtonPressedOnce(buttonSpriteIndex, size);
        } else {
            buttonAnimationFinished = false;
            buttonAnimationTime = 0f;
            applyWorldAnimation(buttonSpriteIndex, buttonIdleAnimationNames(), Gdx.graphics.getDeltaTime(), size, size);
        }
    }

    private void playButtonPressedOnce(int spriteIndex, float drawSize) {
        String id = findAnimationId(buttonPressedAnimationNames());
        if (id == null) {
            applyWorldAnimation(spriteIndex, buttonIdleAnimationNames(), Gdx.graphics.getDeltaTime(), drawSize, drawSize);
            return;
        }

        LevelData.AnimationClip clip = levelData.animationClips.get(id);
        if (clip == null) return;

        LevelRenderer.SpriteRuntimeState state = spriteRuntimeStates.get(spriteIndex);
        state.visible = true;
        state.animationId = id;
        state.texturePath = clip.texturePath;
        state.frameWidth = clip.frameWidth;
        state.frameHeight = clip.frameHeight;
        state.drawWidth = drawSize;
        state.drawHeight = drawSize;
        state.anchorX = 0.5f;
        state.anchorY = 0.5f;

        int total = totalFrames(state.texturePath, state.frameWidth, state.frameHeight);
        int start = Math.max(0, Math.min(total - 1, clip.startFrame));
        int end = Math.max(start, Math.min(total - 1, clip.endFrame));

        if (!buttonAnimationFinished) {
            buttonAnimationTime += Gdx.graphics.getDeltaTime();
            int frame = start + (int)(buttonAnimationTime * Math.max(1f, clip.fps));
            if (frame >= end) {
                frame = end;
                buttonAnimationFinished = true;
            }
            state.frameIndex = frame;
        } else {
            state.frameIndex = end;
        }
    }

    private GameSession.PlayerState findPotionCarrier(GameSession.WorldState world, List<GameSession.PlayerState> players) {
        if (world == null || world.potionCarrierId == null || world.potionCarrierId.length() == 0) return null;
        for (GameSession.PlayerState p : players) {
            if (p != null && world.potionCarrierId.equals(p.id)) return p;
        }
        return null;
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
        // Las animaciones run/jump usan frames más grandes que idle.
        // Para que el gato no parezca pequeño, mantenemos la misma escala de píxel:
        // idle 16px -> 32px, run 20px -> 40px, jump 20x24px -> 40x48px.
        state.drawWidth = PLAYER_DRAW_SIZE * Math.max(1f, clip.frameWidth) / CAT_SOURCE_BASE_SIZE;
        state.drawHeight = PLAYER_DRAW_SIZE * Math.max(1f, clip.frameHeight) / CAT_SOURCE_BASE_SIZE;
        state.anchorX = 0.5f;
        state.anchorY = 0.75f;
        float elapsed = animationElapsed.get(spriteIndex) + dt;
        animationElapsed.set(spriteIndex, elapsed);
        int total = totalFrames(state.texturePath, state.frameWidth, state.frameHeight);
        int start = Math.max(0, Math.min(total - 1, clip.startFrame));
        int end = Math.max(start, Math.min(total - 1, clip.endFrame));
        int span = Math.max(1, end - start + 1);
        int frame = start + ((int)(elapsed * Math.max(1f, clip.fps)) % span);
        state.frameIndex = frame;
    }

    private void applyWorldAnimation(int spriteIndex, String animationName, float dt, float drawWidth, float drawHeight) {
        applyWorldAnimation(spriteIndex, new String[] { animationName }, dt, drawWidth, drawHeight);
    }

    private void applyWorldAnimation(int spriteIndex, String[] animationNames, float dt, float drawWidth, float drawHeight) {
        if (spriteIndex < 0 || spriteIndex >= spriteRuntimeStates.size) return;

        String id = findAnimationId(animationNames);
        LevelRenderer.SpriteRuntimeState state = spriteRuntimeStates.get(spriteIndex);
        state.visible = true;
        state.drawWidth = drawWidth;
        state.drawHeight = drawHeight;

        if (id == null) {
            return;
        }

        LevelData.AnimationClip clip = levelData.animationClips.get(id);
        if (clip == null) return;

        state.animationId = id;
        state.texturePath = clip.texturePath;
        state.frameWidth = clip.frameWidth;
        state.frameHeight = clip.frameHeight;
        state.drawWidth = drawWidth;
        state.drawHeight = drawHeight;
        state.anchorX = 0.5f;
        state.anchorY = 0.5f;

        float elapsed = animationElapsed.get(spriteIndex) + dt;
        animationElapsed.set(spriteIndex, elapsed);
        int total = totalFrames(state.texturePath, state.frameWidth, state.frameHeight);
        int start = Math.max(0, Math.min(total - 1, clip.startFrame));
        int end = Math.max(start, Math.min(total - 1, clip.endFrame));
        int span = Math.max(1, end - start + 1);
        state.frameIndex = start + ((int)(elapsed * Math.max(1f, clip.fps)) % span);
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
        font.getData().setScale(1.25f);
        font.setColor(HUD);
        font.draw(batch, "< MENU", backButton.x, backButton.y + backButton.height - 8);

        // En escritorio no enseñamos el botón táctil, así que tampoco escribimos JUMP.
        if (shouldShowTouchControls()) {
            font.getData().setScale(1.05f);
            font.setColor(Color.BLACK);
            layout.setText(font, "JUMP");
            font.draw(batch, layout, actionButtonCenter.x - layout.width * 0.5f, actionButtonCenter.y + layout.height * 0.5f);
        }

        // Nick arriba a la derecha para no pisar el botón de menú.
        String playerText = GameSession.get().getMyNickname() + " (" + GameSession.get().getMyCatColor() + ")";
        font.getData().setScale(1.15f);
        font.setColor(Color.BLACK);
        layout.setText(font, playerText);
        font.draw(batch, layout, hudViewport.getWorldWidth() - layout.width - 18f, hudViewport.getWorldHeight() - 18f);
        font.getData().setScale(1f); font.setColor(Color.WHITE);
        batch.end();
    }


    private void renderLevel2DynamicObjects() {
        if (levelIndex != 1) return;
        GameSession.WorldState world = GameSession.get().snapshotWorld();
        if (world == null) return;

        ShapeRenderer shapes = game.getShapeRenderer();
        shapes.setProjectionMatrix(camera.combined);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (world.platformWidth > 0f && world.platformHeight > 0f) {
            shapes.setColor(world.platformActive ? Color.valueOf("B77CFFDD") : Color.valueOf("8E55D8CC"));
            float y = levelData.worldHeight - world.platformY - world.platformHeight;
            shapes.rect(world.platformX, y, world.platformWidth, world.platformHeight);
        }
        // El botón se dibuja como sprite animado en applyLevel2ButtonVisual().
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void drawPotionOverCarrier(SpriteBatch batch) {
        GameSession.WorldState world = GameSession.get().snapshotWorld();
        if (world == null || world.potionConsumed) return;

        List<GameSession.PlayerState> players = GameSession.get().snapshotPlayers();
        GameSession.PlayerState carrier = findPotionCarrier(world, players);
        if (carrier == null) return;

        String texturePath = findPotionTexturePath();
        if (texturePath == null || !game.getAssetManager().isLoaded(texturePath, Texture.class)) return;

        Texture potionTexture = game.getAssetManager().get(texturePath, Texture.class);
        int frameW = 45;
        int frameH = 45;
        int cols = Math.max(1, potionTexture.getWidth() / frameW);
        int rows = Math.max(1, potionTexture.getHeight() / frameH);
        int total = Math.max(1, cols * rows);
        int frame = ((int)(animationElapsed.get(carriedPotionSpriteIndex) * 10f)) % total;
        animationElapsed.set(carriedPotionSpriteIndex, animationElapsed.get(carriedPotionSpriteIndex) + Gdx.graphics.getDeltaTime());

        TextureRegion region = new TextureRegion(
            potionTexture,
            (frame % cols) * frameW,
            (frame / cols) * frameH,
            frameW,
            frameH
        );

        float size = CARRIED_POTION_SIZE;
        float x = carrier.x - size * 0.5f;
        float yDown = carrier.y - 30f;
        float y = levelData.worldHeight - yDown - size * 0.5f;
        batch.draw(region, x, y, size, size);
    }

    private String findPotionTexturePath() {
        String id = findAnimationId(potionAnimationNames());
        if (id != null) {
            LevelData.AnimationClip clip = levelData.animationClips.get(id);
            if (clip != null && clip.texturePath != null) return clip.texturePath;
        }

        for (int i = 0; i < levelData.sprites.size; i++) {
            LevelData.LevelSprite s = levelData.sprites.get(i);
            String text = spriteSearchText(s);
            if (isPotionSprite(text) && !text.contains("carried")) return s.texturePath;
        }
        return null;
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
            // Todos los gatos se dibujan al mismo tamaño aunque cambie la animación.
            float drawW = PLAYER_DRAW_SIZE, drawH = PLAYER_DRAW_SIZE, ax = 0.5f, ay = 0.75f;
            LevelData.AnimationClip clip = animId == null ? null : levelData.animationClips.get(animId);
            if (clip != null) { texture = clip.texturePath; }
            levelData.sprites.add(new LevelData.LevelSprite("player_cat" + cat, "player", 0f, -200, -200, drawW, drawH, ax, ay, false, false, 0, texture, animId));
            playerSlotByCat.put(cat, firstPlayerSpriteIndex + cat - 1);
        }
    }


    private void addSmallPotionSprite() {
        for (int i = 0; i < levelData.sprites.size; i++) {
            LevelData.LevelSprite s = levelData.sprites.get(i);
            String text = spriteSearchText(s);
            if (isPotionSprite(text)) {
                carriedPotionSpriteIndex = levelData.sprites.size;
                levelData.sprites.add(new LevelData.LevelSprite(
                    "potion_carried",
                    "potion_carried",
                    s.depth,
                    -200,
                    -200,
                    CARRIED_POTION_SIZE,
                    CARRIED_POTION_SIZE,
                    0.5f,
                    0.5f,
                    false,
                    false,
                    s.frameIndex,
                    s.texturePath,
                    s.animationId
                ));
                return;
            }
        }
    }

    private void addLevel2ButtonSprite() {
        if (levelIndex != 1) return;

        String id = findAnimationId(buttonIdleAnimationNames());
        LevelData.AnimationClip clip = id == null ? null : levelData.animationClips.get(id);

        // Si el botón ya existe en el JSON, usamos ese sprite.
        for (int i = 0; i < levelData.sprites.size; i++) {
            LevelData.LevelSprite s = levelData.sprites.get(i);
            String text = spriteSearchText(s);
            if (isButtonSprite(text)) {
                buttonSpriteIndex = i;
                return;
            }
        }

        // Si no existe como sprite de editor, lo creamos dinámicamente a partir de la animación static_button / Icon7(6).
        if (clip == null) return;
        buttonSpriteIndex = levelData.sprites.size;
        levelData.sprites.add(new LevelData.LevelSprite(
            "level2_button",
            "button",
            0f,
            -200,
            -200,
            24f,
            24f,
            0.5f,
            0.5f,
            false,
            false,
            clip.startFrame,
            clip.texturePath,
            clip.id
        ));
    }

    private void initializeRuntimeStates() {
        spriteRuntimeStates.clear(); animationElapsed.clear();
        for (int i = 0; i < levelData.sprites.size; i++) {
            LevelData.LevelSprite s = levelData.sprites.get(i);
            String kind = normalize(s.type + " " + s.name);
            boolean visible = (!kind.contains("cat") || i >= firstPlayerSpriteIndex) && !kind.contains("potion_carried");
            LevelRenderer.SpriteRuntimeState runtime = new LevelRenderer.SpriteRuntimeState(s.frameIndex, s.anchorX, s.anchorY, s.x, s.y, visible, s.flipX, s.flipY, Math.max(1, Math.round(s.width)), Math.max(1, Math.round(s.height)), s.texturePath, s.animationId);
            runtime.drawWidth = s.width;
            runtime.drawHeight = s.height;
            spriteRuntimeStates.add(runtime);
            animationElapsed.add(0f);
        }
        layerRuntimeStates.clear();
        for (int i = 0; i < levelData.layers.size; i++) layerRuntimeStates.add(new RuntimeTransform(levelData.layers.get(i).x, levelData.layers.get(i).y));
    }

    private String[] potionAnimationNames() {
        if (levelIndex == 1) {
            return new String[] { "Icon7(2)", "icon7(2)", "potion_green", "potiongreen", "green" };
        }
        return new String[] { "Icon1(2)(2)", "icon1(2)(2)", "potion_red", "potionred", "red" };
    }

    private String[] treeDeadAnimationNames() {
        return new String[] { "tree_die1", "tree_died", "tree_die", "dead_tree", "tree_roñoso", "tree" };
    }

    private String[] treeAliveAnimationNames() {
        return new String[] { "tree_alive1", "tree_alive", "tree_alive_1", "tree_2" };
    }

    private String[] buttonIdleAnimationNames() {
        return new String[] { "Icon7(6)", "icon7(6)", "static_button", "button_static" };
    }

    private String[] buttonPressedAnimationNames() {
        return new String[] { "Icon7(5)", "icon7(5)", "button", "button_pressed" };
    }

    private String findAnimationId(String... names) {
        if (names == null) return null;

        for (String name : names) {
            String direct = animationIdByName.get(normalize(name));
            if (direct != null) return direct;
        }

        // Segunda pasada: permite que coincida por nombre o por fichero del spritesheet.
        for (ObjectMap.Entry<String, LevelData.AnimationClip> e : levelData.animationClips) {
            LevelData.AnimationClip clip = e.value;
            if (clip == null) continue;
            String text = normalize(clip.name + " " + clip.texturePath);
            String looseText = normalizeLoose(text);
            for (String name : names) {
                String n = normalize(name);
                if (n.length() == 0) continue;
                if (text.contains(n) || looseText.contains(normalizeLoose(n))) {
                    return clip.id;
                }
            }
        }
        return null;
    }

    private String spriteSearchText(LevelData.LevelSprite sprite) {
        if (sprite == null) return "";
        String animName = "";
        if (sprite.animationId != null) {
            LevelData.AnimationClip clip = levelData.animationClips.get(sprite.animationId);
            if (clip != null) animName = clip.name + " " + clip.texturePath;
        }
        return normalize(sprite.type + " " + sprite.name + " " + sprite.texturePath + " " + animName);
    }

    private boolean isPotionSprite(String text) {
        String loose = normalizeLoose(text);
        if (text.contains("potion")) return true;
        if (levelIndex == 1) return loose.contains("icon72") || loose.contains("icon7");
        return loose.contains("icon122") || loose.contains("icon1") || text.contains("red");
    }

    private boolean isTreeSprite(String text) {
        return text.contains("tree") || text.contains("arbre") || text.contains("dead_tree") || text.contains("die1");
    }

    private boolean isButtonSprite(String text) {
        String loose = normalizeLoose(text);
        return text.contains("button") || text.contains("boton") || loose.contains("icon76") || text.contains("static_button");
    }

    private String normalizeLoose(String v) {
        if (v == null) return "";
        String normalized = normalize(v);
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    private boolean[] buildInitialLayerVisibility(LevelData level) {
        boolean[] visible = new boolean[level.layers.size];
        for (int i = 0; i < visible.length; i++) visible[i] = level.layers.get(i).visible;
        return visible;
    }

    private void applyInitialCamera() {
        camera.setToOrtho(false);
        camera.position.set(levelData.viewportX + levelData.viewportWidth * 0.5f, levelData.worldHeight - levelData.viewportY - levelData.viewportHeight * 0.5f, 0);
        camera.update();
    }

    private void updateCamera() {
        // Cámara fija: siempre se ve el nivel completo, no sigue al jugador.
        applyInitialCamera();
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
