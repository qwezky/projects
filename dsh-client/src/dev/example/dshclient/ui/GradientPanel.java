package dev.example.dshclient.ui;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;
import javax.swing.JPanel;

/** 通用圆角渐变容器；角度以水平向右为 0°、顺时针为正。 */
public class GradientPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private Color startColor;
    private Color endColor;
    private Color borderColor;
    private float angleDegrees;
    private int arc;
    private boolean topHighlight;

    @SuppressWarnings("this-escape")
    public GradientPanel(Color startColor, Color endColor, float angleDegrees,
            int arc, Color borderColor, boolean topHighlight) {
        if (arc < 0 || arc > 12) {
            throw new IllegalArgumentException("圆角必须在 0 到 12px 之间");
        }
        this.startColor = Objects.requireNonNull(startColor, "startColor");
        this.endColor = Objects.requireNonNull(endColor, "endColor");
        this.borderColor = Objects.requireNonNull(borderColor, "borderColor");
        this.angleDegrees = angleDegrees;
        this.arc = arc;
        this.topHighlight = topHighlight;
        super.setOpaque(false);
    }

    public void setGradient(Color startColor, Color endColor) {
        this.startColor = Objects.requireNonNull(startColor, "startColor");
        this.endColor = Objects.requireNonNull(endColor, "endColor");
        repaint();
    }

    public void setAngleDegrees(float angleDegrees) {
        this.angleDegrees = angleDegrees;
        repaint();
    }

    public void setArc(int arc) {
        if (arc < 0 || arc > 12) {
            throw new IllegalArgumentException("圆角必须在 0 到 12px 之间");
        }
        this.arc = arc;
        repaint();
    }

    public void setBorderColor(Color borderColor) {
        this.borderColor = Objects.requireNonNull(borderColor, "borderColor");
        repaint();
    }

    public void setTopHighlight(boolean topHighlight) {
        this.topHighlight = topHighlight;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            RoundRectangle2D.Float shape = new RoundRectangle2D.Float(
                    0f, 0f, getWidth(), getHeight(), arc, arc);
            double radians = Math.toRadians(angleDegrees);
            float dx = (float) (Math.cos(radians) * getWidth() / 2.0);
            float dy = (float) (Math.sin(radians) * getHeight() / 2.0);
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            g.setPaint(new GradientPaint(centerX - dx, centerY - dy, startColor,
                    centerX + dx, centerY + dy, endColor));
            g.fill(shape);

            if (topHighlight) {
                Shape oldClip = g.getClip();
                g.clip(shape);
                g.setColor(new Color(255, 255, 255, 24));
                g.drawLine(arc / 2, 0, getWidth() - arc / 2, 0);
                g.setClip(oldClip);
            }
            g.setColor(borderColor);
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f,
                    getWidth() - 1f, getHeight() - 1f, arc, arc));
        } finally {
            g.dispose();
        }
    }
}
