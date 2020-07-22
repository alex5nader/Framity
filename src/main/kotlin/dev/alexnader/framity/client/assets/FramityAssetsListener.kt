package dev.alexnader.framity.client.assets

import com.google.gson.JsonElement
import dev.alexnader.framity.GSON
import dev.alexnader.framity.LOGGER
import dev.alexnader.framity.util.JsonParseException
import dev.alexnader.framity.util.toContext
import net.fabricmc.fabric.api.resource.SimpleResourceReloadListener
import net.minecraft.resource.ResourceManager
import net.minecraft.util.Identifier
import net.minecraft.util.profiler.Profiler
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.Supplier

private val OverlayInfoMap: MutableMap<Identifier, OverlayInfo> = mutableMapOf()

fun getOverlay(id: Identifier?) =
    id?.let { OverlayInfoMap[it] }
fun getValidOverlay(id: Identifier?) =
    id?.let { OverlayInfoMap[it] as? OverlayInfo.Complete? }


data class FramityAssets(val overlayIds: Collection<Identifier>)


class FramityAssetsListener : SimpleResourceReloadListener<FramityAssets> {
    companion object {
        private val ID = Identifier("framity", "assets_listener")
    }

    override fun getFabricId() = ID

    override fun load(
        manager: ResourceManager,
        profiler: Profiler,
        executor: Executor
    ): CompletableFuture<FramityAssets> =
        CompletableFuture.supplyAsync(Supplier {
            OverlayInfoMap.clear()

            val overlayIds = manager.findResources("framity/overlays") { s -> s.endsWith(".json") }

            FramityAssets(overlayIds)
        }, executor)

    private fun loadOverlay(manager: ResourceManager, rootOverlayId: Identifier) {
        val dependencies = mutableSetOf<Identifier>()

        fun loadOverlayRec(overlayId: Identifier) {
            val ctx = GSON.fromJson(manager.getResource(overlayId).inputStream.buffered().reader(), JsonElement::class.java).toContext(overlayId.toString())

            ctx.runParser(OverlayInfo.DependenciesParser).forEach {
                if (!dependencies.add(it)) {
                    ctx.error("Circular dependency: $it and $overlayId")
                }
                loadOverlayRec(it)
            }

            OverlayInfoMap[overlayId] = ctx.runParser(OverlayInfo.Parser)
        }

        if (rootOverlayId !in OverlayInfoMap) {
            loadOverlayRec(rootOverlayId)
        }
    }

    override fun apply(
        assets: FramityAssets,
        manager: ResourceManager,
        profiler: Profiler,
        executor: Executor
    ): CompletableFuture<Void> = CompletableFuture.runAsync {
        assets.overlayIds.forEach { overlayId ->
            try {
                loadOverlay(manager, overlayId)
            } catch (e: IOException) {
                LOGGER.error("Error while loading a Framity overlay: $e")
            } catch (e: JsonParseException) {
                LOGGER.error("Error while parsing a Framity overlay: $e")
            }
        }
    }
}