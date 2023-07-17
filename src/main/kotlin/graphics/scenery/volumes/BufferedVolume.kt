package graphics.scenery.volumes

import bdv.tools.transformation.TransformedSource
import bvv.core.VolumeViewerOptions
import graphics.scenery.Hub
import graphics.scenery.OrientedBoundingBox
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import net.imglib2.type.numeric.integer.*
import net.imglib2.type.numeric.real.DoubleType
import net.imglib2.type.numeric.real.FloatType
import org.joml.Vector3f
import org.joml.Vector3i
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Convenience class to handle buffer-based volumes. Data descriptor is stored in [ds], similar
 * to [Volume.VolumeDataSource.RAISource], with [options] and a required [hub].
 */
class BufferedVolume(val ds: VolumeDataSource.RAISource<*>, options: VolumeViewerOptions, hub: Hub): Volume(
    ds,
    options,
    hub
) {
    init {
        name = "Volume (Buffer source)"
        logger.debug("Data source is $ds")

        boundingBox = generateBoundingBox()
    }

    override fun generateBoundingBox(): OrientedBoundingBox {
        val source = (ds.sources[0].spimSource as TransformedSource).wrappedSource as? BufferSource<*>

        val sizes = if(source != null) {
            val min = Vector3f(0.0f)
            val max = Vector3f(source.width.toFloat(), source.height.toFloat(), source.depth.toFloat())
            max - min
        } else {
            Vector3f(1.0f, 1.0f, 1.0f)
        }

        return OrientedBoundingBox(this,
            Vector3f(-0.0f, -0.0f, -0.0f),
            sizes)
    }

    data class Timepoint(val name: String, val contents: ByteBuffer)

    /**
     * Access all the timepoints this volume has attached.
     */
    @Suppress("UNNECESSARY_SAFE_CALL", "UNUSED_PARAMETER")
    val timepoints: CopyOnWriteArrayList<Timepoint>?
        get() = ((ds?.sources?.firstOrNull()?.spimSource as? TransformedSource)?.wrappedSource as? BufferSource)?.timepoints

    /**
     * Adds a new timepoint with a given [name], with data stored in [buffer].
     */
    fun addTimepoint(name: String, buffer: ByteBuffer) {
        timepoints?.removeIf { it.name == name }
        timepoints?.add(Timepoint(name, buffer))
        timepointCount = timepoints?.size ?: 0
        viewerState.numTimepoints = timepointCount

        volumeManager.notifyUpdate(this)
    }

    /**
     * Removes the timepoint with the given [name].
     */
    @JvmOverloads fun removeTimepoint(name: String, deallocate: Boolean = false): Boolean {
        val tp = timepoints?.find { it.name == name }
        val result = timepoints?.removeIf { it.name == name }
        if(deallocate) {
            tp?.contents?.let { MemoryUtil.memFree(it) }
        }
        timepointCount = timepoints?.size ?: 0
        viewerState.numTimepoints = timepointCount

        volumeManager.notifyUpdate(this)
        return result != null
    }

    /**
     * Purges the first [count] timepoints, while always leaving [leave] timepoints
     * in the list.
     */
    @JvmOverloads fun purgeFirst(count: Int, leave: Int = 0, deallocate: Boolean = false) {
        val elements = if(timepoints?.size ?: 0 - count < leave) {
            0
        } else {
            max(1, count - leave)
        }

        repeat(elements) {
            val tp = timepoints?.removeAt(0)
            if(deallocate && tp != null) {
                MemoryUtil.memFree(tp.contents)
            }
        }

        timepointCount = timepoints?.size ?: 0
        viewerState.numTimepoints = timepointCount
    }

    /**
     * Purges the last [count] timepoints, while always leaving [leave] timepoints
     * in the list.
     */
    @JvmOverloads fun purgeLast(count: Int, leave: Int = 0, deallocate: Boolean = false) {
        val elements = if(timepoints?.size ?: 0 - count < leave) {
            0
        } else {
            max(1, count - leave)
        }

        val n = timepoints?.size ?: 0 - elements
        repeat(n) {
            val tp = timepoints?.removeLast()
            if(deallocate && tp != null) {
                MemoryUtil.memFree(tp.contents)
            }
        }

        timepointCount = timepoints?.size ?: 0
        viewerState.numTimepoints = timepointCount
    }

    /**
     * Samples a point from the currently used volume, [uv] is the texture coordinate of the volume, [0.0, 1.0] for
     * all of the components.
     *
     * Returns the sampled value as a [Float], or null in case nothing could be sampled.
     */
    @OptIn(ExperimentalUnsignedTypes::class)
    override fun sample(uv: Vector3f, interpolate: Boolean): Float? {
        val texture = timepoints?.lastOrNull() ?: throw IllegalStateException("Could not find timepoint")
        val dimensions = getDimensions()

        val bpp = when(ds.type) {
            is UnsignedByteType, is ByteType -> 1
            is UnsignedShortType, is ShortType -> 2
            is UnsignedIntType, is IntType ->4
            is FloatType -> 4
            is DoubleType -> 8
            else -> throw IllegalStateException("Data type ${ds.type.javaClass.simpleName} is not supported for sampling")
        }

        if(uv.x() < 0.0f || uv.x() > 1.0f || uv.y() < 0.0f || uv.y() > 1.0f || uv.z() < 0.0f || uv.z() > 1.0f) {
            logger.debug("Invalid UV coords for volume access: $uv")
            return null
        }

        val absoluteCoords = Vector3f(uv.x() * dimensions.x(), uv.y() * dimensions.y(), uv.z() * dimensions.z())
//        val index: Int = (floor(gt.dimensions.x() * gt.dimensions.y() * absoluteCoords.z()).toInt()
//            + floor(gt.dimensions.x() * absoluteCoords.y()).toInt()
//            + floor(absoluteCoords.x()).toInt())
        val absoluteCoordsD = Vector3f(floor(absoluteCoords.x()), floor(absoluteCoords.y()), floor(absoluteCoords.z()))
        val diff = absoluteCoords - absoluteCoordsD

        fun toIndex(absoluteCoords: Vector3f): Int = (
            absoluteCoords.x().roundToInt().dec()
                + (dimensions.x() * absoluteCoords.y()).roundToInt().dec()
                + (dimensions.x() * dimensions.y() * absoluteCoords.z()).roundToInt().dec()
            )

        val index = toIndex(absoluteCoordsD)

        val contents = texture.contents

        if(contents.limit() < index*bpp) {
            logger.debug("Absolute index ${index*bpp} for data type ${ds.type.javaClass.simpleName} from $uv exceeds data buffer limit of ${contents.limit()} (capacity=${contents.capacity()}), coords=$absoluteCoords/${dimensions}")
            return 0.0f
        }


        fun density(index:Int): Float {
            if(index*bpp >= contents.limit()) {
                return 0.0f
            }

            val s = when(ds.type) {
                is ByteType -> contents.get(index).toFloat()
                is UnsignedByteType -> contents.get(index).toUByte().toFloat()
                is ShortType -> contents.asShortBuffer().get(index).toFloat()
                is UnsignedShortType -> contents.asShortBuffer().get(index).toUShort().toFloat()
                is IntType -> contents.asIntBuffer().get(index).toFloat()
                is UnsignedIntType -> contents.asIntBuffer().get(index).toUInt().toFloat()
                is FloatType -> contents.asFloatBuffer().get(index)
                is DoubleType -> contents.asDoubleBuffer().get(index).toFloat()
                else -> throw java.lang.IllegalStateException("Can't determine density for ${ds.type.javaClass.simpleName} data")
            }

            // TODO: Correctly query transfer range
            val trangemax = 65536.0f
            return transferFunction.evaluate(s/trangemax)
        }

        return if(interpolate) {
            val offset = 1.0f

            val d00 = lerp(diff.x(), density(index), density(toIndex(absoluteCoordsD + Vector3f(offset, 0.0f, 0.0f))))
            val d10 = lerp(diff.x(), density(toIndex(absoluteCoordsD + Vector3f(0.0f, offset, 0.0f))), density(toIndex(absoluteCoordsD + Vector3f(offset, offset, 0.0f))))
            val d01 = lerp(diff.x(), density(toIndex(absoluteCoordsD + Vector3f(0.0f, 0.0f, offset))), density(toIndex(absoluteCoordsD + Vector3f(offset, 0.0f, offset))))
            val d11 = lerp(diff.x(), density(toIndex(absoluteCoordsD + Vector3f(0.0f, offset, offset))), density(toIndex(absoluteCoordsD + Vector3f(offset, offset, offset))))
            val d0 = lerp(diff.y(), d00, d10)
            val d1 = lerp(diff.y(), d01, d11)
            lerp(diff.z(), d0, d1)
        } else {
            density(index)
        }
    }

    private fun lerp(t: Float, v0: Float, v1: Float): Float {
        return (1.0f - t) * v0 + t * v1
    }

    /**
     * Takes samples along the ray from [start] to [end] from the currently active volume.
     * Values beyond [0.0, 1.0] for [start] and [end] will be clamped to that interval.
     *
     * Returns the list of samples (which might include `null` values in case a sample failed),
     * as well as the delta used along the ray, or null if the start/end coordinates are invalid.
     */
    override fun sampleRay(start: Vector3f, end: Vector3f): Pair<List<Float?>, Vector3f>? {
        val dimensions = Vector3f(getDimensions())

        if (start.x() < 0.0f || start.x() > 1.0f || start.y() < 0.0f || start.y() > 1.0f || start.z() < 0.0f || start.z() > 1.0f) {
            logger.debug("Invalid UV coords for ray start: {} -- will clamp values to [0.0, 1.0].", start)
        }

        if (end.x() < 0.0f || end.x() > 1.0f || end.y() < 0.0f || end.y() > 1.0f || end.z() < 0.0f || end.z() > 1.0f) {
            logger.debug("Invalid UV coords for ray end: {} -- will clamp values to [0.0, 1.0].", end)
        }

        val startClamped = Vector3f(
                min(max(start.x(), 0.0f), 1.0f),
                min(max(start.y(), 0.0f), 1.0f),
                min(max(start.z(), 0.0f), 1.0f)
        )

        val endClamped = Vector3f(
                min(max(end.x(), 0.0f), 1.0f),
                min(max(end.y(), 0.0f), 1.0f),
                min(max(end.z(), 0.0f), 1.0f)
        )

        val direction = (endClamped - startClamped).normalize()
        val maxSteps = (Vector3f(direction).mul(dimensions).length() * 2.0f).roundToInt()
        val delta = direction * (1.0f / maxSteps.toFloat())

        return (0 until maxSteps).map {
            sample(startClamped + (delta * it.toFloat()))
        } to delta
    }

    /**
     * Returns the volume's physical (voxel) dimensions.
     */
    override fun getDimensions(): Vector3i {
        val source = ((ds.sources.first().spimSource as? TransformedSource)?.wrappedSource as? BufferSource) ?: throw IllegalStateException("No source found")
        return Vector3i(source.width, source.height, source.depth)
    }
}
