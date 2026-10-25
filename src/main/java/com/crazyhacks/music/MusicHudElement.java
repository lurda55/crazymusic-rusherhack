package com.crazyhacks.music;

import java.awt.Color;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;
import org.rusherhack.client.api.RusherHackAPI;
import org.rusherhack.client.api.events.client.input.EventMouse;
import org.rusherhack.client.api.feature.hud.ResizeableHudElement;
import org.rusherhack.client.api.render.IRenderer2D;
import org.rusherhack.client.api.render.RenderContext;
import org.rusherhack.client.api.render.font.IFontRenderer;
import org.rusherhack.client.api.render.graphic.TextureGraphic;
import org.rusherhack.client.api.setting.ColorSetting;
import org.rusherhack.core.event.stage.Stage;
import org.rusherhack.core.event.subscribe.Subscribe;
import org.rusherhack.core.setting.BooleanSetting;

/**
 * The "now playing" box: cover, title, artist, a progress bar and previous / play-pause / next buttons, which can be
 * clicked while the chat is open. Like Craftify, without Essential.
 */
public class MusicHudElement extends ResizeableHudElement {
	private static final int BASE_W = 170;
	private static final int BASE_H = 40;
	private static final int COVER = 32;
	/** Row of buttons (previous, play/pause, next) under the box. */
	private static final int CONTROLS_H = 14;
	/** Centers of the three buttons from the middle of the box, and their clickable half size. */
	private static final int[] BUTTON_DX = {-22, 0, 22};
	private static final String[] BUTTON_CMD = {"prev", "playpause", "next"};
	private static final int HALF_W = 9;
	private static final int HALF_H = 6;
	private static final int SHADOW = 0xFF151515;

	private final BooleanSetting background = new BooleanSetting("Background", "Draw the background behind the box", true);
	private final BooleanSetting showCover = new BooleanSetting("Cover", "Show the song's cover", true);
	private final BooleanSetting controls = new BooleanSetting("Controls", "Previous, play/pause and next buttons (click them with the chat open)", true);
	private final BooleanSetting hideWhenPaused = new BooleanSetting("HideWhenPaused", "Hide the box while the music is paused", false);
	private final BooleanSetting chatMessage = new BooleanSetting("ChatMessage", "Write the new song in chat when it changes", false);
	private final ColorSetting barColor = new ColorSetting("BarColor", "Color of the progress bar", new Color(0x1DB954))
			.setAlphaAllowed(false).setThemeSyncAllowed(true);

	public MusicHudElement() {
		super("Music");
		this.setDescription("Now playing (Spotify, Apple Music, YouTube...) with cover, progress and controls. Made by lurda55");
		this.registerSettings(this.background, this.showCover, this.controls, this.hideWhenPaused, this.chatMessage, this.barColor);
		RusherHackAPI.getEventBus().subscribe(this);
		MediaInfo.onNewSong(t -> {
			if (!this.chatMessage.getValue() || !this.isToggled()) return;
			mc.execute(() -> {
				if (mc.player != null) {
					mc.gui.getChat().addMessage(Component.literal("§d♪ §f" + t.title() + (t.artist().isEmpty() ? "" : " §7- " + t.artist())));
				}
			});
		});
	}

	@Override
	public void onEnable() {
		super.onEnable();
		MediaInfo.ensureRunning();
	}

	@Override
	public void onDisable() {
		super.onDisable();
		MediaInfo.stop();
	}

	@Override
	public void tick() {
		// started again if the script died (or the element was on when the game started)
		if (this.isToggled()) MediaInfo.ensureRunning();
	}

	private boolean editing() {
		return mc.screen != null && mc.screen.equals(RusherHackAPI.getThemeManager().getHudEditorScreen());
	}

	/** There is something to show in game right now (not counting the HUD editor). */
	private boolean showing() {
		MediaInfo.Track t = MediaInfo.track();
		return !t.title().isEmpty() && !(this.hideWhenPaused.getValue() && !t.playing());
	}

	@Override
	public boolean shouldDrawBackground() {
		return this.background.getValue() && (this.showing() || this.editing());
	}

	@Override
	public double getWidth() {
		return BASE_W;
	}

	@Override
	public double getHeight() {
		return BASE_H + (this.controls.getValue() ? CONTROLS_H : 0);
	}

	@Override
	public void renderContent(RenderContext context, double mouseX, double mouseY) {
		boolean editing = this.editing();
		if (!editing && !this.showing()) return;
		if (!MediaInfo.supported()) {
			this.getFontRenderer().drawString("Music needs Windows", 4, 4, 0xFFFF5555, true);
			return;
		}

		IRenderer2D r = this.getRenderer();
		IFontRenderer font = this.getFontRenderer();
		MediaInfo.Track t = MediaInfo.track();
		boolean nothing = t.title().isEmpty();

		double tx = 4;
		TextureGraphic cover = MediaInfo.cover();
		if (!nothing && cover != null && this.showCover.getValue()) {
			r.drawGraphicRectangle(cover, 4, 4, COVER, COVER);
			tx = 4 + COVER + 5;
		}
		double maxW = BASE_W - tx - 4;
		String title = nothing ? "Nothing playing" : t.title();
		String artist = nothing ? "Spotify, Apple Music, YouTube..." : t.artist().isEmpty() ? t.app() : t.artist();
		font.drawString(fit(font, title, maxW), tx, 4, 0xFFFFFFFF, true);
		font.drawString(fit(font, artist, maxW), tx, 14, 0xFFAAAAAA, true);

		// progress bar with the times on its sides
		if (!nothing && t.duration() > 0) {
			double pos = t.positionNow();
			String left = time(pos);
			String right = time(t.duration());
			double barX = tx + font.getStringWidth(left) + 3;
			double barEnd = BASE_W - 4 - font.getStringWidth(right) - 3;
			double barY = 29;
			font.drawString(left, tx, 26, 0xFFAAAAAA, true);
			font.drawString(right, BASE_W - 4 - font.getStringWidth(right), 26, 0xFFAAAAAA, true);
			// shadow under the bar, like the text shadow (1 pixel down and right)
			r.drawRectangle(barX + 1, barY + 1, barEnd - barX, 3, SHADOW);
			r.drawRectangle(barX, barY, barEnd - barX, 3, 0xFF404040);
			double filled = (barEnd - barX) * Math.min(1.0, pos / t.duration());
			r.drawRectangle(barX, barY, filled, 3, 0xFF000000 | (this.barColor.getValueRGB() & 0xFFFFFF));
		} else if (!nothing) {
			font.drawString(fit(font, t.app(), maxW), tx, 26, 0xFF777777, true);
		}
		if (this.controls.getValue()) this.drawControls(r, t.playing());
	}

	/** Previous, play/pause, next. White when the mouse is over them with the chat open, else light gray. */
	private void drawControls(IRenderer2D r, boolean playing) {
		int hovered = mc.screen instanceof ChatScreen ? this.buttonAt(org.rusherhack.client.api.utils.InputUtils.getMouseX(),
				org.rusherhack.client.api.utils.InputUtils.getMouseY()) : -1;
		int cy = BASE_H + CONTROLS_H / 2;
		for (int i = 0; i < 3; i++) {
			int cx = BASE_W / 2 + BUTTON_DX[i];
			int color = i == hovered ? 0xFFFFFFFF : 0xFFC8C8C8;
			if (i == hovered) r.drawRectangle(cx - HALF_W, cy - HALF_H, HALF_W * 2, HALF_H * 2, 0x40FFFFFF);
			switch (i) {
				// two triangles pointing left / right
				case 0 -> {
					triangle(r, cx - 4, cy, false, color);
					triangle(r, cx, cy, false, color);
				}
				case 2 -> {
					triangle(r, cx - 4, cy, true, color);
					triangle(r, cx, cy, true, color);
				}
				default -> {
					if (playing) {
						shadowRect(r, cx - 3, cy - 3, 2, 7, color);
						shadowRect(r, cx + 1, cy - 3, 2, 7, color);
					} else {
						triangle(r, cx - 2, cy, true, color);
					}
				}
			}
		}
	}

	/** Which button (0 previous, 1 play/pause, 2 next) is at this screen point, or -1. */
	private int buttonAt(double mx, double my) {
		double s = this.getScale();
		double lx = (mx - this.getStartX()) / s;
		double ly = (my - this.getStartY()) / s;
		double cy = BASE_H + CONTROLS_H / 2.0;
		for (int i = 0; i < 3; i++) {
			double cx = BASE_W / 2.0 + BUTTON_DX[i];
			if (Math.abs(lx - cx) <= HALF_W && Math.abs(ly - cy) <= HALF_H) return i;
		}
		return -1;
	}

	/** Clicks on the buttons while the chat is open (the only time there is a cursor in game). */
	@Subscribe(stage = Stage.PRE)
	private void onMouse(EventMouse.Key event) {
		if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT || event.getAction() != GLFW.GLFW_PRESS) return;
		if (!(mc.screen instanceof ChatScreen) || !this.controls.getValue() || !this.showing()) return;
		int b = this.buttonAt(event.getMouseX(), event.getMouseY());
		if (b < 0) return;
		MediaInfo.send(BUTTON_CMD[b]);
		event.setCancelled(true);
	}

	/** A 7 pixel tall, 4 wide triangle in x..x+4, pointing right or left, with a 1 pixel shadow. */
	private static void triangle(IRenderer2D r, int x, int cy, boolean right, int color) {
		for (int pass = 0; pass < 2; pass++) {
			int off = pass == 0 ? 1 : 0;
			int c = pass == 0 ? SHADOW : color;
			for (int row = -3; row <= 3; row++) {
				int len = 4 - Math.abs(row);
				int x0 = right ? x : x + 4 - len;
				r.drawRectangle(x0 + off, cy + row + off, len, 1, c);
			}
		}
	}

	private static void shadowRect(IRenderer2D r, int x, int y, int w, int h, int color) {
		r.drawRectangle(x + 1, y + 1, w, h, SHADOW);
		r.drawRectangle(x, y, w, h, color);
	}

	/** The text cut with "..." so it fits in the width. */
	private static String fit(IFontRenderer font, String s, double width) {
		if (font.getStringWidth(s) <= width) return s;
		String dots = "...";
		return font.trimStringToWidth(s, width - font.getStringWidth(dots)) + dots;
	}

	private static String time(double seconds) {
		long s = Math.max(0, Math.round(seconds));
		return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
	}
}
