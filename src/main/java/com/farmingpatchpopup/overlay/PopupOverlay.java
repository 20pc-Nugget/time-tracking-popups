/*
 * Adapted from CollectionLogOverlay in Collection Log Popup Enhanced, Copyright (c) 2026, SnakeSteak,
 * BSD 2-Clause (see LICENSE). The panel art, layout, animation and fonts are the original's; the
 * collection log specific parts (rarity, drop rates, kill counts, rarity effects) are gone.
 */
package com.farmingpatchpopup.overlay;

import com.farmingpatchpopup.FarmingPatchPopupConfig;
import com.farmingpatchpopup.sound.SoundManager;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

@Singleton
public class PopupOverlay extends Overlay
{
	// Matches the fixed size of the bundled BackgroundPanel art.
	private static final int BASE_PANEL_WIDTH = 379;
	private static final int BASE_PANEL_HEIGHT = 128;
	private static final int TOP_MARGIN = 2;

	// The icon slot is horizontally centered and straddles the panel's bottom edge.
	private static final int BASE_ICON_CANVAS_SIZE = 89;
	private static final int BASE_ICON_TARGET_SIZE = 66;
	private static final int BASE_ICON_SPRITE_X_OFFSET = 3;

	private static final int BASE_CORNER_PADDING_X = 16;
	private static final int BASE_CORNER_LABEL_BASELINE_Y = 93;
	private static final int BASE_CORNER_VALUE_BASELINE_Y = 116;

	private static final int BASE_NAME_BASELINE_Y = 48;
	private static final int BASE_NAME_SIDE_MARGIN = 16;

	private static final int BASE_PROGRESS_BAR_TOP_Y = 64;
	private static final int BASE_PROGRESS_BAR_HEIGHT = 3;
	private static final float PROGRESS_BAR_SIDE_INSET_FRACTION = 0.0607f;
	private static final float DIVIDER_DARKNESS_SCALE = 0.5f;

	private static final int BASE_CAPTION_BASELINE_Y = 22;

	private static final int RGB_MASK = 0xFFFFFF;

	// Neutral body insets {left, top, right, bottom}, measured off the art.
	private static final int[] NEUTRAL_PANEL_BODY_INSETS = {7, 7, 8, 8};
	private static final int[] NEUTRAL_ICON_BODY_INSETS = {10, 9, 9, 9};

	private static final float BASE_CORNER_LABEL_FONT_SIZE = 16f;
	private static final float BASE_CORNER_VALUE_FONT_SIZE = 19f;
	private static final float BASE_NAME_FONT_SIZE = 27f;
	private static final float BASE_NAME_FONT_MIN_SIZE = 13f;
	private static final float BASE_CAPTION_FONT_SIZE = 16f;

	private static final long FOLD_MILLIS = 550;
	private static final long ICON_POP_MILLIS = 400;
	private static final long FADE_MILLIS = 400;
	/** How long each crop's icon is held before the next one, when several different crops finished together. */
	private static final long ICON_CYCLE_MILLIS = 1300;

	// Compact layout
	private static final int COMPACT_PANEL_HEIGHT = 92;
	private static final int COMPACT_ICON_CENTER_X = 35;
	private static final int COMPACT_ICON_CENTER_Y = 44;
	private static final int COMPACT_ICON_BOX_SIZE = 52;
	private static final int COMPACT_ICON_TARGET_SIZE = 44;
	private static final int COMPACT_DIVIDER_X = 66;
	private static final int COMPACT_DIVIDER_WIDTH = 2;
	private static final int COMPACT_DIVIDER_TOP_Y = 10;
	private static final int COMPACT_DIVIDER_BOTTOM_Y = 78;
	private static final int COMPACT_TEXT_X = 76;
	private static final int COMPACT_RIGHT_MARGIN = 14;
	private static final int COMPACT_CAPTION_BASELINE_Y = 21;
	private static final int COMPACT_NAME_BASELINE_Y = 49;
	private static final int COMPACT_STATS_BASELINE_Y = 72;
	private static final int COMPACT_STAT_GAP = 12;
	private static final float COMPACT_STAT_LABEL_FONT_SIZE = 16f;
	private static final int COMPACT_PROGRESS_BAR_HEIGHT = 4;

	private static final float ICON_MIN_SCALE = 0.4f;
	private static final float FOLD_OVERSHOOT = 1.0f;
	private static final float ICON_POP_OVERSHOOT = 1.9f;

	// Fixed look, taken from the original's defaults.
	private static final int BACKGROUND_DARKNESS = 30;
	private static final Color CAPTION_COLOR = new Color(0xFF981F);
	private static final Color STAT_LABEL_COLOR = new Color(0xDCDCD6);
	private static final Color STAT_VALUE_COLOR = new Color(0xFFCD2D);

	private final Client client;
	private final ItemManager itemManager;
	private final FarmingPatchPopupConfig config;
	private final SoundManager soundManager;

	private final Map<PopupTier, BufferedImage> sourceBackgrounds = new EnumMap<>(PopupTier.class);
	private final Map<PopupTier, BufferedImage> sourceIconFrames = new EnumMap<>(PopupTier.class);
	private final Map<PopupTier, BufferedImage> neutralBackgrounds = new EnumMap<>(PopupTier.class);
	private final Map<PopupTier, BufferedImage> neutralIconFrames = new EnumMap<>(PopupTier.class);
	private final Map<BufferedImage, boolean[]> bodyMasks = new IdentityHashMap<>();

	// Recoloured art, built the first time a tier/colour/style combination is shown.
	private final Map<String, Art> artCache = new HashMap<>();

	// Only ever touched from the client thread.
	private final Deque<PopupData> queue = new ArrayDeque<>();
	private PopupData current;
	private long notificationStartMillis;
	private Point lastAutoLocation;

	private int lastScalePercent = -1;
	private PanelLayout lastLayout;
	private boolean compact;
	private boolean textOutlineEnabled;
	private int panelWidth;
	private int panelHeight;
	private int iconCanvasSize;
	private int iconX;
	private int iconY;
	private int iconTargetSize;
	private int iconSpriteXOffset;
	private int cornerPaddingX;
	private int cornerLabelBaselineY;
	private int cornerValueBaselineY;
	private int cornerTextMaxWidth;
	private int nameBaselineY;
	private int nameSideMargin;
	private int captionBaselineY;
	private int progressBarTopY;
	private int progressBarHeight;
	private int progressBarX;
	private int progressBarWidth;
	private int dividerX;
	private int dividerY;
	private int dividerWidth;
	private int dividerHeight;
	private int textX;
	private int textMaxWidth;
	private int statsBaselineY;
	private int statGap;
	private float nameFontMinSize;
	private Font cornerLabelFont;
	private Font cornerValueFont;
	private Font nameFont;
	private Font captionFont;
	private Font compactStatLabelFont;

	@Inject
	public PopupOverlay(Client client, ItemManager itemManager, FarmingPatchPopupConfig config, SoundManager soundManager)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.config = config;
		this.soundManager = soundManager;
		setPosition(OverlayPosition.TOP_CENTER);
		// Above the native widgets and ahead of any other TOP_CENTER overlay, so the panel stays pinned at the top.
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGHEST);
		setMovable(true);

		loadArt(PanelStyle.COLORFUL, sourceBackgrounds, sourceIconFrames);
		loadArt(PanelStyle.NEUTRAL, neutralBackgrounds, neutralIconFrames);
		for (PopupTier tier : PopupTier.values())
		{
			bodyMasks.put(sourceBackgrounds.get(tier), PanelRecolorer.floodFillBodyMask(sourceBackgrounds.get(tier)));
			bodyMasks.put(sourceIconFrames.get(tier), PanelRecolorer.floodFillBodyMask(sourceIconFrames.get(tier)));
			bodyMasks.put(neutralBackgrounds.get(tier), insetBodyMask(neutralBackgrounds.get(tier), NEUTRAL_PANEL_BODY_INSETS));
			bodyMasks.put(neutralIconFrames.get(tier), insetBodyMask(neutralIconFrames.get(tier), NEUTRAL_ICON_BODY_INSETS));
		}

		applyScale(config.popupScale(), config.panelLayout());
	}

	// ---------------------------------------------------------------- public API

	public void enqueue(PopupData data)
	{
		queue.addLast(data);
	}

	public void clear()
	{
		queue.clear();
		current = null;
	}

	public boolean isIdle()
	{
		return current == null && queue.isEmpty();
	}

	// ---------------------------------------------------------------- scaling

	private void applyScale(int scalePercent, PanelLayout layout)
	{
		float scale = scalePercent / 100f;

		panelWidth = Math.round(BASE_PANEL_WIDTH * scale);
		panelHeight = Math.round(BASE_PANEL_HEIGHT * scale);
		iconCanvasSize = Math.round(BASE_ICON_CANVAS_SIZE * scale);
		iconX = (panelWidth - iconCanvasSize) / 2;
		iconY = panelHeight - iconCanvasSize / 2;
		iconTargetSize = Math.round(BASE_ICON_TARGET_SIZE * scale);
		iconSpriteXOffset = Math.round(BASE_ICON_SPRITE_X_OFFSET * scale);

		cornerPaddingX = Math.round(BASE_CORNER_PADDING_X * scale);
		cornerLabelBaselineY = Math.round(BASE_CORNER_LABEL_BASELINE_Y * scale);
		cornerValueBaselineY = Math.round(BASE_CORNER_VALUE_BASELINE_Y * scale);
		cornerTextMaxWidth = iconX - cornerPaddingX;

		nameBaselineY = Math.round(BASE_NAME_BASELINE_Y * scale);
		nameSideMargin = Math.round(BASE_NAME_SIDE_MARGIN * scale);
		nameFontMinSize = BASE_NAME_FONT_MIN_SIZE * scale;

		captionBaselineY = Math.round(BASE_CAPTION_BASELINE_Y * scale);

		progressBarTopY = Math.round(BASE_PROGRESS_BAR_TOP_Y * scale);
		progressBarHeight = Math.max(1, Math.round(BASE_PROGRESS_BAR_HEIGHT * scale));
		progressBarX = Math.round(panelWidth * PROGRESS_BAR_SIDE_INSET_FRACTION);
		progressBarWidth = panelWidth - 2 * progressBarX;

		cornerLabelFont = FontManager.getRunescapeBoldFont().deriveFont(BASE_CORNER_LABEL_FONT_SIZE * scale);
		cornerValueFont = FontManager.getRunescapeBoldFont().deriveFont(BASE_CORNER_VALUE_FONT_SIZE * scale);
		nameFont = FontManager.getRunescapeBoldFont().deriveFont(BASE_NAME_FONT_SIZE * scale);
		captionFont = FontManager.getRunescapeBoldFont().deriveFont(BASE_CAPTION_FONT_SIZE * scale);

		compact = layout == PanelLayout.COMPACT;
		if (compact)
		{
			applyCompactScale(scale);
		}

		lastScalePercent = scalePercent;
		lastLayout = layout;
	}

	private void applyCompactScale(float scale)
	{
		panelHeight = Math.round(COMPACT_PANEL_HEIGHT * scale);
		iconCanvasSize = Math.round(COMPACT_ICON_BOX_SIZE * scale);
		iconX = Math.round(COMPACT_ICON_CENTER_X * scale) - iconCanvasSize / 2;
		iconY = Math.round(COMPACT_ICON_CENTER_Y * scale) - iconCanvasSize / 2;
		iconTargetSize = Math.round(COMPACT_ICON_TARGET_SIZE * scale);

		dividerX = Math.round(COMPACT_DIVIDER_X * scale);
		dividerY = Math.round(COMPACT_DIVIDER_TOP_Y * scale);
		dividerWidth = Math.max(1, Math.round(COMPACT_DIVIDER_WIDTH * scale));
		dividerHeight = Math.round(COMPACT_DIVIDER_BOTTOM_Y * scale) - dividerY;

		textX = Math.round(COMPACT_TEXT_X * scale);
		textMaxWidth = panelWidth - textX - Math.round(COMPACT_RIGHT_MARGIN * scale);
		captionBaselineY = Math.round(COMPACT_CAPTION_BASELINE_Y * scale);
		nameBaselineY = Math.round(COMPACT_NAME_BASELINE_Y * scale);
		statsBaselineY = Math.round(COMPACT_STATS_BASELINE_Y * scale);
		statGap = Math.round(COMPACT_STAT_GAP * scale);
		compactStatLabelFont = FontManager.getRunescapeSmallFont().deriveFont(COMPACT_STAT_LABEL_FONT_SIZE * scale);

		progressBarHeight = Math.max(1, Math.round(COMPACT_PROGRESS_BAR_HEIGHT * scale));
	}

	// ---------------------------------------------------------------- art

	/** The panel art for one tier/colour/style, recoloured once and then reused. */
	@Value
	private static class Art
	{
		BufferedImage background;
		BufferedImage iconFrame;
		BufferedImage compactBackground;
		Rectangle compactBody;
		Color dividerColor;
		Color nameColor;
		Color accent;
	}

	private Art artFor(PopupData data, boolean neutral)
	{
		Color color = opaque(data.getColor());
		String key = (neutral ? "N" : "C") + data.getTier().ordinal() + (neutral ? "" : "-" + color.getRGB());
		return artCache.computeIfAbsent(key, k -> buildArt(data.getTier(), color, neutral));
	}

	private Art buildArt(PopupTier tier, Color color, boolean neutral)
	{
		BufferedImage background;
		BufferedImage iconFrame;
		Color divider;
		Color name;
		Color accent;
		BufferedImage maskSource;

		if (neutral)
		{
			background = neutralBackgrounds.get(tier);
			iconFrame = neutralIconFrames.get(tier);
			divider = PanelRecolorer.deriveBackground(Color.WHITE, Math.max(1, Math.round(BACKGROUND_DARKNESS * DIVIDER_DARKNESS_SCALE)));
			name = Color.WHITE;
			accent = Color.WHITE;
			maskSource = neutralBackgrounds.get(tier);
		}
		else
		{
			Color panelBackground = PanelRecolorer.deriveBackground(color, BACKGROUND_DARKNESS);
			background = PanelRecolorer.recolor(sourceBackgrounds.get(tier), tier, color, panelBackground);
			iconFrame = PanelRecolorer.recolor(sourceIconFrames.get(tier), tier, color, panelBackground);
			divider = PanelRecolorer.deriveBackground(color, Math.max(1, Math.round(BACKGROUND_DARKNESS * DIVIDER_DARKNESS_SCALE)));
			name = PanelRecolorer.legibleOn(color, panelBackground);
			accent = color;
			maskSource = sourceBackgrounds.get(tier);
		}

		return new Art(background, iconFrame, withoutMiddleRows(background, COMPACT_PANEL_HEIGHT),
			compactBody(maskSource, bodyMasks.get(maskSource)), divider, name, accent);
	}

	private static Rectangle compactBody(BufferedImage source, boolean[] mask)
	{
		int width = source.getWidth();
		int left = width;
		int right = -1;
		int top = source.getHeight();
		int bottom = -1;
		for (int i = 0; i < mask.length; i++)
		{
			if (mask[i])
			{
				left = Math.min(left, i % width);
				right = Math.max(right, i % width);
				top = Math.min(top, i / width);
				bottom = Math.max(bottom, i / width);
			}
		}
		int bottomInset = source.getHeight() - 1 - bottom;
		return new Rectangle(left, top, right - left + 1, COMPACT_PANEL_HEIGHT - bottomInset - top);
	}

	// Cut rather than scaled, so the frame and the Neutral corner studs stay pixel-exact.
	private static BufferedImage withoutMiddleRows(BufferedImage art, int height)
	{
		int top = height / 2;
		int bottom = height - top;
		BufferedImage cut = new BufferedImage(art.getWidth(), height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = cut.createGraphics();
		graphics.drawImage(art.getSubimage(0, 0, art.getWidth(), top), 0, 0, null);
		graphics.drawImage(art.getSubimage(0, art.getHeight() - bottom, art.getWidth(), bottom), 0, top, null);
		graphics.dispose();
		return cut;
	}

	private static boolean[] insetBodyMask(BufferedImage image, int[] insets)
	{
		return PanelRecolorer.insetBodyMask(image.getWidth(), image.getHeight(), insets[0], insets[1], insets[2], insets[3]);
	}

	private static void loadArt(PanelStyle style, Map<PopupTier, BufferedImage> panels, Map<PopupTier, BufferedImage> frames)
	{
		panels.put(PopupTier.COMMON, loadImage(style.resource("Backgrounds/BackgroundPanel1.png")));
		panels.put(PopupTier.UNCOMMON, loadImage(style.resource("Backgrounds/BackgroundPanel2.png")));
		panels.put(PopupTier.RARE, loadImage(style.resource("Backgrounds/BackgroundPanel3.png")));
		panels.put(PopupTier.VERY_RARE, loadImage(style.resource("Backgrounds/BackgroundPanel4.png")));
		panels.put(PopupTier.PET, loadImage(style.resource("Backgrounds/BackgroundPanelPet.png")));

		frames.put(PopupTier.COMMON, loadImage(style.resource("Icons/IconPanel1.png")));
		frames.put(PopupTier.UNCOMMON, loadImage(style.resource("Icons/IconPanel2.png")));
		frames.put(PopupTier.RARE, loadImage(style.resource("Icons/IconPanel3.png")));
		frames.put(PopupTier.VERY_RARE, loadImage(style.resource("Icons/IconPanel4.png")));
		frames.put(PopupTier.PET, loadImage(style.resource("Icons/IconPanelPet.png")));
	}

	private static BufferedImage loadImage(String resourceName)
	{
		try (InputStream in = PopupOverlay.class.getResourceAsStream("/" + resourceName))
		{
			return ImageIO.read(in);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("Failed to load overlay image " + resourceName, e);
		}
	}

	// ---------------------------------------------------------------- rendering

	@Override
	public Dimension render(Graphics2D graphics)
	{
		long now = System.currentTimeMillis();
		// Still advanced in "Sound only": the queue is what plays and spaces out the sounds.
		advance(now);
		if (config.panelStyle() == PanelStyle.SOUND_ONLY)
		{
			return null;
		}

		int scalePercent = config.popupScale();
		PanelLayout layout = config.panelLayout();
		if (scalePercent != lastScalePercent || layout != lastLayout)
		{
			applyScale(scalePercent, layout);
		}

		// Compared by value, not identity: an alt-click that doesn't move still saves our own point.
		Point placed = getPreferredLocation();
		boolean userPlaced = getPreferredPosition() != null || (placed != null && !placed.equals(lastAutoLocation));
		if (!userPlaced)
		{
			// Centers on the viewport rect, not the canvas, so fixed/classic and the hybrid plugin work.
			int viewportWidth = client.getViewportWidth();
			int viewportCenterX = viewportWidth > 0
				? client.getViewportXOffset() + viewportWidth / 2
				: client.getRealDimensions().width / 2;
			lastAutoLocation = new Point(viewportCenterX - (panelWidth / 2), TOP_MARGIN);
			setPreferredLocation(lastAutoLocation);
		}

		if (current == null)
		{
			// Report the real width rather than 0 so the panel doesn't jump once it is shown.
			return new Dimension(panelWidth, 0);
		}

		boolean neutral = config.panelStyle() == PanelStyle.NEUTRAL;
		Art art = artFor(current, neutral);

		long elapsed = now - notificationStartMillis;
		long iconPopStart = FOLD_MILLIS;

		float foldRaw = clamp01(elapsed / (float) FOLD_MILLIS);
		float foldT = easeOutBack(foldRaw, FOLD_OVERSHOOT);

		long fadeStart = iconPopStart + ICON_POP_MILLIS + config.popupSeconds() * 1000L;
		float fadeAlpha = elapsed < fadeStart ? 1f : 1f - easeInCubic(clamp01((elapsed - fadeStart) / (float) FADE_MILLIS));

		TextRenderMode textRenderMode = config.textRenderMode();
		textOutlineEnabled = textRenderMode == TextRenderMode.SMOOTH_OUTLINED;
		if (textRenderMode == TextRenderMode.CRISP)
		{
			graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
			graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_DEFAULT);
		}
		else
		{
			graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			// The font is hand-hinted at its native sizes; STROKE_PURE renders the unhinted outline so smoothing is consistent.
			graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		}

		AffineTransform base = graphics.getTransform();
		Composite originalComposite = graphics.getComposite();

		// Panel: scaleY-only fold pivoted at its own centre, plus whatever is left of the fade-out.
		graphics.translate(panelWidth / 2.0, panelHeight / 2.0);
		graphics.scale(1.0, foldT);
		graphics.translate(-panelWidth / 2.0, -panelHeight / 2.0);

		Shape originalClip = graphics.getClip();
		if (foldRaw < 1f)
		{
			graphics.clip(new Rectangle2D.Float(0, 0, panelWidth, panelHeight));
		}
		setAlpha(graphics, originalComposite, fadeAlpha);
		graphics.drawImage(compact ? art.getCompactBackground() : art.getBackground(), 0, 0, panelWidth, panelHeight, null);
		if (compact)
		{
			drawCompactPanelContent(graphics, art);
		}
		else
		{
			drawPanelContent(graphics, art);
		}

		graphics.setClip(originalClip);
		graphics.setComposite(originalComposite);
		graphics.setTransform(base);

		// Icon: independent pop, only once the fold has played out. With several different crops the
		// icon rotates through them so each finished crop gets its moment.
		BufferedImage itemSprite = currentSprite(elapsed);
		if (elapsed >= iconPopStart && itemSprite != null)
		{
			float iconRaw = clamp01((elapsed - iconPopStart) / (float) ICON_POP_MILLIS);
			float iconEase = easeOutBack(iconRaw, ICON_POP_OVERSHOOT);
			float iconScale = ICON_MIN_SCALE + (1f - ICON_MIN_SCALE) * iconEase;
			float iconAlpha = clamp01(iconEase) * fadeAlpha;

			float iconCenterX = iconX + iconCanvasSize / 2f;
			float iconCenterY = iconY + iconCanvasSize / 2f;
			graphics.translate(iconCenterX, iconCenterY);
			graphics.scale(iconScale, iconScale);
			graphics.translate(-iconCenterX, -iconCenterY);

			if (!compact)
			{
				setAlpha(graphics, originalComposite, iconAlpha);
				graphics.drawImage(art.getIconFrame(), iconX, iconY, iconCanvasSize, iconCanvasSize, null);
			}
			setAlpha(graphics, originalComposite, iconAlpha);
			drawItemSprite(graphics, itemSprite);
			graphics.setComposite(originalComposite);
			graphics.setTransform(base);
		}

		return new Dimension(panelWidth, compact ? panelHeight : panelHeight + iconCanvasSize / 2);
	}

	private BufferedImage currentSprite(long elapsed)
	{
		List<Integer> ids = current.getItemIds();
		if (ids.isEmpty())
		{
			return null;
		}
		long sinceIconShown = Math.max(0, elapsed - FOLD_MILLIS);
		int index = (int) ((sinceIconShown / ICON_CYCLE_MILLIS) % ids.size());
		int id = ids.get(index);
		// The client returns null for an id it has no sprite for.
		return id >= 0 ? itemManager.getImage(id) : null;
	}

	private void drawItemSprite(Graphics2D graphics, BufferedImage sprite)
	{
		float spriteScale = iconTargetSize / (float) Math.max(sprite.getWidth(), sprite.getHeight());
		int scaledWidth = Math.round(sprite.getWidth() * spriteScale);
		int scaledHeight = Math.round(sprite.getHeight() * spriteScale);
		Object originalInterpolation = graphics.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		graphics.drawImage(sprite,
			iconX + iconSpriteXOffset + (iconCanvasSize - scaledWidth) / 2,
			iconY + (iconCanvasSize - scaledHeight) / 2,
			scaledWidth, scaledHeight,
			null);
		if (originalInterpolation != null)
		{
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, originalInterpolation);
		}
	}

	private void drawOutlinedString(Graphics2D graphics, String text, int x, int y, Color color)
	{
		if (textOutlineEnabled)
		{
			graphics.setColor(Color.BLACK);
			graphics.drawString(text, x + 1, y + 1);
		}
		graphics.setColor(color);
		graphics.drawString(text, x, y);
	}

	private static void setAlpha(Graphics2D graphics, Composite original, float alpha)
	{
		graphics.setComposite(alpha < 1f ? AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, alpha)) : original);
	}

	// ---------------------------------------------------------------- extended content

	private void drawPanelContent(Graphics2D graphics, Art art)
	{
		// Drawn top-to-bottom to match the fold-open reveal.
		graphics.setFont(captionFont);
		String caption = current.getCaption();
		int captionX = (panelWidth - graphics.getFontMetrics().stringWidth(caption)) / 2;
		drawOutlinedString(graphics, caption, captionX, captionBaselineY, CAPTION_COLOR);

		int maxNameWidth = panelWidth - 2 * nameSideMargin;
		FittedName fitted = fitName(graphics, current.getTitle(), nameFont, maxNameWidth, nameFontMinSize);
		graphics.setFont(fitted.getFont());
		int nameX = (panelWidth - graphics.getFontMetrics().stringWidth(fitted.getText())) / 2;
		drawOutlinedString(graphics, fitted.getText(), nameX, nameBaselineY, art.getNameColor());

		drawProgressBar(graphics, art);

		drawCornerStat(graphics, current.getLeftLabel(), current.getLeftValue(), cornerPaddingX, false);
		drawCornerStat(graphics, current.getRightLabel(), current.getRightValue(), panelWidth - cornerPaddingX, true);
	}

	private void drawCornerStat(Graphics2D graphics, String label, String value, int edgeX, boolean rightAligned)
	{
		if (label == null || value == null)
		{
			return;
		}
		FontMetrics labelMetrics = graphics.getFontMetrics(cornerLabelFont);
		FontMetrics valueMetrics = graphics.getFontMetrics(cornerValueFont);

		String shownLabel = truncate(graphics, label, cornerLabelFont, cornerTextMaxWidth);
		graphics.setFont(cornerLabelFont);
		drawOutlinedString(graphics, shownLabel,
			rightAligned ? edgeX - labelMetrics.stringWidth(shownLabel) : edgeX, cornerLabelBaselineY, STAT_LABEL_COLOR);

		String shownValue = truncate(graphics, value, cornerValueFont, cornerTextMaxWidth);
		graphics.setFont(cornerValueFont);
		drawOutlinedString(graphics, shownValue,
			rightAligned ? edgeX - valueMetrics.stringWidth(shownValue) : edgeX, cornerValueBaselineY, STAT_VALUE_COLOR);
	}

	/**
	 * The bar in the band the art's divider line used to occupy. The original fills it with collection
	 * log progress; here it is always full, because a popup only shows once everything is ready.
	 */
	private void drawProgressBar(Graphics2D graphics, Art art)
	{
		graphics.setColor(art.getAccent());
		graphics.fillRect(progressBarX, progressBarTopY, progressBarWidth, progressBarHeight);
	}

	// ---------------------------------------------------------------- compact content

	private void drawCompactPanelContent(Graphics2D graphics, Art art)
	{
		int textRight = textX + textMaxWidth;

		graphics.setFont(captionFont);
		drawOutlinedString(graphics, current.getCaption(), textX, captionBaselineY, CAPTION_COLOR);

		FittedName fitted = fitName(graphics, current.getTitle(), nameFont, textMaxWidth, nameFontMinSize);
		graphics.setFont(fitted.getFont());
		drawOutlinedString(graphics, fitted.getText(), textX, nameBaselineY, art.getNameColor());

		drawCompactStats(graphics, textRight);

		// The divider is drawn over the background art.
		graphics.setColor(art.getDividerColor());
		graphics.fillRect(dividerX, dividerY, dividerWidth, dividerHeight);

		// Lay the bar along the bottom of the panel body, edge to edge inside the frame.
		Rectangle body = art.getCompactBody();
		float scaleX = panelWidth / (float) art.getCompactBackground().getWidth();
		float scaleY = panelHeight / (float) COMPACT_PANEL_HEIGHT;
		int barX = Math.round(body.x * scaleX);
		int barWidth = Math.round((body.x + body.width) * scaleX) - barX;
		int barTop = Math.round((body.y + body.height) * scaleY) - progressBarHeight;
		graphics.setColor(art.getAccent());
		graphics.fillRect(barX, barTop, barWidth, progressBarHeight);
	}

	private void drawCompactStats(Graphics2D graphics, int textRight)
	{
		FontMetrics labelMetrics = graphics.getFontMetrics(compactStatLabelFont);
		FontMetrics valueMetrics = graphics.getFontMetrics(cornerValueFont);

		String leftLabel = inlineLabel(current.getLeftLabel());
		String leftValue = current.getLeftValue() == null ? "" : current.getLeftValue();
		int leftWidth = labelMetrics.stringWidth(leftLabel) + valueMetrics.stringWidth(leftValue);
		drawCompactStat(graphics, leftLabel, leftValue, textX);

		if (current.getRightValue() == null)
		{
			return;
		}
		int available = textMaxWidth - leftWidth - statGap;
		if (available <= 0)
		{
			return;
		}

		String rightLabel = inlineLabel(current.getRightLabel());
		String rightValue = current.getRightValue();
		// The label gives way before the value does.
		if (labelMetrics.stringWidth(rightLabel) + valueMetrics.stringWidth(rightValue) > available)
		{
			rightLabel = "";
		}
		rightValue = truncate(graphics, rightValue, cornerValueFont, available);
		int rightWidth = labelMetrics.stringWidth(rightLabel) + valueMetrics.stringWidth(rightValue);
		drawCompactStat(graphics, rightLabel, rightValue, textRight - rightWidth);
	}

	private void drawCompactStat(Graphics2D graphics, String label, String value, int x)
	{
		graphics.setFont(compactStatLabelFont);
		drawOutlinedString(graphics, label, x, statsBaselineY, STAT_LABEL_COLOR);
		x += graphics.getFontMetrics().stringWidth(label);
		graphics.setFont(cornerValueFont);
		drawOutlinedString(graphics, value, x, statsBaselineY, STAT_VALUE_COLOR);
	}

	private static String inlineLabel(String label)
	{
		return label == null || label.isEmpty() ? "" : label.trim() + " ";
	}

	// ---------------------------------------------------------------- queue

	private void advance(long now)
	{
		if (current == null)
		{
			current = queue.pollFirst();
			if (current != null)
			{
				notificationStartMillis = now;
				// Fired here rather than at enqueue time so a burst of popups is spaced out sound by sound.
				soundManager.play(current.getTier());
			}
			return;
		}

		long totalMillis = FOLD_MILLIS + ICON_POP_MILLIS + config.popupSeconds() * 1000L + FADE_MILLIS;
		if (now - notificationStartMillis >= totalMillis)
		{
			current = null;
		}
	}

	// ---------------------------------------------------------------- helpers

	private static float clamp01(float value)
	{
		return Math.max(0f, Math.min(1f, value));
	}

	/** Back-ease-out: overshoots past 1 before settling exactly at 1 when t=1. */
	private static float easeOutBack(float t, float overshoot)
	{
		float c3 = overshoot + 1f;
		float p = t - 1f;
		return 1f + c3 * p * p * p + overshoot * p * p;
	}

	private static float easeInCubic(float t)
	{
		return t * t * t;
	}

	/** Fits the text on a single line, shrinking the font down to {@code minFontSize} and then ellipsising. */
	private static FittedName fitName(Graphics2D graphics, String text, Font baseFont, int maxWidth, float minFontSize)
	{
		Font font = baseFont;
		while (graphics.getFontMetrics(font).stringWidth(text) > maxWidth && font.getSize2D() > minFontSize)
		{
			font = font.deriveFont(font.getSize2D() - 1f);
		}
		return new FittedName(font, truncate(graphics, text, font, maxWidth));
	}

	private static String truncate(Graphics2D graphics, String text, Font font, int maxWidth)
	{
		FontMetrics metrics = graphics.getFontMetrics(font);
		if (metrics.stringWidth(text) <= maxWidth)
		{
			return text;
		}

		String ellipsis = "...";
		StringBuilder truncated = new StringBuilder(text);
		while (truncated.length() > 0 && metrics.stringWidth(truncated + ellipsis) > maxWidth)
		{
			truncated.setLength(truncated.length() - 1);
		}
		return truncated + ellipsis;
	}

	/** Drops any alpha the colour allows - the art is rebuilt from its own alpha. */
	private static Color opaque(Color color)
	{
		return color.getAlpha() == 0xFF ? color : new Color(color.getRGB() & RGB_MASK);
	}

	@Value
	private static class FittedName
	{
		Font font;
		String text;
	}
}
