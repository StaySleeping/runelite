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

import com.google.inject.Guice;
import com.google.inject.testing.fieldbinder.Bind;
import com.google.inject.testing.fieldbinder.BoundFieldModule;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.stretchedmode.StretchedModeConfig;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;

public class NativeOverlayBufferTest
{
	private static final int CANVAS_W = 100;
	private static final int CANVAS_H = 80;
	private static final int STRETCH_W = 200;
	private static final int STRETCH_H = 160;

	@Mock
	@Bind
	private Client client;

	@Mock
	@Bind
	private ConfigManager configManager;

	@Mock
	private StretchedModeConfig stretchedModeConfig;

	@Inject
	private NativeOverlayBuffer buffer;

	@Before
	public void before()
	{
		MockitoAnnotations.initMocks(this);
		when(configManager.getConfig(StretchedModeConfig.class)).thenReturn(stretchedModeConfig);
		when(stretchedModeConfig.fixedOverlaySize()).thenReturn(false);
		Guice.createInjector(BoundFieldModule.of(this)).injectMembers(this);

		when(client.isStretchedEnabled()).thenReturn(true);
		when(client.getCanvasWidth()).thenReturn(CANVAS_W);
		when(client.getCanvasHeight()).thenReturn(CANVAS_H);
		when(client.getStretchedDimensions()).thenReturn(new Dimension(STRETCH_W, STRETCH_H));
		when(client.isGpu()).thenReturn(true);
	}

	@Test
	public void isActive_requiresStretchUpscale()
	{
		assertTrue(buffer.isActive());

		when(client.getStretchedDimensions()).thenReturn(new Dimension(CANVAS_W, CANVAS_H));
		assertFalse(buffer.isActive());

		when(client.isStretchedEnabled()).thenReturn(false);
		when(client.getStretchedDimensions()).thenReturn(new Dimension(STRETCH_W, STRETCH_H));
		assertFalse(buffer.isActive());
	}

	@Test
	public void prepareFrame_underNullWhenCpu_aboveAlways()
	{
		when(client.isGpu()).thenReturn(false);
		buffer.prepareFrame();
		assertNull(buffer.getImage(NativeOverlayBuffer.Pass.UNDER_UI));
		assertNotNull(buffer.getImage(NativeOverlayBuffer.Pass.ABOVE_UI));
		assertEquals(STRETCH_W, buffer.getImage(NativeOverlayBuffer.Pass.ABOVE_UI).getWidth());
		assertEquals(STRETCH_H, buffer.getImage(NativeOverlayBuffer.Pass.ABOVE_UI).getHeight());
	}

	@Test
	public void prepareFrame_allocatesBothPassesOnGpu()
	{
		buffer.prepareFrame();
		assertNotNull(buffer.getImage(NativeOverlayBuffer.Pass.UNDER_UI));
		assertNotNull(buffer.getImage(NativeOverlayBuffer.Pass.ABOVE_UI));
	}

	@Test
	public void prepareFrame_idempotentWithinSameFrame()
	{
		buffer.prepareFrame();
		BufferedImage above = buffer.getImage(NativeOverlayBuffer.Pass.ABOVE_UI);
		int[] pixels = ((DataBufferInt) above.getRaster().getDataBuffer()).getData();
		pixels[0] = 0xFFFF0000;

		buffer.prepareFrame();
		assertSame(above, buffer.getImage(NativeOverlayBuffer.Pass.ABOVE_UI));
		assertEquals(0xFFFF0000, pixels[0]);
	}

	@Test
	public void prepareFrame_clearsDrawnContentOnNextFrame()
	{
		buffer.prepareFrame();
		buffer.markDirty(NativeOverlayBuffer.Pass.ABOVE_UI);
		int[] pixels = buffer.getPixels(NativeOverlayBuffer.Pass.ABOVE_UI);
		pixels[10] = 0xFF00FF00;
		buffer.finishComposite(NativeOverlayBuffer.Pass.ABOVE_UI);

		buffer.nextFrame();
		buffer.prepareFrame();
		assertEquals(0, pixels[10]);
	}

	@Test
	public void untouchedPass_skipsUpload()
	{
		buffer.prepareFrame();
		assertFalse(buffer.isDirty(NativeOverlayBuffer.Pass.ABOVE_UI));
		assertNull(buffer.getUploadRect(NativeOverlayBuffer.Pass.ABOVE_UI));
	}

	@Test
	public void touch_exposesFullBufferForUpload()
	{
		buffer.prepareFrame();
		buffer.markDirty(NativeOverlayBuffer.Pass.ABOVE_UI);
		assertTrue(buffer.isDirty(NativeOverlayBuffer.Pass.ABOVE_UI));
		Rectangle upload = buffer.getUploadRect(NativeOverlayBuffer.Pass.ABOVE_UI);
		assertNotNull(upload);
		assertEquals(0, upload.x);
		assertEquals(0, upload.y);
		assertEquals(STRETCH_W, upload.width);
		assertEquals(STRETCH_H, upload.height);
	}

	@Test
	public void afterTouchAndFinish_nextIdleFrameStillUploadsClearsThenSkips()
	{
		buffer.prepareFrame();
		buffer.markDirty(NativeOverlayBuffer.Pass.UNDER_UI);
		buffer.finishComposite(NativeOverlayBuffer.Pass.UNDER_UI);

		buffer.nextFrame();
		buffer.prepareFrame();
		// Cleared previous content must still reach the compositor once.
		assertTrue(buffer.isDirty(NativeOverlayBuffer.Pass.UNDER_UI));
		assertNotNull(buffer.getUploadRect(NativeOverlayBuffer.Pass.UNDER_UI));
		buffer.finishComposite(NativeOverlayBuffer.Pass.UNDER_UI);

		buffer.nextFrame();
		buffer.prepareFrame();
		assertFalse(buffer.isDirty(NativeOverlayBuffer.Pass.UNDER_UI));
		assertNull(buffer.getUploadRect(NativeOverlayBuffer.Pass.UNDER_UI));
	}

	@Test
	public void getPremultipliedUploadPixels_packsStraightAlpha()
	{
		buffer.prepareFrame();
		buffer.markDirty(NativeOverlayBuffer.Pass.ABOVE_UI);
		int[] src = buffer.getPixels(NativeOverlayBuffer.Pass.ABOVE_UI);
		// a=128, r=255, g=0, b=0 → premul r = (255*128+127)/255 = 128
		src[0] = 0x80FF0000;

		Rectangle upload = buffer.getUploadRect(NativeOverlayBuffer.Pass.ABOVE_UI);
		int[] premul = buffer.getPremultipliedUploadPixels(NativeOverlayBuffer.Pass.ABOVE_UI, upload);
		assertNotNull(premul);
		assertEquals(0x80800000, premul[0]);

		src[1] = 0x00ABCDEF;
		premul = buffer.getPremultipliedUploadPixels(NativeOverlayBuffer.Pass.ABOVE_UI, upload);
		assertEquals(0, premul[1]);

		src[2] = 0xFF112233;
		premul = buffer.getPremultipliedUploadPixels(NativeOverlayBuffer.Pass.ABOVE_UI, upload);
		assertEquals(0xFF112233, premul[2]);
	}

	@Test
	public void prepareFrame_inactiveReleasesBuffers()
	{
		buffer.prepareFrame();
		assertNotNull(buffer.getImage(NativeOverlayBuffer.Pass.ABOVE_UI));

		when(client.isStretchedEnabled()).thenReturn(false);
		buffer.prepareFrame();
		assertNull(buffer.getImage(NativeOverlayBuffer.Pass.ABOVE_UI));
		assertNull(buffer.getImage(NativeOverlayBuffer.Pass.UNDER_UI));
	}

	@Test
	public void scalesAndPanelContentScale_matchStretch()
	{
		assertEquals(2.0, buffer.getScaleX(), 0.0);
		assertEquals(2.0, buffer.getScaleY(), 0.0);
		assertEquals(1.0, buffer.getPanelContentScaleX(), 0.0);

		when(stretchedModeConfig.fixedOverlaySize()).thenReturn(true);
		assertEquals(0.5, buffer.getPanelContentScaleX(), 0.0);
		assertEquals(0.5, buffer.getPanelContentScaleY(), 0.0);
	}

	@Test
	public void getTransparentTextureInit_zerosRequestedRegion()
	{
		int[] init = buffer.getTransparentTextureInit(4, 3);
		assertEquals(12, 4 * 3);
		for (int i = 0; i < 12; i++)
		{
			assertEquals(0, init[i]);
		}
		init[0] = 0xFFFFFFFF;
		init = buffer.getTransparentTextureInit(4, 3);
		assertEquals(0, init[0]);
	}
}
