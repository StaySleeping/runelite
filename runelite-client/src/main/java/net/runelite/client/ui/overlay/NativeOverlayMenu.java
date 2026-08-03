/*
 * Copyright (c) 2026, StaySleeping <https://github.com/StaySleeping>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.ui.overlay;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.BeforeMenuRender;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Defers the right-click menu while native overlays are active, then composites it
 * after ABOVE_UI so translucent menus blend over sharp overlays.
 * <p>
 * Early {@link BeforeMenuRender} is consumed so the menu never enters the UI texture.
 * After ABOVE_UI we re-post that event onto black and white scratch fills to recover
 * real per-pixel alpha. Layout stays at the client's center-X position.
 * <p>
 * Menu look and transparency come only from Interface Styles when that plugin is
 * enabled ({@code hdMenu} / {@code menuAlpha}). With Interface Styles off (or HD
 * off and alpha 255), capture falls back to opaque {@code drawOriginalMenu(255)}.
 * <p>
 * Stretched Mode {@code fixedMenuSize} / {@code fixedMenuAspectRatio} control how the
 * captured menu is placed into the stretched frame; mouse coords are remapped to match.
 */
@Singleton
public class NativeOverlayMenu
{
	/** Extra capture pad so flyout submenus beside the root are included. */
	private static final int SUBMENU_CAPTURE_PAD = 280;
	/** Canvas pixels within this of an edge count as client-clamped flush. */
	private static final int EDGE_FLUSH_TOLERANCE = 1;

	private final Client client;
	private final NativeOverlayBuffer nativeOverlayBuffer;
	private final EventBus eventBus;

	private boolean deferredMenu;
	private boolean capturingMenu;

	/** Canvas crop used for the last successful capture (may include submenu pad). */
	@Getter
	@Nullable
	private Rectangle lastCaptureBounds;

	/** Stretched-space dest for {@link #lastCaptureBounds}. */
	@Getter
	@Nullable
	private Rectangle lastCaptureDest;

	@Inject
	private NativeOverlayMenu(
		Client client,
		NativeOverlayBuffer nativeOverlayBuffer,
		EventBus eventBus)
	{
		this.client = client;
		this.nativeOverlayBuffer = nativeOverlayBuffer;
		this.eventBus = eventBus;
		eventBus.register(this);
	}

	/**
	 * Run before Interface Styles so HD/alpha menus are deferred too.
	 * While {@link #capturingMenu}, do not consume — capture re-posts this event.
	 */
	@Subscribe(priority = 2)
	public void onBeforeMenuRender(BeforeMenuRender event)
	{
		if (capturingMenu || !nativeOverlayBuffer.isActive())
		{
			return;
		}
		if (!client.isMenuOpen())
		{
			return;
		}
		event.consume();
		deferredMenu = true;
	}

	public boolean hasDeferredMenu()
	{
		return deferredMenu;
	}

	public void clearDeferredMenu()
	{
		deferredMenu = false;
	}

	/**
	 * Full canvas-sized straight ARGB image from black/white Interface Styles / client paint,
	 * placed at the client's menu layout (center-X on click). Updates
	 * {@link #lastCaptureBounds} / {@link #lastCaptureDest} for compositors.
	 */
	@Nullable
	public BufferedImage captureMenuLayer()
	{
		if (!deferredMenu)
		{
			return null;
		}

		final Rectangle menu = getCanvasBounds(client);
		if (menu == null)
		{
			clearDeferredMenu();
			lastCaptureBounds = null;
			lastCaptureDest = null;
			return null;
		}

		final int canvasW = client.getCanvasWidth();
		final int canvasH = client.getCanvasHeight();
		if (canvasW <= 0 || canvasH <= 0)
		{
			clearDeferredMenu();
			lastCaptureBounds = null;
			lastCaptureDest = null;
			return null;
		}

		final BufferProvider bufferProvider = client.getBufferProvider();
		final int[] pixels = bufferProvider.getPixels();
		final int stride = bufferProvider.getWidth();
		final int w = menu.width;
		final int h = menu.height;

		final int[] under = new int[w * h];
		copyRect(pixels, stride, menu, under);

		capturingMenu = true;
		final int[] onBlack;
		final int[] onWhite;
		try
		{
			fillRect(pixels, stride, menu, 0xFF000000);
			paintMenuViaSubscribers();
			onBlack = new int[w * h];
			copyRect(pixels, stride, menu, onBlack);

			fillRect(pixels, stride, menu, 0xFFFFFFFF);
			paintMenuViaSubscribers();
			onWhite = new int[w * h];
			copyRect(pixels, stride, menu, onWhite);
		}
		finally
		{
			capturingMenu = false;
			pasteRect(pixels, stride, menu, under);
		}

		final BufferedImage image = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
		final int[] out = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		for (int i = 0; i < onBlack.length; ++i)
		{
			final int b = onBlack[i];
			final int wh = onWhite[i];
			final int br = b >> 16 & 0xFF;
			final int bg = b >> 8 & 0xFF;
			final int bb = b & 0xFF;
			final int wr = wh >> 16 & 0xFF;
			final int wg = wh >> 8 & 0xFF;
			final int wb = wh & 0xFF;
			int invA = ((wr - br) + (wg - bg) + (wb - bb)) / 3;
			if (invA < 0)
			{
				invA = 0;
			}
			else if (invA > 255)
			{
				invA = 255;
			}
			final int a = 255 - invA;
			final int ox = menu.x + i % w;
			final int oy = menu.y + i / w;
			if (ox < 0 || oy < 0 || ox >= canvasW || oy >= canvasH)
			{
				continue;
			}
			final int oi = oy * canvasW + ox;
			if (a == 0)
			{
				out[oi] = 0;
			}
			else
			{
				final int sr = Math.min(255, (br * 255 + a / 2) / a);
				final int sg = Math.min(255, (bg * 255 + a / 2) / a);
				final int sb = Math.min(255, (bb * 255 + a / 2) / a);
				out[oi] = a << 24 | sr << 16 | sg << 8 | sb;
			}
		}

		lastCaptureBounds = new Rectangle(menu);
		// Pin dest to the root menu so opening a submenu does not re-center/shift the tree.
		final Rectangle root = getRootMenuBounds(client);
		lastCaptureDest = computeCaptureDest(
			menu,
			root != null ? root : menu,
			nativeOverlayBuffer.getScaleX(),
			nativeOverlayBuffer.getScaleY(),
			nativeOverlayBuffer.fixedMenuSize(),
			nativeOverlayBuffer.fixedMenuAspectRatio(),
			canvasW,
			canvasH);

		clearDeferredMenu();
		return image;
	}

	/**
	 * Draw the captured menu crop onto a stretched frame after ABOVE_UI.
	 */
	public void compositeOntoStretched(Graphics2D g)
	{
		final BufferedImage menuImage = captureMenuLayer();
		final Rectangle src = lastCaptureBounds;
		final Rectangle dest = lastCaptureDest;
		if (menuImage == null || src == null || dest == null || !client.isStretchedEnabled())
		{
			clearDeferredMenu();
			return;
		}

		final Object oldInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		// Nearest keeps glyph edges from picking up neighbouring translucent fill.
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(
			menuImage,
			dest.x, dest.y, dest.x + dest.width, dest.y + dest.height,
			src.x, src.y, src.x + src.width, src.y + src.height,
			null);
		if (oldInterp != null)
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterp);
		}
	}

	/**
	 * After stretch→canvas mouse translate, remap so clicks match a fixed-size or
	 * aspect-corrected visual menu. Anchored on the root menu so submenu open does
	 * not shift hit-testing. Returns {@code null} when no adjustment is needed.
	 */
	@Nullable
	public Point remapTranslatedMouse(int stretchedX, int stretchedY, int canvasX, int canvasY)
	{
		if (!nativeOverlayBuffer.isActive() || !client.isMenuOpen())
		{
			return null;
		}

		final boolean fixedSize = nativeOverlayBuffer.fixedMenuSize();
		final boolean fixedAspect = nativeOverlayBuffer.fixedMenuAspectRatio();
		if (!fixedSize && !fixedAspect)
		{
			return null;
		}

		final Rectangle root = getRootMenuBounds(client);
		final Rectangle hit = getTightMenuBounds(client);
		if (root == null || hit == null)
		{
			return null;
		}

		final double sx = nativeOverlayBuffer.getScaleX();
		final double sy = nativeOverlayBuffer.getScaleY();
		final int canvasW = client.getCanvasWidth();
		final int canvasH = client.getCanvasHeight();
		final Rectangle rootDest = computeMenuDest(root, sx, sy, fixedSize, fixedAspect, canvasW, canvasH);
		final Rectangle visualHit = mapCanvasRectFromAnchor(hit, root, rootDest);
		final Rectangle defaultHitDest = computeMenuDest(hit, sx, sy, false, false, canvasW, canvasH);
		if (visualHit.equals(defaultHitDest))
		{
			return null;
		}

		if (visualHit.contains(stretchedX, stretchedY))
		{
			final double csx = root.width == 0 ? 1.0 : rootDest.width / (double) root.width;
			final double csy = root.height == 0 ? 1.0 : rootDest.height / (double) root.height;
			final int mx = (int) Math.floor(root.x + (stretchedX - rootDest.x) / csx);
			final int my = (int) Math.floor(root.y + (stretchedY - rootDest.y) / csy);
			return new Point(
				Math.max(hit.x, Math.min(hit.x + hit.width - 1, mx)),
				Math.max(hit.y, Math.min(hit.y + hit.height - 1, my)));
		}

		if (hit.contains(canvasX, canvasY))
		{
			// Outside the visual menu but inside the stretched hit-box — miss the menu.
			return new Point(hit.x - 1, hit.y - 1);
		}

		return null;
	}

	/**
	 * Stretched-space destination for a canvas-space menu rectangle.
	 * Fixed size/aspect center on the menu (X) / top-align (Y), but if the client already
	 * clamped the menu to a canvas edge, pin that edge in stretched space so a smaller
	 * dest does not leave a gap.
	 */
	public static Rectangle computeMenuDest(
		Rectangle menu,
		double scaleX,
		double scaleY,
		boolean fixedSize,
		boolean fixedAspect)
	{
		return computeMenuDest(menu, scaleX, scaleY, fixedSize, fixedAspect, 0, 0);
	}

	public static Rectangle computeMenuDest(
		Rectangle menu,
		double scaleX,
		double scaleY,
		boolean fixedSize,
		boolean fixedAspect,
		int canvasWidth,
		int canvasHeight)
	{
		final double s = Math.min(scaleX == 0 ? 1 : scaleX, scaleY == 0 ? 1 : scaleY);

		if (fixedSize && fixedAspect)
		{
			final int dw = menu.width;
			final int dh = menu.height;
			final int dx = destPosCentered(menu.x, menu.width, dw, scaleX, canvasWidth);
			final int dy = destPosTopAligned(menu.y, menu.height, dh, scaleY, canvasHeight);
			return new Rectangle(dx, dy, dw, dh);
		}

		if (fixedSize)
		{
			final int dw = Math.max(1, (int) Math.round(menu.width * scaleX / s));
			final int dh = Math.max(1, (int) Math.round(menu.height * scaleY / s));
			final int dx = destPosCentered(menu.x, menu.width, dw, scaleX, canvasWidth);
			final int dy = destPosTopAligned(menu.y, menu.height, dh, scaleY, canvasHeight);
			return new Rectangle(dx, dy, dw, dh);
		}

		if (fixedAspect)
		{
			final int dw = Math.max(1, (int) Math.round(menu.width * s));
			final int dh = Math.max(1, (int) Math.round(menu.height * s));
			final int dx = destPosCentered(menu.x, menu.width, dw, scaleX, canvasWidth);
			final int dy = destPosCentered(menu.y, menu.height, dh, scaleY, canvasHeight);
			return new Rectangle(dx, dy, dw, dh);
		}

		final int dx = (int) Math.round(menu.x * scaleX);
		final int dy = (int) Math.round(menu.y * scaleY);
		final int dw = Math.max(1, (int) Math.round(menu.width * scaleX));
		final int dh = Math.max(1, (int) Math.round(menu.height * scaleY));
		return new Rectangle(dx, dy, dw, dh);
	}

	/**
	 * Dest for a capture crop anchored on {@code anchor} (typically the root menu) so
	 * submenu union growth does not re-center/shift the tree.
	 */
	public static Rectangle computeCaptureDest(
		Rectangle capture,
		Rectangle anchor,
		double scaleX,
		double scaleY,
		boolean fixedSize,
		boolean fixedAspect)
	{
		return computeCaptureDest(capture, anchor, scaleX, scaleY, fixedSize, fixedAspect, 0, 0);
	}

	public static Rectangle computeCaptureDest(
		Rectangle capture,
		Rectangle anchor,
		double scaleX,
		double scaleY,
		boolean fixedSize,
		boolean fixedAspect,
		int canvasWidth,
		int canvasHeight)
	{
		if (!fixedSize && !fixedAspect)
		{
			return computeMenuDest(capture, scaleX, scaleY, false, false, canvasWidth, canvasHeight);
		}

		final Rectangle anchorDest = computeMenuDest(anchor, scaleX, scaleY, fixedSize, fixedAspect, canvasWidth, canvasHeight);
		return mapCanvasRectFromAnchor(capture, anchor, anchorDest);
	}

	/**
	 * Maps a canvas-space rect into stretched space using the same content scale as
	 * {@code anchor} → {@code anchorDest}.
	 */
	public static Rectangle mapCanvasRectFromAnchor(Rectangle canvasRect, Rectangle anchor, Rectangle anchorDest)
	{
		final double contentScaleX = anchor.width == 0 ? 1.0 : anchorDest.width / (double) anchor.width;
		final double contentScaleY = anchor.height == 0 ? 1.0 : anchorDest.height / (double) anchor.height;
		final int dx = (int) Math.round(anchorDest.x + (canvasRect.x - anchor.x) * contentScaleX);
		final int dy = (int) Math.round(anchorDest.y + (canvasRect.y - anchor.y) * contentScaleY);
		final int dw = Math.max(1, (int) Math.round(canvasRect.width * contentScaleX));
		final int dh = Math.max(1, (int) Math.round(canvasRect.height * contentScaleY));
		return new Rectangle(dx, dy, dw, dh);
	}

	private static int destPosCentered(int menuPos, int menuSize, int destSize, double scale, int canvasSize)
	{
		final int stretched = canvasSize > 0
			? Math.max(destSize, (int) Math.round(canvasSize * scale))
			: Integer.MAX_VALUE / 4;
		final boolean flushStart = canvasSize > 0 && menuPos <= EDGE_FLUSH_TOLERANCE;
		final boolean flushEnd = canvasSize > 0 && menuPos + menuSize >= canvasSize - EDGE_FLUSH_TOLERANCE;
		int pos;
		if (flushEnd && !flushStart)
		{
			pos = stretched - destSize;
		}
		else if (flushStart && !flushEnd)
		{
			pos = 0;
		}
		else
		{
			pos = (int) Math.round((menuPos + menuSize / 2.0) * scale - destSize / 2.0);
		}
		if (canvasSize > 0)
		{
			if (pos + destSize > stretched)
			{
				pos = stretched - destSize;
			}
			if (pos < 0)
			{
				pos = 0;
			}
		}
		return pos;
	}

	private static int destPosTopAligned(int menuPos, int menuSize, int destSize, double scale, int canvasSize)
	{
		final int stretched = canvasSize > 0
			? Math.max(destSize, (int) Math.round(canvasSize * scale))
			: Integer.MAX_VALUE / 4;
		final boolean flushEnd = canvasSize > 0 && menuPos + menuSize >= canvasSize - EDGE_FLUSH_TOLERANCE;
		int pos;
		if (flushEnd)
		{
			pos = stretched - destSize;
		}
		else
		{
			pos = (int) Math.round(menuPos * scale);
		}
		if (canvasSize > 0)
		{
			if (pos + destSize > stretched)
			{
				pos = stretched - destSize;
			}
			if (pos < 0)
			{
				pos = 0;
			}
		}
		return pos;
	}

	/**
	 * Let Interface Styles paint HD/alpha menus when enabled; otherwise opaque original.
	 */
	private void paintMenuViaSubscribers()
	{
		final BeforeMenuRender event = new BeforeMenuRender();
		eventBus.post(event);
		if (!event.isConsumed())
		{
			// IS off, or IS with hdMenu off and menuAlpha 255
			client.drawOriginalMenu(255);
		}
	}

	/**
	 * Top-level menu bounds only (no submenu union). Used to anchor fixed-size/aspect dest.
	 */
	@Nullable
	public static Rectangle getRootMenuBounds(Client client)
	{
		if (!client.isMenuOpen())
		{
			return null;
		}

		Rectangle bounds = null;
		final Menu root = client.getMenu();
		if (root != null)
		{
			final int w = root.getMenuWidth();
			final int h = root.getMenuHeight();
			if (w > 0 && h > 0)
			{
				bounds = new Rectangle(root.getMenuX(), root.getMenuY(), w, h);
			}
		}

		if (bounds == null)
		{
			final int x = client.getMenuX();
			final int y = client.getMenuY();
			final int w = client.getMenuWidth();
			final int h = client.getMenuHeight();
			if (w <= 0 || h <= 0)
			{
				return null;
			}
			bounds = new Rectangle(x, y, w, h);
		}

		return clipToCanvas(client, bounds);
	}

	/**
	 * Padded capture bounds (includes submenu flyout pad when needed).
	 */
	@Nullable
	public static Rectangle getCanvasBounds(Client client)
	{
		Rectangle bounds = getTightMenuBounds(client);
		if (bounds == null)
		{
			return null;
		}

		final Menu root = client.getMenu();
		if (root != null && menuTreeHasSubmenu(root))
		{
			bounds = new Rectangle(bounds);
			bounds.grow(SUBMENU_CAPTURE_PAD, 16);
		}

		return clipToCanvas(client, bounds);
	}

	/**
	 * Tight union of open menu / submenu rectangles (no capture pad).
	 */
	@Nullable
	public static Rectangle getTightMenuBounds(Client client)
	{
		if (!client.isMenuOpen())
		{
			return null;
		}

		Rectangle bounds = null;
		final Menu root = client.getMenu();
		if (root != null)
		{
			bounds = unionMenuBounds(null, root);
		}

		if (bounds == null)
		{
			final int x = client.getMenuX();
			final int y = client.getMenuY();
			final int w = client.getMenuWidth();
			final int h = client.getMenuHeight();
			if (w <= 0 || h <= 0)
			{
				return null;
			}
			bounds = new Rectangle(x, y, w, h);
		}

		return clipToCanvas(client, bounds);
	}

	@Nullable
	private static Rectangle clipToCanvas(Client client, Rectangle bounds)
	{
		final int canvasW = client.getCanvasWidth();
		final int canvasH = client.getCanvasHeight();
		final Rectangle clipped = bounds.intersection(new Rectangle(0, 0, canvasW, canvasH));
		if (clipped.width <= 0 || clipped.height <= 0)
		{
			return null;
		}
		return clipped;
	}

	@Nullable
	private static Rectangle unionMenuBounds(@Nullable Rectangle acc, Menu menu)
	{
		final int w = menu.getMenuWidth();
		final int h = menu.getMenuHeight();
		if (w > 0 && h > 0)
		{
			final Rectangle r = new Rectangle(menu.getMenuX(), menu.getMenuY(), w, h);
			acc = acc == null ? r : acc.union(r);
		}

		final MenuEntry[] entries = menu.getMenuEntries();
		if (entries != null)
		{
			for (MenuEntry entry : entries)
			{
				final Menu sub = entry.getSubMenu();
				if (sub != null)
				{
					acc = unionMenuBounds(acc, sub);
				}
			}
		}
		return acc;
	}

	private static boolean menuTreeHasSubmenu(Menu menu)
	{
		final MenuEntry[] entries = menu.getMenuEntries();
		if (entries == null)
		{
			return false;
		}
		for (MenuEntry entry : entries)
		{
			final Menu sub = entry.getSubMenu();
			if (sub != null)
			{
				return true;
			}
		}
		return false;
	}

	private static void copyRect(int[] pixels, int stride, Rectangle rect, int[] dest)
	{
		int i = 0;
		for (int y = 0; y < rect.height; ++y)
		{
			final int row = (rect.y + y) * stride + rect.x;
			System.arraycopy(pixels, row, dest, i, rect.width);
			i += rect.width;
		}
	}

	private static void pasteRect(int[] pixels, int stride, Rectangle rect, int[] src)
	{
		int i = 0;
		for (int y = 0; y < rect.height; ++y)
		{
			final int row = (rect.y + y) * stride + rect.x;
			System.arraycopy(src, i, pixels, row, rect.width);
			i += rect.width;
		}
	}

	private static void fillRect(int[] pixels, int stride, Rectangle rect, int argb)
	{
		for (int y = 0; y < rect.height; ++y)
		{
			final int row = (rect.y + y) * stride + rect.x;
			for (int x = 0; x < rect.width; ++x)
			{
				pixels[row + x] = argb;
			}
		}
	}
}
