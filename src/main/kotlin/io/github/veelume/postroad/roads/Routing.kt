package io.github.veelume.postroad.roads

import io.github.veelume.postroad.network.Network
import java.util.PriorityQueue

/**
 * Shortest routes over the path graph. Vertices are *anchors*: a (path, point index) that
 * carries a node or a link end. Consecutive anchors on a path are joined by the polyline
 * between them; links join anchors on different paths at no cost.
 */
object Routing {

    data class Anchor(val pathId: String, val index: Int)

    data class Route(val length: Double, val worstTier: Tier?)

    private class Edge(val to: Anchor, val length: Double, val tier: Tier?)

    /** Routes from [fromNodeId] to every reachable node, keyed by node id. Unreachable nodes are absent. */
    fun routes(network: Network, fromNodeId: String): Map<String, Route> {
        val from = network.nodes[fromNodeId] ?: return emptyMap()
        val graph = build(network)
        val start = Anchor(from.pathId, from.pointIndex)
        if (graph[start] == null) return emptyMap()

        val best = HashMap<Anchor, Route>()
        val queue = PriorityQueue<Pair<Anchor, Route>>(compareBy { it.second.length })
        best[start] = Route(0.0, null)
        queue.add(start to Route(0.0, null))
        while (queue.isNotEmpty()) {
            val (anchor, route) = queue.poll()
            if (best[anchor]!!.length < route.length) continue
            for (edge in graph[anchor] ?: emptyList()) {
                val length = route.length + edge.length
                val tier = worse(route.worstTier, edge.tier, length == 0.0 && edge.length == 0.0)
                val known = best[edge.to]
                if (known == null || length < known.length) {
                    val next = Route(length, tier)
                    best[edge.to] = next
                    queue.add(edge.to to next)
                }
            }
        }
        val result = LinkedHashMap<String, Route>()
        for (node in network.nodes.values) {
            if (node.id == fromNodeId) continue
            best[Anchor(node.pathId, node.pointIndex)]?.let { result[node.id] = it }
        }
        return result
    }

    private fun worse(a: Tier?, b: Tier?, ignore: Boolean): Tier? {
        if (ignore) return a
        if (a == null) return b
        if (b == null) return a
        return if (a.ordinal <= b.ordinal) a else b
    }

    private fun build(network: Network): Map<Anchor, MutableList<Edge>> {
        val anchorsByPath = HashMap<String, MutableSet<Int>>()
        for (node in network.nodes.values) anchorsByPath.getOrPut(node.pathId) { HashSet() }.add(node.pointIndex)
        for (link in network.links) {
            anchorsByPath.getOrPut(link.pathA) { HashSet() }.add(link.indexA)
            anchorsByPath.getOrPut(link.pathB) { HashSet() }.add(link.indexB)
        }
        val graph = HashMap<Anchor, MutableList<Edge>>()
        fun edges(a: Anchor) = graph.getOrPut(a) { ArrayList() }
        for ((pathId, indices) in anchorsByPath) {
            val path = network.paths[pathId] ?: continue
            if (!path.charted) continue
            val sorted = indices.filter { it in path.points.indices }.sorted()
            for (i in 0 until sorted.size - 1) {
                val a = Anchor(pathId, sorted[i])
                val b = Anchor(pathId, sorted[i + 1])
                val length = path.lengthBetween(sorted[i], sorted[i + 1])
                val tier = path.worstTierBetween(sorted[i], sorted[i + 1])
                edges(a).add(Edge(b, length, tier))
                edges(b).add(Edge(a, length, tier))
            }
            if (sorted.size == 1) edges(Anchor(pathId, sorted[0]))
        }
        for (link in network.links) {
            val pa = network.paths[link.pathA]
            val pb = network.paths[link.pathB]
            if (pa == null || pb == null || !pa.charted || !pb.charted) continue
            val a = Anchor(link.pathA, link.indexA)
            val b = Anchor(link.pathB, link.indexB)
            edges(a).add(Edge(b, 0.0, null))
            edges(b).add(Edge(a, 0.0, null))
        }
        return graph
    }
}
