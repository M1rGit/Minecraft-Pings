package dev.chatping.render;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import dev.chatping.config.ChatPingConfig;
import dev.chatping.config.ChatPingConfigRoot;
import dev.chatping.config.ChatPingPlayerColors;
import dev.chatping.ping.PingManager;
import me.shedaniel.autoconfig.AutoConfig;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Third rendering generation in this mod — same visual, third low-level GPU API.
 * Between 26.1.2 (my first 26.2 reference) and 26.2 itself, the manual
 * {@code GpuBuffer}/{@code MappableRingBuffer} approach was replaced by
 * {@link StagedVertexBuffer}, and {@code RenderPipeline.getVertexFormat()}/
 * {@code getVertexFormatMode()} by {@code getVertexFormatBinding()}/
 * {@code getPrimitiveTopology()}. {@code DepthTestFunction} is gone too —
 * {@code withDepthStencilState(Optional.empty())} is now how a pipeline disables
 * depth testing. Ported from FabricMC's own "latest" reference example, since no
 * version-pinned 26.2 reference exists yet. Not compiled — expect more fixes.
 *
 * <p>The extraction/render split and the SubmitNodeCollector-based label text are
 * unchanged from the first 26.2 attempt — only the beam/dot GPU plumbing changed.
 */
public final class PingRenderer {
	private PingRenderer() {}

	private static final float BEAM_HEIGHT_UP = 40f;
	private static final float BEAM_HEIGHT_DOWN = 40f;
	private static final float BEAM_HALF_WIDTH = 0.12f;
	private static final float FADE_START = 0.7f; // fraction of lifetime where fade-out begins

	// RenderPipelines.register(...) is private here (same as 1.21.11) — .build() alone
	// is enough, the pipeline just compiles its shader lazily on first use instead of eagerly.
	private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("chatping", "pipeline/filled_through_walls"))
			.withDepthStencilState(Optional.empty())
			.build();

	private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
	// Vertices below are already camera-relative (computed by hand, same as every other
	// version of this renderer) — an identity matrix leaves those positions untouched.
	private static final Matrix4f IDENTITY = new Matrix4f();
	private static final StagedVertexBuffer STAGED_BUFFER = new StagedVertexBuffer(() -> "chatping ping buffer", RenderType.SMALL_BUFFER_SIZE);

	/** Everything the render phase needs, captured once during extraction. */
	private record FrameState(List<PingManager.Ping> pings, Vec3 cameraPos, Quaternionf cameraOrientation) {}

	private static volatile FrameState frameState;

	public static void register() {
		LevelExtractionEvents.END_EXTRACTION.register(PingRenderer::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(PingRenderer::render);
	}

	private static void extract(LevelExtractionContext context) {
		List<PingManager.Ping> pings = PingManager.activePings();
		if (pings.isEmpty()) {
			frameState = null;
			return;
		}
		Camera camera = context.camera();
		frameState = new FrameState(List.copyOf(pings), camera.position(), new Quaternionf(camera.rotation()));
	}

	private static void render(LevelRenderContext context) {
		FrameState state = frameState;
		if (state == null) {
			return;
		}

		ChatPingConfigRoot root = AutoConfig.getConfigHolder(ChatPingConfigRoot.class).getConfig();
		ChatPingConfig config = root.general;
		ChatPingPlayerColors playerColors = root.players;
		Minecraft client = Minecraft.getInstance();
		Vec3 cameraPos = state.cameraPos();
		long now = System.currentTimeMillis();
		long lifetimeMs = Math.max(1, config.markerLifetimeSeconds * 1000L);

		boolean anyVisible = false;
		for (PingManager.Ping ping : state.pings()) {
			if (beamAlpha(fadeFor(ping, now, lifetimeMs)) > 0) {
				anyVisible = true;
				break;
			}
		}

		if (anyVisible) {
			drawFilledThroughWalls(client, state, config, playerColors, now, lifetimeMs);
		}

		PoseStack matrices = context.poseStack();
		SubmitNodeCollector queue = context.submitNodeCollector();
		for (PingManager.Ping ping : state.pings()) {
			float fade = fadeFor(ping, now, lifetimeMs);
			int rgb = playerColors.colorFor(ping.senderName(), config.markerColor);
			drawLabel(matrices, queue, client.font, state.cameraOrientation(), ping, cameraPos, config, fade, rgb);
		}
	}

	private static void drawFilledThroughWalls(Minecraft client, FrameState state, ChatPingConfig config,
			ChatPingPlayerColors playerColors, long now, long lifetimeMs) {
		RenderPipeline pipeline = FILLED_THROUGH_WALLS;
		VertexFormat formatBinding = pipeline.getVertexFormatBinding(0);
		if (formatBinding == null) {
			return;
		}
		PrimitiveTopology primitive = pipeline.getPrimitiveTopology();
		StagedVertexBuffer.Draw draw = STAGED_BUFFER.appendDraw(formatBinding, primitive,
				primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

		VertexConsumer buffer = STAGED_BUFFER.getVertexBuilder(draw);
		Vec3 cameraPos = state.cameraPos();
		for (PingManager.Ping ping : state.pings()) {
			float fade = fadeFor(ping, now, lifetimeMs);
			int rgb = playerColors.colorFor(ping.senderName(), config.markerColor);
			drawBeam(buffer, ping.pos(), cameraPos, fade, config, rgb);
		}

		STAGED_BUFFER.upload();
		StagedVertexBuffer.ExecuteInfo info = STAGED_BUFFER.getExecuteInfo(draw);
		if (info != null) {
			drawToGpu(client, info, pipeline);
		}
		STAGED_BUFFER.endFrame();
	}

	private static void drawToGpu(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
		GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
				.writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

		RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
		GpuTextureView colorTexture = mainTarget.getColorTextureView();

		try (RenderPass pass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> "chatping ping render", colorTexture, Optional.empty(), mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", dynamicTransforms);
			pass.setVertexBuffer(0, info.vertexBuffer().slice());
			pass.setIndexBuffer(info.indexBuffer(), info.indexType());
			pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
		}
	}

	private static float fadeFor(PingManager.Ping ping, long now, long lifetimeMs) {
		float age = (now - ping.createdAtMs()) / (float) lifetimeMs;
		if (age <= FADE_START) {
			return 1f;
		}
		return Mth.clamp(1f - (age - FADE_START) / (1f - FADE_START), 0f, 1f);
	}

	private static int beamAlpha(float fade) {
		return (int) (200 * fade);
	}

	private static void drawBeam(VertexConsumer buffer, BlockPos pos, Vec3 cameraPos, float fade, ChatPingConfig config, int rgb) {
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

	private static void beamQuads(VertexConsumer buffer, double x, double y, double z, float height, int alpha, int rgb) {
		double yFar = y + height;
		// Two perpendicular vertical quads so the beam reads from any horizontal angle.
		quad(buffer, x - BEAM_HALF_WIDTH, y, z, x + BEAM_HALF_WIDTH, y, z, x + BEAM_HALF_WIDTH, yFar, z, x - BEAM_HALF_WIDTH, yFar, z, alpha, rgb);
		quad(buffer, x, y, z - BEAM_HALF_WIDTH, x, y, z + BEAM_HALF_WIDTH, x, yFar, z + BEAM_HALF_WIDTH, x, yFar, z - BEAM_HALF_WIDTH, alpha, rgb);
	}

	private static void dot(VertexConsumer buffer, double x, double y, double z, int alpha, int rgb, float s) {
		solidQuad(buffer, x - s, y - s, z - s, x + s, y - s, z - s, x + s, y - s, z + s, x - s, y - s, z + s, alpha, rgb); // bottom
		solidQuad(buffer, x - s, y + s, z - s, x - s, y + s, z + s, x + s, y + s, z + s, x + s, y + s, z - s, alpha, rgb); // top
		solidQuad(buffer, x - s, y - s, z - s, x - s, y + s, z - s, x + s, y + s, z - s, x + s, y - s, z - s, alpha, rgb); // front
		solidQuad(buffer, x - s, y - s, z + s, x + s, y - s, z + s, x + s, y + s, z + s, x - s, y + s, z + s, alpha, rgb); // back
		solidQuad(buffer, x - s, y - s, z - s, x - s, y - s, z + s, x - s, y + s, z + s, x - s, y + s, z - s, alpha, rgb); // left
		solidQuad(buffer, x + s, y - s, z - s, x + s, y + s, z - s, x + s, y + s, z + s, x + s, y - s, z + s, alpha, rgb); // right
	}

	private static void quad(VertexConsumer buffer, double x1, double y1, double z1, double x2, double y2, double z2,
			double x3, double y3, double z3, double x4, double y4, double z4, int alpha, int rgb) {
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		buffer.addVertex(IDENTITY, (float) x1, (float) y1, (float) z1).setColor(r, g, b, alpha);
		buffer.addVertex(IDENTITY, (float) x2, (float) y2, (float) z2).setColor(r, g, b, alpha);
		buffer.addVertex(IDENTITY, (float) x3, (float) y3, (float) z3).setColor(r, g, b, 0);
		buffer.addVertex(IDENTITY, (float) x4, (float) y4, (float) z4).setColor(r, g, b, 0);
	}

	private static void solidQuad(VertexConsumer buffer, double x1, double y1, double z1, double x2, double y2, double z2,
			double x3, double y3, double z3, double x4, double y4, double z4, int alpha, int rgb) {
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		buffer.addVertex(IDENTITY, (float) x1, (float) y1, (float) z1).setColor(r, g, b, alpha);
		buffer.addVertex(IDENTITY, (float) x2, (float) y2, (float) z2).setColor(r, g, b, alpha);
		buffer.addVertex(IDENTITY, (float) x3, (float) y3, (float) z3).setColor(r, g, b, alpha);
		buffer.addVertex(IDENTITY, (float) x4, (float) y4, (float) z4).setColor(r, g, b, alpha);
	}

	private static final float LABEL_HEIGHT_ABOVE_DOT = 0.6f;
	private static final double LABEL_FORWARD_OFFSET = 0.4; // pulled toward the viewer so it doesn't intersect the beam
	private static final int LABEL_BACKGROUND_RGB = 0x505050; // gray plate behind the name

	private static void drawLabel(PoseStack matrices, SubmitNodeCollector queue, Font font, Quaternionf cameraOrientation,
			PingManager.Ping ping, Vec3 cameraPos, ChatPingConfig config, float fade, int rgb) {
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
			double distance = Math.sqrt(cameraPos.distanceToSqr(ping.pos().getX() + 0.5, ping.pos().getY() + 0.5, ping.pos().getZ() + 0.5));
			labelText = labelText + " (" + Math.round(distance) + "m)";
		}
		int alpha = (int) (255 * fade);
		if (alpha <= 0) {
			return;
		}
		int color = (alpha << 24) | (rgb & 0xFFFFFF);
		int backgroundAlpha = (int) (0x90 * fade);
		int backgroundColor = (backgroundAlpha << 24) | LABEL_BACKGROUND_RGB;

		float scale = 0.025f * (config.labelScalePercent / 100f);

		matrices.pushPose();
		matrices.translate(x, y, z);
		matrices.mulPose(cameraOrientation);
		matrices.scale(scale, -scale, scale);
		float width = font.width(labelText);
		// Draw with its bottom edge at the local origin (not the top), so the origin stays
		// pinned to the bottom-center of the plate and enlarging grows the text upward.
		queue.submitText(
				matrices,
				-width / 2f, -font.lineHeight,
				Component.literal(labelText).getVisualOrderText(),
				false,
				Font.DisplayMode.SEE_THROUGH,
				0xF000F0,
				color,
				backgroundColor,
				0);
		matrices.popPose();
	}
}
