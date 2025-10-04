/*
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at http://live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

package com.live2d.demo.full;

import android.app.Activity;
import android.content.Context;
import android.opengl.GLES20;
import android.os.Build;
import com.live2d.demo.LAppDefine;
import com.live2d.sdk.cubism.framework.CubismFramework;

import static android.opengl.GLES20.*;

public class LAppDelegate {
    public static LAppDelegate getInstance() {
        if (s_instance == null) {
            s_instance = new LAppDelegate();
        }
        return s_instance;
    }

    /**
     * クラスのインスタンス（シングルトン）を解放する。
     */
    public static void releaseInstance() {
        if (s_instance != null) {
            s_instance = null;
        }
    }

    /**
     * アプリケーションを非アクティブにする
     */
    public void deactivateApp() {
        isActive = false;
    }

    public void onStart(Activity activity) {
        onStartWithContext(activity);
        this.activity = activity;
    }

    public void onStartWithContext(Context context) {
        textureManager = new LAppTextureManager();
        view = new LAppView();

        this.context = context.getApplicationContext();
        if (context instanceof Activity) {
            this.activity = (Activity) context;
        }

        LAppPal.updateTime();
    }

    public void onPause() {
        currentModel = LAppLive2DManager.getInstance().getCurrentModel();
    }

    public void onStop() {
        if (view != null) {
            view.close();
        }
        textureManager = null;

        LAppLive2DManager.releaseInstance();
        CubismFramework.dispose();
    }

    public void onDestroy() {
        releaseInstance();
    }

    public void onSurfaceCreated() {
        // テクスチャサンプリング設定
        GLES20.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);

        // 透過設定
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        // Initialize Cubism SDK framework
        CubismFramework.initialize();
    }

    public void onSurfaceChanged(int width, int height) {
        // 描画範囲指定
        GLES20.glViewport(0, 0, width, height);
        windowWidth = width;
        windowHeight = height;

        // AppViewの初期化
        view.initialize();
        view.initializeSprite();

        // load models
        if (LAppLive2DManager.getInstance().getCurrentModel() != currentModel) {
            LAppLive2DManager.getInstance().changeScene(currentModel);
        }

        isActive = true;
    }

    public void run() {
        // 時間更新
        LAppPal.updateTime();

        // 画面初期化
        glClearColor(clearColorR, clearColorG, clearColorB, clearColorA);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glClearDepthf(1.0f);

        if (view != null) {
            view.render();
        }

        // アプリケーションを非アクティブにする
        if (!isActive && activity != null) {
            activity.finishAndRemoveTask();
        }
    }

    public void onTouchBegan(float x, float y) {
        mouseX = x;
        mouseY = y;

        if (view != null) {
            isCaptured = true;
            view.onTouchesBegan(mouseX, mouseY);
        }
    }

    public void onTouchEnd(float x, float y) {
        mouseX = x;
        mouseY = y;

        if (view != null) {
            isCaptured = false;
            view.onTouchesEnded(mouseX, mouseY);
        }
    }

    public void onTouchMoved(float x, float y) {
        mouseX = x;
        mouseY = y;

        if (isCaptured && view != null) {
            view.onTouchesMoved(mouseX, mouseY);
        }
    }

    public void onMultiTouchBegan(float x1, float y1, float x2, float y2) {
        mouseX = (x1 + x2) * 0.5f;
        mouseY = (y1 + y2) * 0.5f;

        if (view != null) {
            isCaptured = true;
            view.onTouchesBegan(x1, y1, x2, y2);
        }
    }

    public void onMultiTouchMoved(float x1, float y1, float x2, float y2) {
        mouseX = (x1 + x2) * 0.5f;
        mouseY = (y1 + y2) * 0.5f;

        if (isCaptured && view != null) {
            view.onTouchesMoved(x1, y1, x2, y2);
        }
    }

    // getter, setter群
    public Activity getActivity() {
        return activity;
    }

    public Context getContext() {
        return context != null ? context : activity;
    }

    public LAppTextureManager getTextureManager() {
        return textureManager;
    }

    public void setClearColor(float r, float g, float b, float a) {
        clearColorR = r;
        clearColorG = g;
        clearColorB = b;
        clearColorA = a;
    }

    public LAppView getView() {
        return view;
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    private static LAppDelegate s_instance;

    private LAppDelegate() {
        currentModel = 0;

        // Set up Cubism SDK framework.
        cubismOption.logFunction = new LAppPal.PrintLogFunction();
        cubismOption.loggingLevel = LAppDefine.cubismLoggingLevel;

        CubismFramework.cleanUp();
        CubismFramework.startUp(cubismOption);
    }

    private Activity activity;
    private Context context;

    private final CubismFramework.Option cubismOption = new CubismFramework.Option();

    private LAppTextureManager textureManager;
    private LAppView view;
    private int windowWidth;
    private int windowHeight;
    private boolean isActive = true;

    private float clearColorR = 1.0f;
    private float clearColorG = 1.0f;
    private float clearColorB = 1.0f;
    private float clearColorA = 1.0f;

    /**
     * モデルシーンインデックス
     */
    private int currentModel;

    /**
     * クリックしているか
     */
    private boolean isCaptured;
    /**
     * マウスのX座標
     */
    private float mouseX;
    /**
     * マウスのY座標
     */
    private float mouseY;
}
