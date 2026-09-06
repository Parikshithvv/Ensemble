package com.example.ensemble.data

import android.util.Log
import com.example.ensemble.domain.AppearanceSegment
import com.example.ensemble.domain.FaceInstance
import com.example.ensemble.domain.Person
import kotlin.math.sqrt

private const val TAG = "PersonClusterer"

// Agglomerative average-linkage cosine distance merge threshold (0.41f)
// Safely merges near-miss clusters (0.36-0.40) while staying well below over-merge territory (>=0.43)
private const val AGGLOMERATIVE_THRESHOLD = 0.41f

// Quality Gate Cutoffs — blurred or low-quality face passes count for nobody
private const val MIN_SHARPNESS = 3.0f
private const val MIN_QUALITY_SCORE = 0.25f

// Maximum timestamp gap (ms) to consider two appearances part of the same contiguous segment
private const val SEGMENT_GAP_THRESHOLD_MS = 1000L

class PersonClusterer {

    private class AgglomerativeCluster(
        val faces: MutableList<FaceInstance> = mutableListOf()
    ) {
        val sumVector: FloatArray = FloatArray(192)

        init {
            recomputeSumVector()
        }

        fun recomputeSumVector() {
            if (faces.isEmpty()) return
            val dim = faces.first().embedding.size
            java.util.Arrays.fill(sumVector, 0f)
            for (face in faces) {
                val emb = face.embedding
                for (i in 0 until minOf(dim, emb.size)) {
                    sumVector[i] += emb[i]
                }
            }
        }

        fun computeCentroid(): FloatArray {
            if (faces.isEmpty()) return FloatArray(192)
            val dim = faces.first().embedding.size
            var sumSq = 0f
            for (v in sumVector) {
                sumSq += v * v
            }
            val norm = sqrt(sumSq.toDouble()).toFloat()
            val centroid = FloatArray(dim)
            if (norm > 0f) {
                for (i in 0 until dim) {
                    centroid[i] = sumVector[i] / norm
                }
            }
            return centroid
        }
    }

    /**
     * Perform order-independent Agglomerative (Bottom-Up) Hierarchical Clustering with average linkage.
     */
    fun clusterFaces(faces: List<FaceInstance>, videoUri: String): List<Person> {
        Log.i(
            TAG,
            "Starting Agglomerative Clustering on ${faces.size} face(s) | AGGLOMERATIVE_THRESHOLD=$AGGLOMERATIVE_THRESHOLD, " +
                    "MIN_SHARPNESS=$MIN_SHARPNESS, MIN_QUALITY_SCORE=$MIN_QUALITY_SCORE"
        )

        if (faces.isEmpty()) {
            Log.d(TAG, "No faces to cluster.")
            return emptyList()
        }

        // 1. Filter out low-quality faces via Quality Gate
        var lowQualityExcludedCount = 0
        val validFaces = faces.filter { face ->
            if (face.embedding.isEmpty()) {
                Log.w(TAG, "Skipping face @${face.timestampMs}ms — missing embedding.")
                return@filter false
            }
            if (face.sharpness < MIN_SHARPNESS || face.qualityScore < MIN_QUALITY_SCORE) {
                lowQualityExcludedCount++
                Log.d(
                    TAG,
                    "Skipping face @${face.timestampMs}ms for clustering — quality too low (sharpness=%.1f, score=%.3f)"
                        .format(face.sharpness, face.qualityScore)
                )
                return@filter false
            }
            true
        }

        Log.i(
            TAG,
            "Quality Gate: ${validFaces.size} face(s) passed ($lowQualityExcludedCount low-quality face(s) excluded)."
        )

        if (validFaces.isEmpty()) {
            return emptyList()
        }

        // 2. Initialize each valid face as its own singleton cluster
        val clusters = validFaces.mapTo(mutableListOf()) { face ->
            AgglomerativeCluster(mutableListOf(face))
        }

        Log.i(TAG, "Initialized ${clusters.size} singleton cluster(s). Starting bottom-up average-linkage merging...")

        // 3. Bottom-Up Agglomerative Merging
        var mergeCount = 0
        while (clusters.size > 1) {
            var minAverageDistance = Float.MAX_VALUE
            var bestI = -1
            var bestJ = -1

            for (i in 0 until clusters.size) {
                val clusterA = clusters[i]
                for (j in i + 1 until clusters.size) {
                    val clusterB = clusters[j]

                    val avgDist = computeAverageLinkageDistance(clusterA, clusterB)
                    if (avgDist < minAverageDistance) {
                        minAverageDistance = avgDist
                        bestI = i
                        bestJ = j
                    }
                }
            }

            if (bestI != -1 && bestJ != -1 && minAverageDistance <= AGGLOMERATIVE_THRESHOLD) {
                val clusterA = clusters[bestI]
                val clusterB = clusters[bestJ]

                Log.d(
                    TAG,
                    "Merging cluster ${bestJ} (${clusterB.faces.size} face(s)) into cluster ${bestI} (${clusterA.faces.size} face(s)) " +
                            "— avg-linkage dist=%.3f".format(minAverageDistance)
                )

                clusterA.faces.addAll(clusterB.faces)
                clusterA.recomputeSumVector()
                clusters.removeAt(bestJ)
                mergeCount++
            } else {
                Log.i(
                    TAG,
                    "No more cluster pairs with average distance <= $AGGLOMERATIVE_THRESHOLD. Stopping agglomerative merging after $mergeCount merge(s)."
                )
                break
            }
        }

        // 4. Sort clusters by total face count (descending)
        clusters.sortByDescending { it.faces.size }

        val resultPeople = clusters.mapIndexed { index, cluster ->
            val personId = "Person ${index + 1}"
            val sortedFaces = cluster.faces.sortedBy { it.timestampMs }
            val segments = computeAppearanceSegments(sortedFaces)
            val centroid = cluster.computeCentroid()

            val person = Person(
                id = personId,
                videoUri = videoUri,
                faces = sortedFaces,
                centroid = centroid,
                appearanceCount = segments.size,
                appearanceSegments = segments
            )

            Log.i(
                TAG,
                "==> Final $personId: ${person.faces.size} face(s), ${segments.size} appearance segment(s), " +
                        "bestScore=%.3f @${person.representativeFace?.timestampMs}ms"
                            .format(person.representativeFace?.qualityScore ?: 0f)
            )
            for (seg in segments) {
                Log.d(TAG, "       Segment: ${seg.startMs}ms - ${seg.endMs}ms (${seg.endMs - seg.startMs}ms)")
            }

            person
        }

        // 5. POST-CLUSTERING CENTROID & REPRESENTATIVE FRAME ANALYSIS LOGGING
        Log.i(TAG, "==================== POST-CLUSTERING PAIRWISE ANALYSIS ====================")
        for (i in resultPeople.indices) {
            val pA = resultPeople[i]
            val repA = pA.representativeFace
            Log.i(
                TAG,
                "REPRESENTATIVE FRAME: ${pA.id} | timestamp=${repA?.timestampMs}ms | qualityScore=%.3f | yaw=%.1f pitch=%.1f roll=%.1f"
                    .format(
                        repA?.qualityScore ?: 0f,
                        repA?.headEulerAngleY ?: 0f,
                        repA?.headEulerAngleX ?: 0f,
                        repA?.headEulerAngleZ ?: 0f
                    )
            )
            for (j in i + 1 until resultPeople.size) {
                val pB = resultPeople[j]
                val distCentroid = computeCosineDistance(pA.centroid, pB.centroid)
                val avgDist = computeCrossClusterAverageDistance(pA.faces, pB.faces)

                Log.i(
                    TAG,
                    "DISTANCE: ${pA.id} <-> ${pB.id} | Centroid Cosine Dist = %.4f | Avg-Linkage Dist = %.4f"
                        .format(distCentroid, avgDist)
                )
            }
        }
        Log.i(TAG, "====================================================================")

        Log.i(
            TAG,
            "Agglomerative Clustering finished: outputted ${resultPeople.size} person(s) from ${faces.size} total faces " +
                    "($lowQualityExcludedCount low-quality faces excluded from clustering)."
        )
        return resultPeople
    }

    private fun computeCosineDistance(vecA: FloatArray, vecB: FloatArray): Float {
        if (vecA.isEmpty() || vecB.isEmpty()) return 2.0f
        val dim = minOf(vecA.size, vecB.size)
        var dot = 0f
        for (k in 0 until dim) {
            dot += vecA[k] * vecB[k]
        }
        return (1.0f - dot).coerceIn(0f, 2f)
    }

    private fun computeCrossClusterAverageDistance(facesA: List<FaceInstance>, facesB: List<FaceInstance>): Float {
        if (facesA.isEmpty() || facesB.isEmpty()) return 2.0f
        var totalDist = 0f
        var count = 0
        for (fA in facesA) {
            for (fB in facesB) {
                totalDist += computeCosineDistance(fA.embedding, fB.embedding)
                count++
            }
        }
        return if (count > 0) totalDist / count else 2.0f
    }

    /**
     * Compute exact Average-Linkage Cosine Distance between [clusterA] and [clusterB]:
     * avgDist(A, B) = 1.0 - (sumVectorA · sumVectorB) / (|A| * |B|)
     */
    private fun computeAverageLinkageDistance(
        clusterA: AgglomerativeCluster,
        clusterB: AgglomerativeCluster
    ): Float {
        val lenA = clusterA.faces.size
        val lenB = clusterB.faces.size
        if (lenA == 0 || lenB == 0) return 2.0f

        val sumA = clusterA.sumVector
        val sumB = clusterB.sumVector
        val dim = minOf(sumA.size, sumB.size)

        var dot = 0f
        for (k in 0 until dim) {
            dot += sumA[k] * sumB[k]
        }

        val avgDot = dot / (lenA.toFloat() * lenB.toFloat())
        return (1.0f - avgDot).coerceIn(0f, 2f)
    }

    /**
     * Group sorted face timestamps into contiguous appearance segments.
     */
    private fun computeAppearanceSegments(sortedFaces: List<FaceInstance>): List<AppearanceSegment> {
        if (sortedFaces.isEmpty()) return emptyList()

        val segments = mutableListOf<AppearanceSegment>()
        var segStart = sortedFaces.first().timestampMs
        var prevTime = segStart

        for (i in 1 until sortedFaces.size) {
            val currTime = sortedFaces[i].timestampMs
            if (currTime - prevTime > SEGMENT_GAP_THRESHOLD_MS) {
                // Close current segment
                val segEnd = if (prevTime == segStart) segStart + 175L else prevTime
                segments.add(AppearanceSegment(segStart, segEnd))
                segStart = currTime
            }
            prevTime = currTime
        }

        // Close final segment
        val finalEnd = if (prevTime == segStart) segStart + 175L else prevTime
        segments.add(AppearanceSegment(segStart, finalEnd))

        return segments
    }
}
