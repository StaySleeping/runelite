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

import java.awt.AlphaComposite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.Client;
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
	 * Interface overlay scale factor (1.0 = match stretched UI size).
	 */
	public double getOverlayScale()
	{
		return runeLiteConfig.overlayScale() / 100.0;
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
			preparedFrameId = -1;
		}

		int currentFrame = frameId;
		if (preparedFrameId != currentFrame)
		{
			Graphics2D g = image.createGraphics();
			g.setComposite(AlphaComposite.Clear);
			g.fillRect(0, 0, image.getWidth(), image.getHeight());
			g.dispose();
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

	public void release()
	{
		image = null;
		preparedFrameId = -1;
	}
}
