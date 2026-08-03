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
 * Each pass is fully cleared once per frame; compositors upload the full buffer
 * when the pass was touched (or to push a clear after prior content).
 */
@Singleton
public class NativeOverlayBuffer
{
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
	private int[] uploadScratch;
	private int frameId;
	private int preparedFrameId = -1;

	private final PassState under = new PassState();
	private final PassState above = new PassState();

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
	 * When true, the deferred right-click menu keeps canvas (unstretched) size.
	 */
	public boolean fixedMenuSize()
	{
		return stretchedModeConfig.fixedMenuSize();
	}

	/**
	 * When true (and fixed menu size is off), the deferred menu scales uniformly.
	 */
	public boolean fixedMenuAspectRatio()
	{
		return stretchedModeConfig.fixedMenuAspectRatio();
	}

	/**
	 * Content scale applied after the outer stretch transform for interface overlays.
	 * Fixed size: 1/stretch (cancel stretch on size). Otherwise: 1 (scale with stretch).
	 * Also used as the canvas-space visual size factor for DYNAMIC overlays.
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
			underImage = ensureImage(underImage, dim);
		}
		else if (underImage != null)
		{
			underImage = null;
			under.reset();
		}

		aboveImage = ensureImage(aboveImage, dim);

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
		PassState state = state(pass);
		BufferedImage image = getImage(pass);
		if (image == null)
		{
			state.reset();
			return;
		}

		Arrays.fill(getPixels(pass), 0);
		state.touched = false;
		// hadContent from the previous finishComposite keeps isDirty true so the
		// compositor uploads this cleared buffer once (avoids ghosted GL/CPU pixels).
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
	 * Marks the pass as needing a full-buffer upload this frame.
	 */
	public void markDirty(Pass pass)
	{
		if (getImage(pass) == null)
		{
			return;
		}
		state(pass).touched = true;
	}

	public boolean isDirty(Pass pass)
	{
		PassState state = state(pass);
		return state.touched || state.hadContent;
	}

	/**
	 * Full buffer rectangle when this pass needs upload, otherwise null.
	 */
	public Rectangle getUploadRect(Pass pass)
	{
		BufferedImage image = getImage(pass);
		if (image == null || !isDirty(pass))
		{
			return null;
		}
		return new Rectangle(0, 0, image.getWidth(), image.getHeight());
	}

	/**
	 * Call after a successful GPU/CPU composite so the next frame can clear this pass.
	 */
	public void finishComposite(Pass pass)
	{
		PassState state = state(pass);
		state.hadContent = state.touched;
		state.touched = false;
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

	private PassState state(Pass pass)
	{
		return pass == Pass.ABOVE_UI ? above : under;
	}

	public void release()
	{
		underImage = null;
		aboveImage = null;
		uploadScratch = null;
		preparedFrameId = -1;
		under.reset();
		above.reset();
	}

	private static final class PassState
	{
		boolean touched;
		boolean hadContent;

		void reset()
		{
			touched = false;
			hadContent = false;
		}
	}
}
