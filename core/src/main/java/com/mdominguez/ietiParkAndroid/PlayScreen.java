package com.mdominguez.ietiParkAndroid;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ScreenAdapter;


public class PlayScreen extends ScreenAdapter {
    private final GameApp game;
    private final String nickname;
    private LevelGameScreen currentLevel;
    private int currentLevelIndex = 0;

    public PlayScreen(GameApp game, int levelIndex) {
        this(game, levelIndex, GameSession.get().getRequestedNickname());
    }

    public PlayScreen(GameApp game, int levelIndex, String nickname) {
        this.game = game;
        this.nickname = GameSession.sanitizeNickname(nickname);
        this.currentLevelIndex = Math.max(0, levelIndex);
    }

    @Override
    public void show() {
        openLevel(currentLevelIndex);
    }

    @Override
    public void render(float delta) {
        if (currentLevel == null) {
            openLevel(currentLevelIndex);
        }

        if (currentLevel != null) {
            currentLevel.render(delta);
        }

        // El servidor usa shouldChangeScreen / nextLevelIndex para decir cuándo pasar al siguiente nivel.
        if (GameSession.get().consumeLevelChangeTo(currentLevelIndex)) {
            int nextLevel = GameSession.get().getNextLevelIndex();
            if (nextLevel >= 0 && nextLevel != currentLevelIndex) {
                openLevel(nextLevel);
            }
        }
    }

    private void openLevel(int levelIndex) {
        if (currentLevel != null) {
            currentLevel.hide();
            currentLevel.dispose();
        }

        currentLevelIndex = Math.max(0, levelIndex);

        game.queueReferencedAssetsForLevel(currentLevelIndex);
        game.getAssetManager().finishLoading();

        if (currentLevelIndex == 1) {
            currentLevel = new Level1Screen(game, nickname);
        } else {
            currentLevelIndex = 0;
            currentLevel = new Level0Screen(game, nickname);
        }
        currentLevel.show();
        currentLevel.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    @Override
    public void resize(int width, int height) {
        if (currentLevel != null) currentLevel.resize(width, height);
    }

    @Override
    public void pause() {
        if (currentLevel != null) currentLevel.pause();
    }

    @Override
    public void resume() {
        if (currentLevel != null) currentLevel.resume();
    }

    @Override
    public void hide() {
        if (currentLevel != null) currentLevel.hide();
    }

    @Override
    public void dispose() {
        if (currentLevel != null) {
            currentLevel.dispose();
            currentLevel = null;
        }
    }
}
