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
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.stretchedmode.StretchedModeConfig;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;

public class NativeOverlayBufferTest
{
	private static final int CANVAS_W = 100;
	private static final int CANVAS_H = 80;

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
		Guice.createInjector(BoundFieldModule.of(this)).injectMembers(this);

		when(client.isStretchedEnabled()).thenReturn(true);
		when(client.getCanvasWidth()).thenReturn(CANVAS_W);
		when(client.getCanvasHeight()).thenReturn(CANVAS_H);
		when(client.getStretchedDimensions()).thenReturn(new Dimension(300, 160));
	}

	@Test
	public void contentScales_followStretchWhenFixedSizeOff()
	{
		assertEquals(1.0, buffer.getPanelContentScaleX(), 0.0);
		assertEquals(1.0, buffer.getPanelContentScaleY(), 0.0);
		assertEquals(1.0, buffer.getFixedSizeContentScaleX(), 0.0);
		assertEquals(1.0, buffer.getFixedSizeContentScaleY(), 0.0);
	}

	@Test
	public void contentScales_cancelStretchWhenFixedSizeOn()
	{
		when(stretchedModeConfig.fixedOverlaySize()).thenReturn(true);

		// sx=3, sy=2: panels shrink uniformly by 1/min(sx, sy), world overlays cancel each axis
		assertEquals(0.5, buffer.getPanelContentScaleX(), 1e-9);
		assertEquals(0.5, buffer.getPanelContentScaleY(), 1e-9);
		assertEquals(1.0 / 3.0, buffer.getFixedSizeContentScaleX(), 1e-9);
		assertEquals(0.5, buffer.getFixedSizeContentScaleY(), 1e-9);
	}

	@Test
	public void panelContentScale_fixedAspectOnlyScalesUniformly()
	{
		when(stretchedModeConfig.fixedOverlayAspectRatio()).thenReturn(true);

		// sx=3, sy=2: panels net out at min(sx, sy) on both axes, world overlays ignore aspect
		assertEquals(2.0 / 3.0, buffer.getPanelContentScaleX(), 1e-9);
		assertEquals(1.0, buffer.getPanelContentScaleY(), 1e-9);
		assertEquals(1.0, buffer.getFixedSizeContentScaleX(), 0.0);
		assertEquals(1.0, buffer.getFixedSizeContentScaleY(), 0.0);
	}

	@Test
	public void panelContentScale_fixedSizeAndAspectIsTrueCanvasPixels()
	{
		when(stretchedModeConfig.fixedOverlaySize()).thenReturn(true);
		when(stretchedModeConfig.fixedOverlayAspectRatio()).thenReturn(true);

		assertEquals(1.0 / 3.0, buffer.getPanelContentScaleX(), 1e-9);
		assertEquals(0.5, buffer.getPanelContentScaleY(), 1e-9);
	}
}
