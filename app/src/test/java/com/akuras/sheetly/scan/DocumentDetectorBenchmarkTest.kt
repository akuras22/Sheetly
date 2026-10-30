package com.akuras.sheetly.scan

import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentDetectorBenchmarkTest {

    private val seedsPerScenario = 40L
    private val requiredOverlap = 0.9f

    @Test
    fun findsThePageInHardScenes() {
        val detector = DocumentDetector()
        var totalHits = 0
        var total = 0
        var elapsedNanos = 0L
        val report = StringBuilder("\nscenario                         hits    mean overlap\n")
        for (scenario in Scenario.entries) {
            var hits = 0
            var overlapSum = 0f
            for (seed in 0 until seedsPerScenario) {
                val scene = SceneRenderer.render(scenario, seed)
                val started = System.nanoTime()
                val detection = detector.detect(scene.frame)
                elapsedNanos += System.nanoTime() - started
                val overlap = detection?.let { SceneRenderer.intersectionOverUnion(it.quad, scene.quad) } ?: 0f
                overlapSum += overlap
                if (overlap >= requiredOverlap) hits++
            }
            totalHits += hits
            total += seedsPerScenario.toInt()
            report.append(
                "%-32s %2d/%d   %.3f\n".format(scenario.name, hits, seedsPerScenario, overlapSum / seedsPerScenario)
            )
        }
        report.append("total %d/%d = %.1f%%, %.1f ms per frame\n".format(
            totalHits, total, 100f * totalHits / total, elapsedNanos / 1e6 / total
        ))
        println(report)
        assertTrue(report.toString(), totalHits >= total * 0.93)
    }
}
