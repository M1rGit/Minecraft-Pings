package dev.chatping.render;

import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import dev.chatping.config.ChatPingConfig;
import dev.chatping.config.ChatPingConfigRoot;
import dev.chatping.config.ChatPingPlayerColors;
import dev.chatping.ping.PingManager;
import me.shedaniel.autoconfig.AutoConfig;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.DynamicUniforms;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Same visual as the 1.21.1 renderer (beam + cube dot + billboarded label, all ignoring
 * the depth buffer), but rebuilt for the post-1.21.2/1.21.9 rendering pipeline rewrite:
 * {@code BufferRenderer} and the old {@code GameRenderer.getPositionColorProgram()} +
 * one-call draw are gone, replaced by an explicit {@link RenderPipeline} and manual
 * GPU buffer upload + {@link RenderPass}. {@code WorldRenderContext} also lost
 * {@code camera()}/{@code matrixStack()} in favor of {@code worldState().cameraRenderState}
 * and {@code matrices()}.
 *
 * <p>This is the highest-risk file in the mod — the new rendering API is very recent
 * (late 2025) and was translated from Mojang-mapped reference code to Yarn by hand,
 * not compiled against. If it fails to build, the mismatch is almost certainly here.
 */
public final class PingRenderer {
	private PingRenderer() {}

	private static final float BEAM_HEIGHT_UP = 40f;
	private static final float BEAM_HEIGHT_DOWN = 40f;
	private static final float BEAM_HALF_WIDTH = 0.12f;
	private static final float FADE_START = 0.7f; // fraction of lifetime where fade-out begins

	private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
			.withLocation(Identifier.of("chatping", "pipeline/filled_through_walls"))
			.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
			.withCull(false)
			.build();

	private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

	public static void register() {
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(PingRenderer::render);
	}

	private static void render(WorldRenderContext context) {
		var pings = PingManager.activePings();
		if (pings.isEmpty()) {
			return;
		}

		ChatPingConfigRoot root = AutoConfig.getConfigHolder(ChatPingConfigRoot.class).getConfig();
		ChatPingConfig config = root.general;
		ChatPingPlayerColors playerColors = root.players;
		MinecraftClient client = MinecraftClient.getInstance();
		Vec3d cameraPos = context.worldState().cameraRenderState.pos;
		long now = System.currentTimeMillis();
		long lifetimeMs = Math.max(1, config.markerLifetimeSeconds * 1000L);

		boolean anyVisible = false;
		for (PingManager.Ping ping : pings) {
			if (beamAlpha(fadeFor(ping, now, lifetimeMs)) > 0) {
				anyVisible = true;
				break;
			}
		}

		if (anyVisible) {
			Tessellator tessellator = Tessellator.getInstance();
			BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, FILLED_THROUGH_WALLS.getVertexFormat());

			for (PingManager.Ping ping : pings) {
				float fade = fadeFor(ping, now, lifetimeMs);
				int rgb = playerColors.colorFor(ping.senderName(), config.markerColor);
				drawBeam(buffer, ping.pos(), cameraPos, fade, config, rgb);
			}

			drawFilledThroughWalls(client, buffer.end());
		}

		MatrixStack matrices = context.matrices();
		VertexConsumerProvider consumers = context.consumers();
		if (matrices != null && consumers instanceof VertexConsumerProvider.Immediate immediate) {
			for (PingManager.Ping ping : pings) {
				float fade = fadeFor(ping, now, lifetimeMs);
				int rgb = playerColors.colorFor(ping.senderName(), config.markerColor);
				drawLabel(matrices, immediate, client.textRenderer, context, ping, cameraPos, config, fade, rgb);
			}
			immediate.drawCurrentLayer();
		}
	}

	// Uploads the built beam/dot geometry to the GPU and draws it in its own render pass,
	// replacing the old one-call BufferRenderer.drawWithGlobalProgram(buffer.end()).
	private static void drawFilledThroughWalls(MinecraftClient client, BuiltBuffer builtBuffer) {
		BuiltBuffer.DrawParameters drawParameters = builtBuffer.getDrawParameters();

		GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "chatping ping vertices", GpuBuffer.USAGE_VERTEX, builtBuffer.getBuffer());
		RenderSystem.ShapeIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(FILLED_THROUGH_WALLS.getVertexFormatMode());
		GpuBuffer indices = shapeIndexBuffer.getIndexBuffer(drawParameters.indexCount());

		GpuBufferSlice[] transformSlices = RenderSystem.getDynamicUniforms().writeTransforms(new DynamicUniforms.TransformsValue[] {
				new DynamicUniforms.TransformsValue(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX)
		});

		Framebuffer framebuffer = client.getFramebuffer();

		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> "chatping ping render", framebuffer.getColorAttachmentView(), OptionalInt.empty(), framebuffer.getDepthAttachmentView(), OptionalDouble.empty())) {
			pass.setPipeline(FILLED_THROUGH_WALLS);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transformSlices[0]);
			pass.setVertexBuffer(0, vertices);
			pass.setIndexBuffer(indices, shapeIndexBuffer.getIndexType());
			pass.drawIndexed(0, 0, drawParameters.indexCount(), 1);
		}

		vertices.close();
		builtBuffer.close();
	}

	private static float fadeFor(PingManager.Ping ping, long now, long lifetimeMs) {
		float age = (now - ping.createdAtMs()) / (float) lifetimeMs;
		if (age <= FADE_START) {
			return 1f;
		}
		return MathHelper.clamp(1f - (age - FADE_START) / (1f - FADE_START), 0f, 1f);
	}

	private static int beamAlpha(float fade) {
		return (int) (200 * fade);
	}

	private static void drawBeam(BufferBuilder buffer, BlockPos pos, Vec3d cameraPos, float fade, ChatPingConfig config, int rgb) {
		int alpha = beamAlpha(fade);
		if (alpha <= 0) {
			return;
		}
		double x = pos.getX() + 0.5 - cameraPos.x;
		double y = pos.getY() + 1.0 - cameraPos.y;
		double z = pos.getZ() + 0.5 - cameraPos.z;

		if (config.renderMode == ChatPingConfig.RenderMode.BEAM_AND_DOT) {
			// Beam runs both up and down from the ping point, fading out at both far ends.
			beamQuads(buffer, x, y, z, BEAM_HEIGHT_UP, alpha, rgb);
			beamQuads(buffer, x, y, z, -BEAM_HEIGHT_DOWN, alpha, rgb);
		}

		// A thick dot — a solid cube — marks the ping point itself.
		dot(buffer, x, y, z, alpha, rgb, config.dotSizePercent / 100f);
	}

	private static void beamQuads(BufferBuilder buffer, double x, double y, double z, float height, int alpha, int rgb) {
		double yFar = y + height;
		// Two perpendicular vertical quads so the beam reads from any horizontal angle.
		quad(buffer, x - BEAM_HALF_WIDTH, y, z, x + BEAM_HALF_WIDTH, y, z, x + BEAM_HALF_WIDTH, yFar, z, x - BEAM_HALF_WIDTH, yFar, z, alpha, rgb);
		quad(buffer, x, y, z - BEAM_HALF_WIDTH, x, y, z + BEAM_HALF_WIDTH, x, yFar, z + BEAM_HALF_WIDTH, x, yFar, z - BEAM_HALF_WIDTH, alpha, rgb);
	}

	private static void dot(BufferBuilder buffer, double x, double y, double z, int alpha, int rgb, float s) {
		solidQuad(buffer, x - s, y - s, z - s, x + s, y - s, z - s, x + s, y - s, z + s, x - s, y - s, z + s, alpha, rgb); // bottom
		solidQuad(buffer, x - s, y + s, z - s, x - s, y + s, z + s, x + s, y + s, z + s, x + s, y + s, z - s, alpha, rgb); // top
		solidQuad(buffer, x - s, y - s, z - s, x - s, y + s, z - s, x + s, y + s, z - s, x + s, y - s, z - s, alpha, rgb); // front
		solidQuad(buffer, x - s, y - s, z + s, x + s, y - s, z + s, x + s, y + s, z + s, x - s, y + s, z + s, alpha, rgb); // back
		solidQuad(buffer, x - s, y - s, z - s, x - s, y - s, z + s, x - s, y + s, z + s, x - s, y + s, z - s, alpha, rgb); // left
		solidQuad(buffer, x + s, y - s, z - s, x + s, y + s, z - s, x + s, y + s, z + s, x + s, y - s, z + s, alpha, rgb); // right
	}

	private static void quad(BufferBuilder buffer, double x1, double y1, double z1, double x2, double y2, double z2,
			double x3, double y3, double z3, double x4, double y4, double z4, int alpha, int rgb) {
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		buffer.vertex((float) x1, (float) y1, (float) z1).color(r, g, b, alpha);
		buffer.vertex((float) x2, (float) y2, (float) z2).color(r, g, b, alpha);
		buffer.vertex((float) x3, (float) y3, (float) z3).color(r, g, b, 0);
		buffer.vertex((float) x4, (float) y4, (float) z4).color(r, g, b, 0);
	}

	private static void solidQuad(BufferBuilder buffer, double x1, double y1, double z1, double x2, double y2, double z2,
			double x3, double y3, double z3, double x4, double y4, double z4, int alpha, int rgb) {
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		buffer.vertex((float) x1, (float) y1, (float) z1).color(r, g, b, alpha);
		buffer.vertex((float) x2, (float) y2, (float) z2).color(r, g, b, alpha);
		buffer.vertex((float) x3, (float) y3, (float) z3).color(r, g, b, alpha);
		buffer.vertex((float) x4, (float) y4, (float) z4).color(r, g, b, alpha);
	}

	private static final float LABEL_HEIGHT_ABOVE_DOT = 0.6f;
	private static final double LABEL_FORWARD_OFFSET = 0.4; // pulled toward the viewer so it doesn't intersect the beam
	private static final int LABEL_BACKGROUND_RGB = 0x505050; // gray plate behind the name

	private static void drawLabel(MatrixStack matrices, VertexConsumerProvider.Immediate consumers, TextRenderer textRenderer,
			WorldRenderContext context, PingManager.Ping ping, Vec3d cameraPos, ChatPingConfig config, float fade, int rgb) {
		double x = ping.pos().getX() + 0.5 - cameraPos.x;
		double y = ping.pos().getY() + 1.0 - cameraPos.y + LABEL_HEIGHT_ABOVE_DOT;
		double z = ping.pos().getZ() + 0.5 - cameraPos.z;

		// Pull the label along the camera ray towards the viewer, so its billboard plane
		// sits in front of the beam instead of visually crossing through it.
		double cameraDistance = Math.sqrt(x * x + y * y + z * z);
		if (cameraDistance > 0.01) {
			double pull = Math.min(LABEL_FORWARD_OFFSET, cameraDistance - 0.1) / cameraDistance;
			x -= x * pull;
			y -= y * pull;
			z -= z * pull;
		}

		String labelText = ping.senderName();
		if (config.showDistanceInLabel) {
			double distance = Math.sqrt(cameraPos.squaredDistanceTo(ping.pos().getX() + 0.5, ping.pos().getY() + 0.5, ping.pos().getZ() + 0.5));
			labelText = labelText + " (" + Math.round(distance) + "m)";
		}
		Text text = Text.literal(labelText);
		int alpha = (int) (255 * fade);
		if (alpha <= 0) {
			return;
		}
		int color = (alpha << 24) | (rgb & 0xFFFFFF);
		int backgroundAlpha = (int) (0x90 * fade);
		int backgroundColor = (backgroundAlpha << 24) | LABEL_BACKGROUND_RGB;

		float scale = 0.025f * (config.labelScalePercent / 100f);

		matrices.push();
		matrices.translate(x, y, z);
		matrices.multiply(context.worldState().cameraRenderState.orientation);
		matrices.scale(scale, -scale, scale);
		float width = textRenderer.getWidth(text);
		// Draw with its bottom edge at the local origin (not the top), so the origin stays
		// pinned to the bottom-center of the plate and enlarging grows the text upward.
		textRenderer.draw(text, -width / 2f, -textRenderer.fontHeight, color, false, matrices.peek().getPositionMatrix(), consumers,
				TextRenderer.TextLayerType.SEE_THROUGH, backgroundColor, 0xF000F0);
		matrices.pop();
	}
}
