/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
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

import com.google.common.base.Strings;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.util.ColorUtil;

public class OverlayUtil
{
	private static final int MINIMAP_DOT_RADIUS = 4;

	/**
	 * Whether the overlay currently being rendered prefers the native buffer.
	 * Used by shared helpers (e.g. {@code ModelOutlineRenderer}) that draw outside the
	 * overlay's {@link Graphics2D} but should still honour hub opt-in.
	 */
	private static final ThreadLocal<Boolean> CURRENT_OVERLAY_NATIVE = ThreadLocal.withInitial(() -> false);

	/**
	 * Canvas-space scale for DYNAMIC decorations (e.g. progress pies, images, text) under native overlays.
	 * Matches {@link net.runelite.client.ui.overlay.NativeOverlayBuffer#getPanelContentScaleX()}:
	 * fixed overlay size cancels stretch; otherwise stretch is kept.
	 */
	public static final RenderingHints.Key KEY_NATIVE_VISUAL_SIZE_FACTOR = new RenderingHints.Key(0x4e4f5649)
	{
		@Override
		public boolean isCompatibleValue(Object val)
		{
			return val instanceof Number;
		}
	};

	public static final RenderingHints.Key KEY_NATIVE_VISUAL_SIZE_FACTOR_Y = new RenderingHints.Key(0x4e4f5659)
	{
		@Override
		public boolean isCompatibleValue(Object val)
		{
			return val instanceof Number;
		}
	};

	public static void renderPolygon(Graphics2D graphics, Shape poly, Color color)
	{
		renderPolygon(graphics, poly, color, new BasicStroke(2));
	}

	public static void renderPolygon(Graphics2D graphics, Shape poly, Color color, Stroke borderStroke)
	{
		renderPolygon(graphics, poly, color, new Color(0, 0, 0, 50), borderStroke);
	}

	public static void renderPolygon(Graphics2D graphics, Shape poly, Color color, Color fillColor, Stroke borderStroke)
	{
		graphics.setColor(color);
		final Stroke originalStroke = graphics.getStroke();
		graphics.setStroke(borderStroke);
		graphics.draw(poly);
		graphics.setColor(fillColor);
		graphics.fill(poly);
		graphics.setStroke(originalStroke);
	}

	public static void renderMinimapLocation(Graphics2D graphics, Point mini, Color color)
	{
		graphics.setColor(Color.BLACK);
		graphics.fillOval(mini.getX() - MINIMAP_DOT_RADIUS / 2, mini.getY() - MINIMAP_DOT_RADIUS / 2 + 1, MINIMAP_DOT_RADIUS, MINIMAP_DOT_RADIUS);
		graphics.setColor(ColorUtil.colorWithAlpha(color, 0xFF));
		graphics.fillOval(mini.getX() - MINIMAP_DOT_RADIUS / 2, mini.getY() - MINIMAP_DOT_RADIUS / 2, MINIMAP_DOT_RADIUS, MINIMAP_DOT_RADIUS);
	}

	/**
	 * Draws a circular ring. With antialiasing on, uses a stroked ellipse (smooth Hybrid/Linear UI).
	 * With antialiasing off (GPU nearest UI pixel grid), paints a symmetric Euclidean annulus —
	 * slightly smaller than a centered {@link java.awt.BasicStroke}.
	 *
	 * @param x top-left of the diameter×diameter oval frame (same as {@code drawOval})
	 * @param thickness stroke width in pixels
	 */
	public static void drawPixelRing(Graphics2D graphics, int x, int y, int diameter, int thickness)
	{
		drawPixelArc(graphics, x, y, diameter, thickness, 0, -360);
	}

	/**
	 * Same as {@link #drawPixelRing} but only the arc used by {@link java.awt.geom.Arc2D}:
	 * {@code startAngle} degrees (0 = 3 o'clock, CCW positive), {@code extentAngle} degrees
	 * (negative = clockwise), matching {@code Arc2D} / regen meter conventions.
	 */
	public static void drawPixelArc(Graphics2D graphics, int x, int y, int diameter, int thickness,
		double startAngle, double extentAngle)
	{
		if (diameter <= 0 || thickness <= 0 || extentAngle == 0)
		{
			return;
		}

		thickness = Math.min(thickness, Math.max(1, diameter));
		final boolean fullCircle = Math.abs(extentAngle) >= 360;

		if (isShapeAntialiasOn(graphics))
		{
			final Stroke oldStroke = graphics.getStroke();
			graphics.setStroke(new BasicStroke(thickness, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
			if (fullCircle)
			{
				graphics.draw(new Ellipse2D.Double(x - 0.5, y - 0.5, diameter, diameter));
			}
			else
			{
				graphics.draw(new Arc2D.Double(x - 0.5, y - 0.5, diameter, diameter, startAngle, extentAngle, Arc2D.OPEN));
			}
			graphics.setStroke(oldStroke);
			return;
		}

		// Nearest / AA-off: Euclidean ring, ~0.5px smaller than stroke-centered oval
		final double cx = x + diameter / 2.0 - 0.5;
		final double cy = y + diameter / 2.0 - 0.5;
		final double rMid = diameter / 2.0 - 0.5;
		final double rOuter = rMid + thickness / 2.0;
		final double rInner = Math.max(0, rMid - thickness / 2.0);
		final double outerSq = rOuter * rOuter;
		final double innerSq = rInner * rInner;
		final int pad = (int) Math.ceil(thickness / 2.0);

		for (int py = y - pad - 1; py < y + diameter + pad; py++)
		{
			final double dy = py - cy;
			final double dySq = dy * dy;
			for (int px = x - pad - 1; px < x + diameter + pad; px++)
			{
				final double dx = px - cx;
				final double d2 = dx * dx + dySq;
				if (d2 > outerSq || d2 <= innerSq)
				{
					continue;
				}
				if (!fullCircle)
				{
					final double deg = Math.toDegrees(Math.atan2(cy - py, px - cx));
					if (!angleOnArc(deg, startAngle, extentAngle))
					{
						continue;
					}
				}
				graphics.fillRect(px, py, 1, 1);
			}
		}
	}

	private static boolean isShapeAntialiasOn(Graphics2D graphics)
	{
		Object hint = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		return hint != RenderingHints.VALUE_ANTIALIAS_OFF;
	}

	/**
	 * Whether {@code deg} lies on an Arc2D from {@code start} through {@code extent} degrees.
	 */
	private static boolean angleOnArc(double deg, double start, double extent)
	{
		deg = normalizeDegrees360(deg);
		start = normalizeDegrees360(start);
		if (extent < 0)
		{
			// Clockwise distance from start to deg
			final double clockwise = normalizeDegrees360(start - deg);
			return clockwise <= -extent;
		}
		final double counterClockwise = normalizeDegrees360(deg - start);
		return counterClockwise <= extent;
	}

	private static double normalizeDegrees360(double deg)
	{
		deg %= 360;
		if (deg < 0)
		{
			deg += 360;
		}
		return deg;
	}

	@Deprecated
	public static void renderMinimapRect(Client client, Graphics2D graphics, Point center, int width, int height, Color color)
	{
		double angle = client.getCameraYawTarget() * Perspective.UNIT;

		graphics.setColor(color);
		graphics.rotate(angle, center.getX(), center.getY());
		graphics.drawRect(center.getX() - width / 2, center.getY() - height / 2, width - 1, height - 1);
		graphics.rotate(-angle, center.getX(), center.getY());
	}

	public static void renderTextLocation(Graphics2D graphics, Point txtLoc, String text, Color color)
	{
		if (Strings.isNullOrEmpty(text))
		{
			return;
		}

		int x = txtLoc.getX();
		int y = txtLoc.getY();
		float shadow = (float) getNativeVisualSizeFactor(graphics);

		graphics.setColor(Color.BLACK);
		graphics.drawString(text, x + shadow, y + shadow);

		graphics.setColor(ColorUtil.colorWithAlpha(color, 0xFF));
		graphics.drawString(text, x, y);
	}

	public static void renderImageLocation(Client client, Graphics2D graphics, LocalPoint localPoint, BufferedImage image, int zOffset)
	{
		Point imageLocation = Perspective.getCanvasImageLocation(client, localPoint, image, zOffset);
		if (imageLocation != null)
		{
			renderImageLocation(graphics, imageLocation, image);
		}
	}

	/**
	 * Canvas-space width/height the image occupies after size-mode scaling.
	 * Use this for layout next to text instead of {@link BufferedImage#getWidth()}/{@link BufferedImage#getHeight()}.
	 */
	public static Dimension getImageLayoutSize(Graphics2D graphics, BufferedImage image)
	{
		double fx = getNativeVisualSizeFactor(graphics);
		double fy = getNativeVisualSizeFactorY(graphics);
		if (fx == 1.0 && fy == 1.0)
		{
			return new Dimension(image.getWidth(), image.getHeight());
		}
		return new Dimension(
			Math.max(1, (int) Math.round(image.getWidth() * fx)),
			Math.max(1, (int) Math.round(image.getHeight() * fy)));
	}

	/**
	 * Renders an image at a canvas location. {@code imgLoc} is the top-left of the full bitmap
	 * footprint (as returned by {@link Perspective#getCanvasImageLocation} / actor canvas image
	 * helpers). Under native overlays, fixed overlay size inverse-scales the sprite (and centers it
	 * in that footprint); otherwise it draws at full bitmap size so it grows with stretch.
	 */
	public static void renderImageLocation(Graphics2D graphics, Point imgLoc, BufferedImage image)
	{
		Dimension layout = getImageLayoutSize(graphics, image);
		int x = imgLoc.getX() + (image.getWidth() - layout.width) / 2;
		int y = imgLoc.getY() + (image.getHeight() - layout.height) / 2;
		renderImageLocationExact(graphics, x, y, image);
	}

	/**
	 * Renders an image with {@code (x, y)} as the top-left of the drawn (layout-sized) sprite.
	 * Use with {@link #getImageLayoutSize} when packing icons next to text.
	 */
	public static void renderImageLocationExact(Graphics2D graphics, Point imgLoc, BufferedImage image)
	{
		renderImageLocationExact(graphics, imgLoc.getX(), imgLoc.getY(), image);
	}

	private static void renderImageLocationExact(Graphics2D graphics, int x, int y, BufferedImage image)
	{
		// Under native overlays the Graphics2D has stretch scale for canvas-space positioning.
		// Apply size factor so sprites match DYNAMIC text (fixed size cancels stretch).
		double fx = getNativeVisualSizeFactor(graphics);
		double fy = getNativeVisualSizeFactorY(graphics);
		if (fx != 1.0 || fy != 1.0)
		{
			AffineTransform transform = graphics.getTransform();
			graphics.translate(x, y);
			graphics.scale(fx, fy);
			graphics.drawImage(image, 0, 0, null);
			graphics.setTransform(transform);
		}
		else
		{
			graphics.drawImage(image, x, y, null);
		}
	}

	public static void renderActorOverlay(Graphics2D graphics, Actor actor, String text, Color color)
	{
		Polygon poly = actor.getCanvasTilePoly();
		if (poly != null)
		{
			renderPolygon(graphics, poly, color);
		}

		Point textLocation = actor.getCanvasTextLocation(graphics, text, actor.getLogicalHeight() + 40);
		if (textLocation != null)
		{
			renderTextLocation(graphics, textLocation, text, color);
		}
	}

	public static void renderActorOverlayImage(Graphics2D graphics, Actor actor, BufferedImage image, Color color, int zOffset)
	{
		Polygon poly = actor.getCanvasTilePoly();
		if (poly != null)
		{
			renderPolygon(graphics, poly, color);
		}

		Point imageLocation = actor.getCanvasImageLocation(image, zOffset);
		if (imageLocation != null)
		{
			renderImageLocation(graphics, imageLocation, image);
		}
	}

	public static void renderTileOverlay(Graphics2D graphics, TileObject tileObject, String text, Color color)
	{
		Polygon poly = tileObject.getCanvasTilePoly();
		if (poly != null)
		{
			renderPolygon(graphics, poly, color);
		}

		Point minimapLocation = tileObject.getMinimapLocation();
		if (minimapLocation != null)
		{
			renderMinimapLocation(graphics, minimapLocation, color);
		}

		Point textLocation = tileObject.getCanvasTextLocation(graphics, text, 0);
		if (textLocation != null)
		{
			renderTextLocation(graphics, textLocation, text, color);
		}
	}

	public static void renderTileOverlay(Client client, Graphics2D graphics, LocalPoint localLocation, BufferedImage image, Color color)
	{
		Polygon poly = Perspective.getCanvasTilePoly(client, localLocation);
		if (poly != null)
		{
			renderPolygon(graphics, poly, color);
		}

		renderImageLocation(client, graphics, localLocation, image, 0);
	}

	public static void renderHoverableArea(Graphics2D graphics, Shape area, Point mousePosition, Color fillColor, Color borderColor, Color borderHoverColor)
	{
		if (area != null)
		{
			if (area.contains(mousePosition.getX(), mousePosition.getY()))
			{
				graphics.setColor(borderHoverColor);
			}
			else
			{
				graphics.setColor(borderColor);
			}

			graphics.draw(area);
			graphics.setColor(fillColor);
			graphics.fill(area);
		}
	}

	public static void setGraphicProperties(Graphics2D graphics)
	{
		setGraphicProperties(graphics, true);
	}

	public static void setGraphicProperties(Graphics2D graphics, boolean antialias)
	{
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
			antialias ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
	}

	/**
	 * Antialiasing plus stretch overlay-size factors for the native overlay Graphics2D.
	 */
	public static void setNativeOverlayProperties(Graphics2D graphics, double visualSizeFactorX, double visualSizeFactorY)
	{
		setNativeOverlayProperties(graphics, visualSizeFactorX, visualSizeFactorY, true);
	}

	public static void setNativeOverlayProperties(Graphics2D graphics, double visualSizeFactorX, double visualSizeFactorY,
		boolean antialias)
	{
		setGraphicProperties(graphics, antialias);
		graphics.setRenderingHint(KEY_NATIVE_VISUAL_SIZE_FACTOR, visualSizeFactorX);
		graphics.setRenderingHint(KEY_NATIVE_VISUAL_SIZE_FACTOR_Y, visualSizeFactorY);
	}

	public static double getNativeVisualSizeFactor(Graphics2D graphics)
	{
		Object value = graphics.getRenderingHint(KEY_NATIVE_VISUAL_SIZE_FACTOR);
		if (value instanceof Number)
		{
			return ((Number) value).doubleValue();
		}
		return 1.0;
	}

	public static double getNativeVisualSizeFactorY(Graphics2D graphics)
	{
		Object value = graphics.getRenderingHint(KEY_NATIVE_VISUAL_SIZE_FACTOR_Y);
		if (value instanceof Number)
		{
			return ((Number) value).doubleValue();
		}
		return getNativeVisualSizeFactor(graphics);
	}

	/**
	 * @return previous value
	 */
	public static boolean setCurrentOverlayNative(boolean nativePass)
	{
		boolean previous = CURRENT_OVERLAY_NATIVE.get();
		CURRENT_OVERLAY_NATIVE.set(nativePass);
		return previous;
	}

	public static boolean isCurrentOverlayNative()
	{
		return CURRENT_OVERLAY_NATIVE.get();
	}
}
