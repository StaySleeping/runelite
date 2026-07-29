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

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.stretchedmode.StretchedModeConfig;

/**
 * Offscreen buffers for drawing RuneLite overlays at stretched (display) resolution
 * so they are not upscaled with the game UI.
 * <p>
 * Two passes mirror {@link OverlayLayer} ordering relative to game interfaces:
 * under-UI ({@link OverlayLayer#ABOVE_SCENE}, {@link OverlayLayer#UNDER_WIDGETS})
 * and above-UI ({@link OverlayLayer#ABOVE_WIDGETS}, {@link OverlayLayer#ALWAYS_ON_TOP}).
 * <p>
 * Inactive when stretched mode is off, or when stretch does not actually upscale
 * (stretched size equals canvas) — then the legacy single-buffer path is enough.
 * Clear / premultiply / upload are limited to dirty AABBs when possible.
 */
@Singleton
public class NativeOverlayBuffer
{
	private static final float FULL_UPLOAD_AREA_RATIO = 0.5f;

	public enum Pass
	{
		/**
		 * Drawn after the scene and before the game UI (bank, inventory, etc.).
		 */
		UNDER_UI,
		/**
		 * Drawn after the game UI (tooltips, overlays moved over interfaces, etc.).
		 */
		ABOVE_UI
	}

	private final Client client;
	private final StretchedModeConfig stretchedModeConfig;

	private BufferedImage underImage;
	private BufferedImage aboveImage;
	private int[] underPremultipliedUpload;
	private int[] abovePremultipliedUpload;
	private int[] uploadScratch;
	private int frameId;
	private int preparedFrameId = -1;

	private final DirtyPass under = new DirtyPass();
	private final DirtyPass above = new DirtyPass();

	@Inject
	private NativeOverlayBuffer(Client client, ConfigManager configManager)
	{
		this.client = client;
		this.stretchedModeConfig = configManager.getConfig(StretchedModeConfig.class);
	}

	/**
	 * Whether native overlay buffers should be used. Requires stretched mode and an
	 * actual upscale; otherwise overlays can share the canvas buffer like before.
	 */
	public boolean isActive()
	{
		if (!client.isStretchedEnabled())
		{
			return false;
		}
		Dimension stretched = client.getStretchedDimensions();
		return stretched.width > client.getCanvasWidth()
			|| stretched.height > client.getCanvasHeight();
	}

	/**
	 * Whether this overlay layer should be composited above the game UI.
	 */
	public static boolean isAboveUiLayer(OverlayLayer layer)
	{
		return layer == OverlayLayer.ABOVE_WIDGETS || layer == OverlayLayer.ALWAYS_ON_TOP;
	}

	public Pass passForLayer(OverlayLayer layer)
	{
		return isAboveUiLayer(layer) ? Pass.ABOVE_UI : Pass.UNDER_UI;
	}

	public double getScaleX()
	{
		if (!client.isStretchedEnabled())
		{
			return 1;
		}
		int canvasWidth = client.getCanvasWidth();
		return canvasWidth == 0 ? 1 : client.getStretchedDimensions().getWidth() / canvasWidth;
	}

	public double getScaleY()
	{
		if (!client.isStretchedEnabled())
		{
			return 1;
		}
		int canvasHeight = client.getCanvasHeight();
		return canvasHeight == 0 ? 1 : client.getStretchedDimensions().getHeight() / canvasHeight;
	}

	/**
	 * When true, overlays keep their original unstretched size.
	 * When false, overlays grow with stretch (same visual size as the stretched UI).
	 */
	public boolean fixedOverlaySize()
	{
		return stretchedModeConfig.fixedOverlaySize();
	}

	/**
	 * Content scale applied after the outer stretch transform for interface overlays.
	 * Fixed size: 1/stretch (cancel stretch on size). Otherwise: 1 (scale with stretch).
	 */
	public double getPanelContentScaleX()
	{
		if (fixedOverlaySize())
		{
			double sx = getScaleX();
			return 1 / (sx == 0 ? 1 : sx);
		}
		return 1;
	}

	public double getPanelContentScaleY()
	{
		if (fixedOverlaySize())
		{
			double sy = getScaleY();
			return 1 / (sy == 0 ? 1 : sy);
		}
		return 1;
	}

	/**
	 * Canvas-space factor from logical overlay size to visual hit/clamp size.
	 */
	public double getVisualSizeFactorX()
	{
		return getPanelContentScaleX();
	}

	public double getVisualSizeFactorY()
	{
		return getPanelContentScaleY();
	}

	/**
	 * Ensures required buffers exist and are cleared once per client frame.
	 * Under-UI is only allocated on GPU (CPU keeps under-UI overlays on the canvas).
	 */
	public void prepareFrame()
	{
		if (!isActive())
		{
			release();
			return;
		}

		Dimension dim = client.getStretchedDimensions();
		final boolean needUnder = client.isGpu();

		if (needUnder)
		{
			BufferedImage newUnder = ensureImage(underImage, dim);
			if (newUnder != underImage)
			{
				underPremultipliedUpload = null;
				preparedFrameId = -1;
				under.reset();
			}
			underImage = newUnder;
		}
		else if (underImage != null)
		{
			underImage = null;
			underPremultipliedUpload = null;
			under.reset();
		}

		BufferedImage newAbove = ensureImage(aboveImage, dim);
		if (newAbove != aboveImage)
		{
			abovePremultipliedUpload = null;
			preparedFrameId = -1;
			above.reset();
		}
		aboveImage = newAbove;

		int currentFrame = frameId;
		if (preparedFrameId != currentFrame)
		{
			beginFrame(Pass.UNDER_UI);
			beginFrame(Pass.ABOVE_UI);
			preparedFrameId = currentFrame;
		}
	}

	private void beginFrame(Pass pass)
	{
		DirtyPass state = state(pass);
		BufferedImage image = getImage(pass);
		if (image == null)
		{
			state.reset();
			return;
		}

		// Clear last frame's drawn region in the CPU buffer (zeros for this frame / GL update).
		if (state.previous != null)
		{
			clearRect(getPixels(pass), image.getWidth(), state.previous);
			state.pendingZero = copyRect(state.previous);
		}
		else
		{
			state.pendingZero = null;
		}
		state.current = null;
		state.dirty = false;
		state.previous = null;
	}

	private static BufferedImage ensureImage(BufferedImage image, Dimension dim)
	{
		if (image == null || image.getWidth() != dim.width || image.getHeight() != dim.height)
		{
			return new BufferedImage(dim.width, dim.height, BufferedImage.TYPE_INT_ARGB);
		}
		return image;
	}

	public void nextFrame()
	{
		frameId++;
	}

	/**
	 * Marks the entire pass dirty (fallback when bounds are unknown).
	 */
	public void markDirty(Pass pass)
	{
		BufferedImage image = getImage(pass);
		if (image == null)
		{
			return;
		}
		markDirty(pass, 0, 0, image.getWidth(), image.getHeight());
	}

	/**
	 * Marks a buffer-space rectangle dirty (unioned with any existing dirty region).
	 */
	public void markDirty(Pass pass, int x, int y, int w, int h)
	{
		BufferedImage image = getImage(pass);
		if (image == null || w <= 0 || h <= 0)
		{
			return;
		}

		int imgW = image.getWidth();
		int imgH = image.getHeight();
		int x0 = Math.max(0, x);
		int y0 = Math.max(0, y);
		int x1 = Math.min(imgW, x + w);
		int y1 = Math.min(imgH, y + h);
		if (x0 >= x1 || y0 >= y1)
		{
			return;
		}

		DirtyPass state = state(pass);
		Rectangle add = new Rectangle(x0, y0, x1 - x0, y1 - y0);
		if (state.current == null)
		{
			state.current = add;
		}
		else
		{
			state.current = state.current.union(add);
		}
		state.dirty = true;
	}

	/**
	 * Canvas-space dirty mark; converts to buffer pixels via stretch scale.
	 */
	public void markDirtyCanvas(Pass pass, int canvasX, int canvasY, int canvasW, int canvasH)
	{
		if (canvasW <= 0 || canvasH <= 0)
		{
			return;
		}
		double sx = getScaleX();
		double sy = getScaleY();
		int x = (int) Math.floor(canvasX * sx);
		int y = (int) Math.floor(canvasY * sy);
		int w = (int) Math.ceil((canvasX + canvasW) * sx) - x;
		int h = (int) Math.ceil((canvasY + canvasH) * sy) - y;
		markDirty(pass, x, y, w, h);
	}

	public boolean isDirty(Pass pass)
	{
		DirtyPass state = state(pass);
		return state.dirty || state.pendingZero != null;
	}

	/**
	 * Region that must be uploaded this frame (current draws unioned with cleared previous),
	 * or null if nothing to upload. May be the full buffer when the dirty area is large.
	 */
	public Rectangle getUploadRect(Pass pass)
	{
		DirtyPass state = state(pass);
		BufferedImage image = getImage(pass);
		if (image == null)
		{
			return null;
		}
		if (!state.dirty && state.pendingZero == null)
		{
			return null;
		}

		Rectangle rect = null;
		if (state.pendingZero != null)
		{
			rect = new Rectangle(state.pendingZero);
		}
		if (state.current != null)
		{
			rect = rect == null ? new Rectangle(state.current) : rect.union(state.current);
		}
		if (rect == null)
		{
			return null;
		}

		long area = (long) rect.width * rect.height;
		long full = (long) image.getWidth() * image.getHeight();
		if (full > 0 && area >= (long) (full * FULL_UPLOAD_AREA_RATIO))
		{
			return new Rectangle(0, 0, image.getWidth(), image.getHeight());
		}
		return rect;
	}

	/**
	 * Call after a successful GPU/CPU composite so the next frame can clear this region.
	 */
	public void finishComposite(Pass pass)
	{
		DirtyPass state = state(pass);
		state.previous = state.current != null ? copyRect(state.current) : null;
		state.pendingZero = null;
	}

	public BufferedImage getImage(Pass pass)
	{
		return pass == Pass.ABOVE_UI ? aboveImage : underImage;
	}

	public int[] getPixels(Pass pass)
	{
		BufferedImage image = getImage(pass);
		if (image == null)
		{
			return null;
		}
		return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
	}

	/**
	 * Zero-filled buffer for GL texture init (transparent). Reused across resizes.
	 */
	public int[] getTransparentTextureInit(int width, int height)
	{
		int need = width * height;
		if (uploadScratch == null || uploadScratch.length < need)
		{
			uploadScratch = new int[need];
		}
		else
		{
			Arrays.fill(uploadScratch, 0, need, 0);
		}
		return uploadScratch;
	}

	/**
	 * Premultiplies the upload rectangle into a tightly packed scratch buffer (row-major).
	 * Returns null if there is nothing to upload.
	 */
	public int[] getPremultipliedUploadPixels(Pass pass, Rectangle uploadRect)
	{
		int[] src = getPixels(pass);
		BufferedImage image = getImage(pass);
		if (src == null || image == null || uploadRect == null || uploadRect.width <= 0 || uploadRect.height <= 0)
		{
			return null;
		}

		final int imgW = image.getWidth();
		final int w = uploadRect.width;
		final int h = uploadRect.height;
		final int need = w * h;
		if (uploadScratch == null || uploadScratch.length < need)
		{
			uploadScratch = new int[need];
		}

		int di = 0;
		for (int y = 0; y < h; y++)
		{
			int row = (uploadRect.y + y) * imgW + uploadRect.x;
			for (int x = 0; x < w; x++)
			{
				int p = src[row + x];
				int a = (p >>> 24) & 0xFF;
				if (a == 0)
				{
					uploadScratch[di++] = 0;
				}
				else if (a == 255)
				{
					uploadScratch[di++] = p;
				}
				else
				{
					int r = (p >>> 16) & 0xFF;
					int g = (p >>> 8) & 0xFF;
					int b = p & 0xFF;
					uploadScratch[di++] = (a << 24)
						| (((r * a + 127) / 255) << 16)
						| (((g * a + 127) / 255) << 8)
						| ((b * a + 127) / 255);
				}
			}
		}
		return uploadScratch;
	}

	/**
	 * @deprecated Use {@link #getPremultipliedUploadPixels(Pass, Rectangle)} with {@link #getUploadRect(Pass)}.
	 */
	@Deprecated
	public int[] getPremultipliedPixels(Pass pass)
	{
		Rectangle rect = getUploadRect(pass);
		BufferedImage image = getImage(pass);
		if (rect == null || image == null)
		{
			return null;
		}
		if (rect.x == 0 && rect.y == 0 && rect.width == image.getWidth() && rect.height == image.getHeight())
		{
			// Full-buffer path into the long-lived upload array (old callers / resize).
			int[] src = getPixels(pass);
			int[] dest = pass == Pass.ABOVE_UI ? abovePremultipliedUpload : underPremultipliedUpload;
			if (dest == null || dest.length != src.length)
			{
				dest = new int[src.length];
				if (pass == Pass.ABOVE_UI)
				{
					abovePremultipliedUpload = dest;
				}
				else
				{
					underPremultipliedUpload = dest;
				}
			}
			premultiplyInto(src, dest, 0, src.length);
			return dest;
		}
		return getPremultipliedUploadPixels(pass, rect);
	}

	private static void premultiplyInto(int[] src, int[] dest, int start, int end)
	{
		for (int i = start; i < end; i++)
		{
			int p = src[i];
			int a = (p >>> 24) & 0xFF;
			if (a == 0)
			{
				dest[i] = 0;
			}
			else if (a == 255)
			{
				dest[i] = p;
			}
			else
			{
				int r = (p >>> 16) & 0xFF;
				int g = (p >>> 8) & 0xFF;
				int b = p & 0xFF;
				dest[i] = (a << 24)
					| (((r * a + 127) / 255) << 16)
					| (((g * a + 127) / 255) << 8)
					| ((b * a + 127) / 255);
			}
		}
	}

	private DirtyPass state(Pass pass)
	{
		return pass == Pass.ABOVE_UI ? above : under;
	}

	private static void clearRect(int[] pixels, int imgW, Rectangle r)
	{
		if (pixels == null || r == null)
		{
			return;
		}
		for (int y = 0; y < r.height; y++)
		{
			int row = (r.y + y) * imgW + r.x;
			Arrays.fill(pixels, row, row + r.width, 0);
		}
	}

	private static Rectangle copyRect(Rectangle r)
	{
		return r == null ? null : new Rectangle(r);
	}

	public void release()
	{
		underImage = null;
		aboveImage = null;
		underPremultipliedUpload = null;
		abovePremultipliedUpload = null;
		uploadScratch = null;
		preparedFrameId = -1;
		under.reset();
		above.reset();
	}

	private static final class DirtyPass
	{
		boolean dirty;
		Rectangle current;
		Rectangle previous;
		Rectangle pendingZero;

		void reset()
		{
			dirty = false;
			current = null;
			previous = null;
			pendingZero = null;
		}
	}
}
