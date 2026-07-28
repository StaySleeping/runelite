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
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.config.OverlaySizeMode;
import net.runelite.client.config.RuneLiteConfig;

/**
 * Offscreen buffers for drawing RuneLite overlays at stretched (display) resolution
 * so they are not upscaled with the game UI.
 * <p>
 * Two passes mirror {@link OverlayLayer} ordering relative to game interfaces:
 * under-UI ({@link OverlayLayer#ABOVE_SCENE}, {@link OverlayLayer#UNDER_WIDGETS})
 * and above-UI ({@link OverlayLayer#ABOVE_WIDGETS}, {@link OverlayLayer#ALWAYS_ON_TOP}).
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
	private final RuneLiteConfig runeLiteConfig;

	private BufferedImage underImage;
	private BufferedImage aboveImage;
	private int[] underPremultipliedUpload;
	private int[] abovePremultipliedUpload;
	private int frameId;
	private int preparedFrameId = -1;

	@Inject
	private NativeOverlayBuffer(Client client, RuneLiteConfig runeLiteConfig)
	{
		this.client = client;
		this.runeLiteConfig = runeLiteConfig;
	}

	public boolean isActive()
	{
		return client.isStretchedEnabled() && runeLiteConfig.nativeResolutionOverlays();
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
	 * User overlay scale percent as a factor (1.0 = 100%).
	 */
	public double getOverlayScale()
	{
		return runeLiteConfig.overlayScale() / 100.0;
	}

	public OverlaySizeMode getOverlaySizeMode()
	{
		return runeLiteConfig.overlaySizeMode();
	}

	/**
	 * Content scale applied after the outer stretch transform for interface overlays.
	 * MATCH_UI: 1 (content scales with stretch). CANVAS: 1/stretch (cancel stretch on size).
	 * Then multiplied by overlayScale percent.
	 */
	public double getPanelContentScaleX()
	{
		double os = getOverlayScale();
		if (getOverlaySizeMode() == OverlaySizeMode.CANVAS)
		{
			double sx = getScaleX();
			return os / (sx == 0 ? 1 : sx);
		}
		return os;
	}

	public double getPanelContentScaleY()
	{
		double os = getOverlayScale();
		if (getOverlaySizeMode() == OverlaySizeMode.CANVAS)
		{
			double sy = getScaleY();
			return os / (sy == 0 ? 1 : sy);
		}
		return os;
	}

	/**
	 * Canvas-space factor from logical overlay size to visual hit/clamp size.
	 */
	public double getVisualSizeFactorX()
	{
		if (getOverlaySizeMode() == OverlaySizeMode.CANVAS)
		{
			double sx = getScaleX();
			return getOverlayScale() / (sx == 0 ? 1 : sx);
		}
		return getOverlayScale();
	}

	public double getVisualSizeFactorY()
	{
		if (getOverlaySizeMode() == OverlaySizeMode.CANVAS)
		{
			double sy = getScaleY();
			return getOverlayScale() / (sy == 0 ? 1 : sy);
		}
		return getOverlayScale();
	}

	/**
	 * Ensures both buffers exist and are cleared once per client frame.
	 */
	public void prepareFrame()
	{
		if (!isActive())
		{
			release();
			return;
		}

		Dimension dim = client.getStretchedDimensions();
		BufferedImage newUnder = ensureImage(underImage, dim);
		BufferedImage newAbove = ensureImage(aboveImage, dim);
		if (newUnder != underImage || newAbove != aboveImage)
		{
			underPremultipliedUpload = null;
			abovePremultipliedUpload = null;
			preparedFrameId = -1;
		}
		underImage = newUnder;
		aboveImage = newAbove;

		int currentFrame = frameId;
		if (preparedFrameId != currentFrame)
		{
			Arrays.fill(getPixels(Pass.UNDER_UI), 0);
			Arrays.fill(getPixels(Pass.ABOVE_UI), 0);
			preparedFrameId = currentFrame;
		}
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
	 * Returns a reusable buffer of premultiplied ARGB pixels for GL upload.
	 */
	public int[] getPremultipliedPixels(Pass pass)
	{
		int[] src = getPixels(pass);
		if (src == null)
		{
			return null;
		}
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
		for (int i = 0; i < src.length; i++)
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
		return dest;
	}

	public void release()
	{
		underImage = null;
		aboveImage = null;
		underPremultipliedUpload = null;
		abovePremultipliedUpload = null;
		preparedFrameId = -1;
	}
}
