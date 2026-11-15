package com.star.launcher.hud;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Original geometric vector icons for STAR Launcher's in-game touch HUD.
 * Drawn directly via Canvas with crisp strokes and rounded caps—no external bitmaps
 * or proprietary game textures.
 */
public final class StarHudIcons {
    public static final int SPECIAL_KEYBOARD = 1;
    public static final int SPECIAL_VIRTUAL_MOUSE = 2;

    private StarHudIcons() {}

    public static void drawElementIcon(
            Canvas canvas,
            StarHudElementId id,
            RectF bounds,
            Paint strokePaint,
            Paint fillPaint,
            Path scratchPath
    ) {
        if (id == null) return;
        drawIcon(canvas, id.toActionType().getDefaultIcon(), bounds, strokePaint, fillPaint, scratchPath);
    }

    public static void drawIcon(
            Canvas canvas,
            StarHudIconId iconId,
            RectF bounds,
            Paint strokePaint,
            Paint fillPaint,
            Path scratchPath
    ) {
        if (canvas == null || iconId == null || iconId == StarHudIconId.NONE || bounds == null || bounds.isEmpty()) {
            return;
        }
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float r = Math.min(bounds.width(), bounds.height()) * 0.5f;

        switch (iconId) {
            case JUMP:
            case MOVE_UP:
                drawJumpIcon(canvas, cx, cy, r, strokePaint, scratchPath);
                break;
            case ATTACK:
                drawAttackIcon(canvas, cx, cy, r, strokePaint, fillPaint, scratchPath);
                break;
            case INTERACT:
                drawInteractIcon(canvas, cx, cy, r, strokePaint, fillPaint, scratchPath);
                break;
            case SNEAK:
                drawSneakIcon(canvas, cx, cy, r, strokePaint, scratchPath);
                break;
            case SPRINT:
                drawSprintIcon(canvas, cx, cy, r, strokePaint, scratchPath);
                break;
            case INVENTORY:
                drawInventoryIcon(canvas, cx, cy, r, strokePaint, fillPaint);
                break;
            case CHAT:
                drawChatIcon(canvas, cx, cy, r, strokePaint, fillPaint, scratchPath);
                break;
            case THIRD_PERSON:
                drawCameraViewIcon(canvas, cx, cy, r, strokePaint, fillPaint, scratchPath);
                break;
            case PAUSE:
                drawPauseIcon(canvas, cx, cy, r, strokePaint);
                break;
            case DROP:
            case MOVE_DOWN:
                drawDropIcon(canvas, cx, cy, r, strokePaint, scratchPath);
                break;
            case SWAP_HANDS:
                drawSwapHandsIcon(canvas, cx, cy, r, strokePaint, scratchPath);
                break;
            case PICK_BLOCK:
                drawPickBlockIcon(canvas, cx, cy, r, strokePaint, fillPaint);
                break;
            case HOTBAR_SLOT:
                drawHotbarSlotIcon(canvas, cx, cy, r, strokePaint);
                break;
            case HOTBAR_NEXT:
            case MOVE_RIGHT:
                drawArrowRightIcon(canvas, cx, cy, r, strokePaint, scratchPath);
                break;
            case HOTBAR_PREV:
            case MOVE_LEFT:
                drawArrowLeftIcon(canvas, cx, cy, r, strokePaint, scratchPath);
                break;
            case KEY_CAP:
                drawKeyCapIcon(canvas, cx, cy, r, strokePaint);
                break;
            case STAR_CUSTOM:
                drawStarCustomIcon(canvas, cx, cy, r, strokePaint, scratchPath);
                break;
            default:
                break;
        }
    }

    public static void drawSpecialIcon(
            Canvas canvas,
            int kind,
            RectF bounds,
            Paint strokePaint,
            Paint fillPaint
    ) {
        if (canvas == null || bounds == null || bounds.isEmpty()) return;
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float r = Math.min(bounds.width(), bounds.height()) * 0.5f;
        if (kind == SPECIAL_KEYBOARD) {
            RectF kb = new RectF(cx - r * 0.85f, cy - r * 0.52f, cx + r * 0.85f, cy + r * 0.52f);
            canvas.drawRoundRect(kb, r * 0.20f, r * 0.20f, strokePaint);
            float keyW = r * 0.22f;
            float keyH = r * 0.16f;
            for (int col = -1; col <= 1; col++) {
                float kx = cx + col * r * 0.42f;
                canvas.drawRoundRect(kx - keyW * 0.5f, cy - r * 0.24f, kx + keyW * 0.5f, cy - r * 0.24f + keyH, keyH * 0.3f, keyH * 0.3f, fillPaint);
            }
            canvas.drawRoundRect(cx - r * 0.44f, cy + r * 0.12f, cx + r * 0.44f, cy + r * 0.30f, keyH * 0.3f, keyH * 0.3f, fillPaint);
        } else if (kind == SPECIAL_VIRTUAL_MOUSE) {
            RectF body = new RectF(cx - r * 0.56f, cy - r * 0.80f, cx + r * 0.56f, cy + r * 0.80f);
            canvas.drawRoundRect(body, r * 0.52f, r * 0.52f, strokePaint);
            canvas.drawLine(cx, body.top, cx, cy - r * 0.08f, strokePaint);
            canvas.drawLine(body.left, cy - r * 0.08f, body.right, cy - r * 0.08f, strokePaint);
        }
    }

    private static void drawJumpIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint, Path path) {
        path.reset();
        path.moveTo(cx - r * 0.65f, cy + r * 0.18f);
        path.lineTo(cx, cy - r * 0.55f);
        path.lineTo(cx + r * 0.65f, cy + r * 0.18f);
        canvas.drawPath(path, strokePaint);

        canvas.drawLine(cx, cy - r * 0.48f, cx, cy + r * 0.56f, strokePaint);
        canvas.drawLine(cx - r * 0.42f, cy + r * 0.62f, cx + r * 0.42f, cy + r * 0.62f, strokePaint);
    }

    private static void drawAttackIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint, Paint fillPaint, Path path) {
        path.reset();
        path.moveTo(cx - r * 0.38f, cy + r * 0.18f);
        path.lineTo(cx + r * 0.32f, cy - r * 0.62f);
        path.lineTo(cx + r * 0.64f, cy - r * 0.64f);
        path.lineTo(cx + r * 0.62f, cy - r * 0.32f);
        path.lineTo(cx - r * 0.18f, cy + r * 0.38f);
        path.close();
        canvas.drawPath(path, strokePaint);

        canvas.drawLine(cx - r * 0.54f, cy + r * 0.02f, cx - r * 0.02f, cy + r * 0.54f, strokePaint);
        canvas.drawLine(cx - r * 0.32f, cy + r * 0.32f, cx - r * 0.62f, cy + r * 0.62f, strokePaint);
        canvas.drawCircle(cx - r * 0.64f, cy + r * 0.64f, strokePaint.getStrokeWidth() * 0.72f, fillPaint);
    }

    private static void drawInteractIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint, Paint fillPaint, Path path) {
        path.reset();
        path.moveTo(cx, cy - r * 0.66f);
        path.lineTo(cx + r * 0.66f, cy);
        path.lineTo(cx, cy + r * 0.66f);
        path.lineTo(cx - r * 0.66f, cy);
        path.close();
        canvas.drawPath(path, strokePaint);
        canvas.drawCircle(cx, cy, r * 0.18f, fillPaint);
    }

    private static void drawSneakIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint, Path path) {
        path.reset();
        path.moveTo(cx - r * 0.62f, cy - r * 0.42f);
        path.lineTo(cx, cy + r * 0.12f);
        path.lineTo(cx + r * 0.62f, cy - r * 0.42f);
        canvas.drawPath(path, strokePaint);

        path.reset();
        path.moveTo(cx - r * 0.48f, cy + r * 0.08f);
        path.lineTo(cx, cy + r * 0.56f);
        path.lineTo(cx + r * 0.48f, cy + r * 0.08f);
        canvas.drawPath(path, strokePaint);
    }

    private static void drawSprintIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint, Path path) {
        path.reset();
        path.moveTo(cx - r * 0.52f, cy - r * 0.48f);
        path.lineTo(cx - r * 0.02f, cy);
        path.lineTo(cx - r * 0.52f, cy + r * 0.48f);
        canvas.drawPath(path, strokePaint);

        path.reset();
        path.moveTo(cx + r * 0.04f, cy - r * 0.48f);
        path.lineTo(cx + r * 0.54f, cy);
        path.lineTo(cx + r * 0.04f, cy + r * 0.48f);
        canvas.drawPath(path, strokePaint);
    }

    private static void drawInventoryIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint, Paint fillPaint) {
        float gap = r * 0.16f;
        float cell = r * 0.50f;
        float corner = r * 0.14f;
        canvas.drawRoundRect(cx - gap - cell, cy - gap - cell, cx - gap, cy - gap, corner, corner, strokePaint);
        canvas.drawRoundRect(cx + gap, cy - gap - cell, cx + gap + cell, cy - gap, corner, corner, strokePaint);
        canvas.drawRoundRect(cx - gap - cell, cy + gap, cx - gap, cy + gap + cell, corner, corner, strokePaint);
        canvas.drawRoundRect(cx + gap, cy + gap, cx + gap + cell, cy + gap + cell, corner, corner, fillPaint);
    }

    private static void drawChatIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint, Paint fillPaint, Path path) {
        RectF bubble = new RectF(cx - r * 0.72f, cy - r * 0.56f, cx + r * 0.72f, cy + r * 0.34f);
        canvas.drawRoundRect(bubble, r * 0.28f, r * 0.28f, strokePaint);

        path.reset();
        path.moveTo(cx - r * 0.38f, bubble.bottom);
        path.lineTo(cx - r * 0.54f, cy + r * 0.68f);
        path.lineTo(cx - r * 0.08f, bubble.bottom);
        canvas.drawPath(path, strokePaint);

        float dotR = Math.max(1.5f, r * 0.10f);
        float dotY = bubble.centerY();
        canvas.drawCircle(cx - r * 0.32f, dotY, dotR, fillPaint);
        canvas.drawCircle(cx, dotY, dotR, fillPaint);
        canvas.drawCircle(cx + r * 0.32f, dotY, dotR, fillPaint);
    }

    private static void drawCameraViewIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint, Paint fillPaint, Path path) {
        path.reset();
        path.moveTo(cx - r * 0.78f, cy);
        path.quadTo(cx, cy - r * 0.72f, cx + r * 0.78f, cy);
        path.quadTo(cx, cy + r * 0.72f, cx - r * 0.78f, cy);
        path.close();
        canvas.drawPath(path, strokePaint);
        canvas.drawCircle(cx, cy, r * 0.24f, fillPaint);
    }

    private static void drawPauseIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint) {
        float offsetX = r * 0.28f;
        float halfH = r * 0.54f;
        canvas.drawLine(cx - offsetX, cy - halfH, cx - offsetX, cy + halfH, strokePaint);
        canvas.drawLine(cx + offsetX, cy - halfH, cx + offsetX, cy + halfH, strokePaint);
    }

    private static void drawDropIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint, Path path) {
        canvas.drawLine(cx, cy - r * 0.60f, cx, cy + r * 0.32f, strokePaint);
        path.reset();
        path.moveTo(cx - r * 0.50f, cy - r * 0.08f);
        path.lineTo(cx, cy + r * 0.40f);
        path.lineTo(cx + r * 0.50f, cy - r * 0.08f);
        canvas.drawPath(path, strokePaint);
        canvas.drawLine(cx - r * 0.58f, cy + r * 0.64f, cx + r * 0.58f, cy + r * 0.64f, strokePaint);
    }

    private static void drawSwapHandsIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint, Path path) {
        path.reset();
        path.moveTo(cx - r * 0.60f, cy - r * 0.26f);
        path.lineTo(cx + r * 0.55f, cy - r * 0.26f);
        path.lineTo(cx + r * 0.24f, cy - r * 0.58f);
        canvas.drawPath(path, strokePaint);

        path.reset();
        path.moveTo(cx + r * 0.60f, cy + r * 0.26f);
        path.lineTo(cx - r * 0.55f, cy + r * 0.26f);
        path.lineTo(cx - r * 0.24f, cy + r * 0.58f);
        canvas.drawPath(path, strokePaint);
    }

    private static void drawPickBlockIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint, Paint fillPaint) {
        RectF cube = new RectF(cx - r * 0.48f, cy - r * 0.48f, cx + r * 0.48f, cy + r * 0.48f);
        canvas.drawRoundRect(cube, r * 0.16f, r * 0.16f, strokePaint);
        canvas.drawCircle(cx, cy, r * 0.20f, fillPaint);
        canvas.drawLine(cx, cy - r * 0.78f, cx, cy - r * 0.48f, strokePaint);
        canvas.drawLine(cx + r * 0.48f, cy, cx + r * 0.78f, cy, strokePaint);
    }

    private static void drawHotbarSlotIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint) {
        RectF box = new RectF(cx - r * 0.62f, cy - r * 0.62f, cx + r * 0.62f, cy + r * 0.62f);
        canvas.drawRoundRect(box, r * 0.22f, r * 0.22f, strokePaint);
    }

    private static void drawArrowRightIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint, Path path) {
        path.reset();
        path.moveTo(cx - r * 0.26f, cy - r * 0.56f);
        path.lineTo(cx + r * 0.42f, cy);
        path.lineTo(cx - r * 0.26f, cy + r * 0.56f);
        canvas.drawPath(path, strokePaint);
    }

    private static void drawArrowLeftIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint, Path path) {
        path.reset();
        path.moveTo(cx + r * 0.26f, cy - r * 0.56f);
        path.lineTo(cx - r * 0.42f, cy);
        path.lineTo(cx + r * 0.26f, cy + r * 0.56f);
        canvas.drawPath(path, strokePaint);
    }

    private static void drawKeyCapIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint) {
        RectF cap = new RectF(cx - r * 0.66f, cy - r * 0.56f, cx + r * 0.66f, cy + r * 0.56f);
        canvas.drawRoundRect(cap, r * 0.24f, r * 0.24f, strokePaint);
    }

    private static void drawStarCustomIcon(Canvas canvas, float cx, float cy, float r, Paint strokePaint, Path path) {
        path.reset();
        path.moveTo(cx, cy - r * 0.74f);
        path.lineTo(cx + r * 0.22f, cy - r * 0.22f);
        path.lineTo(cx + r * 0.74f, cy);
        path.lineTo(cx + r * 0.22f, cy + r * 0.22f);
        path.lineTo(cx, cy + r * 0.74f);
        path.lineTo(cx - r * 0.22f, cy + r * 0.22f);
        path.lineTo(cx - r * 0.74f, cy);
        path.lineTo(cx - r * 0.22f, cy - r * 0.22f);
        path.close();
        canvas.drawPath(path, strokePaint);
    }
}
