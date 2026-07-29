/*
 * Copyright (c) 2018, Kamiel
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
package net.runelite.client.ui.overlay.components;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import lombok.Setter;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.RenderableEntity;

@Setter
public class ProgressPieComponent implements RenderableEntity
{
	private int diameter = 25;
	private Color borderColor = Color.WHITE;
	private Color fill = Color.WHITE;
	private Stroke stroke = new BasicStroke(1);
	private double progress;
	private Point position;

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Under native overlays, apply stretch size factor without affecting world polygons.
		final double sizeFactor = OverlayUtil.getNativeVisualSizeFactor(graphics);
		final int drawDiameter = Math.max(1, (int) Math.round(diameter * sizeFactor));
		final int x = position.getX() - drawDiameter / 2;
		final int y = position.getY() - drawDiameter / 2;

		final Object oldAa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		Arc2D.Float arc = new Arc2D.Float(Arc2D.PIE);
		arc.setAngleStart(90);
		arc.setAngleExtent(progress * 360);
		arc.setFrame(x, y, drawDiameter, drawDiameter);

		graphics.setColor(fill);
		graphics.fill(arc);

		Stroke drawStroke = stroke;
		if (sizeFactor != 1.0 && stroke instanceof BasicStroke)
		{
			BasicStroke basic = (BasicStroke) stroke;
			drawStroke = new BasicStroke(
				(float) (basic.getLineWidth() * sizeFactor),
				basic.getEndCap(),
				basic.getLineJoin(),
				basic.getMiterLimit(),
				basic.getDashArray(),
				basic.getDashPhase());
		}
		graphics.setStroke(drawStroke);
		graphics.setColor(borderColor);
		graphics.drawOval(x, y, drawDiameter, drawDiameter);

		if (oldAa != null)
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
		}

		return new Dimension(drawDiameter, drawDiameter);
	}

	public void setBorder(Color border, int size)
	{
		this.borderColor = border;
		stroke = new BasicStroke(size);
	}
}
