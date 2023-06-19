@file:Suppress("DEPRECATION")

package graphics.scenery.volumes

import bdv.tools.brightness.ConverterSetup
import bdv.tools.transformation.TransformedSource
import bdv.viewer.RequestRepaint
import bdv.viewer.state.SourceState
import graphics.scenery.*
import graphics.scenery.geometry.GeometryType
import graphics.scenery.attribute.geometry.Geometry
import graphics.scenery.attribute.geometry.DefaultGeometry
import graphics.scenery.attribute.geometry.HasGeometry
import graphics.scenery.attribute.material.HasMaterial
import graphics.scenery.attribute.material.Material
import graphics.scenery.attribute.renderable.DefaultRenderable
import graphics.scenery.attribute.renderable.HasRenderable
import graphics.scenery.attribute.renderable.Renderable
import net.imglib2.realtransform.AffineTransform3D
import net.imglib2.type.numeric.ARGBType
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedShortType
import net.imglib2.type.volatiles.VolatileARGBType
import net.imglib2.type.volatiles.VolatileUnsignedByteType
import net.imglib2.type.volatiles.VolatileUnsignedShortType
import org.joml.Matrix4f
import org.joml.Vector2f
import tpietzsch.backend.Texture
import tpietzsch.backend.Texture3D
import tpietzsch.cache.*
import tpietzsch.example2.MultiVolumeShaderMip
import tpietzsch.example2.TriConsumer
import tpietzsch.example2.VolumeBlocks
import tpietzsch.example2.VolumeShaderSignature
import tpietzsch.multires.MultiResolutionStack3D
import tpietzsch.multires.SimpleStack3D
import tpietzsch.multires.SourceStacks
import tpietzsch.multires.Stack3D
import tpietzsch.shadergen.generate.Segment
import tpietzsch.shadergen.generate.SegmentTemplate
import tpietzsch.shadergen.generate.SegmentType
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ForkJoinPool
import java.util.function.BiConsumer
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

/**
 * Class to handle rendering of multiple, arbitrary aligned, potentially multiresolution volumes.
 *
 * @author Ulrik Guenther <hello@ulrik.is>, Tobias Pietzsch <tpietzsch@mpi-cbg.de>
 */
@Suppress("DEPRECATION")
class VolumeManager(
    override var hub: Hub?,
    val useCompute: Boolean = false,
    val customSegments: Map<SegmentType, SegmentTemplate>? = null,
    val customBindings: TriConsumer<Map<SegmentType, SegmentTemplate>, Map<SegmentType, Segment>, Int>? = null
) : DefaultNode("VolumeManager"), HasGeometry, HasRenderable, HasMaterial, Hubable, RequestRepaint {

    /**
     *  The rendering method used in the shader, can be
     *
     *  0 -- Local Maximum Intensity Projection
     *  1 -- Maximum Intensity Projection
     *  2 -- Alpha compositing
     */

    /** BDV shader context for this volume */
    var context = SceneryContext(this, useCompute)
        protected set

    /** Texture cache. */
    @Volatile
    protected var textureCache: TextureCache

    /** PBO chain for temporary data storage. */
    @Volatile
    protected var pboChain: PboChain

    /** Flexible [ShaderProperty] storage */
    @ShaderProperty
    var shaderProperties = hashMapOf<String, Any>()

    /** Set of [VolumeBlocks]. */
    protected var outOfCoreVolumes = ArrayList<VolumeBlocks>()
    var nodes = CopyOnWriteArrayList<Volume>()
        protected set
    protected var transferFunctionTextures = HashMap<SourceState<*>, Texture>()
    protected var colorMapTextures = HashMap<SourceState<*>, Texture>()

    /** Cache specification. */
    private val cacheSpec = CacheSpec(Texture.InternalFormat.R16, intArrayOf(32, 32, 32))

    private val renderStacksStates = CopyOnWriteArrayList<StackState>()

    private data class StackState(
        val stack: Stack3D<*>,
        val transferFunction: Texture,
        val colorMap: Texture,
        val converterSetup: ConverterSetup,
        val node: Volume
    )

    private val stackManager = SceneryStackManager()

    /** Whether to freeze the current set of blocks in-place. */
    var freezeRequiredBlocks = false

    /** Backing shader program */
    protected var prog = ArrayList<MultiVolumeShaderMip>()
    protected var progvol: MultiVolumeShaderMip? = null
    protected var renderStateUpdated = true

    protected var currentVolumeCount: Pair<Int, Int>

    /** Sets the maximum allowed step size in voxels. */
    var maxAllowedStepInVoxels = 1.0

    /** Numeric factor by which the step size may degrade on the far plane. */
    var farPlaneDegradation = 2.0

    // TODO: What happens when changing this? And should it change the mode for the current node only
    // or for all VolumeManager-managed nodes?
    var renderingMethod = Volume.RenderingMethod.AlphaBlending

    /** List of custom-created textures not to be cleared automatically */
    var customTextures = arrayListOf<String>()
    var customUniforms = arrayListOf<String>()
    init {
        addRenderable {
            state = State.Created
        }

        // fake geometry
        addGeometry {
            this.geometryType = GeometryType.TRIANGLES
        }
        logger.debug("Created new volume manager with compute=$useCompute, segments=$customSegments, bindings=$customBindings")

        addMaterial()

        currentVolumeCount = 0 to 0

        val maxCacheSize =
            (hub?.get(SceneryElement.Settings) as? Settings)?.get("Renderer.MaxVolumeCacheSize", 512) ?: 512

        val cacheGridDimensions = TextureCache.findSuitableGridSize(cacheSpec, maxCacheSize)
        textureCache = TextureCache(cacheGridDimensions, cacheSpec)

        pboChain = PboChain(5, 100, textureCache)

        updateRenderState()
        needAtLeastNumVolumes(renderStacksStates.size)
        logger.debug("renderStacks.size=${renderStacksStates.size}, progs=${prog.size}")

        progvol = prog.lastOrNull()

        if (progvol != null) {
            updateProgram(context)
        }

        renderable {
            preDraw()
        }
    }

    @Synchronized
    private fun recreateMaterial(context: SceneryContext) {
        shaderProperties.clear()
        shaderProperties["transform"] = Matrix4f()
        shaderProperties["viewportSize"] = Vector2f()
        shaderProperties["dsp"] = Vector2f()
        val oldKeys = material().textures.filter { it.key !in customTextures }.keys
        val texturesToKeep = material().textures.filter { it.key in customTextures }
        oldKeys.forEach {
            material().textures.remove(it)
        }

        setMaterial(ShaderMaterial(context.factory)){
            texturesToKeep.forEach { (k, v) ->
                textures[k] = v
            }
            cullingMode = Material.CullingMode.None
            blending.transparent = true
            blending.sourceColorBlendFactor = Blending.BlendFactor.One
            blending.destinationColorBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha
            blending.sourceAlphaBlendFactor = Blending.BlendFactor.One
            blending.destinationAlphaBlendFactor = Blending.BlendFactor.OneMinusSrcAlpha
            blending.colorBlending = Blending.BlendOp.add
            blending.alphaBlending = Blending.BlendOp.add
        }
    }

    @Synchronized
    private fun updateProgram(context: SceneryContext) {
        logger.debug("Updating effective shader program to $progvol")
        recreateMaterial(context)

        progvol?.setTextureCache(textureCache)
        progvol?.use(context)
        progvol?.setUniforms(context)
//        progvol?.bindSamplers(context)


        getScene()?.activeObserver?.let { cam ->
            progvol?.setViewportWidth(cam.width)
            progvol?.setEffectiveViewportSize(cam.width, cam.height)
        }

//        context.clearBindings()
        renderStateUpdated = true

        context.uniformSetter.modified = true
        updateBlocks(context)
    }

    @Synchronized
    private fun needAtLeastNumVolumes(n: Int) {
        val outOfCoreVolumeCount = renderStacksStates.count { it.stack is MultiResolutionStack3D }
        val regularVolumeCount = renderStacksStates.count { it.stack is SimpleStack3D }
        logger.debug("$currentVolumeCount -> ooc:$outOfCoreVolumeCount reg:$regularVolumeCount")

        if (currentVolumeCount.first == outOfCoreVolumeCount && currentVolumeCount.second == regularVolumeCount) {
            logger.debug("Not updating shader, current one compatible with ooc:$outOfCoreVolumeCount reg:$regularVolumeCount")
            return
        }

        while (outOfCoreVolumes.size < n) {
            outOfCoreVolumes.add(VolumeBlocks(textureCache))
        }

        val signatures = renderStacksStates.map {
            val dataType = when (it.stack.type) {
                is VolatileUnsignedByteType,
                is UnsignedByteType -> VolumeShaderSignature.PixelType.UBYTE

                is VolatileUnsignedShortType,
                is UnsignedShortType -> VolumeShaderSignature.PixelType.USHORT

                is VolatileARGBType,
                is ARGBType -> VolumeShaderSignature.PixelType.ARGB
                else -> throw IllegalStateException("Unknown volume type ${it.stack.type.javaClass}")
            }

            val volumeType = when (it.stack) {
                is SimpleStack3D -> SourceStacks.SourceStackType.SIMPLE
                is MultiResolutionStack3D -> SourceStacks.SourceStackType.MULTIRESOLUTION
                else -> SourceStacks.SourceStackType.UNDEFINED
            }

            VolumeShaderSignature.VolumeSignature(volumeType, dataType)
        }

        val segments = MultiVolumeShaderMip.getDefaultSegments(true)
        segments[SegmentType.VertexShader] = SegmentTemplate(
            this.javaClass,
            "BDVVolume.vert"
        )
        segments[SegmentType.FragmentShader] = SegmentTemplate(
            this.javaClass,
            "BDVVolume.frag",
            "intersectBoundingBox", "vis", "localNear", "localFar", "SampleVolume", "Convert", "Accumulate"
        )
        segments[SegmentType.MaxDepth] = SegmentTemplate(
            this.javaClass,
            "MaxDepth.frag"
        )
        segments[SegmentType.SampleMultiresolutionVolume] = SegmentTemplate(
            "SampleBlockVolume.frag",
            "im",
            "sourcemin",
            "sourcemax",
            "intersectBoundingBox",
            "lutSampler",
            "transferFunction",
            "colorMap",
            "blockScales",
            "lutSize",
            "lutOffset",
            "sampleVolume",
            "convert",
            "slicingPlanes",
            "slicingMode",
            "usedSlicingPlanes"
        )
        segments[SegmentType.SampleVolume] = SegmentTemplate(
            "SampleSimpleVolume.frag",
            "im", "sourcemax", "intersectBoundingBox",
            "volume", "transferFunction", "colorMap", "sampleVolume", "convert", "slicingPlanes",
            "slicingMode", "usedSlicingPlanes"
        )
        segments[SegmentType.Convert] = SegmentTemplate(
            "Converter.frag",
            "convert", "offset", "scale"
        )
        segments[SegmentType.AccumulatorMultiresolution] = SegmentTemplate(
            "AccumulateBlockVolume.frag",
            "vis", "localNear", "localFar", "sampleVolume", "convert"
        )
        segments[SegmentType.Accumulator] = SegmentTemplate(
            "AccumulateSimpleVolume.frag",
            "vis", "localNear", "localFar", "sampleVolume", "convert"
        )

        customSegments?.forEach { type, segment -> segments[type] = segment }

        var triggered = false
        val additionalBindings = customBindings

            ?: TriConsumer { _: Map<SegmentType, SegmentTemplate>, instances: Map<SegmentType, Segment>, i: Int ->
                logger.info("Connecting additional bindings")

                if(!triggered) {
                    instances[SegmentType.FragmentShader]?.repeat("localNear", n)
                    instances[SegmentType.FragmentShader]?.repeat("localFar", n)
                    triggered = true
                }

                logger.info("Connecting localNear/localFar for $i")
//                instances[SegmentType.FragmentShader]?.bind(
//                    "localNear",
//                    i,
//                    instances[SegmentType.AccumulatorMultiresolution]
//                )

                instances[SegmentType.FragmentShader]?.bind("localNear", i, instances[SegmentType.Accumulator])

//                instances[SegmentType.FragmentShader]?.bind(
//                    "localFar",
//                    i,
//                    instances[SegmentType.AccumulatorMultiresolution]
//                )

                instances[SegmentType.FragmentShader]?.bind("localFar", i, instances[SegmentType.Accumulator])

                instances[SegmentType.SampleMultiresolutionVolume]?.bind("convert", instances[SegmentType.Convert])

                instances[SegmentType.SampleVolume]?.bind("convert", instances[SegmentType.Convert])
            }

        val newProgvol = MultiVolumeShaderMip(
            VolumeShaderSignature(signatures),
            true, farPlaneDegradation,
            segments,
            additionalBindings,
            "InputZBuffer"
        )

        newProgvol.setTextureCache(textureCache)
        newProgvol.setDepthTextureName("InputZBuffer")
        logger.debug("Using program for $outOfCoreVolumeCount out-of-core volumes and $regularVolumeCount regular volumes")
        prog.add(newProgvol)

//        context.clearBindings()

        currentVolumeCount = outOfCoreVolumeCount to regularVolumeCount

        if (prog.size > 0) {
            logger.debug("We have ${prog.size} shaders ready")
            progvol = newProgvol//prog.last()

//            context.clearBindings()
            context = SceneryContext(this, useCompute)
            updateProgram(context)
            state = State.Ready
        } else {
            state = State.Created
        }
    }

    /**
     * Updates the currently-used set of blocks using [context] to
     * facilitate the updates on the GPU.
     */
    @Synchronized
    protected fun updateBlocks(context: SceneryContext): Boolean {
        val currentProg = progvol
        if (currentProg == null) {
            logger.debug("Not updating blocks, no prog")
            return false
        }

        nodes.forEach { bdvNode ->
            bdvNode.prepareNextFrame()
        }

        val cam = nodes.firstOrNull()?.getScene()?.activeObserver ?: return false
        val settings = hub?.get<Settings>() ?: return false

        val hmd = hub?.getWorkingHMDDisplay()?.wantsVR(settings)
        val vp = if(hmd != null) {
            Matrix4f(hmd.getEyeProjection(0, cam.nearPlaneDistance, cam.farPlaneDistance))
                .mul(cam.spatial().getTransformation())
        } else {
            Matrix4f(cam.spatial().projection)
                .mul(cam.spatial().getTransformation())
        }

        // TODO: original might result in NULL, is this intended?
        currentProg.use(context)
        currentProg.setUniforms(context)

        var numTasks = 0
        val fillTasksPerVolume = ArrayList<VolumeAndTasks>()

        val taskCreationDuration = measureTimeMillis {
            renderStacksStates.forEachIndexed { i, state ->
                if (state.stack is MultiResolutionStack3D) {
                    val volume = outOfCoreVolumes[i]

                    volume.init(state.stack, cam.width, vp)

                    val tasks = volume.fillTasks
                    numTasks += tasks.size
                    fillTasksPerVolume.add(VolumeAndTasks(tasks, volume, state.stack.resolutions().size - 1))
                }
            }
        }

        val fillTasksDuration = measureTimeMillis {
            taskLoop@ while (numTasks > textureCache.maxNumTiles) {
                fillTasksPerVolume.sortByDescending { it.numTasks() }
                for (vat in fillTasksPerVolume) {
                    val baseLevel = vat.volume.baseLevel
                    if (baseLevel < vat.maxLevel) {
                        vat.volume.baseLevel = baseLevel + 1
                        numTasks -= vat.numTasks()
                        vat.tasks.clear()
                        vat.tasks.addAll(vat.volume.fillTasks)
                        numTasks += vat.numTasks()

                        continue@taskLoop
                    }
                }
                break
            }
        }

        val durationFillTaskProcessing = measureTimeMillis {
            val fillTasks = ArrayList<FillTask>()
            fillTasksPerVolume.forEach {
                fillTasks.addAll(it.tasks)
            }
            logger.debug("Got ${fillTasks.size} fill tasks (vs max=${textureCache.maxNumTiles})")

            if (fillTasks.size > textureCache.maxNumTiles) {
                fillTasks.subList(textureCache.maxNumTiles, fillTasks.size).clear()
            }

            ProcessFillTasks.parallel(textureCache, pboChain, context, forkJoinPool, fillTasks)
//            ProcessFillTasks.sequential(textureCache, pboChain, context, fillTasks)
        }

        // TODO: is repaint necessary?
        // var repaint = false
        val durationLutUpdate = measureTimeMillis {
            renderStacksStates.forEachIndexed { i, state ->
                if (state.stack is MultiResolutionStack3D) {
                    val volumeBlocks = outOfCoreVolumes[i]
                    val timestamp = textureCache.nextTimestamp()
                    volumeBlocks.makeLut(timestamp)
                    //val complete = volumeBlocks.makeLut(timestamp)
                    // if (!complete) {
                    //    repaint = true
                    // }
                    context.bindTexture(volumeBlocks.lookupTexture)
                    volumeBlocks.lookupTexture.upload(context)
                }
            }
        }

        var minWorldVoxelSize = Double.POSITIVE_INFINITY
        val ready = readyToRender()

        val durationBinding = measureTimeMillis {
            renderStacksStates
                // sort by classname, so we get MultiResolutionStack3Ds first,
                // then simple stacks
                .sortedBy { it.javaClass.simpleName }
                .forEachIndexed { i, state ->
                    val s = state.stack
                    currentProg.setConverter(i, state.converterSetup)
                    currentProg.registerCustomSampler(i, "transferFunction", state.transferFunction)
                    currentProg.registerCustomSampler(i, "colorMap", state.colorMap)
                    currentProg.setCustomFloatArrayUniformForVolume(i, "slicingPlanes", 4, state.node.slicingArray())
                    currentProg.setCustomUniformForVolume(i, "slicingMode", state.node.slicingMode.id)
                    currentProg.setCustomUniformForVolume(i,"usedSlicingPlanes",
                        min(state.node.slicingPlaneEquations.size, Volume.MAX_SUPPORTED_SLICING_PLANES))

                    context.bindTexture(state.transferFunction)
                    context.bindTexture(state.colorMap)

                    if (s is MultiResolutionStack3D) {
                        currentProg.setVolume(i, outOfCoreVolumes[i])
                        minWorldVoxelSize =
                            min(minWorldVoxelSize, outOfCoreVolumes[i].baseLevelVoxelSizeInWorldCoordinates)
                    }

                    if (s is SimpleStack3D) {
                        val volume = stackManager.getSimpleVolume(context, s)
                        currentProg.setVolume(i, volume)
                        context.bindTexture(volume.volumeTexture)
                        if (ready && state.node in updated) {
                            stackManager.clearReferences(volume.volumeTexture)
                            stackManager.upload(context, s, volume.volumeTexture)
                            updated.remove(state.node)
                        }
                        minWorldVoxelSize = min(minWorldVoxelSize, volume.voxelSizeInWorldCoordinates)
                    }
                }

            currentProg.setViewportWidth(cam.width)
            currentProg.setEffectiveViewportSize(cam.width, cam.height)
            currentProg.setDegrade(farPlaneDegradation)
            currentProg.setProjectionViewMatrix(vp, maxAllowedStepInVoxels * minWorldVoxelSize)
            currentProg.use(context)
            currentProg.setUniforms(context)
            currentProg.bindSamplers(context)
        }

        logger.debug(
            "Task creation: {}ms, Fill task creation: {}ms, Fill task processing: {}ms, LUT update: {}ms, Bindings: {}ms",
            taskCreationDuration,
            fillTasksDuration,
            durationFillTaskProcessing,
            durationLutUpdate,
            durationBinding
        )
        // TODO: check if repaint can be made sufficient for triggering rendering
        return true
    }

    class SimpleTexture2D(
        val data: ByteBuffer,
        val width: Int,
        val height: Int,
        val format: Texture.InternalFormat,
        val wrap: Texture.Wrap,
        val minFilter: Texture.MinFilter,
        val magFilter: Texture.MagFilter
    ) : Texture3D {
        override fun texMinFilter(): Texture.MinFilter = minFilter
        override fun texMagFilter(): Texture.MagFilter = magFilter
        override fun texWrap(): Texture.Wrap = wrap
        override fun texWidth(): Int = width
        override fun texHeight(): Int = height
        override fun texDepth(): Int = 1
        override fun texInternalFormat(): Texture.InternalFormat = format
    }

    private fun TransferFunction.toTexture(): Texture3D {
        val data = this.serialise()
        return SimpleTexture2D(
            data, textureSize, textureHeight,
            Texture.InternalFormat.R32F, Texture.Wrap.CLAMP_TO_EDGE,
            Texture.MinFilter.LINEAR, Texture.MagFilter.LINEAR
        )
    }

    private fun Colormap.toTexture(): Texture3D {
        return SimpleTexture2D(
            buffer, width, height,
            Texture.InternalFormat.RGBA8, Texture.Wrap.CLAMP_TO_EDGE,
            Texture.MinFilter.LINEAR, Texture.MagFilter.LINEAR
        )
    }

    fun readyToRender(): Boolean {
        val multiResCount = renderStacksStates.count { it.stack is MultiResolutionStack3D }
        val regularCount = renderStacksStates.count { it.stack is SimpleStack3D }

        val multiResMatch = material().textures.count { it.key.startsWith("volumeCache") } == 1
            && material().textures.count { it.key.startsWith("lutSampler_") } >= multiResCount
        val regularMatch = material().textures.count { it.key.startsWith("volume_") } >= regularCount
        val counts = listOf("sourcemax", "offset", "scale", "im").map { key ->
            key to shaderProperties.keys.count {
                it.contains("${key}_x_")
            }
        }

//        if(multiResMatch && regularMatch) {
//            state = State.Ready
//        } else {
//            state = State.
//        }
        val ready =
            multiResMatch && regularMatch && (regularCount > 0 || multiResCount > 0) && counts.all { it.second == multiResCount + regularCount }
        if (!ready) {
            logger.debug(
                "ReadyToRender: $multiResCount->$multiResMatch/$regularCount->$regularMatch\n " +
                    " * ShaderProperties: ${shaderProperties.keys.joinToString(",")}\n " +
                    " * Textures: ${material().textures.keys.joinToString(",")}\n " +
                    " * Counts: ${counts.joinToString(",") { "${it.first}=${it.second}" }}"
            )
        }
        return ready
    }

    internal class VolumeAndTasks(tasks: List<FillTask>, val volume: VolumeBlocks, val maxLevel: Int) {
        val tasks: ArrayList<FillTask> = ArrayList(tasks)

        fun numTasks(): Int {
            return tasks.size
        }

    }

    override fun createRenderable(): Renderable {
        return object: DefaultRenderable(this) {
            /**
             * Pre-draw routine to be called by the rendered just before drawing.
             * Updates texture cache and used blocks.
             */
            override fun preDraw(): Boolean {
                logger.debug("Running predraw")
                context.bindTexture(textureCache)

                if (nodes.any { it.transferFunction.stale }) {
                    transferFunctionTextures.clear()
                    val keys = material().textures.filter { it.key.startsWith("transferFunction") }.keys
                    keys.forEach { material().textures.remove(it) }
                    renderStateUpdated = true
                }

                if (renderStateUpdated) {
                    updateRenderState()
                    needAtLeastNumVolumes(renderStacksStates.size)
                    renderStateUpdated = false
                }

                var repaint = true
                val blockUpdateDuration = measureTimeMillis {
                    if (!freezeRequiredBlocks) {
                        try {
                            updateBlocks(context)
                        } catch (e: RuntimeException) {
                            logger.warn("Probably ran out of data, corrupt BDV file? $e")
                            e.printStackTrace()
                        }
                    }
                }

                logger.debug("Block updates took {}ms", blockUpdateDuration)

                context.runDeferredBindings()
                if (repaint) {
                    context.runTextureUpdates()
                }

                return readyToRender()
            }
        }
    }

    override fun createGeometry(): Geometry {
        return object : DefaultGeometry(this) {
            override var vertices: FloatBuffer = BufferUtils.allocateFloatAndPut(
                floatArrayOf(
                    -1.0f, -1.0f, 0.0f,
                    1.0f, -1.0f, 0.0f,
                    1.0f, 1.0f, 0.0f,
                    -1.0f, 1.0f, 0.0f
                )
            )
            override var normals: FloatBuffer = BufferUtils.allocateFloatAndPut(
                floatArrayOf(
                    1.0f, 0.0f, 0.0f,
                    0.0f, 1.0f, 0.0f,
                    0.0f, 0.0f, 1.0f,
                    0.0f, 0.0f, 1.0f
                )
            )
            override var texcoords: FloatBuffer = BufferUtils.allocateFloatAndPut(
                floatArrayOf(
                    0.0f, 0.0f,
                    1.0f, 0.0f,
                    1.0f, 1.0f,
                    0.0f, 1.0f
                )
            )
            override var indices: IntBuffer = BufferUtils.allocateIntAndPut(
                intArrayOf(0, 1, 2, 0, 2, 3)
            )
        }
    }

    /**
     * Updates the current rendering state.
     */
    @Synchronized
    protected fun updateRenderState() {
        val stacks = ArrayList<StackState>(renderStacksStates.size)

        nodes.forEach { bdvNode ->
            val visibleSourceIndices = bdvNode.viewerState.visibleSourceIndices
            val currentTimepoint = bdvNode.viewerState.currentTimepoint

            logger.debug("Visible: at t=$currentTimepoint: ${visibleSourceIndices.joinToString(", ")}")
            for (i in visibleSourceIndices) {
                val source = bdvNode.viewerState.sources[i]
                if (bdvNode is BufferedVolume) {
                    SourceStacks.setSourceStackType(source.spimSource, SourceStacks.SourceStackType.SIMPLE)
                }
                val stack = SourceStacks.getStack3D(source.spimSource, currentTimepoint)

                val sourceTransform = AffineTransform3D()
                source.spimSource.getSourceTransform(currentTimepoint, 0, sourceTransform)

                if (stack is MultiResolutionStack3D) {
                    val o = TransformedMultiResolutionStack3D(stack, bdvNode, sourceTransform)
                    val tf = transferFunctionTextures.getOrPut(
                        bdvNode.viewerState.sources[i],
                        { bdvNode.transferFunction.toTexture() })
                    val colormap =
                        colorMapTextures.getOrPut(bdvNode.viewerState.sources[i], { bdvNode.colormap.toTexture() })
                    stacks.add(StackState(o, tf, colormap, bdvNode.converterSetups[i], bdvNode))
                } else if (stack is SimpleStack3D) {
                    val o: SimpleStack3D<*>
                    val ss = source.spimSource as? TransformedSource
                    val wrapped = ss?.wrappedSource

                    o = if (wrapped is BufferSource) {
                        val timepoints = wrapped.timepoints
                        if (timepoints.isEmpty()) {
                            logger.info("Timepoints is empty, skipping node")
                            return@forEach
                        }

                        val tp = min(max(0, currentTimepoint), timepoints.size - 1)
                        TransformedBufferedSimpleStack3D(
                            stack,
                            timepoints[tp].contents,
                            intArrayOf(wrapped.width, wrapped.height, wrapped.depth),
                            bdvNode,
                            sourceTransform
                        )
                    } else {
                        TransformedSimpleStack3D(stack, bdvNode, sourceTransform)
                    }

                    val tf = transferFunctionTextures.getOrPut(
                        bdvNode.viewerState.sources[i],
                        { bdvNode.transferFunction.toTexture() })
                    val colormap =
                        colorMapTextures.getOrPut(bdvNode.viewerState.sources[i], { bdvNode.colormap.toTexture() })
                    logger.debug("TF for ${bdvNode.viewerState.sources[i]} is $tf")
                    stacks.add(StackState(o, tf, colormap, bdvNode.converterSetups[i], bdvNode))
                }

                bdvNode.converterSetups[i].setViewer(this)
            }
        }

        renderStacksStates.clear()
        renderStacksStates.addAll(stacks)
    }


    /**
     * Adds a new volume [node] to the [VolumeManager]. Will trigger an update of the rendering state,
     * and recreation of the shaders.
     */
    @Synchronized
    fun add(node: Volume) {
        logger.debug("Adding $node to OOC nodes")
        nodes.add(node)
        updated.add(node)
        updateRenderState()
        needAtLeastNumVolumes(renderStacksStates.size)
    }

    fun replace(toReplace: VolumeManager? = null): VolumeManager {
        logger.debug("Replacing volume manager with ${nodes.size} volumes managed")
        val volumes = nodes.toMutableList()
        val current = toReplace ?: hub?.get<VolumeManager>()
        if (current != null) {
            hub?.remove(current)
        }

        val vm = VolumeManager(hub, useCompute, current?.customSegments, current?.customBindings)
        current?.customTextures?.forEach {
            vm.customTextures.add(it)
            vm.material().textures[it] = current.material().textures[it]!!
        }
        volumes.forEach {
            vm.add(it)
            it.volumeManager = vm
        }

        hub?.add(vm)
        return vm
    }

    fun replaceSelf(): VolumeManager {
        logger.debug("Replacing volume manager with ${nodes.size} volumes managed")
        val volumes = nodes.toMutableList()
        val current = hub?.get<VolumeManager>()
        if (current != null) {
            hub?.remove(current)
        }

        val vm = VolumeManager(hub, useCompute, current?.customSegments, current?.customBindings)
        current?.customTextures?.forEach {
            vm.customTextures.add(it)
            vm.material().textures[it] = current.material().textures[it]!!
        }
        volumes.forEach {
            vm.add(it)
            it.volumeManager = vm
        }

        hub?.add(vm)
        return vm
    }

    @Synchronized
    fun remove(node: Volume) {
        logger.debug("Removing $node to OOC nodes")
        nodes.remove(node)

        replace()
    }

    protected val updated = HashSet<Volume>()

    /**
     * Notifies the [VolumeManager] of any updates coming from [node],
     * will trigger an update of the rendering state, and potentially creation of new shaders.
     */
    @Synchronized
    fun notifyUpdate(node: Volume) {
        logger.debug("Received update from {}", node)
        updated.add(node)
        renderStateUpdated = true
        updateRenderState()
        needAtLeastNumVolumes(renderStacksStates.size)
    }

    /**
     * Requests re-rendering.
     */
    override fun requestRepaint() {
        renderStateUpdated = true
    }

    fun recreateCache(newMaxSize: Int) {
        logger.warn("Recreating cache, new size: $newMaxSize MB ")
        val cacheGridDimensions = TextureCache.findSuitableGridSize(cacheSpec, newMaxSize)

        textureCache = TextureCache(cacheGridDimensions, cacheSpec)

        pboChain = PboChain(5, 100, textureCache)

        prog.clear()
//        updateRenderState()
//        needAtLeastNumVolumes(renderStacks.size)
        renderable().preDraw()
    }

    fun removeCachedColormapFor(node: Volume) {
        node.viewerState.sources.forEach { source ->
            colorMapTextures.remove(source)
        }

        context.clearLUTs()
        renderStateUpdated = true
    }

    override fun close() {
        logger.debug("Closing VolumeManager")

        replace()
    }

    /** Companion object for Volume */
    companion object {
        /** Static [ForkJoinPool] for fill task submission. */
        protected val forkJoinPool: ForkJoinPool = ForkJoinPool(max(1, Runtime.getRuntime().availableProcessors()))

        fun regenerateVolumeManagerWithExtraVolume(volume: Volume, hub: Hub ) {
            val vm = hub.get<VolumeManager>()
            val volumes = ArrayList<Volume>(10)

            if (vm != null) {
                volumes.addAll(vm.nodes)
                hub.remove(vm)
            }
            volume.volumeManager = if (vm != null) {
                hub.add(VolumeManager(hub, vm.useCompute, vm.customSegments, vm.customBindings))
            } else {
                hub.add(VolumeManager(hub))
            }
            vm?.customTextures?.forEach {
                volume.volumeManager.customTextures.add(it)
                volume.volumeManager.material().textures[it] = vm.material().textures[it]!!
            }
            volume.volumeManager.add(volume)
            volumes.forEach {
                volume.volumeManager.add(it)
                it.volumeManager = volume.volumeManager
            }
        }
    }
}
