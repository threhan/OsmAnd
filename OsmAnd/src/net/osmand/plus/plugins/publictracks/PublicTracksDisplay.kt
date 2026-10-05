package net.osmand.plus.plugins.publictracks

import kotlin.math.*

/** Display-only clipping and local overlap thinning. Original geometries stay in the store. */
internal class PublicTracksDisplay(private val bounds: DoubleArray, width: Int, height: Int) {
    // A 25-line budget per 8px cell still paints dense GPS traces as solid bands.
    // Keep two representatives in a wider screen-space neighborhood; unique
    // branches resume outside crowded cells and hit testing uses the full store.
    private val columns = max(1, width / 16)
    private val rows = max(1, height / 16)
    private val counts = IntArray(columns * rows)
    private val sx = columns / (bounds[2] - bounds[0])
    private val sy = rows / (bounds[3] - bounds[1])
    val segments = ArrayList<PublicTrackSegment>()
    var thinned = false
        private set

    fun add(source: PublicTrackSegment) {
        val accepted = HashSet<Int>()
        val path = ArrayList<Double>()
        fun flush() {
            if (path.size >= 4) segments.add(source.copy(coordinates = path.toDoubleArray()))
            path.clear()
        }
        fun append(x: Double, y: Double) {
            if (path.size >= 2 && abs(path[path.size-2]-x) < 1e-12 && abs(path.last()-y) < 1e-12) return
            if (path.size >= 4) {
                val priorX = path[path.size-4]; val priorY = path[path.size-3]
                if (hypot((x-priorX)*sx,(y-priorY)*sy) < .125) {
                    path[path.size-2]=x; path[path.size-1]=y
                    return
                }
            }
            path.add(x); path.add(y)
        }
        val points = source.coordinates
        for (i in 2 until points.size step 2) {
            val ax = points[i-2]; val ay = points[i-1]
            val dx = points[i]-ax; val dy = points[i+1]-ay
            var lo = 0.0; var hi = 1.0
            fun clip(p: Double, q: Double): Boolean {
                if (p == 0.0) return q >= 0.0
                val t = q/p
                if (p < 0) lo = max(lo,t) else hi = min(hi,t)
                return lo <= hi
            }
            if (!clip(-dx,ax-bounds[0]) || !clip(dx,bounds[2]-ax) ||
                !clip(-dy,ay-bounds[1]) || !clip(dy,bounds[3]-ay)) { flush(); continue }
            if (lo > 0) flush()
            val steps = max(1,ceil(max(abs(dx*sx),abs(dy*sy))*(hi-lo)*2).toInt())
            for (j in 0 until steps) {
                val from = lo + (hi-lo)*j/steps
                val to = lo + (hi-lo)*(j+1)/steps
                val middle = (from+to)/2
                val col = ((ax+dx*middle-bounds[0])*sx).toInt().coerceIn(0,columns-1)
                val row = ((ay+dy*middle-bounds[1])*sy).toInt().coerceIn(0,rows-1)
                val cell = row*columns+col
                if (cell in accepted || counts[cell] < 2) {
                    if (accepted.add(cell)) counts[cell]++
                    append(ax+dx*from,ay+dy*from); append(ax+dx*to,ay+dy*to)
                } else { thinned = true; flush() }
            }
            if (hi < 1) flush()
        }
        flush()
    }
}
