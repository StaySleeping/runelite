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
package net.runelite.client.ui.overlay.outline;

import com.google.inject.Guice;
import com.google.inject.testing.fieldbinder.Bind;
import com.google.inject.testing.fieldbinder.BoundFieldModule;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GraphicsObject;
import net.runelite.api.MainBufferProvider;
import net.runelite.api.Model;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.client.ui.overlay.NativeOverlayBuffer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;

public class ModelOutlineRendererTest
{
	private static final int CANVAS_W = 100;
	private static final int CANVAS_H = 100;

	@Mock
	@Bind
	private Client client;

	@Mock
	@Bind
	private NativeOverlayBuffer nativeOverlayBuffer;

	@Inject
	private ModelOutlineRenderer renderer;

	private BufferedImage canvasImage;
	private MainBufferProvider bufferProvider;

	@Before
	public void before()
	{
		MockitoAnnotations.initMocks(this);
		Guice.createInjector(BoundFieldModule.of(this)).injectMembers(this);

		canvasImage = new BufferedImage(CANVAS_W, CANVAS_H, BufferedImage.TYPE_INT_ARGB);
		bufferProvider = mock(MainBufferProvider.class);
		when(bufferProvider.getImage()).thenReturn(canvasImage);
		when(client.getBufferProvider()).thenReturn(bufferProvider);
		when(client.isGpu()).thenReturn(false);
		when(nativeOverlayBuffer.isActive()).thenReturn(false);

		when(client.getViewportXOffset()).thenReturn(0);
		when(client.getViewportYOffset()).thenReturn(0);
		when(client.getViewportWidth()).thenReturn(CANVAS_W);
		when(client.getViewportHeight()).thenReturn(CANVAS_H);
	}

	@Test
	public void nextPowerOfTwo() throws Exception
	{
		Method m = ModelOutlineRenderer.class.getDeclaredMethod("nextPowerOfTwo", int.class);
		m.setAccessible(true);
		assertEquals(1, (int) m.invoke(null, 1));
		assertEquals(2, (int) m.invoke(null, 2));
		assertEquals(4, (int) m.invoke(null, 3));
		assertEquals(8, (int) m.invoke(null, 5));
		assertEquals(64, (int) m.invoke(null, 33));
	}

	@Test
	public void cullFace_cullsClockwiseOrDegenerate() throws Exception
	{
		Method m = ModelOutlineRenderer.class.getDeclaredMethod(
			"cullFace", int.class, int.class, int.class, int.class, int.class, int.class);
		m.setAccessible(true);
		// Keep faces where (y2-y1)*(x3-x2)-(x2-x1)*(y3-y2) > 0
		assertFalse((boolean) m.invoke(null, 0, 0, 0, 10, 10, 0));
		// Opposite winding: cull
		assertTrue((boolean) m.invoke(null, 0, 0, 10, 0, 0, 10));
		// Degenerate: cull
		assertTrue((boolean) m.invoke(null, 0, 0, 5, 5, 10, 10));
	}

	@Test
	public void writeOutlinePixel_canvasPath_writesSinglePixel() throws Exception
	{
		setField("nativePass", false);
		setField("scaleX", 1.0);
		setField("scaleY", 1.0);
		int[] data = pixels(canvasImage);
		invokeWrite(data, CANVAS_W, CANVAS_H, 10, 12, 0xFFFF0000);
		assertEquals(0xFFFF0000, data[12 * CANVAS_W + 10]);
		assertEquals(0, data[12 * CANVAS_W + 11]);
	}

	@Test
	public void writeOutlinePixel_nativeScale_fillsStretchBlock() throws Exception
	{
		setField("nativePass", true);
		setField("scaleX", 2.0);
		setField("scaleY", 2.0);
		BufferedImage nativeImg = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
		int[] data = pixels(nativeImg);
		invokeWrite(data, 200, 200, 10, 12, 0xFF00FF00);
		// canvas (10,12) -> buffer [20,22) x [24,26)
		assertEquals(0xFF00FF00, data[24 * 200 + 20]);
		assertEquals(0xFF00FF00, data[24 * 200 + 21]);
		assertEquals(0xFF00FF00, data[25 * 200 + 20]);
		assertEquals(0xFF00FF00, data[25 * 200 + 21]);
		assertEquals(0, data[24 * 200 + 22]);
		assertEquals(0, data[26 * 200 + 20]);
	}

	@Test
	public void readOutlinePixel_nativeScale_samplesScaledLocation() throws Exception
	{
		setField("nativePass", true);
		setField("scaleX", 2.0);
		setField("scaleY", 2.0);
		BufferedImage nativeImg = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
		int[] data = pixels(nativeImg);
		data[24 * 200 + 20] = 0xFFABCDEF;
		Method read = ModelOutlineRenderer.class.getDeclaredMethod(
			"readOutlinePixel", int[].class, int.class, int.class, int.class, int.class);
		read.setAccessible(true);
		assertEquals(0xFFABCDEF, (int) read.invoke(renderer, data, 200, 200, 10, 12));
		assertEquals(0, (int) read.invoke(renderer, data, 200, 200, 11, 12));
	}

	@Test
	public void triangleRasterization_marksVisitedBits_inClip() throws Exception
	{
		prepareVisitedClip(0, 0, 64, 64);
		invokeTriangle(20, 20, 40, 20, 30, 40);

		int[] visited = (int[]) getField("visited");
		assertTrue("expected some silhouette coverage", countVisitedBits(visited) > 0);
		// A point clearly inside a filled CW/CCW triangle of that shape should be visited
		assertTrue(isVisited(visited, 30, 25, 64));
	}

	@Test
	public void triangleRasterization_wideTriangle_canvasCoords_stable() throws Exception
	{
		// Canvas-sized wide triangle — must remain stable with int or long fixed-point.
		prepareVisitedClip(0, 0, 512, 256);
		invokeTriangle(2, 10, 400, 10, 200, 200);
		int[] visited = (int[]) getField("visited");
		int bits = countVisitedBits(visited);
		assertTrue("wide canvas triangle should rasterize", bits > 100);
	}

	@Test
	public void triangleRasterization_clippedAboveViewport_noCrash() throws Exception
	{
		prepareVisitedClip(0, 50, 64, 64);
		// Entirely above clipY1 after sort — should return without throwing
		invokeTriangle(10, 0, 20, 5, 15, 8);
		assertTrue(true);
	}

	@Test
	public void triangleRasterization_yOrderIndependent() throws Exception
	{
		prepareVisitedClip(0, 0, 64, 64);
		invokeTriangle(30, 40, 20, 20, 40, 20);
		int bitsA = countVisitedBits((int[]) getField("visited"));

		prepareVisitedClip(0, 0, 64, 64);
		invokeTriangle(20, 20, 40, 20, 30, 40);
		int bitsB = countVisitedBits((int[]) getField("visited"));

		assertEquals(bitsA, bitsB);
	}

	@Test
	public void directWriteOutline_drawsOpaqueEdgePixels() throws Exception
	{
		prepareVisitedClip(0, 0, 64, 64);
		invokeTriangle(20, 20, 40, 20, 30, 40);

		Method ensure = ModelOutlineRenderer.class.getDeclaredMethod("ensureDistanceDeltasCreated", int.class);
		ensure.setAccessible(true);
		ensure.invoke(renderer, 1);

		Method process = ModelOutlineRenderer.class.getDeclaredMethod(
			"processInitialOutlinePixels", boolean.class, Color.class, int.class);
		process.setAccessible(true);
		process.invoke(renderer, true, Color.RED, 1);

		int[] data = pixels(canvasImage);
		int redCount = 0;
		for (int p : data)
		{
			if (p == Color.RED.getRGB())
			{
				redCount++;
			}
		}
		assertTrue("direct-write outline should paint edge pixels", redCount > 0);
	}

	@Test
	public void queuedOutline_drawsTranslucentEdgePixels() throws Exception
	{
		prepareVisitedClip(0, 0, 64, 64);
		invokeTriangle(20, 20, 40, 20, 30, 40);

		setField("outlineArrayWidth", 3);
		Method init = ModelOutlineRenderer.class.getDeclaredMethod("initializeOutlineBuffers");
		init.setAccessible(true);
		init.invoke(renderer);

		Method process = ModelOutlineRenderer.class.getDeclaredMethod(
			"processInitialOutlinePixels", boolean.class, Color.class, int.class);
		process.setAccessible(true);
		process.invoke(renderer, false, new Color(255, 0, 0, 128), 1);

		Method queue = ModelOutlineRenderer.class.getDeclaredMethod(
			"processOutlinePixelQueue", int.class, Color.class, int.class);
		queue.setAccessible(true);
		queue.invoke(renderer, 1, new Color(255, 0, 0, 128), 0);

		int[] data = pixels(canvasImage);
		boolean any = false;
		for (int p : data)
		{
			if ((p >>> 24) != 0)
			{
				any = true;
				break;
			}
		}
		assertTrue(any);
	}

	@Test
	public void nativePass_getOutlineImageUsesNativeBuffer() throws Exception
	{
		setField("nativePass", true);
		BufferedImage nativeImg = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
		when(nativeOverlayBuffer.getImage(NativeOverlayBuffer.Pass.UNDER_UI)).thenReturn(nativeImg);

		Method getOutlineImage = ModelOutlineRenderer.class.getDeclaredMethod("getOutlineImage");
		getOutlineImage.setAccessible(true);
		assertEquals(nativeImg, getOutlineImage.invoke(renderer));
		verify(nativeOverlayBuffer).getImage(NativeOverlayBuffer.Pass.UNDER_UI);
	}

	@Test
	public void canvasPass_getOutlineImageUsesMainBuffer() throws Exception
	{
		setField("nativePass", false);
		Method getOutlineImage = ModelOutlineRenderer.class.getDeclaredMethod("getOutlineImage");
		getOutlineImage.setAccessible(true);
		assertEquals(canvasImage, getOutlineImage.invoke(renderer));
	}

	@Test
	public void drawOutline_skipsWhenOutlineWidthNonPositive()
	{
		TileObject obj = mock(TileObject.class);
		renderer.drawOutline(obj, 0, Color.RED, 0);
		verify(client, never()).getViewportWidth();
	}

	@Test
	public void drawOutline_skipsWhenAlphaZero()
	{
		TileObject obj = mock(TileObject.class);
		renderer.drawOutline(obj, 2, new Color(255, 0, 0, 0), 0);
		verify(client, never()).getViewportWidth();
	}

	@Test
	public void drawOutline_graphicsObjectNullLocation_noCrash()
	{
		GraphicsObject go = mock(GraphicsObject.class);
		when(go.getLocation()).thenReturn(null);
		renderer.drawOutline(go, 2, Color.RED, 0);
	}

	@Test
	public void drawOutline_clampsExtremeWidthAndFeather_viaEarlyModelNull() throws Exception
	{
		// outlineWidth/feather clamping happens before model null check fails after clamp —
		// null model returns after clamp. Ensure no throw for max values.
		Method draw = ModelOutlineRenderer.class.getDeclaredMethod(
			"drawModelOutline",
			WorldView.class, Model.class,
			int.class, int.class, int.class, int.class,
			int.class, Color.class, int.class);
		draw.setAccessible(true);
		WorldView wv = mock(WorldView.class);
		draw.invoke(renderer, wv, null, 0, 0, 0, 0, 999, Color.RED, 999);
	}

	private void prepareVisitedClip(int x1, int y1, int w, int h) throws Exception
	{
		setField("clipX1", x1);
		setField("clipY1", y1);
		setField("clipX2", x1 + w);
		setField("clipY2", y1 + h);
		setField("croppedX1", x1);
		setField("croppedY1", y1);
		setField("croppedX2", x1 + w);
		setField("croppedY2", y1 + h);
		setField("croppedWidth", w);
		setField("croppedHeight", h);
		setField("nativePass", false);
		setField("scaleX", 1.0);
		setField("scaleY", 1.0);

		Method reset = ModelOutlineRenderer.class.getDeclaredMethod("resetVisited", int.class);
		reset.setAccessible(true);
		reset.invoke(renderer, w * h);

		// clear canvas between tests
		int[] data = pixels(canvasImage);
		java.util.Arrays.fill(data, 0);
	}

	private void invokeTriangle(int x1, int y1, int x2, int y2, int x3, int y3) throws Exception
	{
		Method m = ModelOutlineRenderer.class.getDeclaredMethod(
			"simulateTriangleRasterizationForOutline",
			int.class, int.class, int.class, int.class, int.class, int.class);
		m.setAccessible(true);
		m.invoke(renderer, x1, y1, x2, y2, x3, y3);
	}

	private void invokeWrite(int[] data, int w, int h, int x, int y, int color) throws Exception
	{
		Method m = ModelOutlineRenderer.class.getDeclaredMethod(
			"writeOutlinePixel", int[].class, int.class, int.class, int.class, int.class, int.class);
		m.setAccessible(true);
		m.invoke(renderer, data, w, h, x, y, color);
	}

	private static int[] pixels(BufferedImage image)
	{
		return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
	}

	private static int countVisitedBits(int[] visited)
	{
		int n = 0;
		for (int v : visited)
		{
			n += Integer.bitCount(v);
		}
		return n;
	}

	private static boolean isVisited(int[] visited, int x, int y, int croppedWidth)
	{
		int pos = y * croppedWidth + x;
		return ((visited[pos >> 5] >>> (pos & 31)) & 1) == 1;
	}

	private void setField(String name, Object value) throws Exception
	{
		Field f = ModelOutlineRenderer.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(renderer, value);
	}

	private Object getField(String name) throws Exception
	{
		Field f = ModelOutlineRenderer.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(renderer);
	}
}
