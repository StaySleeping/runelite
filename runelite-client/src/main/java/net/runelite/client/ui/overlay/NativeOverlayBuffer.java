/*
 * Copyright (c) 2026, StaySleeping <StaySleeping@users.noreply.github.com>
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
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.client.config.OverlaySizeMode;
import net.runelite.client.config.RuneLiteConfig;

/**
 * Offscreen buffer for drawing RuneLite overlays at stretched (display) resolution
 * so they are not upscaled with the game UI.
 */
@Singleton
public class NativeOverlayBuffer
{
	private final Client client;
	private final RuneLiteConfig runeLiteConfig;

	@Getter
	private BufferedImage image;
	private int[] premultipliedUpload;
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
	 * Ensures the buffer exists and is cleared once per client frame.
	 */
	public void prepareFrame()
	{
		if (!isActive())
		{
			release();
			return;
		}

		Dimension dim = client.getStretchedDimensions();
		if (image == null || image.getWidth() != dim.width || image.getHeight() != dim.height)
		{
			image = new BufferedImage(dim.width, dim.height, BufferedImage.TYPE_INT_ARGB);
			premultipliedUpload = null;
			preparedFrameId = -1;
		}

		int currentFrame = frameId;
		if (preparedFrameId != currentFrame)
		{
			Arrays.fill(getPixels(), 0);
			preparedFrameId = currentFrame;
		}
	}

	public void nextFrame()
	{
		frameId++;
	}

	public int[] getPixels()
	{
		if (image == null)
		{
			return null;
		}
		return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
	}

	/**
	 * Returns a reusable buffer of premultiplied ARGB pixels for GL upload.
	 */
	public int[] getPremultipliedPixels()
	{
		int[] src = getPixels();
		if (src == null)
		{
			return null;
		}
		if (premultipliedUpload == null || premultipliedUpload.length != src.length)
		{
			premultipliedUpload = new int[src.length];
		}
		for (int i = 0; i < src.length; i++)
		{
			int p = src[i];
			int a = (p >>> 24) & 0xFF;
			if (a == 0)
			{
				premultipliedUpload[i] = 0;
			}
			else if (a == 255)
			{
				premultipliedUpload[i] = p;
			}
			else
			{
				int r = (p >>> 16) & 0xFF;
				int g = (p >>> 8) & 0xFF;
				int b = p & 0xFF;
				premultipliedUpload[i] = (a << 24)
					| (((r * a + 127) / 255) << 16)
					| (((g * a + 127) / 255) << 8)
					| ((b * a + 127) / 255);
			}
		}
		return premultipliedUpload;
	}

	public void release()
	{
		image = null;
		premultipliedUpload = null;
		preparedFrameId = -1;
	}
}
